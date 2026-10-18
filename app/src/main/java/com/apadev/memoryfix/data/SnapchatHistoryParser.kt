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

    /**
     * Parse memories_history.json from an InputStream.
     * Snapchat exports typically structure this as:
     * {
     *   "Saved Media": [
     *      { "Date": "...", "Media Type": "...", "Location": "...", "Download Link": "..." },
     *      ...
     *   ]
     * }
     * Or directly as a list of entries.
     */
    fun parse(inputStream: InputStream): List<MemoryItem> {
        val items = mutableListOf<MemoryItem>()
        try {
            val reader = JsonReader(InputStreamReader(inputStream, Charsets.UTF_8))

            if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
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
            } else if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
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
     * Helper to parse from SAF Uri
     */
    fun parseFromUri(context: Context, uri: Uri): List<MemoryItem> {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            parse(stream)
        } ?: emptyList()
    }
}
