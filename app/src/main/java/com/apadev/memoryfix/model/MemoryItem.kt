package com.apadev.memoryfix.model

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Represents an entry inside Snapchat's `memories_history.json`.
 * Format typically looks like:
 * {
 *   "Date": "2023-08-14 19:42:10 UTC",
 *   "Media Type": "PHOTO",
 *   "Location": "Latitude, Longitude: 40.7128, -74.0060",
 *   "Download Link": "https://..."
 * }
 */
data class MemoryItem(
    @SerializedName("Date")
    val dateString: String? = null,

    @SerializedName("Media Type")
    val mediaType: String? = null,

    @SerializedName("Location")
    val locationString: String? = null,

    @SerializedName("Download Link")
    val downloadLink: String? = null
) {
    /**
     * Parse the UTC date string into epoch millis and ZonedDateTime.
     */
    fun parseEpochMillis(): Long? {
        if (dateString.isNullOrBlank()) return null
        return try {
            val trimmed = dateString.trim()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
            val zdt = ZonedDateTime.of(
                java.time.LocalDateTime.parse(trimmed, formatter),
                ZoneId.of("UTC")
            )
            zdt.toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                // Fallback standard ISO-8601
                Instant.parse(dateString).toEpochMilli()
            } catch (ex: Exception) {
                null
            }
        }
    }

    /**
     * Extracts latitude and longitude from strings like "Latitude, Longitude: 37.7749, -122.4194"
     */
    fun parseCoordinates(): Pair<Double, Double>? {
        if (locationString.isNullOrBlank()) return null
        return try {
            val cleaned = locationString.replace("Latitude, Longitude:", "", ignoreCase = true).trim()
            val parts = cleaned.split(",")
            if (parts.size >= 2) {
                val lat = parts[0].trim().toDouble()
                val lng = parts[1].trim().toDouble()
                if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                    lat to lng
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
