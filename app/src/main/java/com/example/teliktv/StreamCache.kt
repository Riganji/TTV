package com.example.teliktv

import android.content.Context
import android.os.StatFs
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Дисковый кэш сегментов потока (timeshift).
 * Каталог: context.cacheDir/stream_cache
 * Размер ограничен maxBytes; при нехватке места вытесняется LRU.
 */
@OptIn(UnstableApi::class)
object StreamCache {
    private const val DIR = "stream_cache"
    private val lock = Any()

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var maxBytes: Long = 0

    fun get(context: Context, maxBytes: Long): SimpleCache {
        synchronized(lock) {
            val cur = cache
            if (cur != null && this.maxBytes == maxBytes) return cur
            releaseLocked()
            val dir = File(context.cacheDir, DIR).also { it.mkdirs() }
            val db = StandaloneDatabaseProvider(context.applicationContext)
            val c = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes), db)
            cache = c
            this.maxBytes = maxBytes
            return c
        }
    }

    /** Занято кэшем, байт. */
    fun usedBytes(): Long = synchronized(lock) { cache?.cacheSpace ?: 0L }

    /** Свободно на разделе cacheDir, байт. */
    fun freeDiskBytes(context: Context): Long =
        try {
            val path = context.cacheDir?.absolutePath ?: return 0L
            val st = StatFs(path)
            st.availableBlocksLong * st.blockSizeLong
        } catch (_: Exception) {
            0L
        }

    /** Очистить все ключи кэша (при уходе в эфир). */
    fun clear() {
        synchronized(lock) {
            val c = cache ?: return
            try {
                for (key in c.keys) {
                    c.removeResource(key)
                }
            } catch (_: Exception) {
                // best effort
            }
        }
    }

    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        try {
            cache?.release()
        } catch (_: Exception) {
        }
        cache = null
        maxBytes = 0
    }
}
