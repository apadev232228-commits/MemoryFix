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
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                writeToFd(pfd.fileDescriptor, epochMillis, coordinates, timeZoneId)
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Applies date/time to a video file via FileDescriptor (SAF-compatible).
     * Patches MP4 creation/modification time in the `mvhd` atom.
     */
    fun applyMetadataToVideo(
        context: Context,
        uri: Uri,
        epochMillis: Long?
    ): Boolean {
        if (epochMillis == null) return false
        return try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                patchMp4Timestamp(pfd.fileDescriptor, epochMillis)
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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
            val loc = Location("user").apply {
                latitude = lat
                longitude = lng
            }
            exif.setGpsInfo(loc)
        }
    }
}
