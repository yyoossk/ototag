package com.yyoo.ototag.core

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * AcoustID lookup (https://acoustid.org). The user brings their own
 * free API key (same pattern as Musixmatch). Given a Chromaprint
 * fingerprint + duration, returns MusicBrainz recording ids, best
 * match first. Fingerprint computation itself is platform-side
 * (Android: bundled libchromaprint; Windows: bundled fpcalc.exe).
 */
object AcoustIdClient {
    private const val apiBase = "https://api.acoustid.org/v2"

    /** Test seam. */
    var fetcher: ((String) -> ByteArray)? = null

    private fun get(url: String): ByteArray {
        fetcher?.let { return it(url) }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "OtoTag (https://github.com/yyoossk/ototag)")
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.use { s ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf); if (n < 0) break
                bos.write(buf, 0, n)
            }
            bos.toByteArray()
        } ?: ByteArray(0)
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return bytes
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Recording MBIDs for a fingerprint, best score first, deduped,
     * at most 8. Throws on an API error (e.g. an invalid key).
     */
    fun lookupRecordingIds(fingerprint: String, durationSec: Int, apiKey: String): List<String> {
        require(apiKey.isNotBlank()) { "no AcoustID API key" }
        val cacheKey = "acoustid:$fingerprint:$durationSec"
        SearchCache.get<List<String>>(cacheKey)?.let { return it }
        val url = "$apiBase/lookup?client=${enc(apiKey)}&duration=$durationSec" +
            "&fingerprint=${enc(fingerprint)}&meta=recordingids"
        val root = JsonParser(String(get(url), Charsets.UTF_8)).parse().asObj()
        if (root["status"].asStr() != "ok") {
            val msg = root["error"].asObj()["message"].asStr()
            throw RuntimeException("AcoustID: $msg")
        }
        val out = ArrayList<String>()
        for (res in root["results"].asArr()) {
            for (rec in res.asObj()["recordings"].asArr()) {
                val id = rec.asObj()["id"].asStr()
                if (id.isNotBlank() && id !in out) out.add(id)
                if (out.size >= 8) break
            }
            if (out.size >= 8) break
        }
        if (out.isNotEmpty()) SearchCache.put(cacheKey, out)
        return out
    }
}
