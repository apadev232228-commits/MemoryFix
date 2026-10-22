package com.apadev.memoryfix.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class ExifManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMp4LocationPatchingCase1OverwriteUdta() {
        val testFile = tempFolder.newFile("test_video_case1.mp4")

        // Build a minimal MP4 with ftyp, moov (mvhd + udta) and free
        FileOutputStream(testFile).use { fos ->
            // ftyp
            val ftyp = ByteBuffer.allocate(24)
            ftyp.putInt(24)
            ftyp.put("ftyp".toByteArray())
            ftyp.put("mp42".toByteArray())
            ftyp.putInt(0)
            ftyp.put("isom".toByteArray())
            fos.write(ftyp.array())

            // udta with dummy \xa9eng (38 bytes)
            val udta = ByteBuffer.allocate(38)
            udta.putInt(38)
            udta.put("udta".toByteArray())
            udta.putInt(30)
            udta.put(byteArrayOf(0xA9.toByte(), 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte()))
            udta.putShort(0x0012)
            udta.putShort(0x15C7)
            udta.put("+00.0000+000.0000/".toByteArray(Charsets.ISO_8859_1))

            // mvhd (108 bytes)
            val mvhd = ByteBuffer.allocate(108)
            mvhd.putInt(108)
            mvhd.put("mvhd".toByteArray())
            mvhd.putInt(0) // version & flags
            mvhd.putInt(1000) // creation
            mvhd.putInt(1000) // modification
            mvhd.putInt(1000) // timescale
            mvhd.putInt(5000) // duration (5s)
            mvhd.put(ByteArray(80)) // remaining fields

            // moov (8 + 108 + 38 = 154 bytes)
            val moov = ByteBuffer.allocate(8)
            moov.putInt(154)
            moov.put("moov".toByteArray())

            fos.write(moov.array())
            fos.write(mvhd.array())
            fos.write(udta.array())

            // free atom (100 bytes)
            val free = ByteBuffer.allocate(100)
            free.putInt(100)
            free.put("free".toByteArray())
            free.put(ByteArray(92))
            fos.write(free.array())
        }

        val lat = 15.551694
        val lon = 73.77293

        RandomAccessFile(testFile, "rw").use { raf ->
            val method = ExifManager::class.java.getDeclaredMethod(
                "patchMp4Location",
                java.io.FileDescriptor::class.java,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType
            )
            method.isAccessible = true
            val success = method.invoke(ExifManager, raf.fd, lat, lon) as Boolean
            assertEquals(true, success)
        }

        // Read location back directly from file
        val loc = ExifManager.readMp4Location(testFile)
        assertNotNull(loc)
        assertEquals(15.5517, loc!!.first, 0.0001)
        assertEquals(73.7729, loc.second, 0.0001)
    }

    @Test
    fun testMp4LocationPatchingCase2AbsorbFree() {
        val testFile = tempFolder.newFile("test_video_case2.mp4")

        // Build a minimal MP4 with ftyp, moov (no udta), and a following free atom
        FileOutputStream(testFile).use { fos ->
            val ftyp = ByteBuffer.allocate(24)
            ftyp.putInt(24)
            ftyp.put("ftyp".toByteArray())
            ftyp.put("mp42".toByteArray())
            ftyp.putInt(0)
            ftyp.put("isom".toByteArray())
            fos.write(ftyp.array())

            // mvhd (108 bytes)
            val mvhd = ByteBuffer.allocate(108)
            mvhd.putInt(108)
            mvhd.put("mvhd".toByteArray())
            mvhd.putInt(0)
            mvhd.putInt(1000)
            mvhd.putInt(1000)
            mvhd.putInt(1000)
            mvhd.putInt(5000)
            mvhd.put(ByteArray(80))

            // moov (8 + 108 = 116 bytes)
            val moov = ByteBuffer.allocate(8)
            moov.putInt(116)
            moov.put("moov".toByteArray())

            fos.write(moov.array())
            fos.write(mvhd.array())

            // free atom (100 bytes)
            val free = ByteBuffer.allocate(100)
            free.putInt(100)
            free.put("free".toByteArray())
            free.put(ByteArray(92))
            fos.write(free.array())
        }

        val lat = -34.6037
        val lon = -58.3816

        RandomAccessFile(testFile, "rw").use { raf ->
            val method = ExifManager::class.java.getDeclaredMethod(
                "patchMp4Location",
                java.io.FileDescriptor::class.java,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType
            )
            method.isAccessible = true
            val success = method.invoke(ExifManager, raf.fd, lat, lon) as Boolean
            assertEquals(true, success)
        }

        // Read location back directly from file
        val loc = ExifManager.readMp4Location(testFile)
        assertNotNull(loc)
        assertEquals(-34.6037, loc!!.first, 0.0001)
        assertEquals(-58.3816, loc.second, 0.0001)
    }

    @Test
    fun testMp4LocationPatchingWithQuickTimeMeta() {
        val testFile = tempFolder.newFile("test_video_qt.mp4")

        // Build MP4 with ftyp, moov (mvhd + meta + udta) and free atom
        FileOutputStream(testFile).use { fos ->
            // ftyp (24 bytes)
            val ftyp = ByteBuffer.allocate(24)
            ftyp.putInt(24)
            ftyp.put("ftyp".toByteArray())
            ftyp.put("mp42".toByteArray())
            ftyp.putInt(0)
            ftyp.put("isom".toByteArray())
            fos.write(ftyp.array())

            // udta with \xa9eng (38 bytes)
            val udta = ByteBuffer.allocate(38)
            udta.putInt(38)
            udta.put("udta".toByteArray())
            udta.putInt(30)
            udta.put(byteArrayOf(0xA9.toByte(), 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte()))
            udta.putShort(0x0012)
            udta.putShort(0x15C7)
            udta.put("+00.0000+000.0000/".toByteArray(Charsets.ISO_8859_1))

            // mvhd (108 bytes)
            val mvhd = ByteBuffer.allocate(108)
            mvhd.putInt(108)
            mvhd.put("mvhd".toByteArray())
            mvhd.putInt(0)
            mvhd.putInt(1000)
            mvhd.putInt(1000)
            mvhd.putInt(1000)
            mvhd.putInt(5000)
            mvhd.put(ByteArray(80))

            // meta with hdlr, keys (1 key: mdtadescription), ilst (1 item)
            // hdlr (33 bytes)
            val hdlr = ByteBuffer.allocate(33)
            hdlr.putInt(33)
            hdlr.put("hdlr".toByteArray())
            hdlr.putInt(0) // ver/flags
            hdlr.putInt(0)
            hdlr.put("mdta".toByteArray())
            hdlr.put(ByteArray(13))

            // keys (35 bytes: 8 header + 4 ver/flags + 4 count (1) + 19 key1)
            val keys = ByteBuffer.allocate(35)
            keys.putInt(35)
            keys.put("keys".toByteArray())
            keys.putInt(0)
            keys.putInt(1) // 1 key
            keys.putInt(19) // key size
            keys.put("mdtadescription".toByteArray())

            // ilst (132 bytes)
            val ilst = ByteBuffer.allocate(132)
            ilst.putInt(132)
            ilst.put("ilst".toByteArray())
            ilst.putInt(124) // item 1 size
            ilst.putInt(1) // 1-based index 1
            ilst.putInt(116) // data size
            ilst.put("data".toByteArray())
            ilst.putInt(1) // type utf8
            ilst.putInt(0) // locale
            ilst.put(ByteArray(100)) // dummy payload

            // meta total size = 8 + 33 + 35 + 132 = 208 bytes
            val meta = ByteBuffer.allocate(208)
            meta.putInt(208)
            meta.put("meta".toByteArray())
            meta.put(hdlr.array())
            meta.put(keys.array())
            meta.put(ilst.array())

            // moov total size = 8 + 108 + 38 + 208 = 362 bytes
            val moov = ByteBuffer.allocate(8)
            moov.putInt(362)
            moov.put("moov".toByteArray())

            fos.write(moov.array())
            fos.write(mvhd.array())
            fos.write(udta.array())
            fos.write(meta.array())

            // free atom (1000 bytes)
            val free = ByteBuffer.allocate(1000)
            free.putInt(1000)
            free.put("free".toByteArray())
            free.put(ByteArray(992))
            fos.write(free.array())
        }

        val lat = 18.5184
        val lon = 73.9480

        RandomAccessFile(testFile, "rw").use { raf ->
            val method = ExifManager::class.java.getDeclaredMethod(
                "patchMp4Location",
                java.io.FileDescriptor::class.java,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType
            )
            method.isAccessible = true
            val success = method.invoke(ExifManager, raf.fd, lat, lon) as Boolean
            assertEquals(true, success)
        }

        val bytes = testFile.readBytes()
        // Verify QuickTime key was injected
        val keyTarget = "com.apple.quicktime.location.ISO6709".toByteArray()
        val hasKey = (0 until bytes.size - keyTarget.size).any { idx ->
            keyTarget.indices.all { bytes[idx + it] == keyTarget[it] }
        }
        assertEquals(true, hasKey)

        // Verify \xa9xyz in udta was also updated
        val loc = ExifManager.readMp4Location(testFile)
        assertNotNull(loc)
        assertEquals(18.5184, loc!!.first, 0.0001)
        assertEquals(73.9480, loc.second, 0.0001)
    }
}
