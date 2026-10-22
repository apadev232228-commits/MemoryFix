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
}
