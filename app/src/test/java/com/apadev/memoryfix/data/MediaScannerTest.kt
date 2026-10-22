package com.apadev.memoryfix.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaScannerTest {

    @Test
    fun testExtractSnapchatUuid() {
        val name1 = "2021-04-02_0479e3b9-ceaa-9376-3625-9cdcaea3d347-main.jpg"
        val uuid1 = MediaScanner.extractSnapchatUuid(name1)
        assertEquals("0479e3b9-ceaa-9376-3625-9cdcaea3d347", uuid1)

        val name2 = "2021-03-28_0479e3b9-ceaa-9376-3625-9cdcaea3d347-main.jpg"
        val uuid2 = MediaScanner.extractSnapchatUuid(name2)
        assertEquals("0479e3b9-ceaa-9376-3625-9cdcaea3d347", uuid2)

        val nameWithoutUuid = "2023-08-14.jpg"
        val uuidNone = MediaScanner.extractSnapchatUuid(nameWithoutUuid)
        assertNull(uuidNone)
    }

    @Test
    fun testExtractCanonicalBaseKey() {
        val mainPhoto = "2021-03-28_0479e3b9-ceaa-9376-3625-9cdcaea3d347-main.jpg"
        val overlayPhoto = "2021-03-28_0479e3b9-ceaa-9376-3625-9cdcaea3d347-overlay.png"

        val key1 = MediaScanner.extractCanonicalBaseKey(mainPhoto)
        val key2 = MediaScanner.extractCanonicalBaseKey(overlayPhoto)

        assertEquals("2021-03-28_0479e3b9-ceaa-9376-3625-9cdcaea3d347", key1)
        assertEquals(key1, key2)
    }

    @Test
    fun testInterpolateMissingLocationsWithin30Mins() {
        val item1 = com.apadev.memoryfix.model.MemoryItem(
            dateString = "2021-04-02 06:36:00 UTC",
            mediaType = "PHOTO",
            locationString = "Latitude, Longitude: 28.6695, 77.0804"
        )
        val missingItem = com.apadev.memoryfix.model.MemoryItem(
            dateString = "2021-04-02 06:36:43 UTC",
            mediaType = "VIDEO",
            locationString = "Latitude, Longitude: 0.0, 0.0"
        )
        val interpolated = MediaScanner.MemoryMatcher.interpolateMissingLocations(
            listOf(item1, missingItem),
            maxGapMillis = 30 * 60 * 1000L
        )
        assertEquals(2, interpolated.size)
        val coords = interpolated[1].parseCoordinates()
        assertNotNull(coords)
        assertEquals(28.6695, coords!!.first, 0.0001)
        assertEquals(77.0804, coords.second, 0.0001)
    }

    @Test
    fun testInterpolateMissingLocationsRejectsLargeGap() {
        val item1 = com.apadev.memoryfix.model.MemoryItem(
            dateString = "2021-04-02 06:00:00 UTC",
            mediaType = "PHOTO",
            locationString = "Latitude, Longitude: 28.6695, 77.0804"
        )
        // 45 minutes gap (> 30 min)
        val missingItem = com.apadev.memoryfix.model.MemoryItem(
            dateString = "2021-04-02 06:45:00 UTC",
            mediaType = "VIDEO",
            locationString = "Latitude, Longitude: 0.0, 0.0"
        )
        val interpolated = MediaScanner.MemoryMatcher.interpolateMissingLocations(
            listOf(item1, missingItem),
            maxGapMillis = 30 * 60 * 1000L
        )
        assertNull(interpolated[1].parseCoordinates())
    }

    @Test
    fun testInterpolateMissingLocationsRejectsTravelCommute() {
        // Delhi memory at 10:00
        val beforeItem = com.apadev.memoryfix.model.MemoryItem(
            dateString = "2024-05-26 10:00:00 UTC",
            mediaType = "PHOTO",
            locationString = "Latitude, Longitude: 28.5531, 77.0857" // Delhi
        )
        // Missing memory at 10:15
        val missingItem = com.apadev.memoryfix.model.MemoryItem(
            dateString = "2024-05-26 10:15:00 UTC",
            mediaType = "PHOTO",
            locationString = "Latitude, Longitude: 0.0, 0.0"
        )
        // Pune memory at 10:25 (> 1000km away)
        val afterItem = com.apadev.memoryfix.model.MemoryItem(
            dateString = "2024-05-26 10:25:00 UTC",
            mediaType = "PHOTO",
            locationString = "Latitude, Longitude: 18.5009, 73.9209" // Pune
        )
        val interpolated = MediaScanner.MemoryMatcher.interpolateMissingLocations(
            listOf(beforeItem, missingItem, afterItem),
            maxGapMillis = 30 * 60 * 1000L
        )
        // Must reject because distance between before and after > 2km
        assertNull(interpolated[1].parseCoordinates())
    }
}
