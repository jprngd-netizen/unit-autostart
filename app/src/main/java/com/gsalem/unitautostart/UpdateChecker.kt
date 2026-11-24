package com.gsalem.unitautostart

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Auto-atualizacao pelo GitHub Releases: o radio consulta a ultima release do
 * repositorio publico do proprio app, baixa o APK e chama o instalador.
 *
 * Nao depende de LAN, IP ou ADB — e o que sobrevive a rotacao do hotspot.
 * O Android sempre mostra o dialogo de instalacao (app comum nao instala em
 * silencio sem device owner/root).
 */
object UpdateChecker {

    private const val TAG = "UnitAutostart"
    /** Repositorio publico so deste app: nada pessoal dentro. */
    private const val REPO = "jprngd-netizen/unit-autostart"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val EVERY_MS = 6 * 60 * 60 * 1000L // 6 h

    /** Chamado na thread de trabalho, depois da sequencia do despertar. */
    fun checkAndInstall(context: Context) {
        if (!dueNow(context)) return
        try {
            val release = fetchLatest() ?: return
            if (release.versionCode <= currentVersion(context)) {
                Log.i(TAG, "app atualizado (v${release.versionCode})")
                return
            }
            Log.i(TAG, "versao nova disponivel: v${release.versionCode}")
            val apk = download(context, release.url) ?: return
            if (release.sha256 != null && !release.sha256.equals(sha256(apk), ignoreCase = true)) {
                Log.w(TAG, "SHA-256 nao confere; instalacao abortada")
                apk.delete()
                return
            }
            install(context, apk)
        } catch (t: Throwable) {
            Log.w(TAG, "checagem de atualizacao falhou: ${t.message}")
        }
    }

    private fun dueNow(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong(KEY_LAST_CHECK, 0L) < EVERY_MS) return false
        p.edit().putLong(KEY_LAST_CHECK, now).apply()
        return true
    }

    private fun currentVersion(context: Context): Int =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)

    private data class Release(val versionCode: Int, val url: String, val sha256: String?)

    private fun fetchLatest(): Release? {
        val json = JSONObject(httpGet(API) ?: return null)
        val tag = json.optString("tag_name") // ex.: "v3"
        val version = tag.trimStart('v').substringBefore('.').toIntOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            when {
                name.endsWith(".apk") -> apkUrl = a.optString("browser_download_url")
                name.endsWith(".sha256") -> sha = httpGet(a.optString("browser_download_url"))?.trim()
            }
        }
        val url = apkUrl ?: return null
        return Release(version, url, sha)
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "unit-autostart")
        }
        return try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP ${conn.responseCode} em $url")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(context: Context, url: String): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "unit-autostart")
            instanceFollowRedirects = true
        }
        return try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "download HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            Log.i(TAG, "baixado ${out.length()} bytes")
            out
        } catch (t: Throwable) {
            Log.w(TAG, "download falhou: ${t.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Log.i(TAG, "abrindo o instalador")
        context.startActivity(intent)
    }
}
