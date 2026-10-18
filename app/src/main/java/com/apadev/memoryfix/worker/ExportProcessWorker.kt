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
import com.apadev.memoryfix.data.ExifManager
import com.apadev.memoryfix.data.MediaScanner
import com.apadev.memoryfix.data.OverlayCompositor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExportProcessWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TREE_URI = "KEY_TREE_URI"
        const val KEY_EMBED_LOCATION = "KEY_EMBED_LOCATION"
        const val KEY_MERGE_OVERLAYS = "KEY_MERGE_OVERLAYS"
        const val KEY_TIMEZONE = "KEY_TIMEZONE"

        const val PROGRESS_CURRENT = "PROGRESS_CURRENT"
        const val PROGRESS_TOTAL = "PROGRESS_TOTAL"
        const val PROGRESS_FILE = "PROGRESS_FILE"

        const val CHANNEL_ID = "memory_fix_channel"
        const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val treeUriString = inputData.getString(KEY_TREE_URI) ?: return@withContext Result.failure()
        val embedLocation = inputData.getBoolean(KEY_EMBED_LOCATION, true)
        val mergeOverlays = inputData.getBoolean(KEY_MERGE_OVERLAYS, true)
        val timeZone = inputData.getString(KEY_TIMEZONE) ?: "UTC"

        val treeUri = Uri.parse(treeUriString)

        createNotificationChannel()
        val initialNotification = createNotification(0, 100, "Scanning files...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } else {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification))
        }

        val scanResult = MediaScanner.scanTree(context, treeUri)
        val list = scanResult.mediaList
        val total = list.size

        if (total == 0) {
            return@withContext Result.success()
        }

        var processed = 0
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        for (item in list) {
            if (isStopped) {
                return@withContext Result.failure()
            }

            processed++
            val fileName = item.name

            // Update Progress in WorkManager
            setProgress(
                workDataOf(
                    PROGRESS_CURRENT to processed,
                    PROGRESS_TOTAL to total,
                    PROGRESS_FILE to fileName
                )
            )

            // Update Notification every 5 items or on last
            if (processed % 5 == 0 || processed == total) {
                val notif = createNotification(processed, total, "Fixing: $fileName")
                notificationManager.notify(NOTIFICATION_ID, notif)
            }

            try {
                // 1. Merge overlay if requested and present
                if (mergeOverlays && item.overlayUri != null && !item.isVideo) {
                    // For overlay, composite onto base file
                    OverlayCompositor.compositeUris(
                        context,
                        item.uri,
                        item.overlayUri!!,
                        item.uri
                    )
                }

                // 2. Apply dates & GPS
                val memory = item.matchedMemory
                val epochMillis = memory?.parseEpochMillis()
                val coordinates = if (embedLocation) memory?.parseCoordinates() else null

                if (!item.isVideo) {
                    ExifManager.applyMetadataToImage(
                        context = context,
                        uri = item.uri,
                        epochMillis = epochMillis,
                        coordinates = coordinates,
                        timeZoneId = timeZone
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Final completion notification
        val doneNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fix Completed!")
            .setContentText("Successfully fixed $processed memories.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, doneNotification)

        Result.success()
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
