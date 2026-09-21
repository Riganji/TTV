import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Настройки обновления, зашиваемые в BuildConfig.
 *
 * UPDATE_REPO  — откуда приложение берёт релизы (owner/repo).
 * UPDATE_TOKEN — токен чтения; нужен, только если этот репозиторий приватный.
 *
 * Берутся из local.properties (updateRepo= / updateToken=) или из переменных окружения
 * UPDATE_REPO / UPDATE_TOKEN (секреты GitHub Actions). В git токен не попадает.
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun setting(key: String, env: String, default: String): String =
    listOfNotNull(localProps.getProperty(key), System.getenv(env))
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: default

/**
 * Прячет токен от «strings app.apk»: XOR с постоянным ключом и hex.
 * Это замедляет любопытных, но не защищает — кто угодно с APK может достать токен,
 * поэтому у токена должны быть права только на чтение одного репозитория.
 */
fun hideToken(token: String): String {
    if (token.isEmpty()) return ""
    val key = "teliktv".toByteArray(Charsets.UTF_8)
    return token.toByteArray(Charsets.UTF_8)
        .mapIndexed { i, b -> (b.toInt() xor key[i % key.size].toInt()) and 0xFF }
        .joinToString("") { "%02x".format(it) }
}

android {
    namespace = "com.example.teliktv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.teliktv"
        minSdk = 21            // Android TV начинается с API 21; java.time и java.util.Base64 (API 26) — через desugaring
        targetSdk = 35
        // versionName сравнивается с тегом релиза на GitHub (см. Updater.kt),
        // поэтому тег релиза должен быть «v<versionName>», например v0.3.0.
        versionCode = 6
        versionName = "0.3.1"

        buildConfigField("String", "UPDATE_REPO", "\"${setting("updateRepo", "UPDATE_REPO", "Riganji/TTV")}\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"${hideToken(setting("updateToken", "UPDATE_TOKEN", ""))}\"")
    }

    /**
     * Постоянный ключ подписи. Без него каждая сборка (особенно в CI, где debug.keystore
     * создаётся заново) подписана другим ключом, и установка поверх падает.
     * Ключ берётся из keystore.properties или из переменных окружения (секреты GitHub).
     */
    signingConfigs {
        create("app") {
            val props = Properties().apply {
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
        isCoreLibraryDesugaringEnabled = true   // java.time / Base64 / Map.putIfAbsent на API 21–25
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true   // BuildConfig.VERSION_NAME нужен проверке обновлений
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

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
