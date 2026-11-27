package com.yyoo.ototag.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics.ovh: a small community lyrics API. Free, no key. Exact
 * artist/title lookup only, so it slots into the lyrics chain after
 * LRCLIB: when it has the song, the text is complete.
 */
object LyricsOvhClient {

    var base: String = System.getProperty("ototag.lyricsovh.base")
        ?: "https://api.lyrics.ovh"
    var userAgent: String = "OtoTag/1.0 (https://github.com/yyoossk/ototag)"

    /** Test seam: when set, replaces the HTTP fetch (used with recorded real responses). */
    var fetcher: ((String) -> ByteArray)? = null

    private fun get(url: String): ByteArray {
        fetcher?.let { return it(url) }
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", userAgent)
        c.connectTimeout = 15000; c.readTimeout = 30000
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return c.inputStream.readBytes()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Lyrics text for an exact artist/title, or null when not found. */
    fun fetchLyrics(artist: String, title: String): String? {
        if (artist.isBlank() || title.isBlank()) return null
        return try {
            val root = JsonParser(
                String(get("$base/v1/${enc(artist)}/${enc(title)}"), Charsets.UTF_8)
            ).parse().asObj()
            root["lyrics"].asStr().trim().ifBlank { null }
        } catch (e: Exception) { null }
    }
}
