package com.yyoo.ototag.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One song found in the iTunes / Apple Music catalog. */
data class ItunesTrack(
    val title: String,
    val artist: String,
    val album: String,
    val year: String,
    val genre: String,
    val track: String,
    val trackTotal: String,
    val disc: String,
    val discTotal: String,
    /** High-resolution artwork URL (600x600), empty when the result has none. */
    val artworkUrl: String
)

/**
 * iTunes Search API: Apple's catalog. Free, no key.
 * Strong commercial coverage (including Japanese releases) and
 * high-resolution artwork, which makes it a good complement to MusicBrainz.
 */
object ItunesClient {

    var base: String = System.getProperty("ototag.itunes.base") ?: "https://itunes.apple.com"
    var userAgent: String = "OtoTag/1.0 (https://github.com/yyoossk/ototag)"

    /** Storefront country code; the apps set it from the device locale. */
    var country: String = "JP"

    /** Test seam: when set, replaces the HTTP fetch (used with recorded real responses). */
    var fetcher: ((String) -> ByteArray)? = null

    private fun get(url: String): ByteArray {
        fetcher?.let { return it(url) }
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", userAgent)
        c.connectTimeout = 15000; c.readTimeout = 30000
        var code = c.responseCode
        var hops = 0
        var conn = c
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

    private fun num(o: Map<String, Any?>, key: String): String =
        (o[key] as? Number)?.toInt()?.toString() ?: ""

    /** Searches songs by free text (title, artist, or both). */
    fun search(query: String): List<ItunesTrack> {
        val key = "itunes|$country|" + query.trim().lowercase()
        SearchCache.get<List<ItunesTrack>>(key)?.let { return it }
        val r = searchRaw(query)
        if (r.isNotEmpty()) SearchCache.put(key, r)
        return r
    }

    private fun searchRaw(query: String): List<ItunesTrack> {
        if (query.isBlank()) return emptyList()
        val url = "$base/search?term=${enc(query)}&country=${enc(country)}" +
            "&entity=song&limit=25"
        val root = JsonParser(String(get(url), Charsets.UTF_8)).parse().asObj()
        val out = ArrayList<ItunesTrack>()
        for (r in root["results"].asArr()) {
            val o = r.asObj()
            if (o["kind"].asStr() != "song") continue
            val title = o["trackName"].asStr()
            if (title.isBlank()) continue
            val art100 = o["artworkUrl100"].asStr()
            out.add(ItunesTrack(
                title = title,
                artist = o["artistName"].asStr(),
                album = o["collectionName"].asStr(),
                year = o["releaseDate"].asStr().take(4),
                genre = o["primaryGenreName"].asStr(),
                track = num(o, "trackNumber"),
                trackTotal = num(o, "trackCount"),
                disc = num(o, "discNumber"),
                discTotal = num(o, "discCount"),
                artworkUrl = if (art100.isBlank()) "" else art100.replace("100x100bb.jpg", "600x600bb.jpg")
            ))
        }
        return out.distinctBy { listOf(it.title, it.artist, it.album) }
    }

    /** Builds the tag for a picked track, fetching its artwork. */
    fun buildTag(t: ItunesTrack): TagData {
        var cover: ByteArray? = null
        var failed = false
        if (t.artworkUrl.isNotBlank()) {
            try { cover = get(t.artworkUrl) } catch (e: Exception) { failed = true }
        }
        return TagData(
            title = t.title, artist = t.artist, album = t.album,
            year = t.year, genre = t.genre,
            track = t.track, trackTotal = t.trackTotal,
            disc = t.disc, discTotal = t.discTotal,
            cover = cover, coverFetchFailed = failed
        )
    }
}
