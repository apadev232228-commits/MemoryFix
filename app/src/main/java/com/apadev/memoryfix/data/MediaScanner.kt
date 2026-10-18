package com.apadev.memoryfix.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.apadev.memoryfix.model.MemoryItem
import com.apadev.memoryfix.model.ScanStats
import java.io.File
import java.util.TimeZone

data class ScannedMedia(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val size: Long,
    var overlayUri: Uri? = null,
    var matchedMemory: MemoryItem? = null
)

data class FullScanResult(
    val stats: ScanStats,
    val mediaList: List<ScannedMedia>,
    val jsonUri: Uri?,
    val rootFolderUri: Uri
)

object MediaScanner {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp")

    /**
     * Scans an SAF Tree folder recursively.
     */
    fun scanTree(context: Context, treeUri: Uri): FullScanResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return FullScanResult(ScanStats(), emptyList(), null, treeUri)

        var jsonDoc: DocumentFile? = null
        val mediaDocs = mutableListOf<DocumentFile>()
        val overlayDocs = mutableMapOf<String, DocumentFile>()

        fun walk(dir: DocumentFile) {
            val children = dir.listFiles()
            for (file in children) {
                if (file.isDirectory) {
                    walk(file)
                } else {
                    val name = file.name ?: continue
                    val lower = name.lowercase()

                    if (lower == "memories_history.json") {
                        jsonDoc = file
                    } else if (lower.contains("overlay") && lower.endsWith(".png")) {
                        // Normalize base key: e.g. "2023-08-14_19-42-10-overlay.png" -> "2023-08-14_19-42-10"
                        val baseKey = lower.replace("-overlay.png", "")
                            .replace("_overlay.png", "")
                            .replace("overlay_", "")
                        overlayDocs[baseKey] = file
                    } else {
                        val ext = lower.substringAfterLast('.', "")
                        if (IMAGE_EXTENSIONS.contains(ext) || VIDEO_EXTENSIONS.contains(ext)) {
                            mediaDocs.add(file)
                        }
                    }
                }
            }
        }

        walk(root)

        // Parse JSON if found
        val memoryItems: List<MemoryItem> = jsonDoc?.let {
            SnapchatHistoryParser.parseFromUri(context, it.uri)
        } ?: emptyList()

        // Match media files
        var photoCount = 0
        var videoCount = 0
        var matchedCount = 0
        var overlayMatchedCount = 0
        val sizeMap = mutableMapOf<Long, Int>()
        var duplicateCount = 0

        val mediaList = mutableListOf<ScannedMedia>()

        for (doc in mediaDocs) {
            val name = doc.name ?: continue
            val lower = name.lowercase()
            val ext = lower.substringAfterLast('.', "")
            val isVideo = VIDEO_EXTENSIONS.contains(ext)

            if (isVideo) videoCount++ else photoCount++

            // Duplicate detection by exact byte size
            val size = doc.length()
            val seen = sizeMap.getOrDefault(size, 0)
            if (seen > 0) duplicateCount++
            sizeMap[size] = seen + 1

            // Overlay matching
            val baseNameWithoutExt = lower.substringBeforeLast('.')
            val overlay = overlayDocs[baseNameWithoutExt]
            if (overlay != null) overlayMatchedCount++

            // Match with memory items (best effort by date string in filename)
            val matched = findMatchingMemory(name, memoryItems)
            if (matched != null) matchedCount++

            mediaList.add(
                ScannedMedia(
                    uri = doc.uri,
                    name = name,
                    isVideo = isVideo,
                    size = size,
                    overlayUri = overlay?.uri,
                    matchedMemory = matched
                )
            )
        }

        val detectedTimezone = TimeZone.getDefault().id
        val totalFiles = mediaDocs.size
        val estimatedSec = (totalFiles * 0.05).toInt().coerceAtLeast(1)

        val stats = ScanStats(
            totalMediaFiles = totalFiles,
            photoCount = photoCount,
            videoCount = videoCount,
            matchedCount = matchedCount,
            overlayCount = overlayMatchedCount,
            duplicateCount = duplicateCount,
            missingMetadataCount = (totalFiles - matchedCount).coerceAtLeast(0),
            detectedTimezone = detectedTimezone,
            estimatedTimeSeconds = estimatedSec
        )

        return FullScanResult(
            stats = stats,
            mediaList = mediaList,
            jsonUri = jsonDoc?.uri,
            rootFolderUri = treeUri
        )
    }

    /**
     * Matches filename like "2023-08-14_19-42-10-main.jpg" with MemoryItem with date "2023-08-14 19:42:10 UTC"
     */
    private fun findMatchingMemory(fileName: String, items: List<MemoryItem>): MemoryItem? {
        if (items.isEmpty()) return null
        val normalizedName = fileName.replace("-main", "").replace("_main", "")
        for (item in items) {
            val date = item.dateString ?: continue
            // "2023-08-14 19:42:10 UTC" -> "2023-08-14_19-42-10" or "2023-08-14"
            val targetKey = date.replace(" UTC", "")
                .replace(" ", "_")
                .replace(":", "-")
            if (normalizedName.contains(targetKey)) {
                return item
            }
        }
        return null
    }
}
