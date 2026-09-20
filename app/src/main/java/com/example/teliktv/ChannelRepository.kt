package com.example.teliktv

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Кэш каналов в filesDir/channels.json — чтобы список показывался сразу, до обновления. */
class ChannelRepository(context: Context) {
    private val file = File(context.filesDir, "channels.json")
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
}