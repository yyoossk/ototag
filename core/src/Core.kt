package com.yyoo.ototag.core

import java.io.File

data class TagData(
    var title: String = "",
    var artist: String = "",
    var album: String = "",
    var albumArtist: String = "",
    var composer: String = "",
    var genre: String = "",
    var year: String = "",
    var track: String = "",
    var trackTotal: String = "",
    var disc: String = "",
    var discTotal: String = "",
    var comment: String = "",
    var isrc: String = "",
    var lyrics: String = "",
    var mbRecordingId: String = "",
    var mbReleaseId: String = "",
    var cover: ByteArray? = null,
    var durationSec: Long = 0,
    /** Set when a release was expected to have cover art but the download failed. Not a tag field. */
    var coverFetchFailed: Boolean = false,
    /**
     * Set by sources that only know part of the tag (Audius has no album,
     * NDL only knows the album). Applying merges over the current tag so
     * fields the source does not know are kept, not cleared. Not a tag field.
     */
    var partial: Boolean = false
) {
    fun isBasicallyEmpty(): Boolean =
        title.isBlank() && artist.isBlank() && album.isBlank()

    /** This tag's values, with every blank field filled in from [base]. */
    fun mergedOver(base: TagData): TagData {
        fun pick(a: String, b: String) = if (a.isNotBlank()) a else b
        return TagData(
            title = pick(title, base.title),
            artist = pick(artist, base.artist),
            album = pick(album, base.album),
            albumArtist = pick(albumArtist, base.albumArtist),
            composer = pick(composer, base.composer),
            genre = pick(genre, base.genre),
            year = pick(year, base.year),
            track = pick(track, base.track),
            trackTotal = pick(trackTotal, base.trackTotal),
            disc = pick(disc, base.disc),
            discTotal = pick(discTotal, base.discTotal),
            comment = pick(comment, base.comment),
            isrc = pick(isrc, base.isrc),
            lyrics = pick(lyrics, base.lyrics),
            mbRecordingId = pick(mbRecordingId, base.mbRecordingId),
            mbReleaseId = pick(mbReleaseId, base.mbReleaseId),
            cover = cover ?: base.cover,
            durationSec = if (durationSec != 0L) durationSec else base.durationSec,
            coverFetchFailed = coverFetchFailed
        )
    }
}

val AUDIO_EXTS = setOf("mp3", "flac", "m4a", "mp4", "ogg", "oga", "opus",
    "wav", "aif", "aiff", "dsf")

fun File.isAudio(): Boolean = isFile && extension.lowercase() in AUDIO_EXTS

fun collectAudio(root: File, out: MutableList<File>) {
    if (root.isAudio()) { out.add(root); return }
    if (root.isDirectory) root.listFiles()?.forEach { collectAudio(it, out) }
}

/** Minimal JSON parser (objects, arrays, strings, numbers, booleans, null). */
class JsonParser(private val s: String) {
    private var i = 0
    fun parse(): Any? { val v = value(); return v }
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun value(): Any? {
        ws(); if (i >= s.length) return null
        return when (s[i]) {
            '{' -> obj(); '[' -> arr(); '"' -> str()
            't' -> { i += 4; true }; 'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> num()
        }
    }
    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(); i++
        while (true) { ws(); if (s[i] == '}') { i++; break }
            val k = str(); ws(); i++; m[k] = value(); ws()
            if (s[i] == ',') { i++; continue }; if (s[i] == '}') { i++; break } }
        return m
    }
    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>(); i++
        while (true) { ws(); if (s[i] == ']') { i++; break }
            l.add(value()); ws()
            if (s[i] == ',') { i++; continue }; if (s[i] == ']') { i++; break } }
        return l
    }
    private fun str(): String {
        val sb = StringBuilder(); i++
        while (i < s.length) { val c = s[i++]
            when (c) {
                '"' -> break
                '\\' -> { if (i < s.length) { val e = s[i++]
                    when (e) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e) } } }
                else -> sb.append(c) } }
        return sb.toString()
    }
    private fun num(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDoubleOrNull() ?: 0.0
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
fun Any?.asArr(): List<Any?> = this as? List<Any?> ?: emptyList()
fun Any?.asStr(): String = this as? String ?: ""
fun Any?.asInt(): Int = (this as? Double)?.toInt() ?: 0
