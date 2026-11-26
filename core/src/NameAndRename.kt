package com.yyoo.ototag.core

import java.io.File

data class ParsedName(
    var trackNo: String = "",
    var artist: String = "",
    var title: String = "",
    var albumHint: String = "",
    var artistHint: String = ""
)

object NameParser {
    private val noise = listOf(
        Regex("""\[(?i:320kbps|256kbps|128kbps|flac|mp3|hq|hd|mv|pv)\]"""),
        Regex("""(?i)www\.[^\s]+"""),
        Regex("""(?i)\bdownload\b""")
    )

    fun parse(file: File): ParsedName {
        var base = file.nameWithoutExtension
        val p = ParsedName()
        p.albumHint = file.parentFile?.name ?: ""
        p.artistHint = file.parentFile?.parentFile?.name ?: ""
        for (n in noise) base = base.replace(n, " ")
        base = base.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        // leading track number: "01. ", "01 - ", "01 "
        val m = Regex("""^(\d{1,3})[\s\.\-]+(.*)$""").find(base)
        if (m != null) { p.trackNo = m.groupValues[1]; base = m.groupValues[2].trim() }
        // feat. stays in the title; brackets with extra info are split off but kept searchable via title
        val bracket = Regex("""^(.*?)\s*[\(\[]([^\)\]]+)[\)\]](.*)$""").find(base)
        if (bracket != null && !bracket.groupValues[2].startsWith("feat", true) &&
            !bracket.groupValues[2].startsWith("ft", true)) {
            base = (bracket.groupValues[1] + " " + bracket.groupValues[3]).trim()
        }
        val sep = " - "
        if (base.contains(sep)) {
            val a = base.substringBefore(sep).trim()
            val b = base.substringAfter(sep).trim()
            // default: artist - title. The caller also tries the swap.
            p.artist = a; p.title = b
        } else {
            p.title = base
        }
        if (p.artist.isBlank() && p.artistHint.isNotBlank() && p.artistHint != p.albumHint) {
            // folder layout "Artist/Album/file" - only a hint, do not overwrite a parsed artist
        }
        return p
    }

    /** Query variants: parsed, swapped artist/title, title-only + folder hints. */
    fun queries(p: ParsedName): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        out.add(Triple(p.title, p.artist, ""))
        if (p.artist.isNotBlank() && p.title.isNotBlank())
            out.add(Triple(p.artist, p.title, "")) // swapped interpretation: (title, artist)
        if (p.albumHint.isNotBlank())
            out.add(Triple(p.title, p.artist, p.albumHint))
        return out.distinct()
    }
}

object Renamer {
    private val bad = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    fun sanitize(name: String): String {
        var s = name.replace(bad, " ")
        s = s.replace(Regex("""\s+"""), " ").trim().trimEnd('.', ' ')
        if (s.length > 120) s = s.substring(0, 120).trim()
        return s
    }

    fun applyPattern(pattern: String, d: TagData, fallbackTitle: String): String {
        var s = pattern
        s = s.replace("%track%", d.track.padStart(2, '0').ifBlank { "00" })
        s = s.replace("%disc%", d.disc.ifBlank { "1" })
        s = s.replace("%title%", d.title.ifBlank { fallbackTitle })
        s = s.replace("%artist%", d.artist)
        s = s.replace("%album%", d.album)
        s = s.replace("%year%", d.year)
        return sanitize(s)
    }

    /** "01. Title" style base name built from applied tags; "" when there is no title. */
    fun nameFromTag(d: TagData): String {
        if (d.title.isBlank()) return ""
        val n = d.track.substringBefore('/').trim().toIntOrNull()
        val base = if (n != null) "%02d. %s".format(n, d.title) else d.title
        return sanitize(base)
    }

    /** Returns the new File, or null on collision/failure. Extension never changes. */
    fun rename(file: File, newBase: String): File? {
        val clean = sanitize(newBase)
        if (clean.isBlank()) return null
        val target = File(file.parentFile, clean + "." + file.extension)
        if (target.exists() && target.absolutePath != file.absolutePath) return null
        if (target.absolutePath == file.absolutePath) return file
        return if (file.renameTo(target)) target else null
    }
}
