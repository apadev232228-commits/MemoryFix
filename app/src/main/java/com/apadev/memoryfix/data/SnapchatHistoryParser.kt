package com.apadev.memoryfix.data

import android.content.Context
import android.net.Uri
import com.apadev.memoryfix.model.MemoryItem
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import java.io.InputStream
import java.io.InputStreamReader

object SnapchatHistoryParser {

    private val gson: Gson = GsonBuilder().setLenient().create()

    fun parse(inputStream: InputStream, isHtml: Boolean = false): List<MemoryItem> {
        val bufferedStream = inputStream.buffered()
        bufferedStream.mark(2048)
        val headerBytes = ByteArray(1024)
        val read = bufferedStream.read(headerBytes, 0, headerBytes.size)
        bufferedStream.reset()

        val header = if (read > 0) String(headerBytes, 0, read, Charsets.UTF_8).trim() else ""
        val looksLikeHtml = isHtml || header.startsWith("<") || header.contains("<!doctype", ignoreCase = true) || header.contains("<html", ignoreCase = true)

        return if (looksLikeHtml) {
            parseHtml(bufferedStream)
        } else {
            val jsonItems = parseJson(bufferedStream)
            if (jsonItems.isEmpty() && header.contains("<", ignoreCase = true)) {
                parseHtml(bufferedStream)
            } else {
                jsonItems
            }
        }
    }

    fun parseJson(inputStream: InputStream): List<MemoryItem> {
        val items = mutableListOf<MemoryItem>()
        try {
            val reader = JsonReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val token = try { reader.peek() } catch (e: Exception) { null }

            if (token == com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
                val jsonObject = gson.fromJson<JsonObject>(reader, JsonObject::class.java)
                val mediaArray = when {
                    jsonObject.has("Saved Media") -> jsonObject.getAsJsonArray("Saved Media")
                    jsonObject.has("saved_media") -> jsonObject.getAsJsonArray("saved_media")
                    jsonObject.has("Media") -> jsonObject.getAsJsonArray("Media")
                    else -> null
                }

                if (mediaArray != null) {
                    val listType = object : TypeToken<List<MemoryItem>>() {}.type
                    val parsed: List<MemoryItem> = gson.fromJson(mediaArray, listType)
                    items.addAll(parsed)
                }
            } else if (token == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                val listType = object : TypeToken<List<MemoryItem>>() {}.type
                val parsed: List<MemoryItem> = gson.fromJson(reader, listType)
                items.addAll(parsed)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    /**
     * Parses memories_history.html or memories.html tables exported by Snapchat.
     */
    fun parseHtml(inputStream: InputStream): List<MemoryItem> {
        val items = mutableListOf<MemoryItem>()
        val dateRegex = Regex("""(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\s+UTC)""")
        val typeRegex = Regex("""\b(PHOTO|VIDEO|IMAGE|Image|Video|Photo)\b""", RegexOption.IGNORE_CASE)
        val locRegex = Regex("""(Latitude,\s*Longitude:\s*[-0-9.]+\s*,\s*[-0-9.]+)""", RegexOption.IGNORE_CASE)

        try {
            val reader = inputStream.bufferedReader(Charsets.UTF_8)
            val rowBuilder = StringBuilder()
            var inRow = false

            reader.forEachLine { line ->
                val lower = line.lowercase()
                if (lower.contains("<tr")) {
                    inRow = true
                    rowBuilder.setLength(0)
                }

                if (inRow) {
                    rowBuilder.append(line).append(" ")
                }

                if (lower.contains("</tr>") && inRow) {
                    inRow = false
                    val rowText = rowBuilder.toString()
                    val dateMatch = dateRegex.find(rowText)
                    if (dateMatch != null) {
                        val dateStr = dateMatch.groupValues[1]
                        val rowWithoutDate = rowText.replace(dateStr, "")
                        val typeMatch = typeRegex.find(rowWithoutDate)
                        val rawType = typeMatch?.groupValues?.get(1) ?: "PHOTO"
                        val isVideo = rawType.contains("video", ignoreCase = true)
                        val mediaType = if (isVideo) "VIDEO" else "PHOTO"
                        val locMatch = locRegex.find(rowText)
                        val location = locMatch?.groupValues?.get(1)

                        items.add(
                            MemoryItem(
                                dateString = dateStr,
                                mediaType = mediaType,
                                locationString = location
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    /**
     * Helper to parse from SAF Uri
     */
    fun parseFromUri(context: Context, uri: Uri): List<MemoryItem> {
        val path = uri.path?.lowercase() ?: ""
        val isHtml = path.endsWith(".html") || path.endsWith(".htm")
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            parse(stream, isHtml)
        } ?: emptyList()
    }
}
