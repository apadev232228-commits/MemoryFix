package com.apadev.memoryfix.data

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileDescriptor
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
     * Overload for direct java.io.File
     */
    fun applyMetadataToFile(
        file: File,
        epochMillis: Long?,
        coordinates: Pair<Double, Double>?,
        timeZoneId: String = "UTC"
    ): Boolean {
        return try {
            val exif = ExifInterface(file)
            writeExifTags(exif, epochMillis, coordinates, timeZoneId)
            exif.saveAttributes()
            if (epochMillis != null) {
                file.setLastModified(epochMillis)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
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
