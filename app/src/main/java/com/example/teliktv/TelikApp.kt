package com.example.teliktv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient

/** Загрузчик логотипов (Coil) со своим User-Agent — часть хостов режет «голый» okhttp. */
class TelikApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("User-Agent", Config.UA).build())
                    }
                    .build()
            }
            .build()
}
