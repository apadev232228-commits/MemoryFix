package com.apadev.memoryfix.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
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

object VideoOverlayCompositor {

    /**
     * Bakes a PNG overlay into an MP4 video in-place using Jetpack Media3 Transformer.
     * Returns true if successfully re-encoded with overlay, false otherwise.
     */
    suspend fun compositeInPlace(
        context: Context,
        videoUri: Uri,
        overlayUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val tempInputFile = File(cacheDir, "temp_in_${System.currentTimeMillis()}.mp4")
        val tempOutputFile = File(cacheDir, "temp_out_${System.currentTimeMillis()}.mp4")

        try {
            // 1. Copy video from SAF into a local temp file for Transformer
            context.contentResolver.openInputStream(videoUri)?.use { input ->
                FileOutputStream(tempInputFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext false

            // 2. Decode overlay bitmap
            val overlayBitmap = context.contentResolver.openInputStream(overlayUri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@withContext false

            val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)
            val overlayEffect = OverlayEffect(listOf(bitmapOverlay))

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(tempInputFile)))
                .setEffects(Effects(emptyList(), listOf(overlayEffect)))
                .build()

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
                    transformer.start(editedMediaItem, tempOutputFile.absolutePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                    completionSignal.complete(false)
                }
            }

            val success = completionSignal.await()
            overlayBitmap.recycle()

            if (success && tempOutputFile.exists() && tempOutputFile.length() > 0) {
                // 3. Write back into video SAF Uri
                context.contentResolver.openOutputStream(videoUri, "wt")?.use { outStream ->
                    FileInputStream(tempOutputFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    outStream.flush()
                } ?: return@withContext false

                // 4. Delete overlay file so duplicate sticker isn't kept
                try {
                    android.provider.DocumentsContract.deleteDocument(context.contentResolver, overlayUri)
                } catch (e: Exception) {
                    try {
                        androidx.documentfile.provider.DocumentFile.fromSingleUri(context, overlayUri)?.delete()
                    } catch (ex: Exception) { }
                }

                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            if (tempInputFile.exists()) tempInputFile.delete()
            if (tempOutputFile.exists()) tempOutputFile.delete()
        }
    }
}
