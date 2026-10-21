package com.apadev.memoryfix.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object VideoChunkMerger {

    data class ChunkItem(
        val uri: Uri,
        val overlayUri: Uri? = null,
        val epochMillis: Long = 0L
    )

    /**
     * Merges multiple consecutive video chunks into a single continuous video.
     * The result is written in-place to the first chunk's Uri.
     * Subsequent chunk files are cleaned up.
     */
    suspend fun mergeChunksInPlace(
        context: Context,
        chunks: List<ChunkItem>,
        burnOverlays: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (chunks.size < 2) return@withContext false

        val cacheDir = context.cacheDir
        val tempInputFiles = mutableListOf<File>()
        val tempOutputFile = File(cacheDir, "merged_${System.currentTimeMillis()}.mp4")
        val decodedBitmaps = mutableListOf<android.graphics.Bitmap>()

        try {
            val editedItems = mutableListOf<EditedMediaItem>()

            for ((idx, chunk) in chunks.withIndex()) {
                val tempIn = File(cacheDir, "chunk_in_${idx}_${System.currentTimeMillis()}.mp4")
                tempInputFiles.add(tempIn)

                context.contentResolver.openInputStream(chunk.uri)?.use { inStream ->
                    FileOutputStream(tempIn).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                } ?: return@withContext false

                val mediaItem = MediaItem.fromUri(Uri.fromFile(tempIn))
                val effectsList = mutableListOf<androidx.media3.common.Effect>()

                if (burnOverlays && chunk.overlayUri != null) {
                    context.contentResolver.openInputStream(chunk.overlayUri)?.use { oStream ->
                        val bmp = BitmapFactory.decodeStream(oStream)
                        if (bmp != null) {
                            decodedBitmaps.add(bmp)
                            val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(bmp)
                            effectsList.add(OverlayEffect(listOf(bitmapOverlay)))
                        }
                    }
                }

                val editedItem = EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(emptyList(), effectsList))
                    .build()
                editedItems.add(editedItem)
            }

            val sequence = EditedMediaItemSequence(editedItems)
            val composition = Composition.Builder(listOf(sequence)).build()

            val completionSignal = CompletableDeferred<Boolean>()

            withContext(Dispatchers.Main) {
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            completionSignal.complete(true)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            exportException.printStackTrace()
                            completionSignal.complete(false)
                        }
                    })
                    .build()

                try {
                    transformer.start(composition, tempOutputFile.absolutePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                    completionSignal.complete(false)
                }
            }

            val success = completionSignal.await()

            if (success && tempOutputFile.exists() && tempOutputFile.length() > 0) {
                val targetUri = chunks.first().uri

                // 1. Write merged file back into primary chunk URI
                context.contentResolver.openOutputStream(targetUri, "wt")?.use { outStream ->
                    FileInputStream(tempOutputFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    outStream.flush()
                } ?: return@withContext false

                try {
                    context.contentResolver.notifyChange(targetUri, null)
                } catch (e: Exception) { }

                // 2. Delete subsequent chunk files
                for (i in 1 until chunks.size) {
                    deleteFileSilently(context, chunks[i].uri)
                    if (chunks[i].overlayUri != null) {
                        deleteFileSilently(context, chunks[i].overlayUri!!)
                    }
                }

                if (burnOverlays && chunks[0].overlayUri != null) {
                    deleteFileSilently(context, chunks[0].overlayUri!!)
                }

                true
            } else {
                android.util.Log.e("VideoChunkMerger", "Merge failed or empty output file for chunks: ${chunks.map { it.uri }}")
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            decodedBitmaps.forEach { it.recycle() }
            tempInputFiles.forEach { if (it.exists()) it.delete() }
            if (tempOutputFile.exists()) tempOutputFile.delete()
        }
    }

    private fun deleteFileSilently(context: Context, uri: Uri) {
        try {
            android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            try {
                androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.delete()
            } catch (ex: Exception) { }
        }
    }
}
