package com.yyoo.ototag.core

/**
 * Human-readable "what will change" lines for the pre-apply preview:
 * one line per field whose value differs between the file's current
 * tag and the candidate about to be written.
 */
object TagDiff {
    fun lines(old: TagData, new: TagData): List<String> {
        val out = ArrayList<String>()
        fun f(labelKey: String, o: String, n: String) {
            if (o != n) out.add("${Strings.t(labelKey)}: ${o.ifBlank { "—" }} → ${n.ifBlank { "—" }}")
        }
        f("title", old.title, new.title)
        f("artist", old.artist, new.artist)
        f("album", old.album, new.album)
        f("album_artist", old.albumArtist, new.albumArtist)
        f("year", old.year, new.year)
        fun num(v: String, tot: String) =
            if (tot.isBlank()) v else "$v/$tot"
        if (old.track != new.track || old.trackTotal != new.trackTotal)
            out.add("${Strings.t("track")}: ${num(old.track, old.trackTotal)} → ${num(new.track, new.trackTotal)}")
        if (old.disc != new.disc || old.discTotal != new.discTotal)
            out.add("${Strings.t("disc")}: ${num(old.disc, old.discTotal)} → ${num(new.disc, new.discTotal)}")
        f("genre", old.genre, new.genre)
        f("composer", old.composer, new.composer)
        f("comment", old.comment, new.comment)
        f("isrc", old.isrc, new.isrc)
        val coverChanges = when {
            new.cover != null && old.cover == null -> true
            new.cover != null && old.cover != null -> !old.cover.contentEquals(new.cover)
            else -> false
        }
        if (coverChanges) out.add("${Strings.t("cover")}: ${Strings.t("changed")}")
        if (new.lyrics.isNotBlank() && new.lyrics != old.lyrics)
            out.add("${Strings.t("lyrics")}: ${Strings.t("changed")}")
        return out
    }
}
