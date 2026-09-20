package com.example.teliktv

import android.content.Context

/** Хранит slug'и избранных каналов в SharedPreferences. */
class FavoritesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("teliktv_favorites", Context.MODE_PRIVATE)

    fun load(): Set<String> =
        prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun save(slugs: Set<String>) {
        prefs.edit().putStringSet(KEY, slugs).apply()
    }

    private companion object {
        const val KEY = "slugs"
    }
}