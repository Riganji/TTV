package com.example.teliktv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Откуда берутся обновления: релизы GitHub с приложенным APK.
 *
 * Репозиторий и токен зашиваются при сборке (см. app/build.gradle.kts). Токен нужен только
 * для приватного репозитория: без него GitHub отвечает 404, как будто репозитория нет.
 */
object UpdateConfig {
    val REPO: String = BuildConfig.UPDATE_REPO
    val LATEST: String get() = "https://api.github.com/repos/$REPO/releases/latest"
    val LIST: String get() = "https://api.github.com/repos/$REPO/releases?per_page=10"

    const val API_HOST = "api.github.com"

    /** Как часто проверять обновление автоматически (при старте). Вручную — всегда. */
    const val CHECK_TTL_MS = 6 * 60 * 60 * 1000L

    /** Токен чтения из BuildConfig; пустой — репозиторий публичный. */
    val TOKEN: String by lazy { reveal(BuildConfig.UPDATE_TOKEN) }

    val HAS_TOKEN: Boolean get() = TOKEN.isNotEmpty()

    /** Обратная операция к hideToken() из build.gradle.kts: hex -> XOR -> строка. */
    private fun reveal(hex: String): String {
        if (hex.length < 2) return ""
        return try {
            val key = "teliktv".toByteArray(Charsets.UTF_8)
            val bytes = ByteArray(hex.length / 2) { i ->
                val v = hex.substring(i * 2, i * 2 + 2).toInt(16)
                (v xor key[i % key.size].toInt()).toByte()
            }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}

/** Релиз с GitHub: версия из тега и ссылка на APK-файл. */
data class ReleaseInfo(
    val version: String,
    val tag: String,
    /** Прямая ссылка на файл — работает только для публичного репозитория. */
    val apkUrl: String,
    /** Адрес ассета в API — по нему скачивается APK из приватного репозитория (с токеном). */
    val apkApiUrl: String,
    val size: Long,
    val notes: String,
) {
    /** Откуда реально качать: с токеном — через API, без него — прямая ссылка. */
    val downloadUrl: String
        get() = if (UpdateConfig.HAS_TOKEN && apkApiUrl.isNotEmpty()) apkApiUrl else apkUrl
}

/**
 * Состояние обновления для меню настроек.
 * available != null — на GitHub лежит версия новее установленной.
 */
data class UpdateState(
    val current: String = BuildConfig.VERSION_NAME,
    val checking: Boolean = false,
    val available: ReleaseInfo? = null,
    val downloading: Boolean = false,
    /** 0..100, -1 — размер файла неизвестен. */
    val progress: Int = 0,
    val installing: Boolean = false,
    val checkedAt: Long = 0L,
    val error: String? = null,
)

/** Сравнение версий вида «0.2.4» и тегов вида «v0.3.0». Вынесено отдельно — покрыто тестами. */
object Versions {
    /** «v1.2.3-beta» -> [1, 2, 3]. Нечисловой хвост отбрасывается. */
    fun parse(v: String): List<Int> =
        v.trim()
            .removePrefix("v")
            .removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }
            .split('.')
            .mapNotNull { it.toIntOrNull() }

    /** Строго новее: 0.3.0 > 0.2.4, 0.2.4 == 0.2.4.0 (не новее). */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        if (a.isEmpty()) return false
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** Результат установки прилетает из InstallReceiver — ViewModel его слушает. */
object UpdateBus {
    /** null — установлено успешно, строка — текст ошибки. */
    private val _results = MutableSharedFlow<String?>(extraBufferCapacity = 4)
    val results = _results.asSharedFlow()

    fun report(error: String?) {
        _results.tryEmit(error)
    }
}

/**
 * Проверка, загрузка и установка обновления.
 *
 * APK берётся из последнего релиза UpdateConfig.REPO, кладётся в cacheDir и ставится
 * через PackageInstaller (без FileProvider). Установка поверх пройдёт только если и уже
 * установленный APK, и новый подписаны одним ключом — поэтому CI подписывает teliktv.jks.
 *
 * Приватный репозиторий: запросы к api.github.com идут с токеном из BuildConfig, а APK
 * качается по API-адресу ассета — прямая ссылка для приватного релиза отдаёт страницу входа.
 */
class Updater(private val context: Context) {

    private companion object {
        const val MAX_REDIRECTS = 6
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Редиректы обрабатываем вручную: ссылка на файл ассета уходит на другой хост
    // с собственной подписью в параметрах, и заголовок Authorization там ломает запрос.
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Запрос с ручным проходом по редиректам. Токен отправляется только на api.github.com;
     * при уходе на другой хост заголовок Authorization снимается.
     */
    private fun call(url: String, accept: String): Response {
        var current = url.toHttpUrl()
        var auth = UpdateConfig.HAS_TOKEN && current.host == UpdateConfig.API_HOST
        repeat(MAX_REDIRECTS) {
            val req = Request.Builder()
                .url(current)
                .header("User-Agent", "TelikTV/${BuildConfig.VERSION_NAME}")
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (auth) header("Authorization", "Bearer ${UpdateConfig.TOKEN}") }
                .build()
            val response = client.newCall(req).execute()
            val location = if (response.isRedirect) response.header("Location") else null
            if (location == null) return response
            val next = current.resolve(location)
            response.close()
            if (next == null) throw IOException("некорректный редирект от GitHub")
            if (next.host != current.host) auth = false
            current = next
        }
        throw IOException("слишком много редиректов")
    }

    // ------------------------------------------------------------------ проверка

    /** Последний опубликованный релиз с APK. null — релизов ещё нет. */
    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val single = get(UpdateConfig.LATEST)
        val release = if (single != null) {
            json.decodeFromString(GhRelease.serializer(), single)
        } else {
            // releases/latest отдаёт 404, пока нет ни одного не-draft релиза — смотрим список.
            // Если и список 404 — репозиторий недоступен (приватный и токен не подошёл).
            val list = get(UpdateConfig.LIST) ?: throw IOException(
                if (UpdateConfig.HAS_TOKEN) {
                    "нет доступа к ${UpdateConfig.REPO} — токен просрочен или без прав на этот репозиторий"
                } else {
                    "репозиторий ${UpdateConfig.REPO} недоступен — если он приватный, нужна сборка с токеном"
                },
            )
            json.decodeFromString(ListSerializer(GhRelease.serializer()), list)
                .firstOrNull { !it.draft && it.assets.any { a -> a.name.endsWith(".apk", true) } }
                ?: return@withContext null
        }
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: throw IOException("в релизе ${release.tagName} нет APK-файла")
        ReleaseInfo(
            version = release.tagName.removePrefix("v").removePrefix("V"),
            tag = release.tagName,
            apkUrl = apk.browser,
            apkApiUrl = apk.api,
            size = apk.size,
            notes = (release.body ?: "").trim(),
        )
    }

    /** GET с телом в строку; null — ответ 404 (нет релизов или нет доступа). */
    private fun get(url: String): String? {
        call(url, "application/vnd.github+json").use { r ->
            if (r.code == 404) return null
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val hint = when (r.code) {
                    401 -> " (токен недействителен)"
                    403 -> if (UpdateConfig.HAS_TOKEN) {
                        " (токену не хватает прав на содержимое репозитория)"
                    } else {
                        " (лимит запросов GitHub, попробуйте позже)"
                    }
                    else -> ""
                }
                throw IOException("GitHub ответил ${r.code}$hint")
            }
            return body
        }
    }

    // ------------------------------------------------------------------ загрузка

    /** Качает APK в cacheDir; onProgress вызывается с процентами (или -1, если размер неизвестен). */
    suspend fun download(release: ReleaseInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "update.apk")
            // Для приватного репозитория качаем ассет по API-адресу: прямая ссылка там
            // ведёт на страницу входа, а не на файл.
            call(release.downloadUrl, "application/octet-stream").use { r ->
                if (!r.isSuccessful) throw IOException("не скачалось: код ${r.code}")
                val body = r.body ?: throw IOException("пустой ответ")
                val total = body.contentLength().takeIf { it > 0 } ?: release.size
                file.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            val p = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else -1
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            if (file.length() < 1024) throw IOException("файл обновления пустой")
            file
        }

    // ------------------------------------------------------------------ установка

    /** На Android 8+ установка «из неизвестных источников» разрешается отдельно для каждого приложения. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Открыть системный экран разрешения установки. false — на этом устройстве такого экрана нет. */
    fun openInstallSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ставит скачанный APK. Система сама покажет подтверждение (через InstallReceiver),
     * результат придёт в UpdateBus.
     */
    suspend fun install(file: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("teliktv.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // MUTABLE обязателен: система дописывает в intent статус и окно подтверждения.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}

// ---------------------------------------------------------------------- GitHub API

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String = "",
    /** Адрес ассета в API: .../releases/assets/<id> — работает и для приватного репозитория. */
    @SerialName("url") val api: String = "",
    @SerialName("browser_download_url") val browser: String = "",
    val size: Long = 0,
)
