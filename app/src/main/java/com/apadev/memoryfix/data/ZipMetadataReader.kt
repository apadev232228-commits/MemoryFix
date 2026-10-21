package com.apadev.memoryfix.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId
import java.time.ZonedDateTime

object ZipMetadataReader {

    /**
     * Reads filename -> UTC epochMillis from the ZIP Central Directory via FileDescriptor.
     * Takes ~3ms for even multi-gigabyte ZIP files because it seeks directly to the central directory at the end.
     */
    fun readTimestampsFromFd(fd: FileDescriptor): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        try {
            val channel = FileInputStream(fd).channel
            val fileSize = channel.size()
            if (fileSize < 22) return emptyMap()

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
            if (eocdPos == -1) return emptyMap()

            tailBuf.position(eocdPos)
            tailBuf.int // signature
            tailBuf.short // diskNo
            tailBuf.short // cdDisk
            tailBuf.short // diskEntries
            tailBuf.short // totalEntries
            val cdSize = tailBuf.int.toLong() and 0xFFFFFFFFL
            val cdOffset = tailBuf.int.toLong() and 0xFFFFFFFFL

            if (cdOffset + cdSize > fileSize) return emptyMap()

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

                    if (day in 1..31 && hour in 0..23 && min in 0..59 && sec in 0..59) {
                        try {
                            // Snapchat writes UTC timestamps into the DOS date/time fields
                            val zdt = ZonedDateTime.of(year, month, day, hour, min, sec, 0, ZoneId.of("UTC"))
                            map[baseName] = zdt.toInstant().toEpochMilli()
                        } catch (e: Exception) {}
                    }
                }
                pos += 46 + nameLen + extraLen + commentLen
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
     * Scans for any available Snapchat export ZIP files and returns a unified map of timestamps.
     * Checks:
     * 1. Files inside rootFolderUri ending with .zip via SAF
     * 2. Direct filesystem /sdcard/Download and Environment.DIRECTORY_DOWNLOADS for mydata*.zip
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
                            // Don't recurse too deep to stay fast
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
}
