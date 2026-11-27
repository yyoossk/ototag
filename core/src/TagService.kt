package com.yyoo.ototag.core

import org.jaudiotagger.kt.AudioTagger
import org.jaudiotagger.kt.tag.Artwork
import org.jaudiotagger.kt.tag.FieldKey
import org.jaudiotagger.kt.tag.Tag
import java.io.File
import kotlinx.io.files.Path as KPath

object TagService {

    fun formatOf(file: File) = when (file.extension.lowercase()) {
        "mp3" -> org.jaudiotagger.kt.AudioFormat.MP3
        "flac" -> org.jaudiotagger.kt.AudioFormat.FLAC
        "m4a", "mp4" -> org.jaudiotagger.kt.AudioFormat.MP4
        "ogg", "oga", "opus" -> org.jaudiotagger.kt.AudioFormat.OGG
        else -> null
    }

    /** Detects the real container from magic bytes; extensions often lie
     *  (e.g. a YouTube download is an MP4 renamed to .mp3). */
    fun sniffFormat(file: File): org.jaudiotagger.kt.AudioFormat? {
        val head = ByteArray(12)
        val n = try { file.inputStream().use { it.read(head) } } catch (e: Exception) { 0 }
        if (n < 4) return null
        fun has(s: String, off: Int = 0): Boolean {
            val b = s.toByteArray(Charsets.US_ASCII)
            if (n < off + b.size) return false
            for (i in b.indices) if (head[off + i] != b[i]) return false
            return true
        }
        return when {
            has("ftyp", 4) -> org.jaudiotagger.kt.AudioFormat.MP4
            has("fLaC") -> org.jaudiotagger.kt.AudioFormat.FLAC
            has("OggS") -> org.jaudiotagger.kt.AudioFormat.OGG
            has("ID3") -> org.jaudiotagger.kt.AudioFormat.MP3
            (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 ->
                org.jaudiotagger.kt.AudioFormat.MP3
            else -> null
        }
    }

    private fun extFor(fmt: org.jaudiotagger.kt.AudioFormat) = when (fmt) {
        org.jaudiotagger.kt.AudioFormat.MP3 -> "mp3"
        org.jaudiotagger.kt.AudioFormat.FLAC -> "flac"
        org.jaudiotagger.kt.AudioFormat.MP4 -> "m4a"
        org.jaudiotagger.kt.AudioFormat.OGG -> "ogg"
        else -> "bin"
    }

    /** When the real container disagrees with the extension, the tag library
     *  (which dispatches on extension) cannot parse the file. Work on a
     *  correctly-named copy instead, and copy the result back for writes. */
    private fun <T> withRealFile(file: File, writeBack: Boolean, block: (File) -> T): T {
        val sniffed = sniffFormat(file)
        if (sniffed == null || sniffed == formatOf(file)) return block(file)
        val tmp = File(file.parentFile, file.nameWithoutExtension + ".ototag-real." + extFor(sniffed))
        file.copyTo(tmp, overwrite = true)
        try {
            val r = block(tmp)
            if (writeBack) tmp.copyTo(file, overwrite = true)
            return r
        } finally { tmp.delete() }
    }

    fun read(file: File): TagData = withRealFile(file, false) { readImpl(it) }

    private fun readImpl(file: File): TagData {
        val af = AudioTagger.read(KPath(file.absolutePath))
        val t = af.tag
        val d = TagData()
        if (t != null) {
            d.title = t.first(FieldKey.TITLE) ?: ""
            d.artist = t.first(FieldKey.ARTIST) ?: ""
            d.album = t.first(FieldKey.ALBUM) ?: ""
            d.albumArtist = t.first(FieldKey.ALBUM_ARTIST) ?: ""
            d.composer = t.first(FieldKey.COMPOSER) ?: ""
            d.genre = t.first(FieldKey.GENRE) ?: ""
            d.year = t.first(FieldKey.YEAR) ?: ""
            d.track = t.first(FieldKey.TRACK) ?: ""
            d.trackTotal = t.first(FieldKey.TRACK_TOTAL) ?: ""
            d.disc = t.first(FieldKey.DISC_NO) ?: ""
            d.discTotal = t.first(FieldKey.DISC_TOTAL) ?: ""
            d.comment = t.first(FieldKey.COMMENT) ?: ""
            d.lyrics = t.first(FieldKey.LYRICS) ?: ""
            d.isrc = t.first(FieldKey.ISRC) ?: ""
            d.mbRecordingId = t.first(FieldKey.MUSICBRAINZ_TRACK_ID) ?: ""
            d.mbReleaseId = t.first(FieldKey.MUSICBRAINZ_RELEASEID) ?: ""
            val arts = t.artworks
            if (arts.isNotEmpty()) d.cover = arts[0].data
        }
        // MP3 workaround: the library writes the recording MBID into a UFID frame
        // with an empty owner, which its own reader then refuses to return.
        // Read the UFID frame ourselves, ignoring the owner.
        if (d.mbRecordingId.isBlank() && file.extension.lowercase() == "mp3") {
            d.mbRecordingId = readMp3Ufid(file) ?: ""
        }
        try { d.durationSec = af.properties.duration.inWholeSeconds } catch (e: Exception) { }
        return d
    }

    private fun setOrRemove(t: Tag, key: FieldKey, v: String) {
        if (v.isBlank()) t.remove(key) else t.set(key, v.trim())
    }

    /** Writes tags to a copy-first workflow: caller backs up before calling. */
    fun write(file: File, d: TagData) = withRealFile(file, true) { writeImpl(it, d) }

    private fun writeImpl(file: File, d: TagData) {
        val af = AudioTagger.read(KPath(file.absolutePath))
        val fmt = af.format
        var t = af.tag
        if (t == null) t = AudioTagger.createTag(fmt)
        setOrRemove(t, FieldKey.TITLE, d.title)
        setOrRemove(t, FieldKey.ARTIST, d.artist)
        setOrRemove(t, FieldKey.ALBUM, d.album)
        setOrRemove(t, FieldKey.ALBUM_ARTIST, d.albumArtist)
        setOrRemove(t, FieldKey.COMPOSER, d.composer)
        setOrRemove(t, FieldKey.GENRE, d.genre)
        setOrRemove(t, FieldKey.YEAR, d.year)
        setOrRemove(t, FieldKey.TRACK, d.track)
        setOrRemove(t, FieldKey.TRACK_TOTAL, d.trackTotal)
        setOrRemove(t, FieldKey.DISC_NO, d.disc)
        setOrRemove(t, FieldKey.DISC_TOTAL, d.discTotal)
        setOrRemove(t, FieldKey.COMMENT, d.comment)
        setOrRemove(t, FieldKey.LYRICS, d.lyrics)
        setOrRemove(t, FieldKey.ISRC, d.isrc)
        setOrRemove(t, FieldKey.MUSICBRAINZ_TRACK_ID, d.mbRecordingId)
        setOrRemove(t, FieldKey.MUSICBRAINZ_RELEASEID, d.mbReleaseId)
        if (d.cover != null) {
            val (mime, w, h) = imageInfo(d.cover!!)
            t.setArtwork(Artwork(d.cover!!, mime, "", 3, w, h, 0, 0))
        } else {
            t.clearArtworks()
        }
        AudioTagger.write(KPath(file.absolutePath), t)
        // verify by re-reading
        val back = read(file)
        if (back.title != d.title.trim() || back.artist != d.artist.trim()) {
            throw IllegalStateException("verify failed: title/artist mismatch after write")
        }
    }

    /**
     * Detects the real image format and dimensions from the bytes.
     * The artwork MIME type must match the actual data: writing PNG bytes
     * labelled image/jpeg makes many players refuse to show the cover.
     */
    fun imageInfo(bytes: ByteArray): Triple<String, Int, Int> {
        fun be32(o: Int) = ((bytes[o].toInt() and 0xFF) shl 24) or ((bytes[o + 1].toInt() and 0xFF) shl 16) or
            ((bytes[o + 2].toInt() and 0xFF) shl 8) or (bytes[o + 3].toInt() and 0xFF)
        fun be16(o: Int) = ((bytes[o].toInt() and 0xFF) shl 8) or (bytes[o + 1].toInt() and 0xFF)
        if (bytes.size >= 24 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
            return Triple("image/png", be32(16), be32(20))
        }
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            var i = 2
            while (i + 9 < bytes.size) {
                if (bytes[i] != 0xFF.toByte()) { i++; continue }
                val marker = bytes[i + 1].toInt() and 0xFF
                if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                    return Triple("image/jpeg", be16(i + 7), be16(i + 5))
                }
                if (marker == 0xD9 || marker == 0xDA) break
                i += 2 + be16(i + 2)
            }
            return Triple("image/jpeg", 0, 0)
        }
        if (bytes.size >= 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()) {
            return Triple("image/webp", 0, 0)
        }
        return Triple("image/jpeg", 0, 0)
    }

    /** Parse an ID3v2 tag and return the identifier of the first UFID frame, any owner. */
    fun readMp3Ufid(file: File): String? {
        val bytes = file.readBytes()
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return null
        val ver = bytes[3].toInt()
        val tagSize = ((bytes[6].toInt() and 0x7F) shl 21) or ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F)
        var pos = 10
        val end = minOf(10 + tagSize, bytes.size)
        while (pos + 10 <= end) {
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            if (id[0].code == 0) break
            val size = if (ver >= 4) {
                ((bytes[pos + 4].toInt() and 0x7F) shl 21) or ((bytes[pos + 5].toInt() and 0x7F) shl 14) or
                    ((bytes[pos + 6].toInt() and 0x7F) shl 7) or (bytes[pos + 7].toInt() and 0x7F)
            } else {
                ((bytes[pos + 4].toInt() and 0xFF) shl 24) or ((bytes[pos + 5].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 8) or (bytes[pos + 7].toInt() and 0xFF)
            }
            if (size < 0) break
            if (id == "UFID") {
                val bodyStart = pos + 10
                var z = bodyStart
                while (z < bodyStart + size && bytes[z].toInt() != 0) z++
                if (z + 1 < bodyStart + size) {
                    return String(bytes, z + 1, bodyStart + size - (z + 1), Charsets.ISO_8859_1).trim()
                }
                return null
            }
            pos += 10 + size
        }
        return null
    }
}

object Backup {
    /** Copies the file into backupDir under a stable per-path folder; returns the backup file. */
    fun backup(file: File, backupDir: File): File {
        backupDir.mkdirs()
        val key = Integer.toHexString(file.absolutePath.hashCode())
        val dir = File(backupDir, key); dir.mkdirs()
        val dst = File(dir, System.currentTimeMillis().toString() + "_" + file.name)
        file.copyTo(dst, overwrite = true)
        return dst
    }

    /** Backups are keyed by absolute path; call this after a rename so Undo follows the file. */
    fun move(old: File, new: File, backupDir: File) {
        val oldDir = File(backupDir, Integer.toHexString(old.absolutePath.hashCode()))
        val newDir = File(backupDir, Integer.toHexString(new.absolutePath.hashCode()))
        if (oldDir.isDirectory && oldDir.absolutePath != newDir.absolutePath) {
            if (newDir.exists()) newDir.deleteRecursively()
            oldDir.renameTo(newDir)
        }
    }

    fun latestBackup(file: File, backupDir: File): File? {
        val key = Integer.toHexString(file.absolutePath.hashCode())
        val dir = File(backupDir, key)
        if (!dir.isDirectory) return null
        return dir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.name }
    }

    fun restore(file: File, backupDir: File): Boolean {
        val b = latestBackup(file, backupDir) ?: return false
        b.copyTo(file, overwrite = true)
        return true
    }
}
