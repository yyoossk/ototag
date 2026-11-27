package com.yyoo.ototag.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The newest published OtoTag release on GitHub. */
data class UpdateInfo(
    val tag: String,
    val androidApkUrl: String,
    val windowsZipUrl: String,
    /** The Windows app jar on its own, for a much smaller update download. */
    val windowsJarUrl: String = ""
)

/** One published release, for the in-app update history page. */
data class ReleaseNote(
    val tag: String,
    /** Publication date, yyyy-MM-dd. */
    val publishedAt: String,
    /** The release notes text as published. */
    val body: String
)

/**
 * Self-update check against the GitHub releases of yyoossk/ototag.
 * Version stamps use the format YYYYMMDD-HHMM, so a plain string
 * comparison is also the chronological comparison.
 */
object UpdateChecker {

    var apiUrl: String = "https://api.github.com/repos/yyoossk/ototag/releases/latest"
    var userAgent: String = "OtoTag/1.0 (https://github.com/yyoossk/ototag)"

    /** Test seam: when set, replaces the HTTP fetch (used with recorded real responses). */
    var fetcher: ((String) -> ByteArray)? = null

    private fun get(url: String): ByteArray {
        fetcher?.let { return it(url) }
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        var code = conn.responseCode
        var hops = 0
        while (code in 301..308 && hops < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            code = conn.responseCode; hops++
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return conn.inputStream.readBytes()
    }

    /** Returns the latest release, or null when it cannot be read. */
    fun fetchLatest(): UpdateInfo? {
        return try {
            val root = JsonParser(String(get(apiUrl), Charsets.UTF_8)).parse().asObj()
            val tag = root["tag_name"].asStr()
            if (tag.isBlank()) return null
            var apk = ""; var zip = ""; var jar = ""
            for (a in root["assets"].asArr()) {
                val o = a.asObj()
                when (o["name"].asStr()) {
                    "OtoTag-Android.apk" -> apk = o["browser_download_url"].asStr()
                    "OtoTag-Windows.zip" -> zip = o["browser_download_url"].asStr()
                    "OtoTag.jar" -> jar = o["browser_download_url"].asStr()
                }
            }
            UpdateInfo(tag, apk, zip, jar)
        } catch (e: Exception) { null }
    }

    /** Recent published releases, newest first, for the history page. */
    fun fetchReleases(): List<ReleaseNote> {
        val url = apiUrl.removeSuffix("/latest") + "?per_page=30"
        val root = JsonParser(String(get(url), Charsets.UTF_8)).parse()
        val out = ArrayList<ReleaseNote>()
        for (item in root.asArr()) {
            val o = item.asObj()
            if (o["draft"] as? Boolean == true) continue
            val tag = o["tag_name"].asStr()
            if (tag.isBlank()) continue
            out.add(ReleaseNote(
                tag = tag,
                publishedAt = o["published_at"].asStr().take(10),
                body = o["body"].asStr()
            ))
        }
        return out
    }

    /** True when [latest] is a newer build stamp than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val fmt = Regex("""\d{8}-\d{4}""")
        if (!fmt.matches(latest) || !fmt.matches(current)) return false
        return latest > current
    }

    /** Downloads [url] to [dest] (used by the Windows self-update). */
    fun download(url: String, dest: File) {
        dest.writeBytes(get(url))
    }
}
