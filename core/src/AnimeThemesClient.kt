package com.yyoo.ototag.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One anime theme song found on AnimeThemes (animethemes.moe). */
data class AtSong(
    val title: String,
    val artist: String,
    val anime: String,
    val year: String,
    /** "OP1", "ED2", ... empty when the song is not tied to a theme slot. */
    val themeLabel: String
) {
    /** Tags for a theme song: the anime title becomes the album so files group by series. */
    fun toTag(): TagData = TagData(
        title = title,
        artist = artist,
        album = anime,
        year = year,
        comment = themeLabel
    )
}

/**
 * AnimeThemes (animethemes.moe): the anime OP/ED database.
 * Free, no API key. Its REST JSON:API is marked deprecated upstream in
 * favour of GraphQL, but it is still the documented read API; if it is
 * ever removed this client just returns nothing and MusicBrainz carries on.
 * Titles there are mainly romaji.
 */
object AnimeThemesClient {

    var base: String = System.getProperty("ototag.at.base") ?: "https://api.animethemes.moe"
    var userAgent: String = "OtoTag/1.0 (https://github.com/yyoossk/ototag)"

    /** Test seam: when set, replaces the HTTP fetch (used with recorded real responses). */
    var fetcher: ((String) -> ByteArray)? = null

    private fun get(url: String): ByteArray {
        fetcher?.let { return it(url) }
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept", "application/json")
        c.connectTimeout = 15000; c.readTimeout = 30000
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return c.inputStream.readBytes()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Searches songs by free text (song title, artist or anime name).
     * One song used by several anime yields one entry per anime/theme slot.
     */
    fun searchSongs(query: String): List<AtSong> {
        if (query.isBlank()) return emptyList()
        val url = "$base/song?q=${enc(query)}" +
            "&include=artists,animethemes.anime&page[size]=15"
        val root = JsonParser(String(get(url), Charsets.UTF_8)).parse().asObj()
        val out = ArrayList<AtSong>()
        for (s in root["songs"].asArr()) {
            val song = s.asObj()
            val title = song["title"].asStr()
            if (title.isBlank()) continue
            val artist = song["artists"].asArr()
                .map { it.asObj()["name"].asStr() }
                .filter { it.isNotBlank() }
                .joinToString(", ")
            val themes = song["animethemes"].asArr()
            if (themes.isEmpty()) {
                out.add(AtSong(title, artist, "", "", ""))
                continue
            }
            for (t in themes) {
                val theme = t.asObj()
                val anime = theme["anime"].asObj()
                val type = theme["type"].asStr()
                val seq = (theme["sequence"] as? Number)?.toInt() ?: 0
                val label = when {
                    type.isBlank() -> ""
                    seq > 0 -> "$type$seq"
                    else -> type
                }
                val year = (anime["year"] as? Number)?.toInt()?.toString() ?: ""
                out.add(AtSong(title, artist, anime["name"].asStr(), year, label))
            }
        }
        return out.distinctBy { listOf(it.title, it.artist, it.anime, it.themeLabel) }
    }
}
