package com.apadev.memoryfix

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apadev.memoryfix.data.VideoOverlayCompositor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoOverlayTest {

    @Test
    fun testVideoOverlayCompositor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val videoFile = File(downloadDir, "test_vid.mp4")
        val overlayFile = File(downloadDir, "test_overlay.png")

        assertTrue("Video file must exist", videoFile.exists())
        assertTrue("Overlay file must exist", overlayFile.exists())

        val success = VideoOverlayCompositor.compositeInPlace(context, videoFile, overlayFile)
        assertTrue("Video overlay compositing should succeed", success)
    }
}
