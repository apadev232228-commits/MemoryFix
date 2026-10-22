package com.apadev.memoryfix.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import java.nio.ByteBuffer

object VideoChunkMerger {

    data class ChunkItem(
        val uri: Uri,
        val overlayUri: Uri? = null,
        val epochMillis: Long = 0L
    )

    /**
     * Merges multiple consecutive video chunks into a single continuous video.
     * When burnOverlays is false, uses direct sample copying via MediaExtractor + MediaMuxer (~30ms per group).
     * When burnOverlays is true, falls back to Media3 Transformer.
     * The result is written in-place to the first chunk's Uri.
     * Subsequent chunk files are cleaned up.
     */
    suspend fun mergeChunksInPlace(
        context: Context,
        chunks: List<ChunkItem>,
        burnOverlays: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (chunks.size < 2) return@withContext false
        val sortedChunks = chunks.sortedBy { it.epochMillis }

        val cacheDir = context.cacheDir
        val tempInputFiles = mutableListOf<File>()
        val tempOutputFile = File(cacheDir, "merged_${System.currentTimeMillis()}.mp4")
        val decodedBitmaps = mutableListOf<android.graphics.Bitmap>()

        try {
            // Copy chunk inputs to temp cache files in chronological order
            for ((idx, chunk) in sortedChunks.withIndex()) {
                val tempIn = File(cacheDir, "chunk_in_${idx}_${System.currentTimeMillis()}.mp4")
                tempInputFiles.add(tempIn)

                context.contentResolver.openInputStream(chunk.uri)?.use { inStream ->
                    FileOutputStream(tempIn).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                } ?: return@withContext false
            }

            var success = false

            // Fast path: direct sample copying without re-encoding (~30ms)
            if (!burnOverlays) {
                success = fastRemuxChunks(tempInputFiles, tempOutputFile)
            }

            // Fallback path: Media3 Transformer for overlay burning or if remuxing failed
            if (!success) {
                val editedItems = mutableListOf<EditedMediaItem>()
                for ((idx, chunk) in chunks.withIndex()) {
                    val tempIn = tempInputFiles[idx]
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

                success = completionSignal.await()
            }

            if (success && tempOutputFile.exists() && tempOutputFile.length() > 0) {
                val targetUri = sortedChunks.first().uri

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
                for (i in 1 until sortedChunks.size) {
                    deleteFileSilently(context, sortedChunks[i].uri)
                    if (sortedChunks[i].overlayUri != null) {
                        deleteFileSilently(context, sortedChunks[i].overlayUri!!)
                    }
                }

                if (burnOverlays && sortedChunks[0].overlayUri != null) {
                    deleteFileSilently(context, sortedChunks[0].overlayUri!!)
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

    /**
     * Fast remuxing using MediaExtractor and MediaMuxer without re-encoding frames.
     * Takes ~30ms to merge 1080p chunks with 100% original quality.
     */
    fun fastRemuxChunks(inputFiles: List<File>, outputFile: File, coordinates: Pair<Double, Double>? = null): Boolean {
        if (inputFiles.size < 2) return false
        var muxer: MediaMuxer? = null
        val extractors = mutableListOf<MediaExtractor>()

        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (coordinates != null) {
                try {
                    muxer.setLocation(coordinates.first.toFloat(), coordinates.second.toFloat())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            for (file in inputFiles) {
                val ext = MediaExtractor()
                ext.setDataSource(file.absolutePath)
                extractors.add(ext)
            }

            val firstExtractor = extractors[0]
            var videoTrackIdx = -1
            var audioTrackIdx = -1
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1

            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIdx == -1) {
                    videoTrackIdx = i
                    muxerVideoTrack = muxer.addTrack(format)
                } else if (mime.startsWith("audio/") && audioTrackIdx == -1) {
                    audioTrackIdx = i
                    muxerAudioTrack = muxer.addTrack(format)
                }
            }

            if (muxerVideoTrack == -1) return false
            muxer.start()

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var videoPtsOffsetUs = 0L
            var audioPtsOffsetUs = 0L

            for (extractor in extractors) {
                var extVideoTrack = -1
                var extAudioTrack = -1

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && extVideoTrack == -1) {
                        extVideoTrack = i
                    } else if (mime.startsWith("audio/") && extAudioTrack == -1) {
                        extAudioTrack = i
                    }
                }

                var chunkMaxVideoPtsUs = 0L
                var chunkMaxAudioPtsUs = 0L

                // 1. Copy video samples
                if (extVideoTrack != -1 && muxerVideoTrack != -1) {
                    extractor.selectTrack(extVideoTrack)
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val sampleTime = extractor.sampleTime
                        val flags = extractor.sampleFlags
                        chunkMaxVideoPtsUs = maxOf(chunkMaxVideoPtsUs, sampleTime)

                        bufferInfo.set(0, sampleSize, sampleTime + videoPtsOffsetUs, flags)
                        muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                        extractor.advance()
                    }
                    extractor.unselectTrack(extVideoTrack)
                }

                // 2. Copy audio samples
                if (extAudioTrack != -1 && muxerAudioTrack != -1) {
                    extractor.selectTrack(extAudioTrack)
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val sampleTime = extractor.sampleTime
                        val flags = extractor.sampleFlags
                        chunkMaxAudioPtsUs = maxOf(chunkMaxAudioPtsUs, sampleTime)

                        bufferInfo.set(0, sampleSize, sampleTime + audioPtsOffsetUs, flags)
                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        extractor.advance()
                    }
                    extractor.unselectTrack(extAudioTrack)
                }

                // Advance PTS offset for the next chunk
                videoPtsOffsetUs += chunkMaxVideoPtsUs + 33_333L // ~1 frame at 30fps
                audioPtsOffsetUs += chunkMaxAudioPtsUs + 23_220L // ~1 AAC frame (1024 samples @ 44.1kHz)
            }

            muxer.stop()
            muxer.release()
            muxer = null
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            try { muxer?.release() } catch (ex: Exception) {}
            return false
        } finally {
            extractors.forEach { it.release() }
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
