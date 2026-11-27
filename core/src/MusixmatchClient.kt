package com.yyoo.ototag.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Musixmatch: the largest licensed lyrics catalog. The API needs a free
 * personal key (developer.musixmatch.com), which the user pastes into the
 * editor; it is stored on the device only. Used as the lyrics fallback
 * when LRCLIB has nothing. Note the free plan returns the lyrics with a
 * disclaimer block appended, which is stripped here, and may return only
 * part of the text — that is the plan's limit, not a parse error.
 */
object MusixmatchClient {

    var base: String = System.getProperty("ototag.musixmatch.base")
        ?: "https://api.musixmatch.com/ws/1.1"
    var userAgent: String = "OtoTag/1.0 (https://github.com/yyoossk/ototag)"

    /** The user's own API key; empty disables this source. */
    var apiKey: String = ""

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

    /** Lyrics text for a track, or null (no key, no match, restricted). */
    fun fetchLyrics(title: String, artist: String): String? {
        if (apiKey.isBlank() || title.isBlank()) return null
        val url = "$base/matcher.lyrics.get?track_name=${enc(title)}" +
            "&artist_name=${enc(artist)}&apikey=${enc(apiKey)}"
        val root = JsonParser(String(get(url), Charsets.UTF_8)).parse().asObj()
        val message = root["message"]?.asObj() ?: return null
        val code = (message["header"]?.asObj()?.get("status_code") as? Number)
            ?.toInt() ?: 0
        if (code != 200) return null
        val lyrics = message["body"]?.asObj()?.get("lyrics")?.asObj() ?: return null
        if ((lyrics["lyrics_restricted"] as? Number)?.toInt() == 1) return null
        var body = lyrics["lyrics_body"].asStr()
        // The free plan appends a disclaimer block; it is not lyrics.
        val cut = body.indexOf("*******")
        if (cut >= 0) body = body.substring(0, cut)
        return body.trim().ifBlank { null }
    }
}
