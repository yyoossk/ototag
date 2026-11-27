package com.yyoo.ototag.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One track found on Audius. */
data class AudiusTrack(
    val title: String,
    val artist: String,
    val genre: String,
    val year: String,
    /** Artwork URL at 480x480 (embedded when applied); empty when none. */
    val coverUrl: String,
    /** Artwork URL at 150x150 for the candidate thumbnail. */
    val coverThumbUrl: String
)

/**
 * Audius: the open music streaming catalog. Its discovery API is free and
 * needs no key. The catalog leans indie / electronic and uploads carry no
 * album information, so candidates are partial: applying one keeps the
 * file's album and other fields the source does not know.
 */
object AudiusClient {

    var base: String = System.getProperty("ototag.audius.base")
        ?: "https://discoveryprovider.audius.co"
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

    fun search(query: String): List<AudiusTrack> {
        if (query.isBlank()) return emptyList()
        val root = JsonParser(
            String(get("$base/v1/tracks/search?query=${enc(query)}&app_name=OtoTag"),
                Charsets.UTF_8)).parse().asObj()
        val out = ArrayList<AudiusTrack>()
        for (item in root["data"].asArr()) {
            val o = item.asObj()
            val title = o["title"].asStr()
            if (title.isBlank()) continue
            val art = o["artwork"]?.asObj()
            out.add(AudiusTrack(
                title = title,
                artist = o["user"]?.asObj()?.get("name").asStr(),
                genre = o["genre"].asStr(),
                year = o["release_date"].asStr().take(4),
                coverUrl = art?.get("480x480").asStr(),
                coverThumbUrl = art?.get("150x150").asStr()
                    .ifBlank { art?.get("480x480").asStr() }
            ))
            if (out.size >= 25) break
        }
        return out
    }

    /** Builds the tag for a picked track, fetching its cover art. */
    fun buildTag(t: AudiusTrack): TagData {
        var cover: ByteArray? = null
        var failed = false
        if (t.coverUrl.isNotBlank()) {
            try { cover = get(t.coverUrl) } catch (e: Exception) { failed = true }
        }
        return TagData(
            title = t.title, artist = t.artist, genre = t.genre, year = t.year,
            cover = cover, coverFetchFailed = failed, partial = true
        )
    }
}
