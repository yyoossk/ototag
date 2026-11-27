package com.yyoo.ototag.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One song found on Deezer. */
data class DeezerTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val isrc: String,
    /** 1000x1000 cover URL, empty when the result has none. */
    val coverUrl: String
)

/**
 * Deezer public API: free, no key. Wide catalog and large cover art.
 * The API sits behind a bot filter that rejects non-browser user agents,
 * so requests carry a browser UA.
 */
object DeezerClient {

    var base: String = System.getProperty("ototag.deezer.base") ?: "https://api.deezer.com"
    var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /** Test seam: when set, replaces the HTTP fetch (used with recorded real responses). */
    var fetcher: ((String) -> ByteArray)? = null

    private fun get(url: String): ByteArray {
        fetcher?.let { return it(url) }
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Searches songs by free text (title, artist, or both). */
    fun search(query: String): List<DeezerTrack> {
        val key = "deezer|" + query.trim().lowercase()
        SearchCache.get<List<DeezerTrack>>(key)?.let { return it }
        val r = searchRaw(query)
        if (r.isNotEmpty()) SearchCache.put(key, r)
        return r
    }

    private fun searchRaw(query: String): List<DeezerTrack> {
        if (query.isBlank()) return emptyList()
        val url = "$base/search?q=${enc(query)}&limit=25"
        val root = JsonParser(String(get(url), Charsets.UTF_8)).parse().asObj()
        val out = ArrayList<DeezerTrack>()
        for (r in root["data"].asArr()) {
            val o = r.asObj()
            val title = o["title"].asStr()
            if (title.isBlank()) continue
            val album = o["album"].asObj()
            out.add(DeezerTrack(
                id = (o["id"] as? Number)?.toLong() ?: 0,
                title = title,
                artist = o["artist"].asObj()["name"].asStr(),
                album = album["title"].asStr(),
                albumId = (album["id"] as? Number)?.toLong() ?: 0,
                isrc = o["isrc"].asStr(),
                coverUrl = album["cover_xl"].asStr().ifBlank { album["cover_big"].asStr() }
            ))
        }
        return out.distinctBy { listOf(it.title, it.artist, it.album) }
    }

    /** Builds the tag for a picked track: album details (year, genre,
     *  track position) and the cover are fetched at pick time. */
    fun buildTag(t: DeezerTrack): TagData {
        var year = ""; var genre = ""; var track = ""; var trackTotal = ""
        if (t.albumId > 0) {
            try {
                val root = JsonParser(
                    String(get("$base/album/${t.albumId}"), Charsets.UTF_8)).parse().asObj()
                year = root["release_date"].asStr().take(4)
                genre = root["genres"].asObj()["data"].asArr()
                    .firstOrNull()?.asObj()?.get("name").asStr()
                val tracks = root["tracks"].asObj()["data"].asArr()
                trackTotal = tracks.size.toString()
                tracks.forEachIndexed { i, tr ->
                    if ((tr.asObj()["id"] as? Number)?.toLong() == t.id) track = (i + 1).toString()
                }
            } catch (e: Exception) { /* search data alone still makes a tag */ }
        }
        var cover: ByteArray? = null
        var failed = false
        if (t.coverUrl.isNotBlank()) {
            try { cover = get(t.coverUrl) } catch (e: Exception) { failed = true }
        }
        return TagData(
            title = t.title, artist = t.artist, album = t.album,
            year = year, genre = genre, isrc = t.isrc,
            track = track, trackTotal = trackTotal,
            cover = cover, coverFetchFailed = failed
        )
    }
}
