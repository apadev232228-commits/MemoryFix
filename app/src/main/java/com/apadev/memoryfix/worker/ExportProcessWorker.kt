package com.apadev.memoryfix.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.apadev.memoryfix.R
import com.apadev.memoryfix.data.DateExtractor
import com.apadev.memoryfix.data.ExifManager
import com.apadev.memoryfix.data.MediaScanner
import com.apadev.memoryfix.data.OverlayCompositor
import android.media.MediaScannerConnection
import android.os.Environment
import com.apadev.memoryfix.data.ScannedMedia
import com.apadev.memoryfix.data.VideoChunkMerger
import com.apadev.memoryfix.data.VideoOverlayCompositor
import com.apadev.memoryfix.logging.AppLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExportProcessWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ExportProcessWorker"
        const val KEY_TREE_URI = "KEY_TREE_URI"
        const val KEY_ZIP_URIS = "KEY_ZIP_URIS"
        const val KEY_EMBED_LOCATION = "KEY_EMBED_LOCATION"
        const val KEY_MERGE_OVERLAYS = "KEY_MERGE_OVERLAYS"
        const val KEY_BURN_VIDEO_OVERLAYS = "KEY_BURN_VIDEO_OVERLAYS"
        const val KEY_MERGE_VIDEO_CHUNKS = "KEY_MERGE_VIDEO_CHUNKS"
        const val KEY_SKIP_DUPLICATES = "KEY_SKIP_DUPLICATES"
        const val KEY_TIMEZONE = "KEY_TIMEZONE"
        const val KEY_MAX_MEDIA_LIMIT = "KEY_MAX_MEDIA_LIMIT"

        const val PROGRESS_CURRENT = "PROGRESS_CURRENT"
        const val PROGRESS_TOTAL = "PROGRESS_TOTAL"
        const val PROGRESS_MEDIA_CURRENT = "PROGRESS_MEDIA_CURRENT"
        const val PROGRESS_MEDIA_TOTAL = "PROGRESS_MEDIA_TOTAL"
        const val PROGRESS_FILE = "PROGRESS_FILE"
        const val PROGRESS_PHOTOS = "PROGRESS_PHOTOS"
        const val PROGRESS_VIDEOS = "PROGRESS_VIDEOS"
        const val PROGRESS_OVERLAYS = "PROGRESS_OVERLAYS"

        const val RESULT_TOTAL = "RESULT_TOTAL"
        const val RESULT_PHOTOS = "RESULT_PHOTOS"
        const val RESULT_VIDEOS = "RESULT_VIDEOS"
        const val RESULT_OVERLAYS_MERGED = "RESULT_OVERLAYS_MERGED"
        const val RESULT_FAILED = "RESULT_FAILED"
        const val RESULT_TOTAL_SCANNED = "RESULT_TOTAL_SCANNED"
        const val RESULT_DUPLICATES_SKIPPED = "RESULT_DUPLICATES_SKIPPED"
        const val RESULT_CHUNKS_MERGED = "RESULT_CHUNKS_MERGED"

        const val CHANNEL_ID = "memory_fix_channel"
        const val CHANNEL_COMPLETE_ID = "memory_fix_complete_channel"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_COMPLETE_ID = 1002
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val zipUriStrings = inputData.getStringArray(KEY_ZIP_URIS)
        val treeUriString = inputData.getString(KEY_TREE_URI)
        if (zipUriStrings.isNullOrEmpty() && treeUriString == null) return@withContext Result.failure()

        val embedLocation = inputData.getBoolean(KEY_EMBED_LOCATION, false)
        val mergeOverlays = inputData.getBoolean(KEY_MERGE_OVERLAYS, false)
        val burnVideoOverlays = inputData.getBoolean(KEY_BURN_VIDEO_OVERLAYS, false)
        val mergeVideoChunks = inputData.getBoolean(KEY_MERGE_VIDEO_CHUNKS, false)
        val skipDuplicates = inputData.getBoolean(KEY_SKIP_DUPLICATES, false)
        val timeZone = inputData.getString(KEY_TIMEZONE) ?: "UTC"
        val maxMediaLimit = inputData.getInt(KEY_MAX_MEDIA_LIMIT, -1)

        createNotificationChannel()
        val initialNotification = createNotification(0, 100, "Scanning files...")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } else {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification))
        }
        notificationManager.notify(NOTIFICATION_ID, initialNotification)
        AppLogger.i(TAG, "Export started: isZip=${!zipUriStrings.isNullOrEmpty()}, embedLocation=$embedLocation, mergeOverlays=$mergeOverlays, burnVideo=$burnVideoOverlays, maxLimit=$maxMediaLimit")

        if (!zipUriStrings.isNullOrEmpty()) {
            return@withContext processZipExport(
                zipUris = zipUriStrings.map { Uri.parse(it) },
                embedLocation = embedLocation,
                mergeOverlays = mergeOverlays,
                burnVideoOverlays = burnVideoOverlays,
                mergeVideoChunks = mergeVideoChunks,
                skipDuplicates = skipDuplicates,
                timeZone = timeZone,
                maxMediaLimit = maxMediaLimit,
                notificationManager = notificationManager
            )
        }

        val treeUri = Uri.parse(treeUriString)
        val scope = this
        val cached = MediaScanner.lastScanResult
        val scanResult = if (cached != null && cached.rootFolderUri == treeUri && cached.mediaList.isNotEmpty()) {
            cached
        } else {
            MediaScanner.scanTree(context, treeUri, targetTimeZone = timeZone) { scanProgress ->
                if (scanProgress.totalFound > 0 && scanProgress.totalFound % 15 == 0) {
                    scope.launch {
                        setProgress(
                            workDataOf(
                                PROGRESS_CURRENT to 0,
                                PROGRESS_TOTAL to scanProgress.totalFound,
                                PROGRESS_FILE to if (scanProgress.isMatchingPhase) "Matching timeline history..." else "Indexing memories: ${scanProgress.totalFound} found...",
                                PROGRESS_PHOTOS to 0,
                                PROGRESS_VIDEOS to 0,
                                PROGRESS_OVERLAYS to 0
                            )
                        )
                    }
                }
            }
        }
        val rawList = scanResult.mediaList
        val list = if (maxMediaLimit > 0) rawList.take(maxMediaLimit) else rawList
        val total = list.size

        if (total == 0) {
            return@withContext Result.success(
                workDataOf(
                    RESULT_TOTAL to 0,
                    RESULT_PHOTOS to 0,
                    RESULT_VIDEOS to 0,
                    RESULT_OVERLAYS_MERGED to 0,
                    RESULT_FAILED to 0,
                    RESULT_TOTAL_SCANNED to 0
                )
            )
        }


        // Identify multi-segment video chunk groups
        val chunkGroups = if (mergeVideoChunks) MediaScanner.detectVideoChunks(list) else emptyList()
        val chunkGroupMap = mutableMapOf<Uri, List<ScannedMedia>>()
        val mergedSecondaryUris = mutableSetOf<Uri>()

        for (group in chunkGroups) {
            val primary = group.first()
            chunkGroupMap[primary.uri] = group
        }

        // Report total immediately so UI shows progress from the start
        setProgress(
            workDataOf(
                PROGRESS_CURRENT to 0,
                PROGRESS_TOTAL to total,
                PROGRESS_FILE to "Starting...",
                PROGRESS_PHOTOS to 0,
                PROGRESS_VIDEOS to 0,
                PROGRESS_OVERLAYS to 0
            )
        )

        var processed = 0
        var photosFixed = 0
        var videosFixed = 0
        var overlaysMerged = 0
        var failedCount = 0
        val failureLogList = mutableListOf<String>()

        for (item in list) {
            if (isStopped) {
                return@withContext Result.failure()
            }

            // Skip secondary chunks that were successfully merged into their primary video
            if (mergedSecondaryUris.contains(item.uri)) {
                processed++
                continue
            }

            // Skip duplicate photos or duplicate videos if skipDuplicates is true
            if (skipDuplicates && item.isDuplicate) {
                processed++
                continue
            }

            processed++
            val fileName = item.name

            // Update Notification every 5 items or on last
            if (processed % 5 == 0 || processed == total) {
                val notif = createNotification(processed, total, "Fixing: $fileName")
                notificationManager.notify(NOTIFICATION_ID, notif)
            }

            var itemFixed = false

            try {
                // If this is the primary chunk of a multi-part video, merge the entire group
                val chunkGroup = chunkGroupMap[item.uri]
                if (chunkGroup != null && chunkGroup.size > 1) {
                    val chunkItems = chunkGroup.map { c ->
                        val mEpoch = c.mp4Meta?.creationEpochMillis
                            ?: c.matchedMemory?.parseEpochMillis()
                            ?: DateExtractor.extractEpochMillisFromFileName(c.name, timeZone)
                            ?: 0L
                        com.apadev.memoryfix.data.VideoChunkMerger.ChunkItem(
                            uri = c.uri,
                            overlayUri = c.overlayUri,
                            epochMillis = mEpoch
                        )
                    }
                    val merged = com.apadev.memoryfix.data.VideoChunkMerger.mergeChunksInPlace(
                        context = context,
                        chunks = chunkItems,
                        burnOverlays = burnVideoOverlays
                    )

                    if (merged) {
                        for (i in 1 until chunkGroup.size) {
                            mergedSecondaryUris.add(chunkGroup[i].uri)
                        }
                        if (burnVideoOverlays) {
                            overlaysMerged += chunkItems.count { it.overlayUri != null }
                        }
                    }
                } else if (mergeOverlays && item.overlayUri != null) {
                    // Regular overlay handling for single items
                    if (!item.isVideo) {
                        val merged = OverlayCompositor.compositeInPlace(
                            context = context,
                            baseUri = item.uri,
                            overlayUri = item.overlayUri!!,
                            cleanupOverlayFile = true
                        )
                        if (merged) {
                            overlaysMerged++
                        }
                    } else if (burnVideoOverlays) {
                        val vMerged = com.apadev.memoryfix.data.VideoOverlayCompositor.compositeInPlace(
                            context = context,
                            videoUri = item.uri,
                            overlayUri = item.overlayUri!!
                        )
                        if (vMerged) {
                            overlaysMerged++
                        }
                    }
                }

                // 2. Resolve date (JSON first, filename fallback)
                val memory = item.matchedMemory
                val epochMillis = memory?.parseEpochMillis()
                    ?: DateExtractor.extractEpochMillisFromFileName(fileName, timeZone)
                val coordinates = if (embedLocation) memory?.parseCoordinates() else null

                // 3. Apply dates & GPS
                if (item.isVideo) {
                    val vSuccess = ExifManager.applyMetadataToVideo(
                        context = context,
                        uri = item.uri,
                        epochMillis = epochMillis,
                        coordinates = coordinates
                    )
                    if (vSuccess) {
                        videosFixed++
                        itemFixed = true
                    }
                } else {
                    val pSuccess = ExifManager.applyMetadataToImage(
                        context = context,
                        uri = item.uri,
                        epochMillis = epochMillis,
                        coordinates = coordinates,
                        timeZoneId = timeZone
                    )
                    if (pSuccess) {
                        photosFixed++
                        itemFixed = true
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to restore folder item: $fileName: ${e.message}", e)
                failureLogList.add("$fileName: ${e.message ?: "Exception occurred"}")
                e.printStackTrace()
            }

            if (!itemFixed) {
                failedCount++
                if (!failureLogList.any { it.startsWith(fileName) }) {
                    failureLogList.add("$fileName: Metadata write unconfirmed")
                }
            }

            // Update Progress in WorkManager live with accurate fixed counts
            setProgress(
                workDataOf(
                    PROGRESS_CURRENT to processed,
                    PROGRESS_TOTAL to total,
                    PROGRESS_FILE to fileName,
                    PROGRESS_PHOTOS to photosFixed,
                    PROGRESS_VIDEOS to videosFixed,
                    PROGRESS_OVERLAYS to overlaysMerged
                )
            )
        }

        val totalFixed = photosFixed + videosFixed
        val duplicatesCleaned = if (skipDuplicates) list.count { it.isDuplicate } else 0
        val secondaryChunksMerged = mergedSecondaryUris.size
        val actualFailed = failureLogList.size

        val manifest = buildString {
            appendLine("EXPORT RUN MANIFEST (FOLDER EXPORT)")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Total Files Scanned: ${rawList.size}")
            appendLine("Processed in Run: $total")
            appendLine("Photos Fixed: $photosFixed")
            appendLine("Videos Fixed: $videosFixed")
            appendLine("Overlays Merged: $overlaysMerged")
            appendLine("Duplicates Cleaned: $duplicatesCleaned")
            appendLine("Video Chunks Stitched: $secondaryChunksMerged")
            appendLine("Failed Count: $actualFailed")
            appendLine("Settings: embedLocation=$embedLocation, mergeOverlays=$mergeOverlays, burnVideoOverlays=$burnVideoOverlays, mergeVideoChunks=$mergeVideoChunks, maxMediaLimit=$maxMediaLimit")
            if (failureLogList.isNotEmpty()) {
                appendLine("\n--- FAILED FILES (${failureLogList.size}) ---")
                failureLogList.forEach { appendLine("• $it") }
            } else {
                appendLine("\nStatus: All files restored successfully with 0 errors.")
            }
        }
        AppLogger.recordRunManifest(context, manifest)

        // Final completion notification with persistent tap-to-open intent
        showCompletionNotification(notificationManager, totalFixed, actualFailed, photosFixed, videosFixed)

        Result.success(
            workDataOf(
                RESULT_TOTAL to totalFixed,
                RESULT_PHOTOS to photosFixed,
                RESULT_VIDEOS to videosFixed,
                RESULT_OVERLAYS_MERGED to overlaysMerged,
                RESULT_FAILED to actualFailed,
                RESULT_DUPLICATES_SKIPPED to duplicatesCleaned,
                RESULT_CHUNKS_MERGED to secondaryChunksMerged,
                RESULT_TOTAL_SCANNED to rawList.size
            )
        )
    }

    private suspend fun processZipExport(
        zipUris: List<Uri>,
        embedLocation: Boolean,
        mergeOverlays: Boolean,
        burnVideoOverlays: Boolean,
        mergeVideoChunks: Boolean,
        skipDuplicates: Boolean,
        timeZone: String,
        maxMediaLimit: Int = -1,
        notificationManager: NotificationManager
    ): Result {
        val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Snapchat Memories")
        if (!outputDir.exists()) outputDir.mkdirs()

        val cached = MediaScanner.lastScanResult
        val scanResult = if (cached != null && cached.isZipSource && cached.mediaList.isNotEmpty()) {
            cached
        } else {
            MediaScanner.scanZipUris(context, zipUris, targetTimeZone = timeZone)
        }
        val rawList = scanResult.mediaList
        val list = if (maxMediaLimit > 0) rawList.take(maxMediaLimit) else rawList
        val total = list.size

        if (total == 0) {
            return Result.success(
                workDataOf(
                    RESULT_TOTAL to 0,
                    RESULT_PHOTOS to 0,
                    RESULT_VIDEOS to 0,
                    RESULT_OVERLAYS_MERGED to 0,
                    RESULT_FAILED to 0,
                    RESULT_TOTAL_SCANNED to 0
                )
            )
        }

        // Multi-part chunk groups
        val chunkGroups = if (mergeVideoChunks) MediaScanner.detectVideoChunks(list) else emptyList()
        val chunkGroupMap = mutableMapOf<String, List<ScannedMedia>>()
        val secondaryChunkNames = mutableSetOf<String>()

        for (group in chunkGroups) {
            val primary = group.first()
            chunkGroupMap[primary.name] = group
            for (i in 1 until group.size) {
                secondaryChunkNames.add(group[i].name)
            }
        }

        val mediaByFileName = list.associateBy { it.name }
        val mediaByBaseKey = list.associateBy { MediaScanner.extractCanonicalBaseKey(it.name) }
        val overlayCacheDir = File(context.cacheDir, "zip_overlays").apply { mkdirs() }
        val chunkCacheDir = File(context.cacheDir, "zip_chunks").apply { mkdirs() }

        val totalChunkGroups = chunkGroupMap.size
        val totalWorkUnits = total + totalChunkGroups
        var workProgress = 0

        // Report total immediately so UI shows progress from the start
        setProgress(
            workDataOf(
                PROGRESS_CURRENT to 0,
                PROGRESS_TOTAL to totalWorkUnits,
                PROGRESS_MEDIA_CURRENT to 0,
                PROGRESS_MEDIA_TOTAL to total,
                PROGRESS_FILE to "Starting restoration...",
                PROGRESS_PHOTOS to 0,
                PROGRESS_VIDEOS to 0,
                PROGRESS_OVERLAYS to 0
            )
        )

        var processed = 0
        var photosFixed = 0
        var videosFixed = 0
        var overlaysMerged = 0
        var failedCount = 0
        val failureLogList = mutableListOf<String>()
        val writtenFilePaths = mutableListOf<String>()

        // Extract media and overlays in a single stream pass
        for (zipUri in zipUris) {
            try {
                context.contentResolver.openInputStream(zipUri)?.use { rawStream ->
                    ZipInputStream(BufferedInputStream(rawStream)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (isStopped) {
                                return Result.failure()
                            }
                            val name = entry.name
                            val baseName = name.substringAfterLast('/')
                            val mediaItem = mediaByFileName[baseName]

                            if (mediaItem != null) {
                                val isSecondary = secondaryChunkNames.contains(baseName)
                                val chunkGroup = chunkGroupMap[baseName]

                                val memory = mediaItem.matchedMemory
                                val epochMillis = memory?.parseEpochMillis()
                                    ?: mediaItem.mp4Meta?.creationEpochMillis
                                    ?: 0L
                                val coordinates = if (embedLocation) memory?.parseCoordinates() else null

                                // Skip duplicate photos or duplicate videos if skipDuplicates is true
                                if (skipDuplicates && mediaItem.isDuplicate) {
                                    processed++
                                    workProgress++
                                    if (workProgress % 5 == 0 || workProgress == total) {
                                        setProgress(
                                            workDataOf(
                                                PROGRESS_CURRENT to workProgress,
                                                PROGRESS_TOTAL to totalWorkUnits,
                                                PROGRESS_MEDIA_CURRENT to processed,
                                                PROGRESS_MEDIA_TOTAL to total,
                                                PROGRESS_FILE to baseName,
                                                PROGRESS_PHOTOS to photosFixed,
                                                PROGRESS_VIDEOS to videosFixed,
                                                PROGRESS_OVERLAYS to overlaysMerged
                                            )
                                        )
                                    }
                                    entry = zis.nextEntry
                                    continue
                                }

                                processed++
                                workProgress++
                                if (processed % 5 == 0 || processed == total) {
                                    val notif = createNotification(workProgress, totalWorkUnits, "Fixing: $baseName")
                                    notificationManager.notify(NOTIFICATION_ID, notif)
                                }

                                if (mediaItem.isVideo) {
                                    if (isSecondary || (chunkGroup != null && chunkGroup.size > 1)) {
                                        val tempChunkFile = File(chunkCacheDir, baseName)
                                        FileOutputStream(tempChunkFile).use { fos -> zis.copyTo(fos) }
                                    } else {
                                        val destFile = File(outputDir, baseName)
                                        FileOutputStream(destFile).use { fos -> zis.copyTo(fos) }

                                        val baseKey = MediaScanner.extractCanonicalBaseKey(baseName)
                                        val cachedOverlay = File(overlayCacheDir, "$baseKey.png")
                                        if (cachedOverlay.exists() && burnVideoOverlays) {
                                            val vMerged = VideoOverlayCompositor.compositeInPlace(
                                                context = context,
                                                videoFile = destFile,
                                                overlayFile = cachedOverlay
                                            )
                                            if (vMerged) {
                                                overlaysMerged++
                                            }
                                        }

                                        ExifManager.applyMetadataToVideo(context, Uri.fromFile(destFile), epochMillis, coordinates)
                                        writtenFilePaths.add(destFile.absolutePath)
                                        videosFixed++
                                    }
                                } else {
                                    val destFile = File(outputDir, baseName)
                                    FileOutputStream(destFile).use { fos -> zis.copyTo(fos) }

                                    val baseKey = MediaScanner.extractCanonicalBaseKey(baseName)
                                    val cachedOverlay = File(overlayCacheDir, "$baseKey.png")
                                    if (mergeOverlays && cachedOverlay.exists()) {
                                        val merged = OverlayCompositor.compositeInPlace(
                                            context = context,
                                            baseUri = Uri.fromFile(destFile),
                                            overlayUri = Uri.fromFile(cachedOverlay),
                                            cleanupOverlayFile = false
                                        )
                                        if (merged) overlaysMerged++
                                    }

                                    ExifManager.applyMetadataToImage(
                                        context = context,
                                        uri = Uri.fromFile(destFile),
                                        epochMillis = epochMillis,
                                        coordinates = coordinates,
                                        timeZoneId = timeZone
                                    )
                                    writtenFilePaths.add(destFile.absolutePath)
                                    photosFixed++
                                }

                                if (workProgress % 5 == 0 || workProgress == total) {
                                    setProgress(
                                        workDataOf(
                                            PROGRESS_CURRENT to workProgress,
                                            PROGRESS_TOTAL to totalWorkUnits,
                                            PROGRESS_MEDIA_CURRENT to processed,
                                            PROGRESS_MEDIA_TOTAL to total,
                                            PROGRESS_FILE to baseName,
                                            PROGRESS_PHOTOS to photosFixed,
                                            PROGRESS_VIDEOS to videosFixed,
                                            PROGRESS_OVERLAYS to overlaysMerged
                                        )
                                    )
                                }
                            } else if (name.contains("overlay", ignoreCase = true) && name.endsWith(".png", ignoreCase = true)) {
                                val baseKey = MediaScanner.extractCanonicalBaseKey(baseName)
                                val overlayFile = File(overlayCacheDir, "$baseKey.png")
                                FileOutputStream(overlayFile).use { fos -> zis.copyTo(fos) }

                                // If the matching media was already extracted before this overlay in the ZIP:
                                val matchingMedia = mediaByBaseKey[baseKey]
                                if (matchingMedia != null && !matchingMedia.isVideo) {
                                    val destFile = File(outputDir, matchingMedia.name)
                                    if (destFile.exists() && mergeOverlays) {
                                        val merged = OverlayCompositor.compositeInPlace(
                                            context = context,
                                            baseUri = Uri.fromFile(destFile),
                                            overlayUri = Uri.fromFile(overlayFile),
                                            cleanupOverlayFile = false
                                        )
                                        if (merged) {
                                            overlaysMerged++
                                            val memory = matchingMedia.matchedMemory
                                            val epochMillis = memory?.parseEpochMillis()
                                                ?: matchingMedia.mp4Meta?.creationEpochMillis
                                                ?: 0L
                                            val coordinates = if (embedLocation) memory?.parseCoordinates() else null
                                            ExifManager.applyMetadataToImage(
                                                context = context,
                                                uri = Uri.fromFile(destFile),
                                                epochMillis = epochMillis,
                                                coordinates = coordinates,
                                                timeZoneId = timeZone
                                            )
                                        }
                                    }
                                } else if (matchingMedia != null && matchingMedia.isVideo) {
                                    val isSecondary = secondaryChunkNames.contains(matchingMedia.name)
                                    val chunkGroup = chunkGroupMap[matchingMedia.name]
                                    if (!isSecondary && (chunkGroup == null || chunkGroup.size <= 1)) {
                                        val destFile = File(outputDir, matchingMedia.name)
                                        if (destFile.exists() && burnVideoOverlays) {
                                            val vMerged = VideoOverlayCompositor.compositeInPlace(
                                                context = context,
                                                videoFile = destFile,
                                                overlayFile = overlayFile
                                            )
                                            if (vMerged) {
                                                overlaysMerged++
                                                val memory = matchingMedia.matchedMemory
                                                val epochMillis = memory?.parseEpochMillis()
                                                    ?: matchingMedia.mp4Meta?.creationEpochMillis
                                                    ?: DateExtractor.extractEpochMillisFromFileName(matchingMedia.name, timeZone)
                                                    ?: 0L
                                                val coordinates = if (embedLocation) memory?.parseCoordinates() else null
                                                ExifManager.applyMetadataToVideo(
                                                    context = context,
                                                    uri = Uri.fromFile(destFile),
                                                    epochMillis = epochMillis,
                                                    coordinates = coordinates
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Step 3: Fast remux multi-part video chunk groups in chronological order
        var groupIndex = 0
        for ((primaryName, group) in chunkGroupMap) {
            groupIndex++
            workProgress++
            val statusMsg = "Stitching video stories ($groupIndex of $totalChunkGroups)..."
            setProgress(
                workDataOf(
                    PROGRESS_CURRENT to workProgress,
                    PROGRESS_TOTAL to totalWorkUnits,
                    PROGRESS_MEDIA_CURRENT to total,
                    PROGRESS_MEDIA_TOTAL to total,
                    PROGRESS_FILE to statusMsg,
                    PROGRESS_PHOTOS to photosFixed,
                    PROGRESS_VIDEOS to videosFixed,
                    PROGRESS_OVERLAYS to overlaysMerged
                )
            )
            val notif = createNotification(workProgress, totalWorkUnits, statusMsg)
            notificationManager.notify(NOTIFICATION_ID, notif)

            val chunkFiles = group.map { File(chunkCacheDir, it.name) }
            val allChunksExist = chunkFiles.all { it.exists() && it.length() > 0 }
            if (!allChunksExist || chunkFiles.isEmpty()) continue

            val primaryDestFile = File(outputDir, primaryName)

            val candidatesRaw = chunkFiles.map { file ->
                val media = mediaByFileName[file.name] ?: group.first { it.name == file.name }
                val meta = ExifManager.readMp4Meta(file)
                val creation = meta?.creationEpochMillis
                    ?: media.matchedMemory?.parseEpochMillis()
                    ?: 0L
                val duration = meta?.durationSeconds ?: 0f
                ChunkCandidate(file, media, creation, duration)
            }.sortedWith(
                compareBy<ChunkCandidate> { it.creationEpoch }
                    .thenBy { if (it.durationSeconds in 7.5f..12.5f) 0 else 1 }
                    .thenBy { it.file.name }
            )

            // Helper to detect if two candidates are duplicate variations of the exact same video:
            // 1. Identical file length (< 500B difference)
            // 2. OR matching duration down to decimals (< 0.05s diff) AND either:
            //    a) Both are standalone clips (not ~10s recording buffers, e.g. dur < 7.5s)
            //    b) Or recorded within 1.5 seconds of each other (duplicate exports of the same chunk)
            fun isDuplicateOf(c1: ChunkCandidate, c2: ChunkCandidate): Boolean {
                val sizeDiff = Math.abs(c1.file.length() - c2.file.length())
                if (sizeDiff < 500L) return true
                val durDiff = Math.abs(c1.durationSeconds - c2.durationSeconds)
                if (durDiff >= 0.05f) return false
                val timeDiff = Math.abs(c1.creationEpoch - c2.creationEpoch)
                val isStandalone = c1.durationSeconds < 7.5f && c2.durationSeconds < 7.5f
                return isStandalone || timeDiff <= 1500L
            }

            // Deduplicate candidates if skipDuplicates is true (keeping the higher-quality / largest file)
            val candidates = if (skipDuplicates) {
                val unique = mutableListOf<ChunkCandidate>()
                val sortedByQuality = candidatesRaw.sortedByDescending { it.file.length() }
                for (cand in sortedByQuality) {
                    if (unique.none { isDuplicateOf(it, cand) }) {
                        unique.add(cand)
                    }
                }
                unique.sortedWith(
                    compareBy<ChunkCandidate> { it.creationEpoch }
                        .thenBy { if (it.durationSeconds in 7.5f..12.5f) 0 else 1 }
                        .thenBy { it.file.name }
                )
            } else {
                candidatesRaw
            }

            // Verify Snapchat multi-snap continuous recording criteria:
            // 1. Initial chunks are in full ~10s recording buffers (7.5s..12.5s)
            // 2. Final chunk is up to 12.5s (> 0.5s)
            val initialChunksValid = candidates.dropLast(1).all { it.durationSeconds in 7.5f..12.5f }
            val lastChunkValid = candidates.last().durationSeconds in 0.5f..12.5f
            val isTrueMultiSnap = candidates.size > 1 && initialChunksValid && lastChunkValid

            var mergedSuccessfully = false

            if (isTrueMultiSnap) {
                val orderedFiles = candidates.map { it.file }
                val primaryMedia = mediaByFileName[primaryName]
                val chunkCoords = if (embedLocation) {
                    primaryMedia?.matchedMemory?.parseCoordinates()
                        ?: candidates.firstOrNull { it.media.matchedMemory?.parseCoordinates() != null }?.media?.matchedMemory?.parseCoordinates()
                } else null
                val merged = VideoChunkMerger.fastRemuxChunks(orderedFiles, primaryDestFile, chunkCoords)
                if (merged) {
                    mergedSuccessfully = true

                    // Look for overlay on primary or any of the candidate chunks
                    var overlayToApply: File? = null
                    val primaryBaseKey = MediaScanner.extractCanonicalBaseKey(primaryName)
                    val primaryOverlay = File(overlayCacheDir, "$primaryBaseKey.png")
                    if (primaryOverlay.exists()) {
                        overlayToApply = primaryOverlay
                    } else {
                        for (c in candidates) {
                            val cKey = MediaScanner.extractCanonicalBaseKey(c.file.name)
                            val cOverlay = File(overlayCacheDir, "$cKey.png")
                            if (cOverlay.exists()) {
                                overlayToApply = cOverlay
                                break
                            }
                        }
                    }

                    if (overlayToApply != null && overlayToApply.exists() && burnVideoOverlays) {
                        val vMerged = VideoOverlayCompositor.compositeInPlace(
                            context = context,
                            videoFile = primaryDestFile,
                            overlayFile = overlayToApply
                        )
                        if (vMerged) {
                            overlaysMerged++
                        }
                    }

                    val epochMillis = primaryMedia?.matchedMemory?.parseEpochMillis()
                        ?: candidates.first().creationEpoch
                    ExifManager.applyMetadataToVideo(context, Uri.fromFile(primaryDestFile), epochMillis, chunkCoords)
                    writtenFilePaths.add(primaryDestFile.absolutePath)
                    videosFixed++
                }
            }

            if (!mergedSuccessfully) {
                // Standalone fallback: preserve each unique candidate independently
                val filesToSave = candidates

                // Standalone fallback: preserve each chunk independently
                for (chunk in filesToSave) {
                    val destFile = File(outputDir, chunk.file.name)
                    chunk.file.copyTo(destFile, overwrite = true)

                    val cKey = MediaScanner.extractCanonicalBaseKey(chunk.file.name)
                    val cOverlay = File(overlayCacheDir, "$cKey.png")
                    if (cOverlay.exists() && burnVideoOverlays) {
                        val vMerged = VideoOverlayCompositor.compositeInPlace(
                            context = context,
                            videoFile = destFile,
                            overlayFile = cOverlay
                        )
                        if (vMerged) {
                            overlaysMerged++
                        }
                    }

                    val epoch = chunk.media.matchedMemory?.parseEpochMillis() ?: chunk.creationEpoch
                    val chunkCoords = if (embedLocation) chunk.media.matchedMemory?.parseCoordinates() else null
                    ExifManager.applyMetadataToVideo(context, Uri.fromFile(destFile), epoch, chunkCoords)
                    writtenFilePaths.add(destFile.absolutePath)
                    videosFixed++
                }
            }
        }

        overlayCacheDir.deleteRecursively()
        chunkCacheDir.deleteRecursively()

        workProgress = totalWorkUnits
        setProgress(
            workDataOf(
                PROGRESS_CURRENT to totalWorkUnits,
                PROGRESS_TOTAL to totalWorkUnits,
                PROGRESS_MEDIA_CURRENT to total,
                PROGRESS_MEDIA_TOTAL to total,
                PROGRESS_FILE to "Finalizing gallery indexing...",
                PROGRESS_PHOTOS to photosFixed,
                PROGRESS_VIDEOS to videosFixed,
                PROGRESS_OVERLAYS to overlaysMerged
            )
        )

        // Batch scan all written files into the system media store to prevent IPC thread crash
        if (writtenFilePaths.isNotEmpty()) {
            writtenFilePaths.distinct().chunked(100).forEach { batch ->
                try {
                    MediaScannerConnection.scanFile(
                        context.applicationContext,
                        batch.toTypedArray(),
                        null,
                        null
                    )
                } catch (e: Throwable) {
                    AppLogger.w(TAG, "Batch media scan warning: ${e.message}")
                }
            }
        }

        val totalFixed = photosFixed + videosFixed
        val duplicatesCleaned = if (skipDuplicates) list.count { it.isDuplicate } else 0
        val secondaryChunksMerged = secondaryChunkNames.size
        val actualFailed = failureLogList.size
        failedCount = actualFailed

        val manifest = buildString {
            appendLine("EXPORT RUN MANIFEST (ZIP EXPORT)")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Total Files Scanned: ${rawList.size}")
            appendLine("Processed in Run: $total")
            appendLine("Photos Fixed: $photosFixed")
            appendLine("Videos Fixed: $videosFixed")
            appendLine("Overlays Merged: $overlaysMerged")
            appendLine("Duplicates Cleaned: $duplicatesCleaned")
            appendLine("Video Chunks Stitched: $secondaryChunksMerged")
            appendLine("Failed Count: $actualFailed")
            appendLine("Settings: embedLocation=$embedLocation, mergeOverlays=$mergeOverlays, burnVideoOverlays=$burnVideoOverlays, mergeVideoChunks=$mergeVideoChunks, maxMediaLimit=$maxMediaLimit")
            if (failureLogList.isNotEmpty()) {
                appendLine("\n--- FAILED FILES (${failureLogList.size}) ---")
                failureLogList.forEach { appendLine("• $it") }
            } else {
                appendLine("\nStatus: All files restored successfully with 0 errors.")
            }
        }
        AppLogger.recordRunManifest(context, manifest)

        // Final completion notification with persistent tap-to-open intent
        showCompletionNotification(notificationManager, totalFixed, actualFailed, photosFixed, videosFixed)

        return Result.success(
            workDataOf(
                RESULT_TOTAL to totalFixed,
                RESULT_PHOTOS to photosFixed,
                RESULT_VIDEOS to videosFixed,
                RESULT_OVERLAYS_MERGED to overlaysMerged,
                RESULT_FAILED to actualFailed,
                RESULT_DUPLICATES_SKIPPED to duplicatesCleaned,
                RESULT_CHUNKS_MERGED to secondaryChunksMerged,
                RESULT_TOTAL_SCANNED to rawList.size
            )
        )
    }

    private fun createNotification(current: Int, total: Int, text: String): android.app.Notification {
        val pct = if (total > 0) ((current.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
        val intent = Intent(context, com.apadev.memoryfix.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fixing Memories ($pct%)")
            .setContentText("$current of $total • $text")
            .setProgress(total, current, false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Progress channel
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                "Memory Fixing Progress",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows live progress while repairing photo timestamps and overlays"
            }
            manager.createNotificationChannel(progressChannel)

            // Completion alert channel (high importance with sound and banner)
            val completeChannel = NotificationChannel(
                CHANNEL_COMPLETE_ID,
                "Restoration Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when photo and video restoration is complete"
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(completeChannel)
        }
    }

    private fun showCompletionNotification(
        notificationManager: NotificationManager,
        totalFixed: Int,
        failedCount: Int,
        photosFixed: Int,
        videosFixed: Int
    ) {
        // Dismiss the ongoing progress notification so it doesn't linger
        notificationManager.cancel(NOTIFICATION_ID)

        val intent = Intent(context, com.apadev.memoryfix.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val title = if (failedCount == 0) "🎉 Restoration Complete!" else "Restoration Finished with Warnings"
        val message = if (failedCount == 0) {
            "Successfully fixed $totalFixed memories ($photosFixed photos, $videosFixed videos) to Pictures > Snapchat Memories."
        } else {
            "Fixed $totalFixed memories ($photosFixed photos, $videosFixed videos) to Pictures > Snapchat Memories. $failedCount items had issues."
        }

        val doneNotification = NotificationCompat.Builder(context, CHANNEL_COMPLETE_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_COMPLETE_ID, doneNotification)
    }

    private fun showFailureNotification(notificationManager: NotificationManager, reason: String) {
        // Dismiss the ongoing progress notification
        notificationManager.cancel(NOTIFICATION_ID)

        val intent = Intent(context, com.apadev.memoryfix.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val failureNotification = NotificationCompat.Builder(context, CHANNEL_COMPLETE_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MemoryFix Encountered an Issue")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_COMPLETE_ID, failureNotification)
    }

    private data class ChunkCandidate(
        val file: File,
        val media: ScannedMedia,
        val creationEpoch: Long,
        val durationSeconds: Float
    )
}

