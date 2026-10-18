package com.apadev.memoryfix.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

object OverlayCompositor {

    /**
     * Composites an overlay PNG onto a base photo and writes the resulting JPEG to outputStream.
     */
    fun composite(
        baseImageStream: InputStream,
        overlayImageStream: InputStream,
        outputStream: OutputStream,
        quality: Int = 95
    ): Boolean {
        var baseBitmap: Bitmap? = null
        var overlayBitmap: Bitmap? = null
        var resultBitmap: Bitmap? = null

        return try {
            baseBitmap = BitmapFactory.decodeStream(baseImageStream) ?: return false
            overlayBitmap = BitmapFactory.decodeStream(overlayImageStream) ?: return false

            // Create mutable copy of base or new bitmap matching base dimensions
            val width = baseBitmap.width
            val height = baseBitmap.height

            resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            // Draw base photo
            canvas.drawBitmap(baseBitmap, 0f, 0f, paint)

            // Draw overlay scaled to base dimensions
            val destRect = Rect(0, 0, width, height)
            canvas.drawBitmap(overlayBitmap, null, destRect, paint)

            // Compress to JPEG
            resultBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            outputStream.flush()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            baseBitmap?.recycle()
            overlayBitmap?.recycle()
            resultBitmap?.recycle()
        }
    }

    /**
     * Overload for SAF Uris
     */
    fun compositeUris(
        context: Context,
        baseUri: Uri,
        overlayUri: Uri,
        outputUri: Uri,
        quality: Int = 95
    ): Boolean {
        return try {
            val baseIn = context.contentResolver.openInputStream(baseUri) ?: return false
            val overlayIn = context.contentResolver.openInputStream(overlayUri) ?: return false
            val out = context.contentResolver.openOutputStream(outputUri) ?: return false

            baseIn.use { bStream ->
                overlayIn.use { oStream ->
                    out.use { outStream ->
                        composite(bStream, oStream, outStream, quality)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
