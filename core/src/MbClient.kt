package com.yyoo.ototag.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MbReleaseRef(
    val id: String, val title: String, val date: String, val country: String,
    val trackCount: Int, val trackPos: Int, val discPos: Int, val trackTotalOfDisc: Int
)

data class MbCandidate(
    val recordingId: String, val title: String, val artist: String,
    val score: Int, val lengthMs: Long,
    val releases: List<MbReleaseRef>
) {
    fun label(): String {
        val r = releases.firstOrNull()
        return if (r != null) "$title / $artist — ${r.title} (${r.date.take(4)}, ${r.country})"
        else "$title / $artist"
    }
}

object MbClient {
    var userAgent = "OtoTag/0.1 ( https://github.com/yyoossk/ototag )"
    // Base URLs are overridable (MusicBrainz mirrors / test relays) via system properties.
    var mbBase: String = System.getProperty("ototag.mbBase", "https://musicbrainz.org")
    var caaBase: String = System.getProperty("ototag.caaBase", "https://coverartarchive.org")
    private var lastAt = 0L

    @Synchronized
    private fun throttle() {
        val now = System.currentTimeMillis()
        val wait = lastAt + 1100 - now
        if (wait > 0) Thread.sleep(wait)
        lastAt = System.currentTimeMillis()
    }

    /** Test seam: when set, replaces the HTTP fetch (used with recorded real responses). */
    var fetcher: ((String) -> ByteArray)? = null

    private fun get(url: String, accept: String = "application/json"): ByteArray {
        fetcher?.let { return it(url) }
        return getFollowing(url, accept, 0)
    }

    /** HTTP GET that follows redirects manually (CAA 307s to archive.org). */
    private fun getFollowing(url: String, accept: String, redirects: Int, retries: Int = 0): ByteArray {
        throttle()
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept", accept)
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.instanceFollowRedirects = false
        val code = c.responseCode
        if (code in 301..308) {
            val loc = c.getHeaderField("Location")
            if (loc != null && redirects < 5) return getFollowing(loc, accept, redirects + 1)
            throw IllegalStateException("HTTP $code (redirect)")
        }
        if ((code == 503 || code == 429) && retries < 3) {
            // MusicBrainz answers 503 when its rate limiter trips; back off and retry.
            Thread.sleep(longArrayOf(2000, 5000, 10000)[retries])
            return getFollowing(url, accept, redirects, retries + 1)
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return c.inputStream.readBytes()
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun searchByTagsOrName(title: String, artist: String, album: String): List<MbCandidate> {
        val parts = ArrayList<String>()
        if (title.isNotBlank()) parts.add("recording:\"${esc(title)}\"")
        if (artist.isNotBlank()) parts.add("artist:\"${esc(artist)}\"")
        if (album.isNotBlank()) parts.add("release:\"${esc(album)}\"")
        require(parts.isNotEmpty()) { "empty query" }
        val q = parts.joinToString(" AND ")
        return searchRecordings("$mbBase/ws/2/recording/?query=${enc(q)}&fmt=json&limit=15")
    }

    /** Free-text recording search for a user-typed query. */
    fun searchFreeText(query: String): List<MbCandidate> {
        require(query.isNotBlank()) { "empty query" }
        return searchRecordings("$mbBase/ws/2/recording/?query=${enc(query)}&fmt=json&limit=15")
    }

    private fun searchRecordings(url: String): List<MbCandidate> {
        SearchCache.get<List<MbCandidate>>(url)?.let { return it }
        val root = JsonParser(String(get(url), Charsets.UTF_8)).parse().asObj()
        val out = ArrayList<MbCandidate>()
        for (r in root["recordings"].asArr()) out.add(parseCandidate(r.asObj()))
        if (out.isNotEmpty()) SearchCache.put(url, out)
        return out
    }

    /** One recording object (search result element or lookup root). */
    private fun parseCandidate(ro: Map<String, Any?>): MbCandidate {
        val artistCredit = ro["artist-credit"].asArr().joinToString("") { ac ->
            val aco = ac.asObj()
            (aco["name"].asStr()) + (aco["joinphrase"].asStr())
        }
        val releases = ArrayList<MbReleaseRef>()
        for (rel in ro["releases"].asArr()) {
            val relo = rel.asObj()
            var tPos = 0; var dPos = 0; var tTotal = 0
            for (m in relo["media"].asArr()) {
                val mo = m.asObj()
                for (tr in mo["track"].asArr()) {
                    val tro = tr.asObj()
                    if (tro["recording"].asObj()["id"].asStr() == ro["id"].asStr() ||
                        tro["title"].asStr() == ro["title"].asStr()) {
                        // search results carry "number" (string), lookups carry "position"
                        tPos = tro["position"].asInt().takeIf { it > 0 }
                            ?: tro["number"].asStr().toIntOrNull() ?: 0
                        dPos = mo["position"].asInt()
                        tTotal = mo["track-count"].asInt()
                    }
                }
            }
            releases.add(MbReleaseRef(
                relo["id"].asStr(), relo["title"].asStr(), relo["date"].asStr(),
                relo["country"].asStr(), relo["track-count"].asInt(), tPos, dPos, tTotal))
        }
        return MbCandidate(ro["id"].asStr(), ro["title"].asStr(), artistCredit,
            ro["score"].asInt(), (ro["length"] as? Double)?.toLong() ?: 0, releases)
    }

    /** Recording lookup by MBID (used by the AcoustID fingerprint flow). */
    fun lookupRecording(recordingId: String): MbCandidate? {
        val url = "$mbBase/ws/2/recording/$recordingId?inc=artist-credits+releases&fmt=json"
        SearchCache.get<MbCandidate>(url)?.let { return it }
        val root = JsonParser(String(get(url), Charsets.UTF_8)).parse().asObj()
        if (root["id"].asStr() != recordingId) return null
        val cand = parseCandidate(root)
        SearchCache.put(url, cand)
        return cand
    }

    /** Cover Art Archive thumbnail URL for a release (front-250). */
    fun coverUrl(releaseId: String): String = "$caaBase/release/$releaseId/front-250"

    /** Builds full tag data from a chosen candidate + release. */
    fun buildTag(cand: MbCandidate, rel: MbReleaseRef): TagData {
        val url = "$mbBase/ws/2/release/${rel.id}?inc=recordings+artist-credits+genres&fmt=json"
        val root = JsonParser(String(get(url), Charsets.UTF_8)).parse().asObj()
        val d = TagData()
        d.title = cand.title
        d.artist = cand.artist
        d.album = root["title"].asStr().ifBlank { rel.title }
        d.albumArtist = root["artist-credit"].asArr().joinToString("") { ac ->
            val aco = ac.asObj(); aco["name"].asStr() + aco["joinphrase"].asStr()
        }
        d.year = (root["date"].asStr()).take(4)
        d.mbRecordingId = cand.recordingId
        d.mbReleaseId = rel.id
        val genres = root["genres"].asArr().map { it.asObj() }
            .sortedByDescending { it["count"].asInt() }
        if (genres.isNotEmpty()) d.genre = genres[0]["name"].asStr()
        var discTotal = 0
        for (m in root["media"].asArr()) {
            val mo = m.asObj(); discTotal++
            // release lookup uses "tracks"; recording search results use "track"
            val tracks = if (mo.containsKey("tracks")) mo["tracks"] else mo["track"]
            for (tr in tracks.asArr()) {
                val tro = tr.asObj()
                if (tro["recording"].asObj()["id"].asStr() == cand.recordingId) {
                    d.track = tro["position"].asInt().toString()
                    d.trackTotal = mo["track-count"].asInt().toString()
                    d.disc = mo["position"].asInt().toString()
                }
            }
        }
        if (discTotal > 0) d.discTotal = discTotal.toString()
        // Cover: only a failure when the release actually has front art.
        val hasFront = root["cover-art-archive"].asObj()["front"] as? Boolean ?: true
        try {
            val b = get("$caaBase/release/${rel.id}/front-500", "image/*")
            if (b.size > 1000) d.cover = b else if (hasFront) d.coverFetchFailed = true
        } catch (e: Exception) {
            if (hasFront) d.coverFetchFailed = true
        }
        return d
    }

    fun fetchCover(releaseId: String): ByteArray? {
        return try {
            val b = get("$caaBase/release/$releaseId/front-500", "image/*")
            if (b.size > 1000) b else null
        } catch (e: Exception) { null }
    }

    /** Downloads an image file the user picked (desktop helper lives in UI; core keeps bytes check). */
    fun isImageFile(f: File): Boolean =
        f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png")
}
