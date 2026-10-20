package com.apadev.memoryfix.data

import com.apadev.memoryfix.model.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DateExtractorTest {

    @Test
    fun testHasTimeInFileName() {
        assertFalse(DateExtractor.hasTimeInFileName("2023-08-14.jpg"))
        assertFalse(DateExtractor.hasTimeInFileName("2023-08-14-1.jpg"))
        assertFalse(DateExtractor.hasTimeInFileName("2023-08-14-main.jpg"))
        assertFalse(DateExtractor.hasTimeInFileName("2023-08-14-1-overlay.png"))

        assertTrue(DateExtractor.hasTimeInFileName("2023-08-14_19-42-10-main.jpg"))
        assertTrue(DateExtractor.hasTimeInFileName("2023-08-14-19-42-10.jpg"))
        assertTrue(DateExtractor.hasTimeInFileName("20230814_194210.mp4"))
    }

    @Test
    fun testExtractDateKey() {
        assertEquals("2023-08-14", DateExtractor.extractDateKey("2023-08-14.jpg"))
        assertEquals("2023-08-14", DateExtractor.extractDateKey("2023-08-14-1.jpg"))
        assertEquals("2023-08-14", DateExtractor.extractDateKey("2023-08-14-2-main.jpg"))
        assertEquals("2023-08-14", DateExtractor.extractDateKey("2023-08-14_19-42-10.jpg"))
    }

    @Test
    fun testExtractSequenceIndex() {
        assertEquals(0, DateExtractor.extractSequenceIndex("2023-08-14.jpg"))
        assertEquals(0, DateExtractor.extractSequenceIndex("2023-08-14-main.jpg"))
        assertEquals(1, DateExtractor.extractSequenceIndex("2023-08-14-1.jpg"))
        assertEquals(1, DateExtractor.extractSequenceIndex("2023-08-14-1-main.jpg"))
        assertEquals(2, DateExtractor.extractSequenceIndex("2023-08-14-2.jpg"))
        assertEquals(3, DateExtractor.extractSequenceIndex("2023-08-14 (3).jpg"))
        assertEquals(0, DateExtractor.extractSequenceIndex("2023-08-14_19-42-10-main.jpg"))
    }

    @Test
    fun testSameDayFallbackPhotosDoNotClampAt12PM() {
        val timeZone = "Asia/Calcutta"
        val epoch0 = DateExtractor.extractEpochMillisFromFileName("2023-08-14.jpg", timeZone)
        val epoch1 = DateExtractor.extractEpochMillisFromFileName("2023-08-14-1.jpg", timeZone)
        val epoch2 = DateExtractor.extractEpochMillisFromFileName("2023-08-14-2.jpg", timeZone)

        assertTrue(epoch0 != null)
        assertTrue(epoch1 != null)
        assertTrue(epoch2 != null)

        assertNotEquals(epoch0, epoch1)
        assertNotEquals(epoch1, epoch2)

        val zdt0 = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch0!!), ZoneId.of(timeZone))
        val zdt1 = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch1!!), ZoneId.of(timeZone))

        assertEquals(10, zdt0.hour)
        assertEquals(0, zdt0.minute)
        assertEquals(10, zdt1.hour)
        assertEquals(15, zdt1.minute)
    }

    @Test
    fun testMemoryItemParsingWithAsiaCalcutta() {
        val memory = MemoryItem(
            dateString = "2023-08-14 09:15:20 UTC",
            mediaType = "PHOTO",
            locationString = "Latitude, Longitude: 28.6139, 77.2090"
        )

        val epoch = memory.parseEpochMillis()
        assertTrue(epoch != null)

        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch!!), ZoneId.of("Asia/Calcutta"))
        assertEquals(2023, zdt.year)
        assertEquals(8, zdt.monthValue)
        assertEquals(14, zdt.dayOfMonth)
        assertEquals(14, zdt.hour) // 09:15 UTC + 05:30 = 14:45
        assertEquals(45, zdt.minute)
        assertEquals(20, zdt.second)

        val coords = memory.parseCoordinates()
        assertTrue(coords != null)
        assertEquals(28.6139, coords!!.first, 0.0001)
        assertEquals(77.2090, coords.second, 0.0001)
    }
}
