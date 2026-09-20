package com.example.teliktv

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Кэш каналов (filesDir/channels.json) и избранное (SharedPreferences). */
class ChannelRepository(context: Context) {
    private val file = File(context.filesDir, "channels.json")
    private val prefs = context.getSharedPreferences("teliktv", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Channel.serializer())

    fun load(): List<Channel> =
        try {
            if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    fun save(channels: List<Channel>) {
        try {
            file.writeText(json.encodeToString(serializer, channels))
        } catch (e: Exception) {
            // кэш — best effort
        }
    }

    fun loadFavorites(): Set<String> = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()

    fun saveFavorites(slugs: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVORITES, slugs).apply()
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
    }
}
