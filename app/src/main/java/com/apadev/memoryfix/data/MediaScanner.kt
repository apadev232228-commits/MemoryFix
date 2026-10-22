package com.apadev.memoryfix.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.apadev.memoryfix.model.MemoryItem
import com.apadev.memoryfix.model.ScanStats
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

data class ScannedMedia(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val size: Long,
    var overlayUri: Uri? = null,
    var matchedMemory: MemoryItem? = null,
    var mp4Meta: ExifManager.Mp4Meta? = null,
    var zipEntryPath: String? = null,
    var zipUri: Uri? = null,
    var isDuplicate: Boolean = false
)

data class FullScanResult(
    val stats: ScanStats,
    val mediaList: List<ScannedMedia>,
    val jsonUri: Uri?,
    val rootFolderUri: Uri,
    val zipUris: List<Uri> = emptyList(),
    val isZipSource: Boolean = false
)



data class ScanProgress(
    val totalFound: Int = 0,
    val photosFound: Int = 0,
    val videosFound: Int = 0,
    val overlaysFound: Int = 0,
    val duplicatesFound: Int = 0,
    val matchedCount: Int = 0,
    val currentFileName: String = "",
    val isMatchingPhase: Boolean = false,
    val isComplete: Boolean = false
)

object MediaScanner {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp")
    private val UUID_REGEX = Regex("""([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})""")

    /**
     * Extracts the 36-character Snapchat memory UUID from a file name.
     * Example: "2021-04-02_0479e3b9-ceaa-9376-3625-9cdcaea3d347-main.jpg" -> "0479e3b9-ceaa-9376-3625-9cdcaea3d347"
     */
    fun extractSnapchatUuid(fileName: String): String? {
        return UUID_REGEX.find(fileName)?.value?.lowercase()
    }

    /**
     * Extracts a canonical base key to match main photos and overlay PNGs.
     * Example: "2023-08-14_19-42-10-main.jpg" -> "2023-08-14_19-42-10"
     * Example: "2023-08-14_19-42-10-overlay.png" -> "2023-08-14_19-42-10"
     */
    fun extractCanonicalBaseKey(fileName: String): String {
        val lower = fileName.lowercase()
        val nameWithoutExt = lower.substringBeforeLast('.')
        return nameWithoutExt
            .replace("-main", "")
            .replace("_main", "")
            .replace("-overlay", "")
            .replace("_overlay", "")
            .replace("overlay-", "")
            .replace("overlay_", "")
            .trim()
    }

    /**
     * Scans an SAF Tree folder recursively with real-time live progress updates.
     */
    fun scanTree(
        context: Context,
        treeUri: Uri,
        targetTimeZone: String = TimeZone.getDefault().id,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): FullScanResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return FullScanResult(ScanStats(), emptyList(), null, treeUri)

        val metadataDocs = mutableListOf<DocumentFile>()
        val rawMediaDocs = mutableListOf<DocumentFile>()
        val overlayDocs = mutableMapOf<String, DocumentFile>()

        var overlayCount = 0

        fun isMetadataFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower == "memories_history.json" ||
                   lower == "memories_history.html" ||
                   lower == "memories.json" ||
                   lower == "saved_media.json" ||
                   lower == "saved_media.html"
        }

        fun walk(dir: DocumentFile) {
            val children = dir.listFiles()
            for (file in children) {
                if (file.isDirectory) {
                    walk(file)
                } else {
                    val name = file.name ?: continue
                    val lower = name.lowercase()

                    if (isMetadataFile(name)) {
                        metadataDocs.add(file)
                    } else if (lower.contains("overlay") && lower.endsWith(".png")) {
                        overlayCount++
                        val baseKey = extractCanonicalBaseKey(name)
                        overlayDocs[baseKey] = file
                    } else {
                        val ext = lower.substringAfterLast('.', "")
                        if (IMAGE_EXTENSIONS.contains(ext) || VIDEO_EXTENSIONS.contains(ext)) {
                            rawMediaDocs.add(file)
                        }
                    }

                    if (onProgress != null && (rawMediaDocs.size % 5 == 0 || overlayCount > 0)) {
                        onProgress.invoke(
                            ScanProgress(
                                totalFound = rawMediaDocs.size,
                                photosFound = 0,
                                videosFound = 0,
                                overlaysFound = overlayCount,
                                duplicatesFound = 0,
                                currentFileName = name,
                                isMatchingPhase = false
                            )
                        )
                    }
                }
            }
        }

        walk(root)

        // Deduplicate memories:
        // 1. Detect duplicate Snapchat throwback/flashback story re-saves (same UUID + size)
        // 2. Detect same-date + same-size photo and video duplicates
        val duplicateDocNames = mutableSetOf<String>()

        val groupedDocsByUuid = rawMediaDocs.groupBy { doc ->
            val name = doc.name ?: ""
            val uuid = extractSnapchatUuid(name)
            val len = doc.length()
            if (uuid != null && len > 0L) "uuid_${uuid}_${len}" else "name_$name"
        }

        val primaryDocsAfterUuid = mutableListOf<DocumentFile>()
        for ((_, group) in groupedDocsByUuid) {
            if (group.size == 1) {
                primaryDocsAfterUuid.add(group.first())
            } else {
                val sorted = group.sortedWith(Comparator { a, b ->
                    val dateA = DateExtractor.extractDateKey(a.name ?: "") ?: ""
                    val dateB = DateExtractor.extractDateKey(b.name ?: "") ?: ""
                    if (dateA.isNotEmpty() && dateB.isNotEmpty() && dateA != dateB) {
                        dateA.compareTo(dateB)
                    } else {
                        a.lastModified().compareTo(b.lastModified())
                    }
                })
                primaryDocsAfterUuid.add(sorted.first())
                for (dup in sorted.drop(1)) {
                    val n = dup.name
                    if (n != null) duplicateDocNames.add(n)
                }
            }
        }

        val groupedDocsByDateAndSize = primaryDocsAfterUuid.groupBy { doc ->
            val n = doc.name ?: ""
            val dateKey = DateExtractor.extractDateKey(n) ?: "unknown"
            val len = doc.length()
            val ext = n.lowercase().substringAfterLast('.', "")
            val isVid = VIDEO_EXTENSIONS.contains(ext)
            if (len > 0L) "${dateKey}_${len}_${isVid}" else "name_$n"
        }

        for ((_, group) in groupedDocsByDateAndSize) {
            if (group.size > 1) {
                val sorted = group.sortedBy { it.name ?: "" }
                for (dup in sorted.drop(1)) {
                    val n = dup.name
                    if (n != null) duplicateDocNames.add(n)
                }
            }
        }

        val mediaDocs = rawMediaDocs
        val uniqueDocs = rawMediaDocs.filter { !duplicateDocNames.contains(it.name) }
        val photoCount = uniqueDocs.count { doc ->
            val ext = (doc.name ?: "").lowercase().substringAfterLast('.', "")
            !VIDEO_EXTENSIONS.contains(ext)
        }
        val videoCount = uniqueDocs.count { doc ->
            val ext = (doc.name ?: "").lowercase().substringAfterLast('.', "")
            VIDEO_EXTENSIONS.contains(ext)
        }
        val duplicateCount = duplicateDocNames.size
        val totalMedia = uniqueDocs.size

        // Parse JSON / HTML metadata if found
        onProgress?.invoke(
            ScanProgress(
                totalFound = totalMedia,
                photosFound = photoCount,
                videosFound = videoCount,
                overlaysFound = overlayCount,
                duplicatesFound = duplicateCount,
                currentFileName = if (metadataDocs.isNotEmpty()) "Reading ${metadataDocs.size} history metadata file(s)..." else "Reading history metadata...",
                isMatchingPhase = true
            )
        )

        // Prioritize structured JSON files over HTML files if available
        val jsonDocs = metadataDocs.filter { it.name?.lowercase()?.endsWith(".json") == true }
        val targetDocs = if (jsonDocs.isNotEmpty()) jsonDocs else metadataDocs

        val memoryItems = mutableListOf<MemoryItem>()
        for (doc in targetDocs) {
            val parsed = SnapchatHistoryParser.parseFromUri(context, doc.uri)
            memoryItems.addAll(parsed)
        }
        val distinctMemoryItems = if (targetDocs.size > 1) {
            memoryItems.distinctBy { "${it.dateString}_${it.mediaType}_${it.locationString}" }
        } else {
            memoryItems
        }

        val detectedTimezone = targetTimeZone
        val matcher = MemoryMatcher(distinctMemoryItems, detectedTimezone)

        // Match media files against parsed metadata
        // Pre-scan for original ZIP central directory timestamps (~3ms)
        val zipTimestamps = ZipMetadataReader.findZipTimestamps(context, treeUri)

        var matchedCount = 0
        var overlayMatchedCount = 0
        var photoOverlayMatchedCount = 0
        var videoOverlayMatchedCount = 0
        val mediaList = mutableListOf<ScannedMedia>()
        val sortedMediaDocs = mediaDocs.sortedBy { it.name ?: "" }

        for ((index, doc) in sortedMediaDocs.withIndex()) {
            val name = doc.name ?: continue
            val lower = name.lowercase()
            val ext = lower.substringAfterLast('.', "")
            val isVideo = VIDEO_EXTENSIONS.contains(ext)
            val size = doc.length()

            // Overlay matching using canonical key
            val baseKey = extractCanonicalBaseKey(name)
            val overlay = overlayDocs[baseKey]
            if (overlay != null) {
                overlayMatchedCount++
                if (isVideo) videoOverlayMatchedCount++ else photoOverlayMatchedCount++
            }

            // 1. Check exact original timestamp from ZIP central directory
            val zipEpoch = zipTimestamps[name]
            var fileLastModified = if (zipEpoch != null && zipEpoch > 0) {
                zipEpoch
            } else {
                doc.lastModified()
            }

            var mp4Meta: ExifManager.Mp4Meta? = null
            if (isVideo) {
                mp4Meta = ExifManager.readMp4Meta(context, doc.uri)
                if (zipEpoch == null && mp4Meta != null && mp4Meta.creationEpochMillis > 0) {
                    fileLastModified = mp4Meta.creationEpochMillis
                }
            }

            // Tolerance-aware memory matching
            val matched = matcher.findMatch(
                fileName = name,
                isVideo = isVideo,
                fileLastModified = fileLastModified,
                timeZoneId = detectedTimezone
            )
            val isDup = duplicateDocNames.contains(name)
            if (matched != null && !isDup) matchedCount++

            mediaList.add(
                ScannedMedia(
                    uri = doc.uri,
                    name = name,
                    isVideo = isVideo,
                    size = size,
                    overlayUri = overlay?.uri,
                    matchedMemory = matched,
                    mp4Meta = mp4Meta,
                    isDuplicate = duplicateDocNames.contains(name)
                )
            )

            if (onProgress != null && (index % 5 == 0 || index == mediaDocs.size - 1)) {
                onProgress.invoke(
                    ScanProgress(
                        totalFound = totalMedia,
                        photosFound = photoCount,
                        videosFound = videoCount,
                        overlaysFound = overlayCount,
                        duplicatesFound = duplicateCount,
                        matchedCount = matchedCount,
                        currentFileName = name,
                        isMatchingPhase = true
                    )
                )
            }
        }

        val totalFiles = mediaDocs.size
        val estimatedSec = (totalFiles * 0.05).toInt().coerceAtLeast(1)

        val chunkGroups = detectVideoChunks(mediaList)
        val chunkGroupsCount = chunkGroups.size
        val chunkFilesCount = chunkGroups.sumOf { it.size }

        val stats = ScanStats(
            totalMediaFiles = totalFiles,
            photoCount = photoCount,
            videoCount = videoCount,
            matchedCount = matchedCount,
            overlayCount = maxOf(overlayCount, overlayMatchedCount),
            photoOverlayCount = photoOverlayMatchedCount,
            videoOverlayCount = videoOverlayMatchedCount,
            videoChunkGroupsCount = chunkGroupsCount,
            videoChunkFilesCount = chunkFilesCount,
            duplicateCount = duplicateCount,
            missingMetadataCount = (totalFiles - matchedCount).coerceAtLeast(0),
            detectedTimezone = detectedTimezone,
            estimatedTimeSeconds = estimatedSec,
            totalMetadataEntries = distinctMemoryItems.size,
            hasMetadataFile = metadataDocs.isNotEmpty()
        )

        onProgress?.invoke(
            ScanProgress(
                totalFound = totalFiles,
                photosFound = photoCount,
                videosFound = videoCount,
                overlaysFound = maxOf(overlayCount, overlayMatchedCount),
                duplicatesFound = duplicateCount,
                matchedCount = matchedCount,
                isMatchingPhase = false,
                isComplete = true
            )
        )

        val result = FullScanResult(
            stats = stats,
            mediaList = mediaList,
            jsonUri = metadataDocs.firstOrNull()?.uri,
            rootFolderUri = treeUri
        )
        lastScanResult = result
        return result
    }

    /**
     * Scans one or multiple Snapchat export ZIP files directly without requiring manual extraction.
     * Extracts exact timestamps, matches history metadata, and groups multi-part video chunks.
     */
    fun scanZipUris(
        context: Context,
        zipUris: List<Uri>,
        targetTimeZone: String = TimeZone.getDefault().id,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): FullScanResult {
        val allZipEntries = mutableListOf<ZipEntryInfo>()
        val historyItems = mutableListOf<MemoryItem>()

        onProgress?.invoke(
            ScanProgress(
                totalFound = 0,
                currentFileName = "Reading Snapchat ZIP archives...",
                isMatchingPhase = false
            )
        )

        for (zipUri in zipUris) {
            try {
                context.contentResolver.openFileDescriptor(zipUri, "r")?.use { pfd ->
                    val entries = ZipMetadataReader.readAllZipEntriesFromFd(pfd.fileDescriptor, zipUri)
                    allZipEntries.addAll(entries)
                    // Prefer JSON history (contains all 1000s of entries) over HTML (which only has 1 sample row)
                    val historyEntry = entries.find { it.isHistory && it.entryPath.endsWith(".json", ignoreCase = true) }
                        ?: entries.find { it.isHistory }
                    if (historyEntry != null) {
                        val parsed = ZipMetadataReader.readHistoryFromZip(context, zipUri, historyEntry.entryPath)
                        historyItems.addAll(parsed)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val overlayEntries = mutableMapOf<String, ZipEntryInfo>()
        val rawMediaEntries = mutableListOf<ZipEntryInfo>()
        var overlayCount = 0

        for (entry in allZipEntries) {
            if (entry.isOverlay) {
                overlayCount++
                val baseKey = extractCanonicalBaseKey(entry.baseName)
                overlayEntries[baseKey] = entry
            } else if (!entry.isHistory && entry.entryPath.startsWith("memories/")) {
                val ext = entry.baseName.substringAfterLast('.', "").lowercase()
                if (IMAGE_EXTENSIONS.contains(ext) || VIDEO_EXTENSIONS.contains(ext)) {
                    rawMediaEntries.add(entry)
                }
            }
        }

        // Deduplicate memories:
        // 1. Detect duplicate Snapchat throwback/flashback story re-saves (same UUID + size)
        // 2. Detect same-date + same-size photo and video duplicates
        val duplicateNames = mutableSetOf<String>()

        val groupedByUuid = rawMediaEntries.groupBy { entry ->
            val uuid = extractSnapchatUuid(entry.baseName)
            if (uuid != null && entry.uncompressedSize > 0L) {
                "uuid_${uuid}_${entry.uncompressedSize}"
            } else {
                "name_${entry.baseName}"
            }
        }

        val primaryAfterUuid = mutableListOf<ZipEntryInfo>()

        for ((_, group) in groupedByUuid) {
            if (group.size == 1) {
                primaryAfterUuid.add(group.first())
            } else {
                val sorted = group.sortedWith(Comparator { a, b ->
                    val dateA = DateExtractor.extractDateKey(a.baseName) ?: ""
                    val dateB = DateExtractor.extractDateKey(b.baseName) ?: ""
                    if (dateA.isNotEmpty() && dateB.isNotEmpty() && dateA != dateB) {
                        dateA.compareTo(dateB)
                    } else {
                        a.epochMillis.compareTo(b.epochMillis)
                    }
                })
                primaryAfterUuid.add(sorted.first())
                for (dup in sorted.drop(1)) {
                    duplicateNames.add(dup.baseName)
                }
            }
        }

        // Pass 2: Detect duplicate photos and duplicate videos sharing the same date prefix and identical byte size
        val groupedByDateAndSize = primaryAfterUuid.groupBy { entry ->
            val dateKey = DateExtractor.extractDateKey(entry.baseName) ?: "unknown"
            if (entry.uncompressedSize > 0L) {
                "${dateKey}_${entry.uncompressedSize}_${entry.isVideo}"
            } else {
                "name_${entry.baseName}"
            }
        }

        for ((_, group) in groupedByDateAndSize) {
            if (group.size > 1) {
                val sorted = group.sortedBy { it.baseName }
                for (dup in sorted.drop(1)) {
                    duplicateNames.add(dup.baseName)
                }
            }
        }

        val uniqueEntries = rawMediaEntries.filter { !duplicateNames.contains(it.baseName) }
        val photoCount = uniqueEntries.count { !it.isVideo }
        val videoCount = uniqueEntries.count { it.isVideo }
        val duplicateCount = duplicateNames.size
        val totalMedia = uniqueEntries.size

        val distinctHistory = historyItems.distinctBy { "${it.dateString}_${it.mediaType}_${it.locationString}" }
        val matcher = MemoryMatcher(distinctHistory, targetTimeZone)

        var matchedCount = 0
        var photoOverlayMatchedCount = 0
        var videoOverlayMatchedCount = 0
        val mediaList = mutableListOf<ScannedMedia>()

        // Sort entries by exact ZIP timestamp
        val sortedEntries = rawMediaEntries.sortedWith(Comparator { a, b ->
            if (a.epochMillis != b.epochMillis) {
                a.epochMillis.compareTo(b.epochMillis)
            } else {
                a.baseName.compareTo(b.baseName)
            }
        })

        for ((index, entry) in sortedEntries.withIndex()) {
            val baseKey = extractCanonicalBaseKey(entry.baseName)
            val overlay = overlayEntries[baseKey]
            if (overlay != null) {
                if (entry.isVideo) videoOverlayMatchedCount++ else photoOverlayMatchedCount++
            }

            val matched = matcher.findMatch(
                fileName = entry.baseName,
                isVideo = entry.isVideo,
                fileLastModified = entry.epochMillis,
                timeZoneId = targetTimeZone
            )
            val isDup = duplicateNames.contains(entry.baseName)
            if (matched != null && !isDup) matchedCount++

            val media = ScannedMedia(
                uri = entry.zipUri,
                name = entry.baseName,
                isVideo = entry.isVideo,
                size = entry.uncompressedSize,
                overlayUri = overlay?.zipUri,
                matchedMemory = matched,
                mp4Meta = if (entry.isVideo && entry.epochMillis > 0) ExifManager.Mp4Meta(entry.epochMillis, 10f) else null,
                isDuplicate = isDup
            )
            media.zipEntryPath = entry.entryPath
            media.zipUri = entry.zipUri
            mediaList.add(media)

            if (onProgress != null && (index % 10 == 0 || index == sortedEntries.size - 1)) {
                onProgress.invoke(
                    ScanProgress(
                        totalFound = totalMedia,
                        photosFound = photoCount,
                        videosFound = videoCount,
                        overlaysFound = maxOf(overlayCount, photoOverlayMatchedCount + videoOverlayMatchedCount),
                        duplicatesFound = duplicateCount,
                        matchedCount = matchedCount,
                        currentFileName = entry.baseName,
                        isMatchingPhase = true
                    )
                )
            }
        }

        val chunkGroups = detectVideoChunks(mediaList)
        val estimatedSec = (totalMedia * 0.02).toInt().coerceAtLeast(1)

        val stats = ScanStats(
            totalMediaFiles = totalMedia,
            photoCount = photoCount,
            videoCount = videoCount,
            matchedCount = matchedCount,
            overlayCount = maxOf(overlayCount, photoOverlayMatchedCount + videoOverlayMatchedCount),
            photoOverlayCount = photoOverlayMatchedCount,
            videoOverlayCount = videoOverlayMatchedCount,
            videoChunkGroupsCount = chunkGroups.size,
            videoChunkFilesCount = chunkGroups.sumOf { it.size },
            duplicateCount = duplicateCount,
            missingMetadataCount = (totalMedia - matchedCount).coerceAtLeast(0),
            detectedTimezone = targetTimeZone,
            estimatedTimeSeconds = estimatedSec,
            totalMetadataEntries = distinctHistory.size,
            hasMetadataFile = historyItems.isNotEmpty()
        )

        val result = FullScanResult(
            stats = stats,
            mediaList = mediaList,
            jsonUri = null,
            rootFolderUri = zipUris.firstOrNull() ?: Uri.EMPTY,
            zipUris = zipUris,
            isZipSource = true
        )
        lastScanResult = result
        return result
    }

    @Volatile
    var lastScanResult: FullScanResult? = null


    /**
     * Detects candidate multi-snap video segments.
     * In Snapchat, multi-snap recordings (split chunks of one continuous recording session)
     * share the exact same recorded timestamp down to the second.
     * Prevents false-positive merging of independent snaps recorded seconds or minutes apart.
     */
    fun detectVideoChunks(mediaList: List<ScannedMedia>): List<List<ScannedMedia>> {
        val videosByTimestamp = mediaList.filter { it.isVideo && !it.isDuplicate }
            .filter {
                val epoch = it.matchedMemory?.parseEpochMillis()
                    ?: it.mp4Meta?.creationEpochMillis
                    ?: 0L
                epoch > 0L
            }
            .groupBy {
                val epoch = it.matchedMemory?.parseEpochMillis()
                    ?: it.mp4Meta?.creationEpochMillis
                    ?: 0L
                val date = DateExtractor.extractDateKey(it.name) ?: "unknown"
                "${date}_${epoch / 1000L}"
            }

        val groups = mutableListOf<List<ScannedMedia>>()

        for ((_, videosAtTimestamp) in videosByTimestamp) {
            if (videosAtTimestamp.size < 2) continue

            // If real mp4Meta is known for all items (e.g. folder mode where mp4Meta duration is real, not 10f placeholder from zip)
            val isZipSource = videosAtTimestamp.any { it.zipUri != null }
            val allHaveRealMeta = !isZipSource && videosAtTimestamp.all { it.mp4Meta != null && it.mp4Meta!!.durationSeconds > 0f }
            if (allHaveRealMeta) {
                var hasDuplicates = false
                for (i in 0 until videosAtTimestamp.size) {
                    for (j in i + 1 until videosAtTimestamp.size) {
                        val v1 = videosAtTimestamp[i]
                        val v2 = videosAtTimestamp[j]
                        val sizeDiff = Math.abs(v1.size - v2.size)
                        val durDiff = Math.abs(v1.mp4Meta!!.durationSeconds - v2.mp4Meta!!.durationSeconds)
                        val maxLen = Math.max(v1.size, v2.size)
                        val sizeDiffPct = if (maxLen > 0L) sizeDiff.toFloat() / maxLen else 0f
                        if (durDiff < 0.05f && (sizeDiff < 500L || (sizeDiff < 150_000L && sizeDiffPct < 0.03f))) {
                            hasDuplicates = true
                            break
                        }
                    }
                    if (hasDuplicates) break
                }

                if (!hasDuplicates) {
                    val sorted = videosAtTimestamp.sortedBy { it.mp4Meta!!.creationEpochMillis }
                    val initialValid = sorted.dropLast(1).all { it.mp4Meta!!.durationSeconds in 8.5f..12.5f }
                    val lastValid = sorted.last().mp4Meta!!.durationSeconds in 0.5f..12.5f
                    if (initialValid && lastValid) {
                        groups.add(sorted)
                    }
                }
            } else {
                // In zip scan without extracted files yet, sort by name and add as candidate group.
                // Step 3 in ExportProcessWorker will extract them to chunkCacheDir, inspect exact MP4 durations
                // & exact file sizes, and verify continuous buffer criteria vs duplicates.
                val sorted = videosAtTimestamp.sortedBy { it.name }
                groups.add(sorted)
            }
        }

        return groups
    }


    /**
     * Tolerance-aware matcher that pairs media files with memories_history.json entries.
     * Accounts for:
     * - Sequence index pairing for same-day photos (2023-08-14.jpg, 2023-08-14-1.jpg)
     * - ZIP 2-second DOS timestamp quantization
     * - Snapchat export video/photo processing delays (1-60s skew)
     * - Media type matching (PHOTO vs VIDEO)
     */
    private class MemoryMatcher(
        items: List<MemoryItem>,
        targetTimeZoneId: String = "UTC"
    ) {
        private val exactKeyMap = mutableMapOf<String, MemoryItem>()

        private class ParsedMemory(
            val epochMillis: Long,
            val item: MemoryItem,
            val isVideo: Boolean
        )
        private val sortedItems: List<ParsedMemory>

        // Groupings by date (YYYY-MM-DD) sorted chronologically
        private val photosByUtcDate = mutableMapOf<String, MutableList<MemoryItem>>()
        private val videosByUtcDate = mutableMapOf<String, MutableList<MemoryItem>>()
        private val allByUtcDate = mutableMapOf<String, MutableList<MemoryItem>>()

        private val photosByLocalDate = mutableMapOf<String, MutableList<MemoryItem>>()
        private val videosByLocalDate = mutableMapOf<String, MutableList<MemoryItem>>()
        private val allByLocalDate = mutableMapOf<String, MutableList<MemoryItem>>()

        // Track consumed indices for same-day fallback to distribute UUID files across distinct timestamps
        private val consumedDateIndices = mutableMapOf<String, Int>()

        init {
            val list = mutableListOf<ParsedMemory>()
            val zone = try { ZoneId.of(targetTimeZoneId) } catch (e: Exception) { ZoneId.of("UTC") }

            for (item in items) {
                val date = item.dateString
                val epoch = item.parseEpochMillis()
                val isVideo = item.isVideo()

                if (date != null) {
                    val targetKey = date.replace(" UTC", "")
                        .replace(" ", "_")
                        .replace(":", "-")
                        .lowercase()
                    exactKeyMap[targetKey] = item

                    // Also map local time string if epoch is available
                    if (epoch != null) {
                        try {
                            val localLdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch), zone)
                            val localKey = localLdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                            exactKeyMap[localKey] = item
                        } catch (e: Exception) { }
                    }
                }

                if (epoch != null) {
                    list.add(ParsedMemory(epoch, item, isVideo))

                    // UTC date key e.g. "2023-08-14"
                    val utcDateKey = if (date != null && date.length >= 10) {
                        date.substring(0, 10)
                    } else {
                        val utcZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.of("UTC"))
                        utcZdt.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    }

                    // Local date key in target timezone
                    val localDateKey = try {
                        val localZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch), zone)
                        localZdt.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    } catch (e: Exception) {
                        utcDateKey
                    }

                    allByUtcDate.getOrPut(utcDateKey) { mutableListOf() }.add(item)
                    if (isVideo) {
                        videosByUtcDate.getOrPut(utcDateKey) { mutableListOf() }.add(item)
                    } else {
                        photosByUtcDate.getOrPut(utcDateKey) { mutableListOf() }.add(item)
                    }

                    allByLocalDate.getOrPut(localDateKey) { mutableListOf() }.add(item)
                    if (isVideo) {
                        videosByLocalDate.getOrPut(localDateKey) { mutableListOf() }.add(item)
                    } else {
                        photosByLocalDate.getOrPut(localDateKey) { mutableListOf() }.add(item)
                    }
                }
            }

            sortedItems = list.sortedBy { it.epochMillis }

            val sortComparator = Comparator<MemoryItem> { a, b ->
                val ea = a.parseEpochMillis() ?: 0L
                val eb = b.parseEpochMillis() ?: 0L
                ea.compareTo(eb)
            }
            photosByUtcDate.values.forEach { it.sortWith(sortComparator) }
            videosByUtcDate.values.forEach { it.sortWith(sortComparator) }
            allByUtcDate.values.forEach { it.sortWith(sortComparator) }
            photosByLocalDate.values.forEach { it.sortWith(sortComparator) }
            videosByLocalDate.values.forEach { it.sortWith(sortComparator) }
            allByLocalDate.values.forEach { it.sortWith(sortComparator) }
        }

        fun findMatch(
            fileName: String,
            isVideo: Boolean,
            fileLastModified: Long,
            timeZoneId: String
        ): MemoryItem? {
            if (sortedItems.isEmpty() && exactKeyMap.isEmpty()) return null

            // 1. Priority 1: Exact timestamp match using fileLastModified (preserved by ZIP extract)
            if (fileLastModified > 0) {
                val match = findClosestWithinTolerance(fileLastModified, isVideo, toleranceMs = 60_000L)
                if (match != null) {
                    val dateKey = DateExtractor.extractDateKey(fileName)
                    if (dateKey == null || match.dateString?.startsWith(dateKey) == true) {
                        return match
                    }
                    val matchEpoch = match.parseEpochMillis() ?: 0L
                    if (Math.abs(matchEpoch - fileLastModified) <= 60_000L) {
                        return match
                    }
                }
            }

            val hasFullTime = DateExtractor.hasTimeInFileName(fileName) ||
                Regex("""(?:snapchat|media|item)[-_]\d{10,13}""", RegexOption.IGNORE_CASE).containsMatchIn(fileName)

            // 2. Priority 2: Full timestamp in filename (e.g. 2023-08-14_19-42-10 or epoch)
            if (hasFullTime) {
                val canonical = extractCanonicalBaseKey(fileName)
                    .replace(" ", "_")
                    .replace(":", "-")

                // Exact canonical string match
                for ((key, item) in exactKeyMap) {
                    if (canonical == key || canonical.contains(key)) {
                        return item
                    }
                }

                // Tolerance-based matching from filename timestamp (UTC first, then local)
                val fileEpoch = DateExtractor.extractEpochMillisFromFileName(fileName, "UTC")
                    ?: DateExtractor.extractEpochMillisFromFileName(fileName, timeZoneId)

                if (fileEpoch != null) {
                    val match = findClosestWithinTolerance(fileEpoch, isVideo, toleranceMs = 60_000L)
                    if (match != null) return match
                }
            }

            // 3. Priority 3: Fallback when filename only contains date (e.g. 2023-08-14.jpg, 2023-08-14_<uuid>-main.jpg)
            val dateKey = DateExtractor.extractDateKey(fileName)
            if (dateKey != null) {
                val hasExplicitSeq = fileName.contains(Regex("""\d{4}[-_]\d{2}[-_]\d{2}[-_(\s]+\d+\)?"""))
                val seqIndex = DateExtractor.extractSequenceIndex(fileName)
                val lookupKey = "${dateKey}_${isVideo}"

                val candidateList = (if (isVideo) videosByUtcDate[dateKey] else photosByUtcDate[dateKey])
                    ?: (if (isVideo) videosByLocalDate[dateKey] else photosByLocalDate[dateKey])
                    ?: allByUtcDate[dateKey]
                    ?: allByLocalDate[dateKey]

                if (!candidateList.isNullOrEmpty()) {
                    return if (hasExplicitSeq) {
                        if (seqIndex < candidateList.size) candidateList[seqIndex] else candidateList.last()
                    } else {
                        val nextIdx = consumedDateIndices.getOrDefault(lookupKey, 0)
                        consumedDateIndices[lookupKey] = nextIdx + 1
                        candidateList[nextIdx % candidateList.size]
                    }
                }
            }

            return null
        }

        private fun findClosestWithinTolerance(
            targetEpoch: Long,
            isVideo: Boolean,
            toleranceMs: Long
        ): MemoryItem? {
            if (sortedItems.isEmpty()) return null

            var bestItem: MemoryItem? = null
            var minDiff = Long.MAX_VALUE

            // Binary search to find closest timestamp index
            var idx = sortedItems.binarySearch { it.epochMillis.compareTo(targetEpoch) }
            if (idx < 0) idx = -idx - 1
            val startIndex = idx.coerceIn(0, sortedItems.size - 1)

            // Search in local neighborhood around startIndex
            val startSearch = maxOf(0, startIndex - 50)
            val endSearch = minOf(sortedItems.size - 1, startIndex + 50)

            for (i in startSearch..endSearch) {
                val entry = sortedItems[i]
                if (entry.isVideo == isVideo) {
                    val diff = Math.abs(entry.epochMillis - targetEpoch)
                    if (diff <= toleranceMs && diff < minDiff) {
                        minDiff = diff
                        bestItem = entry.item
                    }
                }
            }

            // If no match of the exact same media type, check nearby if within 5s
            if (bestItem == null) {
                for (i in startSearch..endSearch) {
                    val entry = sortedItems[i]
                    val diff = Math.abs(entry.epochMillis - targetEpoch)
                    if (diff <= 5_000L && diff < minDiff) {
                        minDiff = diff
                        bestItem = entry.item
                    }
                }
            }

            return bestItem
        }
    }
}
