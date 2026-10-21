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
    var matchedMemory: MemoryItem? = null
)

data class FullScanResult(
    val stats: ScanStats,
    val mediaList: List<ScannedMedia>,
    val jsonUri: Uri?,
    val rootFolderUri: Uri
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
        val mediaDocs = mutableListOf<DocumentFile>()
        val overlayDocs = mutableMapOf<String, DocumentFile>()

        var totalMedia = 0
        var photoCount = 0
        var videoCount = 0
        var overlayCount = 0
        val sizeMap = mutableMapOf<Long, Int>()
        var duplicateCount = 0

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
                            val isVideo = VIDEO_EXTENSIONS.contains(ext)
                            if (isVideo) videoCount++ else photoCount++
                            totalMedia++
                            mediaDocs.add(file)

                            // Detect duplicate file sizes continuously during scan phase
                            val size = file.length()
                            val seen = sizeMap.getOrDefault(size, 0)
                            if (seen > 0) duplicateCount++
                            sizeMap[size] = seen + 1
                        }
                    }

                    if (onProgress != null && (totalMedia % 5 == 0 || overlayCount > 0 || duplicateCount > 0)) {
                        onProgress.invoke(
                            ScanProgress(
                                totalFound = totalMedia,
                                photosFound = photoCount,
                                videosFound = videoCount,
                                overlaysFound = overlayCount,
                                duplicatesFound = duplicateCount,
                                currentFileName = name,
                                isMatchingPhase = false
                            )
                        )
                    }
                }
            }
        }

        walk(root)

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
        var matchedCount = 0
        var overlayMatchedCount = 0
        var photoOverlayMatchedCount = 0
        var videoOverlayMatchedCount = 0
        val mediaList = mutableListOf<ScannedMedia>()

        for ((index, doc) in mediaDocs.withIndex()) {
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

            // Tolerance-aware memory matching
            val matched = matcher.findMatch(
                fileName = name,
                isVideo = isVideo,
                fileLastModified = doc.lastModified(),
                timeZoneId = detectedTimezone
            )
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

        val stats = ScanStats(
            totalMediaFiles = totalFiles,
            photoCount = photoCount,
            videoCount = videoCount,
            matchedCount = matchedCount,
            overlayCount = maxOf(overlayCount, overlayMatchedCount),
            photoOverlayCount = photoOverlayMatchedCount,
            videoOverlayCount = videoOverlayMatchedCount,
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

        return FullScanResult(
            stats = stats,
            mediaList = mediaList,
            jsonUri = metadataDocs.firstOrNull()?.uri,
            rootFolderUri = treeUri
        )
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
                val seqIndex = DateExtractor.extractSequenceIndex(fileName)

                val candidateList = (if (isVideo) videosByUtcDate[dateKey] else photosByUtcDate[dateKey])
                    ?: (if (isVideo) videosByLocalDate[dateKey] else photosByLocalDate[dateKey])
                    ?: allByUtcDate[dateKey]
                    ?: allByLocalDate[dateKey]

                if (!candidateList.isNullOrEmpty()) {
                    return if (seqIndex < candidateList.size) {
                        candidateList[seqIndex]
                    } else {
                        candidateList.last()
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
