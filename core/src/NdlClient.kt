package com.yyoo.ototag.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One sound-recording record from the National Diet Library catalog. */
data class NdlRecord(
    /** The CD's title: this becomes the album tag. */
    val album: String,
    val artist: String,
    val year: String,
    val publisher: String
)

/**
 * National Diet Library Search (国立国会図書館サーチ): the legal-deposit
 * catalog of everything published in Japan. Free, no key. Records are
 * album-level (a CD's title, artist and year), so candidates are partial:
 * applying one fills album / artist / year and keeps the track's own
 * title and other fields. The API rate-limits bursts, so this client
 * makes a single search call and treats any failure as "no results".
 */
object NdlClient {

    var base: String = System.getProperty("ototag.ndl.base")
        ?: "https://ndlsearch.ndl.go.jp/api/opensearch"
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

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("<!\\[CDATA\\[(.*?)\\]\\]>", RegexOption.DOT_MATCHES_ALL), "$1")
        .trim()

    /** Text of the first <tag>…</tag> in [xml], XML-unescaped. */
    private fun tagText(xml: String, tag: String): String {
        val m = Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml) ?: return ""
        return unescape(m.groupValues[1])
    }

    /** Artist cleanup: "LiSA" stays; credit strings keep their first name. */
    private fun cleanArtist(raw: String): String {
        var a = raw.substringBefore(',').trim()
        for (suffix in listOf(" 著", " 作詞", " 作曲", " 編曲", " 演奏")) {
            if (a.endsWith(suffix)) a = a.dropLast(suffix.length).trim()
        }
        return a
    }

    fun search(query: String): List<NdlRecord> {
        if (query.isBlank()) return emptyList()
        val xml = String(get("$base?q=${enc(query)}&cnt=20"), Charsets.UTF_8)
        val out = ArrayList<NdlRecord>()
        for (m in Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val item = m.groupValues[1]
            // Only sound recordings (録音資料) describe music CDs.
            if (!item.contains("録音資料")) continue
            val album = tagText(item, "title")
            if (album.isBlank()) continue
            val artist = cleanArtist(
                tagText(item, "author").ifBlank { tagText(item, "dc:creator") })
            val year = Regex("\\d{4}").find(tagText(item, "dc:date"))?.value ?: ""
            out.add(NdlRecord(
                album = album,
                artist = artist,
                year = year,
                publisher = tagText(item, "dc:publisher")
            ))
            if (out.size >= 15) break
        }
        return out
    }

    /** Album-level tag; partial so applying keeps the track's own fields. */
    fun buildTag(r: NdlRecord): TagData = TagData(
        album = r.album, artist = r.artist, year = r.year, partial = true
    )
}
