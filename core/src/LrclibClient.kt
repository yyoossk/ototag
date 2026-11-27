package com.yyoo.ototag.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One lyrics match from LRCLIB. */
data class LrcEntry(
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Double,
    /** Plain (unsynced) lyrics text. */
    val plainLyrics: String,
    /** Timestamped lyrics ([mm:ss.xx] lines), empty when unavailable. */
    val syncedLyrics: String
)

/**
 * LRCLIB (lrclib.net): the community lyrics database. Free, no key; its
 * terms only ask for a User-Agent that identifies the app. Used to fill
 * the lyrics tag, preferring an exact title/artist match whose duration
 * is closest to the file's.
 */
object LrclibClient {

    var base: String = System.getProperty("ototag.lrclib.base") ?: "https://lrclib.net"
    var userAgent: String = "OtoTag (https://github.com/yyoossk/ototag)"

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

    fun search(title: String, artist: String, album: String): List<LrcEntry> {
        if (title.isBlank() && artist.isBlank()) return emptyList()
        val params = ArrayList<String>()
        if (title.isNotBlank()) params.add("track_name=${enc(title)}")
        if (artist.isNotBlank()) params.add("artist_name=${enc(artist)}")
        if (album.isNotBlank()) params.add("album_name=${enc(album)}")
        val root = JsonParser(
            String(get("$base/api/search?${params.joinToString("&")}"), Charsets.UTF_8)
        ).parse()
        val out = ArrayList<LrcEntry>()
        for (item in root.asArr()) {
            val o = item.asObj()
            if (o["instrumental"] as? Boolean == true) continue
            val plain = o["plainLyrics"].asStr()
            if (plain.isBlank()) continue
            out.add(LrcEntry(
                trackName = o["trackName"].asStr(),
                artistName = o["artistName"].asStr(),
                albumName = o["albumName"].asStr(),
                durationSec = (o["duration"] as? Number)?.toDouble() ?: 0.0,
                plainLyrics = plain,
                syncedLyrics = o["syncedLyrics"].asStr()
            ))
        }
        return out
    }

    /**
     * Best lyrics for a track, or null. Prefers an exact (case-insensitive)
     * title+artist match; among matches, the duration closest to
     * [durationSec] wins when a duration is known.
     */
    fun fetchBest(title: String, artist: String, album: String, durationSec: Long): LrcEntry? {
        val all = search(title, artist, album)
        if (all.isEmpty()) return null
        fun norm(s: String) = s.trim().lowercase()
        val exact = all.filter {
            norm(it.trackName) == norm(title) &&
                (artist.isBlank() || norm(it.artistName) == norm(artist))
        }
        val pool = if (exact.isNotEmpty()) exact else all
        return if (durationSec > 0) {
            pool.minByOrNull { kotlin.math.abs(it.durationSec - durationSec) }
        } else pool.first()
    }
}
