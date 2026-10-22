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
     * Bakes a PNG overlay into an MP4 video file in-place using Jetpack Media3 Transformer.
     * Returns true if successfully re-encoded with overlay, false otherwise.
     */
    suspend fun compositeInPlace(
        context: Context,
        videoFile: File,
        overlayFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || !overlayFile.exists() || overlayFile.length() == 0L) {
            return@withContext false
        }

        val cacheDir = context.cacheDir
        val tempOutputFile = File(cacheDir, "temp_vout_${System.currentTimeMillis()}_${videoFile.name}")

        try {
            val overlayBitmap = BitmapFactory.decodeFile(overlayFile.absolutePath)
                ?: return@withContext false

            val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)
            val overlayEffect = OverlayEffect(listOf(bitmapOverlay))

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(videoFile)))
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
                            android.util.Log.e("VideoOverlay", "Transformer error: ${exportException.message}", exportException)
                            completionSignal.complete(false)
                        }
                    })
                    .build()

                try {
                    transformer.start(editedMediaItem, tempOutputFile.absolutePath)
                } catch (e: Exception) {
                    android.util.Log.e("VideoOverlay", "Failed to start transformer", e)
                    completionSignal.complete(false)
                }
            }

            val success = completionSignal.await()
            overlayBitmap.recycle()

            if (success && tempOutputFile.exists() && tempOutputFile.length() > 0) {
                FileOutputStream(videoFile).use { outStream ->
                    FileInputStream(tempOutputFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    outStream.flush()
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoOverlay", "Error compositing video overlay", e)
            false
        } finally {
            if (tempOutputFile.exists()) {
                tempOutputFile.delete()
            }
        }
    }

    /**
     * Bakes a PNG overlay into an MP4 video SAF Uri in-place using Jetpack Media3 Transformer.
     * Returns true if successfully re-encoded with overlay, false otherwise.
     */
    suspend fun compositeInPlace(
        context: Context,
        videoUri: Uri,
        overlayUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val tempInputFile = File(cacheDir, "temp_vin_${System.currentTimeMillis()}.mp4")
        val tempOverlayFile = File(cacheDir, "temp_vov_${System.currentTimeMillis()}.png")

        try {
            context.contentResolver.openInputStream(videoUri)?.use { input ->
                FileOutputStream(tempInputFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext false

            context.contentResolver.openInputStream(overlayUri)?.use { input ->
                FileOutputStream(tempOverlayFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext false

            val success = compositeInPlace(context, tempInputFile, tempOverlayFile)
            if (success && tempInputFile.exists() && tempInputFile.length() > 0) {
                context.contentResolver.openOutputStream(videoUri, "wt")?.use { outStream ->
                    FileInputStream(tempInputFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    outStream.flush()
                } ?: return@withContext false

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
            if (tempOverlayFile.exists()) tempOverlayFile.delete()
        }
    }
}

