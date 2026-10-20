package com.apadev.memoryfix.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object DateExtractor {

    private val PATTERNS = listOf(
        // 2023-08-14_19-42-10 or 2023-08-14-19-42-10 or 2023_08_14_19_42_10
        Regex("""(\d{4})[-_](\d{2})[-_](\d{2})[-_T\s](\d{2})[-_.:](\d{2})[-_.:](\d{2})"""),
        // 20230814_194210
        Regex("""(\d{4})(\d{2})(\d{2})[-_](\d{2})(\d{2})(\d{2})"""),
        // 2023-08-14
        Regex("""(\d{4})[-_](\d{2})[-_](\d{2})""")
    )

    /**
     * Checks if the filename contains hours, minutes, and seconds.
     */
    fun hasTimeInFileName(fileName: String): Boolean {
        return PATTERNS[0].containsMatchIn(fileName) || PATTERNS[1].containsMatchIn(fileName)
    }

    /**
     * Extracts date key formatted as "YYYY-MM-DD" from filename.
     */
    fun extractDateKey(fileName: String): String? {
        val match = PATTERNS[2].find(fileName) ?: return null
        val (y, m, d) = match.destructured
        return "$y-$m-$d"
    }

    /**
     * Extracts same-day sequence index (e.g. "2023-08-14-1-main.jpg" -> 1, "2023-08-14.jpg" -> 0).
     */
    fun extractSequenceIndex(fileName: String): Int {
        if (hasTimeInFileName(fileName)) return 0
        val clean = fileName.lowercase()
            .replace("-main", "")
            .replace("_main", "")
            .replace("-overlay", "")
            .replace("_overlay", "")
            .substringBeforeLast('.')

        // Check for trailing sequence index like "-1", "_2", "(3)" after date
        val match = Regex("""\d{4}[-_]\d{2}[-_]\d{2}[-_(\s]+(\d+)\)?""").find(clean)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Extracts date/time from filename if JSON metadata is missing or unmatched.
     * Supports Snapchat naming formats like "2023-08-14_19-42-10-main.jpg",
     * "Snapchat-1692042130000.jpg", "20230814_194210.mp4", etc.
     */
    fun extractEpochMillisFromFileName(fileName: String, timeZoneId: String = "UTC"): Long? {
        val zone = try { ZoneId.of(timeZoneId) } catch (e: Exception) { ZoneId.of("UTC") }

        // 1. Check for epoch timestamp e.g. Snapchat-1692042130000 or 1692042130
        val epochMatch = Regex("""(?:snapchat|media|item)[-_](\d{10,13})""", RegexOption.IGNORE_CASE).find(fileName)
        if (epochMatch != null) {
            val numStr = epochMatch.groupValues[1]
            val num = numStr.toLongOrNull()
            if (num != null) {
                return if (numStr.length == 10) num * 1000L else num
            }
        }

        // 2. Try YYYY-MM-DD HH-mm-ss patterns
        val firstRegex = PATTERNS[0].find(fileName)
        if (firstRegex != null) {
            return try {
                val (y, m, d, h, min, s) = firstRegex.destructured
                val ldt = LocalDateTime.of(y.toInt(), m.toInt(), d.toInt(), h.toInt(), min.toInt(), s.toInt())
                ldt.atZone(zone).toInstant().toEpochMilli()
            } catch (e: Exception) { null }
        }

        // 3. Try YYYYMMDD_HHMMSS
        val secondRegex = PATTERNS[1].find(fileName)
        if (secondRegex != null) {
            return try {
                val (y, m, d, h, min, s) = secondRegex.destructured
                val ldt = LocalDateTime.of(y.toInt(), m.toInt(), d.toInt(), h.toInt(), min.toInt(), s.toInt())
                ldt.atZone(zone).toInstant().toEpochMilli()
            } catch (e: Exception) { null }
        }

        // 4. Try YYYY-MM-DD (fallback when time is not in filename: distributes same-day photos chronologically)
        val thirdRegex = PATTERNS[2].find(fileName)
        if (thirdRegex != null) {
            return try {
                val (y, m, d) = thirdRegex.destructured
                val seqIndex = extractSequenceIndex(fileName)
                // Stagger photos sequentially throughout the day (e.g. 10:00, 10:15, 10:30) rather than clamping at 12:00 PM
                val totalMinutes = 600 + (seqIndex * 15) // Start from 10:00 AM
                val hour = (totalMinutes / 60) % 24
                val minute = totalMinutes % 60
                val ldt = LocalDate.of(y.toInt(), m.toInt(), d.toInt()).atTime(hour, minute, 0)
                ldt.atZone(zone).toInstant().toEpochMilli()
            } catch (e: Exception) { null }
        }

        return null
    }
}
