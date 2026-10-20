package com.apadev.memoryfix.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SnapchatHistoryParserTest {

    @Test
    fun testParseHtmlMemories() {
        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head><title>Memories History</title></head>
            <body>
            <table>
              <tr>
                <th>Date</th>
                <th>Media Type</th>
                <th>Location</th>
                <th>Download Link</th>
              </tr>
              <tr>
                <td>2023-08-14 09:15:20 UTC</td>
                <td>PHOTO</td>
                <td>Latitude, Longitude: 28.6139, 77.2090</td>
                <td><a href="https://aws.snapchat.com/media1">Download</a></td>
              </tr>
              <tr>
                <td>2023-08-14 14:22:10 UTC</td>
                <td>Video</td>
                <td>Latitude, Longitude: 28.6139, 77.2090</td>
                <td><a href="https://aws.snapchat.com/media2">Download</a></td>
              </tr>
              <tr>
                <td>2023-08-15 18:30:00 UTC</td>
                <td>Image</td>
                <td></td>
                <td><a href="https://aws.snapchat.com/media3">Download</a></td>
              </tr>
            </table>
            </body>
            </html>
        """.trimIndent()

        val items = SnapchatHistoryParser.parse(ByteArrayInputStream(htmlContent.toByteArray(Charsets.UTF_8)))

        assertEquals(3, items.size)

        assertEquals("2023-08-14 09:15:20 UTC", items[0].dateString)
        assertEquals("PHOTO", items[0].mediaType)
        assertFalse(items[0].isVideo())
        assertEquals("Latitude, Longitude: 28.6139, 77.2090", items[0].locationString)

        assertEquals("2023-08-14 14:22:10 UTC", items[1].dateString)
        assertEquals("VIDEO", items[1].mediaType)
        assertTrue(items[1].isVideo())

        assertEquals("2023-08-15 18:30:00 UTC", items[2].dateString)
        assertEquals("PHOTO", items[2].mediaType)
        assertFalse(items[2].isVideo())
    }

    @Test
    fun testParseJsonMemories() {
        val jsonContent = """
            {
              "Saved Media": [
                {
                  "Date": "2023-08-14 09:15:20 UTC",
                  "Media Type": "PHOTO",
                  "Location": "Latitude, Longitude: 28.6139, 77.2090"
                },
                {
                  "Date": "2023-08-14 14:22:10 UTC",
                  "Media Type": "VIDEO",
                  "Location": "Latitude, Longitude: 28.6139, 77.2090"
                }
              ]
            }
        """.trimIndent()

        val items = SnapchatHistoryParser.parse(ByteArrayInputStream(jsonContent.toByteArray(Charsets.UTF_8)))
        assertEquals(2, items.size)
        assertEquals("2023-08-14 09:15:20 UTC", items[0].dateString)
        assertEquals("2023-08-14 14:22:10 UTC", items[1].dateString)
    }
}
