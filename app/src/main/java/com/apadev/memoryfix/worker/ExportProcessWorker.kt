package com.apadev.memoryfix.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExportProcessWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TREE_URI = "KEY_TREE_URI"
        const val KEY_ZIP_URIS = "KEY_ZIP_URIS"
        const val KEY_EMBED_LOCATION = "KEY_EMBED_LOCATION"
        const val KEY_MERGE_OVERLAYS = "KEY_MERGE_OVERLAYS"
        const val KEY_BURN_VIDEO_OVERLAYS = "KEY_BURN_VIDEO_OVERLAYS"
        const val KEY_MERGE_VIDEO_CHUNKS = "KEY_MERGE_VIDEO_CHUNKS"
        const val KEY_TIMEZONE = "KEY_TIMEZONE"

        const val PROGRESS_CURRENT = "PROGRESS_CURRENT"
        const val PROGRESS_TOTAL = "PROGRESS_TOTAL"
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

        const val CHANNEL_ID = "memory_fix_channel"
        const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val zipUriStrings = inputData.getStringArray(KEY_ZIP_URIS)
        val treeUriString = inputData.getString(KEY_TREE_URI)
        if (zipUriStrings.isNullOrEmpty() && treeUriString == null) return@withContext Result.failure()

        val embedLocation = inputData.getBoolean(KEY_EMBED_LOCATION, true)
        val mergeOverlays = inputData.getBoolean(KEY_MERGE_OVERLAYS, true)
        val burnVideoOverlays = inputData.getBoolean(KEY_BURN_VIDEO_OVERLAYS, false)
        val mergeVideoChunks = inputData.getBoolean(KEY_MERGE_VIDEO_CHUNKS, true)
        val timeZone = inputData.getString(KEY_TIMEZONE) ?: "UTC"

        createNotificationChannel()
        val initialNotification = createNotification(0, 100, "Scanning files...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } else {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification))
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!zipUriStrings.isNullOrEmpty()) {
            return@withContext processZipExport(
                zipUris = zipUriStrings.map { Uri.parse(it) },
                embedLocation = embedLocation,
                mergeOverlays = mergeOverlays,
                burnVideoOverlays = burnVideoOverlays,
                mergeVideoChunks = mergeVideoChunks,
                timeZone = timeZone,
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
        val list = scanResult.mediaList
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

        for (item in list) {
            if (isStopped) {
                return@withContext Result.failure()
            }

            // Skip secondary chunks that were successfully merged into their primary video
            if (mergedSecondaryUris.contains(item.uri)) {
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
                        epochMillis = epochMillis
                    )
                    if (vSuccess) {
                        videosFixed++
                        itemFixed = true
                    }
                    // If video overlay was not burned into video, sync its date and GPS so it sits next to the video
                    if (item.overlayUri != null && !burnVideoOverlays) {
                        ExifManager.applyMetadataToImage(
                            context = context,
                            uri = item.overlayUri!!,
                            epochMillis = epochMillis,
                            coordinates = coordinates,
                            timeZoneId = timeZone
                        )
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
                e.printStackTrace()
            }

            if (!itemFixed) {
                failedCount++
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

        // Final completion notification
        val doneNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fix Completed!")
            .setContentText("Completed: $totalFixed successful, $failedCount failed ($photosFixed photos, $videosFixed videos).")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, doneNotification)

        Result.success(
            workDataOf(
                RESULT_TOTAL to totalFixed,
                RESULT_PHOTOS to photosFixed,
                RESULT_VIDEOS to videosFixed,
                RESULT_OVERLAYS_MERGED to overlaysMerged,
                RESULT_FAILED to failedCount,
                RESULT_TOTAL_SCANNED to total
            )
        )
    }

    private suspend fun processZipExport(
        zipUris: List<Uri>,
        embedLocation: Boolean,
        mergeOverlays: Boolean,
        burnVideoOverlays: Boolean,
        mergeVideoChunks: Boolean,
        timeZone: String,
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
        val list = scanResult.mediaList
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
        val overlayCacheDir = File(context.cacheDir, "zip_overlays").apply { mkdirs() }
        val chunkCacheDir = File(context.cacheDir, "zip_chunks").apply { mkdirs() }

        var processed = 0
        var photosFixed = 0
        var videosFixed = 0
        var overlaysMerged = 0
        var failedCount = 0

        // Step 1: Pre-extract overlays to cacheDir (~1 second for 164 overlays)
        for (zipUri in zipUris) {
            try {
                context.contentResolver.openInputStream(zipUri)?.use { rawStream ->
                    ZipInputStream(BufferedInputStream(rawStream)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (name.contains("overlay", ignoreCase = true) && name.endsWith(".png", ignoreCase = true)) {
                                val baseName = name.substringAfterLast('/')
                                val baseKey = MediaScanner.extractCanonicalBaseKey(baseName)
                                val overlayFile = File(overlayCacheDir, "$baseKey.png")
                                FileOutputStream(overlayFile).use { fos -> zis.copyTo(fos) }
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Step 2: Extract & fix media in one sequential stream pass
        for (zipUri in zipUris) {
            try {
                context.contentResolver.openInputStream(zipUri)?.use { rawStream ->
                    ZipInputStream(BufferedInputStream(rawStream)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            val baseName = name.substringAfterLast('/')
                            val mediaItem = mediaByFileName[baseName]

                            if (mediaItem != null) {
                                processed++
                                if (processed % 5 == 0 || processed == total) {
                                    val notif = createNotification(processed, total, "Fixing: $baseName")
                                    notificationManager.notify(NOTIFICATION_ID, notif)
                                }

                                val isSecondary = secondaryChunkNames.contains(baseName)
                                val chunkGroup = chunkGroupMap[baseName]

                                val memory = mediaItem.matchedMemory
                                val epochMillis = memory?.parseEpochMillis()
                                    ?: mediaItem.mp4Meta?.creationEpochMillis
                                    ?: 0L
                                val coordinates = if (embedLocation) memory?.parseCoordinates() else null

                                if (mediaItem.isVideo) {
                                    if (isSecondary || (chunkGroup != null && chunkGroup.size > 1)) {
                                        val tempChunkFile = File(chunkCacheDir, baseName)
                                        FileOutputStream(tempChunkFile).use { fos -> zis.copyTo(fos) }
                                    } else {
                                        val destFile = File(outputDir, baseName)
                                        FileOutputStream(destFile).use { fos -> zis.copyTo(fos) }
                                        ExifManager.applyMetadataToVideo(context, Uri.fromFile(destFile), epochMillis)
                                        MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
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
                                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
                                    photosFixed++
                                }

                                setProgress(
                                    workDataOf(
                                        PROGRESS_CURRENT to processed,
                                        PROGRESS_TOTAL to total,
                                        PROGRESS_FILE to baseName,
                                        PROGRESS_PHOTOS to photosFixed,
                                        PROGRESS_VIDEOS to videosFixed,
                                        PROGRESS_OVERLAYS to overlaysMerged
                                    )
                                )
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
        for ((primaryName, group) in chunkGroupMap) {
            val chunkFiles = group.map { File(chunkCacheDir, it.name) }
            val allChunksExist = chunkFiles.all { it.exists() && it.length() > 0 }
            val primaryDestFile = File(outputDir, primaryName)

            if (allChunksExist && chunkFiles.size > 1) {
                val merged = VideoChunkMerger.fastRemuxChunks(chunkFiles, primaryDestFile)
                if (merged) {
                    val primaryMedia = mediaByFileName[primaryName]
                    val epochMillis = primaryMedia?.matchedMemory?.parseEpochMillis()
                        ?: primaryMedia?.mp4Meta?.creationEpochMillis
                        ?: 0L
                    ExifManager.applyMetadataToVideo(context, Uri.fromFile(primaryDestFile), epochMillis)
                    MediaScannerConnection.scanFile(context, arrayOf(primaryDestFile.absolutePath), null, null)
                    videosFixed++
                }
            }
        }

        overlayCacheDir.deleteRecursively()
        chunkCacheDir.deleteRecursively()

        val totalFixed = photosFixed + videosFixed

        val doneNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fix Completed!")
            .setContentText("Completed: $totalFixed successful, $failedCount failed ($photosFixed photos, $videosFixed videos).")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, doneNotification)

        return Result.success(
            workDataOf(
                RESULT_TOTAL to totalFixed,
                RESULT_PHOTOS to photosFixed,
                RESULT_VIDEOS to videosFixed,
                RESULT_OVERLAYS_MERGED to overlaysMerged,
                RESULT_FAILED to failedCount,
                RESULT_TOTAL_SCANNED to total
            )
        )
    }

    private fun createNotification(current: Int, total: Int, text: String): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MemoryFix is working...")
            .setContentText(text)
            .setProgress(total, current, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Memory Fixing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while repairing photo timestamps and overlays"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

