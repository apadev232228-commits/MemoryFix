package com.apadev.memoryfix.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

object OverlayCompositor {

    /**
     * Composites an overlay PNG onto a base photo in-place and safely cleans up the overlay file.
     * Decodes all input streams into memory FIRST before opening any output stream to avoid
     * SAF file truncation collisions.
     */
    fun compositeInPlace(
        context: Context,
        baseUri: Uri,
        overlayUri: Uri,
        cleanupOverlayFile: Boolean = true,
        quality: Int = 95
    ): Boolean {
        var baseBitmap: Bitmap? = null
        var overlayBitmap: Bitmap? = null
        var resultBitmap: Bitmap? = null

        return try {
            // 1. Read base bitmap into memory
            val baseIn = context.contentResolver.openInputStream(baseUri) ?: return false
            baseBitmap = baseIn.use { BitmapFactory.decodeStream(it) } ?: return false

            // 2. Read overlay bitmap into memory
            val overlayIn = context.contentResolver.openInputStream(overlayUri) ?: return false
            overlayBitmap = overlayIn.use { BitmapFactory.decodeStream(it) } ?: return false

            // 3. Create composited bitmap matching base dimensions
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

            // Recycle input bitmaps to free memory before compressing
            baseBitmap.recycle()
            baseBitmap = null
            overlayBitmap.recycle()
            overlayBitmap = null

            // 4. Safely write result back into base photo
            val out = context.contentResolver.openOutputStream(baseUri, "wt") ?: return false
            out.use { outStream ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
                outStream.flush()
            }

            // 5. Clean up overlay file so it doesn't show as a separate image in gallery
            if (cleanupOverlayFile) {
                deleteFileSilently(context, overlayUri)
            }

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

    private fun deleteFileSilently(context: Context, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            try {
                DocumentFile.fromSingleUri(context, uri)?.delete()
            } catch (ex: Exception) {
                // Ignore cleanup errors
            }
        }
    }
}
