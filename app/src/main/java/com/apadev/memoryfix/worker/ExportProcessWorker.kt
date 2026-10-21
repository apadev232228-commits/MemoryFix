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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExportProcessWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TREE_URI = "KEY_TREE_URI"
        const val KEY_EMBED_LOCATION = "KEY_EMBED_LOCATION"
        const val KEY_MERGE_OVERLAYS = "KEY_MERGE_OVERLAYS"
        const val KEY_BURN_VIDEO_OVERLAYS = "KEY_BURN_VIDEO_OVERLAYS"
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
        val treeUriString = inputData.getString(KEY_TREE_URI) ?: return@withContext Result.failure()
        val embedLocation = inputData.getBoolean(KEY_EMBED_LOCATION, true)
        val mergeOverlays = inputData.getBoolean(KEY_MERGE_OVERLAYS, true)
        val burnVideoOverlays = inputData.getBoolean(KEY_BURN_VIDEO_OVERLAYS, false)
        val timeZone = inputData.getString(KEY_TIMEZONE) ?: "UTC"

        val treeUri = Uri.parse(treeUriString)

        createNotificationChannel()
        val initialNotification = createNotification(0, 100, "Scanning files...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } else {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification))
        }

        val scope = this
        val scanResult = MediaScanner.scanTree(context, treeUri, targetTimeZone = timeZone) { scanProgress ->
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

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        for (item in list) {
            if (isStopped) {
                return@withContext Result.failure()
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
                // 1. Merge overlay if requested and present
                if (mergeOverlays && item.overlayUri != null) {
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
