package com.apadev.memoryfix.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.apadev.memoryfix.model.MemoryItem
import java.io.BufferedInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream

data class ZipEntryInfo(
    val zipUri: Uri,
    val entryPath: String,
    val baseName: String,
    val uncompressedSize: Long,
    val epochMillis: Long,
    val isVideo: Boolean,
    val isOverlay: Boolean,
    val isHistory: Boolean
)

object ZipMetadataReader {

    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")

    /**
     * Reads all entry metadata from the ZIP Central Directory via FileDescriptor in ~3ms.
     */
    fun readAllZipEntriesFromFd(fd: FileDescriptor, zipUri: Uri): List<ZipEntryInfo> {
        val list = mutableListOf<ZipEntryInfo>()
        try {
            val channel = FileInputStream(fd).channel
            val fileSize = channel.size()
            if (fileSize < 22) return emptyList()

            // Read last 64KB (or whole file if smaller) to locate EOCD
            val readLen = Math.min(fileSize, 65536L).toInt()
            val tailBuf = ByteBuffer.allocate(readLen)
            tailBuf.order(ByteOrder.LITTLE_ENDIAN)
            channel.position(fileSize - readLen)
            channel.read(tailBuf)
            tailBuf.flip()

            val tailBytes = tailBuf.array()
            var eocdPos = -1
            // Search backwards for EOCD signature 0x06054b50 (PK\x05\x06)
            for (i in readLen - 22 downTo 0) {
                if (tailBytes[i] == 0x50.toByte() &&
                    tailBytes[i + 1] == 0x4b.toByte() &&
                    tailBytes[i + 2] == 0x05.toByte() &&
                    tailBytes[i + 3] == 0x06.toByte()
                ) {
                    eocdPos = i
                    break
                }
            }
            if (eocdPos == -1) return emptyList()

            tailBuf.position(eocdPos)
            tailBuf.int // signature
            tailBuf.short // diskNo
            tailBuf.short // cdDisk
            tailBuf.short // diskEntries
            tailBuf.short // totalEntries
            val cdSize = tailBuf.int.toLong() and 0xFFFFFFFFL
            val cdOffset = tailBuf.int.toLong() and 0xFFFFFFFFL

            if (cdOffset + cdSize > fileSize) return emptyList()

            val cdBuf = ByteBuffer.allocate(cdSize.toInt())
            cdBuf.order(ByteOrder.LITTLE_ENDIAN)
            channel.position(cdOffset)
            channel.read(cdBuf)
            cdBuf.flip()

            val cdBytes = cdBuf.array()
            var pos = 0
            val cdLen = cdBytes.size

            while (pos + 46 <= cdLen) {
                if (cdBytes[pos] != 0x50.toByte() ||
                    cdBytes[pos + 1] != 0x4b.toByte() ||
                    cdBytes[pos + 2] != 0x01.toByte() ||
                    cdBytes[pos + 3] != 0x02.toByte()
                ) {
                    break
                }

                cdBuf.position(pos + 12)
                val dosTime = cdBuf.short.toInt() and 0xFFFF
                val dosDate = cdBuf.short.toInt() and 0xFFFF
                cdBuf.position(pos + 24)
                val uncompSize = cdBuf.int.toLong() and 0xFFFFFFFFL
                cdBuf.position(pos + 28)
                val nameLen = cdBuf.short.toInt() and 0xFFFF
                val extraLen = cdBuf.short.toInt() and 0xFFFF
                val commentLen = cdBuf.short.toInt() and 0xFFFF

                if (pos + 46 + nameLen <= cdLen) {
                    val nameBytes = ByteArray(nameLen)
                    System.arraycopy(cdBytes, pos + 46, nameBytes, 0, nameLen)
                    val rawName = String(nameBytes, Charsets.UTF_8)
                    val baseName = rawName.substringAfterLast('/').substringAfterLast('\\')

                    val sec = (dosTime and 0x1F) * 2
                    val min = (dosTime shr 5) and 0x3F
                    val hour = (dosTime shr 11) and 0x1F
                    val day = dosDate and 0x1F
                    val month = ((dosDate shr 5) and 0x0F).coerceIn(1, 12)
                    val year = ((dosDate shr 9) and 0x7F) + 1980

                    var epochMillis = 0L
                    if (day in 1..31 && hour in 0..23 && min in 0..59 && sec in 0..59) {
                        try {
                            val zdt = ZonedDateTime.of(year, month, day, hour, min, sec, 0, ZoneId.of("UTC"))
                            epochMillis = zdt.toInstant().toEpochMilli()
                        } catch (e: Exception) {}
                    }

                    val lowerBase = baseName.lowercase()
                    val ext = lowerBase.substringAfterLast('.', "")
                    val isVideo = VIDEO_EXTENSIONS.contains(ext)
                    val isOverlay = lowerBase.contains("overlay") && lowerBase.endsWith(".png")
                    val isHistory = lowerBase == "memories_history.json" ||
                                    lowerBase == "memories_history.html" ||
                                    lowerBase == "memories.json"

                    list.add(
                        ZipEntryInfo(
                            zipUri = zipUri,
                            entryPath = rawName,
                            baseName = baseName,
                            uncompressedSize = uncompSize,
                            epochMillis = epochMillis,
                            isVideo = isVideo,
                            isOverlay = isOverlay,
                            isHistory = isHistory
                        )
                    )
                }
                pos += 46 + nameLen + extraLen + commentLen
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Reads filename -> UTC epochMillis from the ZIP Central Directory via FileDescriptor.
     */
    fun readTimestampsFromFd(fd: FileDescriptor): Map<String, Long> {
        val dummyUri = Uri.EMPTY
        val entries = readAllZipEntriesFromFd(fd, dummyUri)
        val map = mutableMapOf<String, Long>()
        for (e in entries) {
            if (e.epochMillis > 0) {
                map[e.baseName] = e.epochMillis
            }
        }
        return map
    }

    /**
     * Reads filename -> UTC epochMillis from a regular File.
     */
    fun readTimestampsFromFile(file: File): Map<String, Long> {
        if (!file.exists() || !file.canRead()) return emptyMap()
        return try {
            FileInputStream(file).use { fis ->
                readTimestampsFromFd(fis.fd)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Reads memories history (JSON or HTML) directly from a ZIP file via SAF.
     */
    fun readHistoryFromZip(context: Context, zipUri: Uri, entryPath: String): List<MemoryItem> {
        return try {
            context.contentResolver.openInputStream(zipUri)?.use { rawStream ->
                ZipInputStream(BufferedInputStream(rawStream)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == entryPath || (entryPath.isEmpty() && name.endsWith("memories_history.json", ignoreCase = true))) {
                            val bytes = zis.readBytes()
                            val isHtml = name.endsWith(".html", ignoreCase = true)
                            return SnapchatHistoryParser.parse(bytes.inputStream(), isHtml = isHtml)
                        }
                        entry = zis.nextEntry
                    }
                }
            }
            emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Scans for any available Snapchat export ZIP files in Download directory.
     */
    fun findZipTimestamps(context: Context, rootFolderUri: Uri): Map<String, Long> {
        val unifiedMap = mutableMapOf<String, Long>()

        // 1. Check direct Download directory via java.io.File
        try {
            val downloadDirs = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                File("/sdcard/Download"),
                File("/storage/emulated/0/Download")
            )
            for (dir in downloadDirs) {
                if (dir.exists() && dir.canRead()) {
                    val zips = dir.listFiles { _, name -> name.lowercase().endsWith(".zip") }
                    if (zips != null) {
                        for (zip in zips) {
                            val map = readTimestampsFromFile(zip)
                            unifiedMap.putAll(map)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Scoped storage may restrict direct File access
        }

        // 2. Check root SAF tree for any .zip files
        try {
            val root = DocumentFile.fromTreeUri(context, rootFolderUri)
            if (root != null) {
                fun searchZips(dir: DocumentFile) {
                    val files = dir.listFiles()
                    for (file in files) {
                        if (file.isDirectory) {
                            if (file.name?.lowercase() != "memories") {
                                searchZips(file)
                            }
                        } else if (file.name?.lowercase()?.endsWith(".zip") == true) {
                            try {
                                context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                                    val map = readTimestampsFromFd(pfd.fileDescriptor)
                                    unifiedMap.putAll(map)
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
                searchZips(root)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return unifiedMap
    }

    /**
     * Quickly validates whether a ZIP contains Snapchat export indicators:
     * - memories_history.json / memories_history.html
     * - media files in memories/ folder or matching Snapchat naming pattern (YYYY-MM-DD_...-main.jpg)
     */
    fun validateSnapchatZip(context: Context, zipUri: Uri): ZipValidationResult {
        return try {
            val entries = context.contentResolver.openFileDescriptor(zipUri, "r")?.use { pfd ->
                readAllZipEntriesFromFd(pfd.fileDescriptor, zipUri)
            } ?: emptyList()

            var mediaCount = 0
            var hasHistory = false
            var sampleMedia: String? = null
            var hasMemoriesFolder = false

            for (entry in entries) {
                if (entry.isHistory) {
                    hasHistory = true
                }
                if (entry.entryPath.contains("memories", ignoreCase = true)) {
                    hasMemoriesFolder = true
                }
                val nameLower = entry.baseName.lowercase()
                val ext = nameLower.substringAfterLast('.', "")
                val isMedia = VIDEO_EXTENSIONS.contains(ext) || IMAGE_EXTENSIONS.contains(ext)
                if (isMedia && !entry.isOverlay) {
                    mediaCount++
                    if (sampleMedia == null && (nameLower.contains("-main") || nameLower.contains("memory") || nameLower.matches(Regex(".*\\d{4}-\\d{2}-\\d{2}.*")))) {
                        sampleMedia = entry.baseName
                    }
                }
            }

            val isValid = hasHistory || sampleMedia != null || (mediaCount > 0 && hasMemoriesFolder)
            ZipValidationResult(
                isValid = isValid,
                mediaCount = mediaCount,
                hasHistory = hasHistory,
                sampleMediaName = sampleMedia
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ZipValidationResult(isValid = false, mediaCount = 0, hasHistory = false)
        }
    }
}

data class ZipValidationResult(
    val isValid: Boolean,
    val mediaCount: Int,
    val hasHistory: Boolean,
    val sampleMediaName: String? = null
)

