package com.yyoo.ototag.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One track of one album found on VGMdb (game / anime soundtracks). */
data class VgmdbTrack(
    val title: String,
    val artist: String,
    val album: String,
    val year: String,
    val track: String,
    val trackTotal: String,
    val disc: String,
    val discTotal: String,
    val coverUrl: String
)

/**
 * VGMdb via the vgmdb.info mirror API: free, no key. The strongest source
 * for game and anime soundtrack albums, where MusicBrainz is often thin.
 * Name maps arrive in several language keys ("ja", "Japanese", "en",
 * "English", "romaji", ...), so name picking is deliberately tolerant.
 */
object VgmdbClient {

    var base: String = System.getProperty("ototag.vgmdb.base") ?: "https://vgmdb.info"
    var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /** Test seam: when set, replaces the HTTP fetch (used with fixtures). */
    var fetcher: ((String) -> ByteArray)? = null

    private fun get(url: String): ByteArray {
        fetcher?.let { return it(url) }
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        var code = conn.responseCode
        var hops = 0
        while (code in 301..308 && hops < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            code = conn.responseCode; hops++
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return conn.inputStream.readBytes()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Best display name out of a VGMdb name map: Japanese first for album
     *  titles is wrong for tracks in some dumps, so the order is simply
     *  ja -> en -> romaji -> anything. */
    private fun bestName(names: Map<String, Any?>?): String {
        if (names == null) return ""
        for (k in listOf("ja", "Japanese", "ja_orig", "en", "English", "romaji", "Romaji")) {
            val v = names[k].asStr()
            if (v.isNotBlank()) return v
        }
        for ((_, v) in names) {
            val s = v.asStr()
            if (s.isNotBlank()) return s
        }
        return ""
    }

    private fun creditName(album: Map<String, Any?>): String {
        for (key in listOf("performers", "composers", "arrangers")) {
            val first = album[key].asArr().firstOrNull()?.asObj() ?: continue
            val n = bestName(first["names"]?.asObj())
            if (n.isNotBlank()) return n
        }
        return ""
    }

    /**
     * Searches albums by free text and flattens their tracklists into
     * candidates. Album detail fetches are capped so a search stays quick.
     */
    fun search(query: String): List<VgmdbTrack> {
        if (query.isBlank()) return emptyList()
        val root = JsonParser(
            String(get("$base/search?q=${enc(query)}"), Charsets.UTF_8)).parse().asObj()
        val albums = root["results"].asObj()["albums"].asArr()
        val out = ArrayList<VgmdbTrack>()
        for (a in albums.take(4)) {
            val link = a.asObj()["link"].asStr()
            if (link.isBlank()) continue
            try {
                val album = JsonParser(
                    String(get("$base/$link"), Charsets.UTF_8)).parse().asObj()
                val albumTitle = bestName(album["names"]?.asObj())
                    .ifBlank { bestName(a.asObj()["titles"]?.asObj()) }
                    .ifBlank { album["name"].asStr() }
                val year = album["release_date"].asStr().take(4)
                    .ifBlank { a.asObj()["release_date"].asStr().take(4) }
                val artist = creditName(album)
                var cover = album["picture_full"].asStr()
                if (cover.isBlank()) {
                    cover = album["covers"].asArr().firstOrNull()?.asObj()?.get("full").asStr()
                }
                val discs = album["discs"].asArr()
                discs.forEachIndexed { di, d ->
                    val tracks = d.asObj()["tracks"].asArr()
                    tracks.forEachIndexed { ti, tr ->
                        val tro = tr.asObj()
                        val name = bestName(tro["names"]?.asObj())
                        if (name.isBlank()) return@forEachIndexed
                        out.add(VgmdbTrack(
                            title = name,
                            artist = artist,
                            album = albumTitle,
                            year = year,
                            track = (tro["track_number"] as? Number)?.toInt()?.toString()
                                ?: (ti + 1).toString(),
                            trackTotal = tracks.size.toString(),
                            disc = (di + 1).toString(),
                            discTotal = discs.size.toString(),
                            coverUrl = cover
                        ))
                    }
                }
            } catch (e: Exception) { /* one bad album must not sink the search */ }
            if (out.size >= 40) break
        }
        return out
    }

    /** Builds the tag for a picked track, fetching the album cover. */
    fun buildTag(t: VgmdbTrack): TagData {
        var cover: ByteArray? = null
        var failed = false
        if (t.coverUrl.isNotBlank()) {
            try { cover = get(t.coverUrl) } catch (e: Exception) { failed = true }
        }
        return TagData(
            title = t.title, artist = t.artist, album = t.album,
            year = t.year, track = t.track, trackTotal = t.trackTotal,
            disc = t.disc, discTotal = t.discTotal,
            cover = cover, coverFetchFailed = failed
        )
    }
}
