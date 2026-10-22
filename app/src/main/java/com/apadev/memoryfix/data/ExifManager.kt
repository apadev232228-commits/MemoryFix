package com.apadev.memoryfix.data

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object ExifManager {

    private val exifDateFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)
    private val offsetFormatter = DateTimeFormatter.ofPattern("xxx", Locale.US) // e.g. +05:30 or Z

    /**
     * Applies date/time and GPS coordinates to an image file via FileDescriptor (SAF-compatible).
     */
    fun applyMetadataToImage(
        context: Context,
        uri: Uri,
        epochMillis: Long?,
        coordinates: Pair<Double, Double>?,
        timeZoneId: String = "UTC"
    ): Boolean {
        if (epochMillis == null && coordinates == null) return false
        return try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        val exif = ExifInterface(file.absolutePath)
                        writeExifTags(exif, epochMillis, coordinates, timeZoneId)
                        exif.saveAttributes()
                        return true
                    }
                }
            }
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                writeToFd(pfd.fileDescriptor, epochMillis, coordinates, timeZoneId)
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Applies date/time and GPS coordinates to a video file via FileDescriptor (SAF-compatible).
     * Patches MP4 creation/modification time in the `mvhd` atom.
     * Encodes ISO-6709 GPS coordinates into the MP4 `\xa9xyz` atom inside `udta` and Apple QuickTime keys.
     */
    fun applyMetadataToVideo(
        context: Context,
        uri: Uri,
        epochMillis: Long?,
        coordinates: Pair<Double, Double>? = null
    ): Boolean {
        if (epochMillis == null && coordinates == null) return false
        return try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        java.io.RandomAccessFile(file, "rw").use { raf ->
                            var success = false
                            if (epochMillis != null) {
                                val patchedTime = patchMp4Timestamp(raf.fd, epochMillis)
                                if (patchedTime) success = true
                            }
                            if (coordinates != null) {
                                val patchedLoc = patchMp4Location(raf.fd, coordinates.first, coordinates.second)
                                if (patchedLoc) success = true
                            }
                            return success
                        }
                    }
                }
            }
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                var success = false
                if (epochMillis != null) {
                    val patchedTime = patchMp4Timestamp(pfd.fileDescriptor, epochMillis)
                    if (patchedTime) success = true
                }
                if (coordinates != null) {
                    val patchedLoc = patchMp4Location(pfd.fileDescriptor, coordinates.first, coordinates.second)
                    if (patchedLoc) success = true
                }
                success
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    data class Mp4Meta(
        val creationEpochMillis: Long,
        val durationSeconds: Float
    )

    /**
     * Reads creation timestamp and duration from an MP4 file.
     * Searches both head (fast-started MP4) and tail (streaming MP4) for the mvhd atom.
     */
    fun readMp4Meta(file: File): Mp4Meta? {
        return try {
            FileInputStream(file).use { fis ->
                readMp4MetaFromChannel(fis.channel)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads creation timestamp and duration from the MP4 mvhd atom via SAF Uri.
     * Searches both head (fast-started MP4) and tail (streaming MP4) for the mvhd atom.
     */
    fun readMp4Meta(context: Context, uri: Uri): Mp4Meta? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        return readMp4Meta(file)
                    }
                }
            }
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val inChannel = FileInputStream(pfd.fileDescriptor).channel
                readMp4MetaFromChannel(inChannel)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readMp4MetaFromChannel(inChannel: java.nio.channels.FileChannel): Mp4Meta? {
        val fileSize = inChannel.size()
        if (fileSize < 16) return null

        val mp4EpochOffset = 2082844800L

        // 1. Search head (up to 2MB)
        val headLimit = Math.min(fileSize, 2 * 1024 * 1024L).toInt()
        val headBuffer = ByteBuffer.allocate(headLimit)
        inChannel.position(0)
        inChannel.read(headBuffer)
        headBuffer.flip()
        val headBytes = headBuffer.array()

        var mvhdPos = -1
        var mvhdVersion = 0
        var activeBuffer = headBuffer

        for (i in 0 until headBuffer.limit() - 8) {
            if (headBytes[i] == 0x6D.toByte() &&
                headBytes[i + 1] == 0x76.toByte() &&
                headBytes[i + 2] == 0x68.toByte() &&
                headBytes[i + 3] == 0x64.toByte()
            ) {
                mvhdPos = i
                mvhdVersion = headBytes[i + 4].toInt()
                break
            }
        }

        // 2. If not found in head, search tail (last 2MB)
        if (mvhdPos == -1 && fileSize > headLimit) {
            val tailSize = Math.min(fileSize - headLimit, 2 * 1024 * 1024L).toInt()
            val tailStart = fileSize - tailSize
            val tailBuffer = ByteBuffer.allocate(tailSize)
            inChannel.position(tailStart)
            inChannel.read(tailBuffer)
            tailBuffer.flip()
            val tailBytes = tailBuffer.array()

            for (i in 0 until tailBuffer.limit() - 8) {
                if (tailBytes[i] == 0x6D.toByte() &&
                    tailBytes[i + 1] == 0x76.toByte() &&
                    tailBytes[i + 2] == 0x68.toByte() &&
                    tailBytes[i + 3] == 0x64.toByte()
                ) {
                    mvhdPos = i
                    mvhdVersion = tailBytes[i + 4].toInt()
                    activeBuffer = tailBuffer
                    break
                }
            }
        }

        if (mvhdPos != -1) {
            if (mvhdVersion == 0 && mvhdPos + 8 + 16 <= activeBuffer.limit()) {
                activeBuffer.position(mvhdPos + 8)
                val creation = activeBuffer.int.toLong() and 0xFFFFFFFFL
                activeBuffer.int // modification
                val timescale = activeBuffer.int.toLong() and 0xFFFFFFFFL
                val duration = activeBuffer.int.toLong() and 0xFFFFFFFFL

                val epochSec = creation - mp4EpochOffset
                val durSec = if (timescale > 0) duration.toFloat() / timescale else 0f
                if (epochSec > 0) return Mp4Meta(epochSec * 1000L, durSec)
            } else if (mvhdVersion == 1 && mvhdPos + 8 + 28 <= activeBuffer.limit()) {
                activeBuffer.position(mvhdPos + 8)
                val creation = activeBuffer.long
                activeBuffer.long // modification
                val timescale = activeBuffer.int.toLong() and 0xFFFFFFFFL
                val duration = activeBuffer.long

                val epochSec = creation - mp4EpochOffset
                val durSec = if (timescale > 0) duration.toFloat() / timescale else 0f
                if (epochSec > 0) return Mp4Meta(epochSec * 1000L, durSec)
            }
        }
        return null
    }

    fun readMp4CreationEpoch(context: Context, uri: Uri): Long? {
        return readMp4Meta(context, uri)?.creationEpochMillis
    }


    private fun patchMp4Timestamp(fd: FileDescriptor, epochMillis: Long): Boolean {
        return try {
            val inChannel = FileInputStream(fd).channel
            val outChannel = FileOutputStream(fd).channel

            val fileSize = inChannel.size()
            if (fileSize < 16) return false

            // Seconds from Jan 1, 1904 to Jan 1, 1970 UTC
            val mp4EpochOffset = 2082844800L
            val mp4Seconds = (epochMillis / 1000) + mp4EpochOffset

            var mvhdAbsolutePos = -1L
            var mvhdVersion = 0

            // 1. Search head (first 2MB)
            val headLimit = Math.min(fileSize, 2 * 1024 * 1024L).toInt()
            val headBuffer = ByteBuffer.allocate(headLimit)
            inChannel.position(0)
            inChannel.read(headBuffer)
            headBuffer.flip()
            val headBytes = headBuffer.array()

            for (i in 0 until headBytes.size - 8) {
                if (headBytes[i] == 0x6D.toByte() &&
                    headBytes[i + 1] == 0x76.toByte() &&
                    headBytes[i + 2] == 0x68.toByte() &&
                    headBytes[i + 3] == 0x64.toByte()
                ) {
                    mvhdAbsolutePos = i.toLong()
                    mvhdVersion = headBytes[i + 4].toInt()
                    break
                }
            }

            // 2. If not found in head, search tail (last 2MB) where moov is located in streaming MP4s
            if (mvhdAbsolutePos == -1L && fileSize > headLimit) {
                val tailSize = Math.min(fileSize - headLimit, 2 * 1024 * 1024L).toInt()
                val tailStart = fileSize - tailSize
                val tailBuffer = ByteBuffer.allocate(tailSize)
                inChannel.position(tailStart)
                inChannel.read(tailBuffer)
                tailBuffer.flip()
                val tailBytes = tailBuffer.array()

                for (i in 0 until tailBytes.size - 8) {
                    if (tailBytes[i] == 0x6D.toByte() &&
                        tailBytes[i + 1] == 0x76.toByte() &&
                        tailBytes[i + 2] == 0x68.toByte() &&
                        tailBytes[i + 3] == 0x64.toByte()
                    ) {
                        mvhdAbsolutePos = tailStart + i
                        mvhdVersion = tailBytes[i + 4].toInt()
                        break
                    }
                }
            }

            if (mvhdAbsolutePos != -1L) {
                val writePos = mvhdAbsolutePos + 8 // skip 'mvhd' (4) + version(1) + flags(3) = 8 bytes
                outChannel.position(writePos)

                if (mvhdVersion == 0) {
                    val timeBuf = ByteBuffer.allocate(8)
                    timeBuf.putInt(mp4Seconds.toInt())
                    timeBuf.putInt(mp4Seconds.toInt())
                    timeBuf.flip()
                    outChannel.write(timeBuf)
                } else if (mvhdVersion == 1) {
                    val timeBuf = ByteBuffer.allocate(16)
                    timeBuf.putLong(mp4Seconds)
                    timeBuf.putLong(mp4Seconds)
                    timeBuf.flip()
                    outChannel.write(timeBuf)
                }
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reads ISO-6709 location coordinates from an MP4 file.
     */
    fun readMp4Location(file: File): Pair<Double, Double>? {
        return try {
            FileInputStream(file).use { fis ->
                readMp4LocationFromChannel(fis.channel)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads ISO-6709 location coordinates from an MP4 file via SAF Uri.
     */
    fun readMp4Location(context: Context, uri: Uri): Pair<Double, Double>? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        FileInputStream(file).use { fis ->
                            return readMp4LocationFromChannel(fis.channel)
                        }
                    }
                }
            }
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    readMp4LocationFromChannel(fis.channel)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readMp4LocationFromChannel(inChannel: java.nio.channels.FileChannel): Pair<Double, Double>? {
        val fileSize = inChannel.size()
        if (fileSize < 32) return null

        val limit = Math.min(fileSize, 2 * 1024 * 1024L).toInt()
        val buf = ByteBuffer.allocate(limit)
        inChannel.position(0)
        inChannel.read(buf)
        buf.flip()
        val bytes = buf.array()

        var loc = findLocationInBytes(bytes, limit)
        if (loc == null && fileSize > limit) {
            val tailSize = Math.min(fileSize - limit, 2 * 1024 * 1024L).toInt()
            val tailBuf = ByteBuffer.allocate(tailSize)
            inChannel.position(fileSize - tailSize)
            inChannel.read(tailBuf)
            tailBuf.flip()
            loc = findLocationInBytes(tailBuf.array(), tailSize)
        }
        return loc
    }

    private fun findLocationInBytes(bytes: ByteArray, limit: Int): Pair<Double, Double>? {
        for (i in 0 until limit - 26) {
            if (bytes[i] == 0xA9.toByte() &&
                bytes[i + 1] == 'x'.code.toByte() &&
                bytes[i + 2] == 'y'.code.toByte() &&
                bytes[i + 3] == 'z'.code.toByte()
            ) {
                val strBytes = bytes.copyOfRange(i + 8, Math.min(i + 8 + 18, limit))
                val str = String(strBytes, Charsets.ISO_8859_1).trimEnd('/')
                val secondSign = str.indexOfAny(charArrayOf('+', '-'), startIndex = 1)
                if (secondSign > 0) {
                    val lat = str.substring(0, secondSign).toDoubleOrNull()
                    val lon = str.substring(secondSign).toDoubleOrNull()
                    if (lat != null && lon != null) {
                        return Pair(lat, lon)
                    }
                }
            }
        }
        return null
    }

    /**
     * Patches ISO-6709 GPS coordinates into the MP4 `\xa9xyz` atom inside `udta`.
     * Supported formats:
     * 1. In-place overwrite if `\xa9eng` or `\xa9xyz` already exists in `udta` (30 bytes).
     * 2. Absorbs bytes from a following `free` atom after `moov` (no media offset changes).
     * 3. Expands tail `moov` at the end of the file.
     */
    private fun patchMp4Location(fd: FileDescriptor, lat: Double, lon: Double): Boolean {
        return try {
            val inChannel = FileInputStream(fd).channel
            val outChannel = FileOutputStream(fd).channel
            val fileSize = inChannel.size()
            if (fileSize < 32) return false

            val xyzBox = buildXyzBox(lat, lon)
            val udtaBox = buildUdtaBox(xyzBox)

            var patched = false

            // 1. Search head (first 2MB)
            val headLimit = Math.min(fileSize, 2 * 1024 * 1024L).toInt()
            val headBuffer = ByteBuffer.allocate(headLimit)
            inChannel.position(0)
            inChannel.read(headBuffer)
            headBuffer.flip()
            val headBytes = headBuffer.array()

            // Check Case 1 in head: existing \xa9eng or \xa9xyz box in udta
            for (i in 4 until headLimit - 26) {
                if ((headBytes[i] == 0xA9.toByte() && headBytes[i + 1] == 'e'.code.toByte() && headBytes[i + 2] == 'n'.code.toByte() && headBytes[i + 3] == 'g'.code.toByte()) ||
                    (headBytes[i] == 0xA9.toByte() && headBytes[i + 1] == 'x'.code.toByte() && headBytes[i + 2] == 'y'.code.toByte() && headBytes[i + 3] == 'z'.code.toByte())
                ) {
                    val boxSize = headBuffer.getInt(i - 4)
                    if (boxSize in 28..38) {
                        outChannel.position((i - 4).toLong())
                        outChannel.write(ByteBuffer.wrap(xyzBox))
                        System.arraycopy(xyzBox, 0, headBytes, i - 4, xyzBox.size)
                        patched = true
                        break
                    }
                }
            }

            // Also check if moov contains meta with keys and ilst (QuickTime metadata) and is followed by free atom
            val qtPatched = patchQuickTimeMetaLocation(outChannel, headBytes, headLimit, lat, lon)
            if (qtPatched) {
                patched = true
            }

            if (patched) return true

            // Check Case 2 in head: moov atom followed immediately by a free atom
            var pos = 0
            var moovPos = -1
            var moovSize = -1
            while (pos + 8 <= headLimit) {
                val size = headBuffer.getInt(pos)
                if (size <= 0) break
                val isMoov = headBytes[pos + 4] == 'm'.code.toByte() && headBytes[pos + 5] == 'o'.code.toByte() && headBytes[pos + 6] == 'o'.code.toByte() && headBytes[pos + 7] == 'v'.code.toByte()
                val isFree = headBytes[pos + 4] == 'f'.code.toByte() && headBytes[pos + 5] == 'r'.code.toByte() && headBytes[pos + 6] == 'e'.code.toByte() && headBytes[pos + 7] == 'e'.code.toByte()

                if (isMoov) {
                    moovPos = pos
                    moovSize = size
                } else if (isFree && moovPos != -1 && pos == moovPos + moovSize) {
                    if (size >= udtaBox.size + 8) {
                        // Expand moov size
                        val newMoovSize = moovSize + udtaBox.size
                        val moovSizeBuf = ByteBuffer.allocate(4).putInt(newMoovSize)
                        moovSizeBuf.flip()
                        outChannel.position(moovPos.toLong())
                        outChannel.write(moovSizeBuf)

                        // Write udta at end of old moov
                        outChannel.position(pos.toLong())
                        outChannel.write(ByteBuffer.wrap(udtaBox))

                        // Move and shrink free atom
                        val newFreePos = pos + udtaBox.size
                        val newFreeSize = size - udtaBox.size
                        val freeHeader = ByteBuffer.allocate(8)
                        freeHeader.putInt(newFreeSize)
                        freeHeader.put('f'.code.toByte())
                        freeHeader.put('r'.code.toByte())
                        freeHeader.put('e'.code.toByte())
                        freeHeader.put('e'.code.toByte())
                        freeHeader.flip()
                        outChannel.position(newFreePos.toLong())
                        outChannel.write(freeHeader)
                        return true
                    }
                }
                pos += size
            }

            // 2. Search tail if not found in head
            if (fileSize > headLimit) {
                val tailSize = Math.min(fileSize - headLimit, 2 * 1024 * 1024L).toInt()
                val tailStart = fileSize - tailSize
                val tailBuffer = ByteBuffer.allocate(tailSize)
                inChannel.position(tailStart)
                inChannel.read(tailBuffer)
                tailBuffer.flip()
                val tailBytes = tailBuffer.array()

                // Check Case 1 in tail: existing \xa9eng or \xa9xyz box
                for (i in 4 until tailSize - 26) {
                    if ((tailBytes[i] == 0xA9.toByte() && tailBytes[i + 1] == 'e'.code.toByte() && tailBytes[i + 2] == 'n'.code.toByte() && tailBytes[i + 3] == 'g'.code.toByte()) ||
                        (tailBytes[i] == 0xA9.toByte() && tailBytes[i + 1] == 'x'.code.toByte() && tailBytes[i + 2] == 'y'.code.toByte() && tailBytes[i + 3] == 'z'.code.toByte())
                    ) {
                        val boxSize = tailBuffer.getInt(i - 4)
                        if (boxSize in 28..38) {
                            outChannel.position(tailStart + i - 4)
                            outChannel.write(ByteBuffer.wrap(xyzBox))
                            return true
                        }
                    }
                }

                // Check Case 3: moov atom at tail of file
                for (i in tailSize - 8 downTo 0) {
                    if (tailBytes[i + 4] == 'm'.code.toByte() && tailBytes[i + 5] == 'o'.code.toByte() && tailBytes[i + 6] == 'o'.code.toByte() && tailBytes[i + 7] == 'v'.code.toByte()) {
                        val size = tailBuffer.getInt(i)
                        val moovAbsPos = tailStart + i
                        if (moovAbsPos + size == fileSize) {
                            val newMoovSize = size + udtaBox.size
                            val moovSizeBuf = ByteBuffer.allocate(4).putInt(newMoovSize)
                            moovSizeBuf.flip()
                            outChannel.position(moovAbsPos)
                            outChannel.write(moovSizeBuf)

                            outChannel.position(fileSize)
                            outChannel.write(ByteBuffer.wrap(udtaBox))
                            return true
                        }
                    }
                }
            }

            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun patchQuickTimeMetaLocation(
        outChannel: java.nio.channels.FileChannel,
        headBytes: ByteArray,
        headLimit: Int,
        lat: Double,
        lon: Double
    ): Boolean {
        return try {
            val headBuf = ByteBuffer.wrap(headBytes)
            var pos = 0
            var moovPos = -1
            var moovSize = -1

            while (pos + 8 <= headLimit) {
                val size = headBuf.getInt(pos)
                if (size <= 0) break
                val isMoov = headBytes[pos + 4] == 'm'.code.toByte() &&
                    headBytes[pos + 5] == 'o'.code.toByte() &&
                    headBytes[pos + 6] == 'o'.code.toByte() &&
                    headBytes[pos + 7] == 'v'.code.toByte()
                if (isMoov) {
                    moovPos = pos
                    moovSize = size
                    break
                }
                pos += size
            }

            if (moovPos == -1 || moovPos + moovSize > headLimit) return false

            val freePos = moovPos + moovSize
            if (freePos + 8 > headLimit) return false
            val isFree = headBytes[freePos + 4] == 'f'.code.toByte() &&
                headBytes[freePos + 5] == 'r'.code.toByte() &&
                headBytes[freePos + 6] == 'e'.code.toByte() &&
                headBytes[freePos + 7] == 'e'.code.toByte()
            if (!isFree) return false
            val freeSize = headBuf.getInt(freePos)
            if (freeSize < 120) return false

            var metaPos = -1
            var metaSize = -1
            var mpos = moovPos + 8
            val moovEnd = moovPos + moovSize
            while (mpos + 8 <= moovEnd) {
                val size = headBuf.getInt(mpos)
                if (size <= 0) break
                val isMeta = headBytes[mpos + 4] == 'm'.code.toByte() &&
                    headBytes[mpos + 5] == 'e'.code.toByte() &&
                    headBytes[mpos + 6] == 't'.code.toByte() &&
                    headBytes[mpos + 7] == 'a'.code.toByte()
                if (isMeta) {
                    metaPos = mpos
                    metaSize = size
                    break
                }
                mpos += size
            }

            if (metaPos == -1 || metaPos + metaSize > moovEnd) return false

            var keysPos = -1
            var keysSize = -1
            var ilstPos = -1
            var ilstSize = -1
            var kpos = metaPos + 8
            val metaEnd = metaPos + metaSize

            while (kpos + 8 <= metaEnd) {
                val size = headBuf.getInt(kpos)
                if (size <= 0) break
                val name = String(headBytes, kpos + 4, 4, Charsets.ISO_8859_1)
                if (name == "keys") {
                    keysPos = kpos
                    keysSize = size
                } else if (name == "ilst") {
                    ilstPos = kpos
                    ilstSize = size
                }
                kpos += size
            }

            if (keysPos == -1 || ilstPos == -1) return false

            val numKeys = headBuf.getInt(keysPos + 12)
            val latSign = if (lat >= 0) "+" else "-"
            val lonSign = if (lon >= 0) "+" else "-"
            val isoStr = String.format(Locale.US, "%s%07.4f%s%08.4f/", latSign, Math.abs(lat), lonSign, Math.abs(lon))
            val isoBytes = isoStr.toByteArray(Charsets.UTF_8)

            val keyTarget = "com.apple.quicktime.location.ISO6709"
            val keyBytes = "mdta$keyTarget".toByteArray(Charsets.ISO_8859_1)
            var existingKeyIdx = -1
            var curKeyPos = keysPos + 16
            for (k in 1..numKeys) {
                if (curKeyPos + 4 > keysPos + keysSize) break
                val kSize = headBuf.getInt(curKeyPos)
                if (curKeyPos + kSize > keysPos + keysSize) break
                val kName = String(headBytes, curKeyPos + 4, kSize - 4, Charsets.ISO_8859_1)
                if (kName.contains(keyTarget)) {
                    existingKeyIdx = k
                    break
                }
                curKeyPos += kSize
            }

            if (existingKeyIdx != -1) {
                var curItemPos = ilstPos + 8
                val ilstEnd = ilstPos + ilstSize
                while (curItemPos + 8 <= ilstEnd) {
                    val itemSize = headBuf.getInt(curItemPos)
                    val itemIdx = headBuf.getInt(curItemPos + 4)
                    if (itemIdx == existingKeyIdx) {
                        val dataAtomPos = curItemPos + 8
                        if (dataAtomPos + 16 + isoBytes.size <= curItemPos + itemSize) {
                            outChannel.position((dataAtomPos + 16).toLong())
                            outChannel.write(ByteBuffer.wrap(isoBytes))
                            return true
                        }
                    }
                    if (itemSize <= 0) break
                    curItemPos += itemSize
                }
                return false
            }

            val newKeySize = 4 + keyBytes.size
            val newKeyBuf = ByteBuffer.allocate(newKeySize)
            newKeyBuf.putInt(newKeySize)
            newKeyBuf.put(keyBytes)
            newKeyBuf.flip()

            val newKeyIdx = numKeys + 1
            val dataAtomSize = 16 + isoBytes.size
            val newItemSize = 8 + dataAtomSize
            val newItemBuf = ByteBuffer.allocate(newItemSize)
            newItemBuf.putInt(newItemSize)
            newItemBuf.putInt(newKeyIdx)
            newItemBuf.putInt(dataAtomSize)
            newItemBuf.put("data".toByteArray(Charsets.ISO_8859_1))
            newItemBuf.putInt(1) // UTF-8
            newItemBuf.putInt(0) // locale
            newItemBuf.put(isoBytes)
            newItemBuf.flip()

            val totalAdded = newKeySize + newItemSize
            if (freeSize < totalAdded + 8) return false

            val oldKeysPayload = headBytes.copyOfRange(keysPos + 16, keysPos + keysSize)
            val newKeysBox = ByteBuffer.allocate(keysSize + newKeySize)
            newKeysBox.putInt(keysSize + newKeySize)
            newKeysBox.put("keys".toByteArray(Charsets.ISO_8859_1))
            newKeysBox.putInt(0)
            newKeysBox.putInt(newKeyIdx)
            newKeysBox.put(oldKeysPayload)
            newKeysBox.put(newKeyBuf.array())
            newKeysBox.flip()

            val oldIlstPayload = headBytes.copyOfRange(ilstPos + 8, ilstPos + ilstSize)
            val newIlstBox = ByteBuffer.allocate(ilstSize + newItemSize)
            newIlstBox.putInt(ilstSize + newItemSize)
            newIlstBox.put("ilst".toByteArray(Charsets.ISO_8859_1))
            newIlstBox.put(oldIlstPayload)
            newIlstBox.put(newItemBuf.array())
            newIlstBox.flip()

            val hdlrPos = headBytes.indexOfSequence(metaPos, metaPos + metaSize, "hdlr".toByteArray(Charsets.ISO_8859_1)) - 4
            if (hdlrPos < 0) return false
            val hdlrSize = headBuf.getInt(hdlrPos)
            val hdlrBox = headBytes.copyOfRange(hdlrPos, hdlrPos + hdlrSize)

            val newMetaPayload = ByteBuffer.allocate(hdlrBox.size + newKeysBox.remaining() + newIlstBox.remaining())
            newMetaPayload.put(hdlrBox)
            newMetaPayload.put(newKeysBox)
            newMetaPayload.put(newIlstBox)
            newMetaPayload.flip()

            val newMetaBox = ByteBuffer.allocate(8 + newMetaPayload.remaining())
            newMetaBox.putInt(8 + newMetaPayload.remaining())
            newMetaBox.put("meta".toByteArray(Charsets.ISO_8859_1))
            newMetaBox.put(newMetaPayload)
            newMetaBox.flip()

            val moovPrefix = headBytes.copyOfRange(moovPos + 8, metaPos)
            val moovSuffix = headBytes.copyOfRange(metaPos + metaSize, moovPos + moovSize)

            val newMoovSize = 8 + moovPrefix.size + newMetaBox.remaining() + moovSuffix.size
            val newMoovBox = ByteBuffer.allocate(newMoovSize)
            newMoovBox.putInt(newMoovSize)
            newMoovBox.put("moov".toByteArray(Charsets.ISO_8859_1))
            newMoovBox.put(moovPrefix)
            newMoovBox.put(newMetaBox)
            newMoovBox.put(moovSuffix)
            newMoovBox.flip()

            val newFreeSize = freeSize - totalAdded
            val newFreeBox = ByteBuffer.allocate(8)
            newFreeBox.putInt(newFreeSize)
            newFreeBox.put("free".toByteArray(Charsets.ISO_8859_1))
            newFreeBox.flip()

            outChannel.position(moovPos.toLong())
            outChannel.write(newMoovBox)
            outChannel.write(newFreeBox)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun ByteArray.indexOfSequence(start: Int, end: Int, seq: ByteArray): Int {
        for (i in start until end - seq.size) {
            var match = true
            for (j in seq.indices) {
                if (this[i + j] != seq[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun buildUdtaBox(xyzBox: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(8 + xyzBox.size)
        buffer.putInt(8 + xyzBox.size)
        buffer.put('u'.code.toByte())
        buffer.put('d'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.put(xyzBox)
        return buffer.array()
    }

    private fun buildXyzBox(lat: Double, lon: Double): ByteArray {
        val latSign = if (lat >= 0) "+" else "-"
        val lonSign = if (lon >= 0) "+" else "-"
        val latStr = String.format(Locale.US, "%s%07.4f", latSign, Math.abs(lat))
        val lonStr = String.format(Locale.US, "%s%08.4f", lonSign, Math.abs(lon))
        val isoBytes = ("$latStr$lonStr/").toByteArray(Charsets.ISO_8859_1)

        val buffer = ByteBuffer.allocate(30)
        buffer.putInt(30)
        buffer.put(0xA9.toByte())
        buffer.put('x'.code.toByte())
        buffer.put('y'.code.toByte())
        buffer.put('z'.code.toByte())
        buffer.putShort(0x0012.toShort())
        buffer.putShort(0x15C7.toShort())
        buffer.put(isoBytes)
        return buffer.array()
    }

    private fun writeToFd(
        fd: FileDescriptor,
        epochMillis: Long?,
        coordinates: Pair<Double, Double>?,
        timeZoneId: String
    ): Boolean {
        val exif = ExifInterface(fd)
        writeExifTags(exif, epochMillis, coordinates, timeZoneId)
        exif.saveAttributes()
        return true
    }

    private fun writeExifTags(
        exif: ExifInterface,
        epochMillis: Long?,
        coordinates: Pair<Double, Double>?,
        timeZoneId: String
    ) {
        if (epochMillis != null) {
            val zone = try { ZoneId.of(timeZoneId) } catch (e: Exception) { ZoneId.of("UTC") }
            val zonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
            val formattedDate = zonedDateTime.format(exifDateFormatter)
            val formattedOffset = zonedDateTime.format(offsetFormatter)

            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, formattedOffset)
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, formattedOffset)
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, formattedOffset)
            }
        }

        if (coordinates != null) {
            val (lat, lng) = coordinates
            exif.setLatLong(lat, lng)
            if (epochMillis != null) {
                val utc = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.of("UTC"))
                exif.setAttribute(
                    ExifInterface.TAG_GPS_DATESTAMP,
                    utc.format(DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US))
                )
                exif.setAttribute(
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    utc.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US))
                )
            }
            exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, null)
            exif.setAttribute(ExifInterface.TAG_GPS_SPEED, null)
            exif.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, null)
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
        }
    }
}
