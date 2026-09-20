plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.teliktv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.teliktv"
        minSdk = 26            // java.util.Base64 (парсер) — с API 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    /**
     * Постоянный ключ подписи. Без него каждая сборка (особенно в CI, где debug.keystore
     * создаётся заново) подписана другим ключом, и установка поверх падает.
     * Ключ берётся из keystore.properties или из переменных окружения (секреты GitHub).
     */
    signingConfigs {
        create("app") {
            val props = java.util.Properties().apply {
                val f = rootProject.file("keystore.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            fun value(key: String, env: String): String? =
                props.getProperty(key) ?: System.getenv(env)

            val path = value("storeFile", "KEYSTORE_FILE")
            if (path != null && file(path).exists()) {
                storeFile = file(path)
                storePassword = value("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = value("keyAlias", "KEY_ALIAS")
                keyPassword = value("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        val appSigning = signingConfigs.getByName("app")
        debug {
            if (appSigning.storeFile != null) signingConfig = appSigning
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (appSigning.storeFile != null) appSigning else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val media3 = "1.5.1"

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
