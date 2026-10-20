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

            // Seconds from Jan 1, 1904 to Jan 1, 1970 UTC
            val mp4EpochOffset = 2082844800L
            val mp4Seconds = (epochMillis / 1000) + mp4EpochOffset

            // Search for "mvhd" atom in the first 2MB
            val searchLimit = Math.min(inChannel.size(), 2 * 1024 * 1024L)
            if (searchLimit < 16) return false

            val buffer = ByteBuffer.allocate(searchLimit.toInt())
            inChannel.position(0)
            inChannel.read(buffer)
            buffer.flip()

            val bytes = buffer.array()
            var mvhdPos = -1
            // search for 'm', 'v', 'h', 'd'
            for (i in 0 until bytes.size - 8) {
                if (bytes[i] == 0x6D.toByte() &&
                    bytes[i + 1] == 0x76.toByte() &&
                    bytes[i + 2] == 0x68.toByte() &&
                    bytes[i + 3] == 0x64.toByte()
                ) {
                    mvhdPos = i
                    break
                }
            }

            if (mvhdPos != -1) {
                val version = bytes[mvhdPos + 4].toInt()
                val writePos = (mvhdPos + 4 + 4).toLong() // skip 'mvhd' (4) + version(1) + flags(3) = 8 bytes
                outChannel.position(writePos)

                if (version == 0) {
                    val timeBuf = ByteBuffer.allocate(8)
                    timeBuf.putInt(mp4Seconds.toInt())
                    timeBuf.putInt(mp4Seconds.toInt())
                    timeBuf.flip()
                    outChannel.write(timeBuf)
                } else if (version == 1) {
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
