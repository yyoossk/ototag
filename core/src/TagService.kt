package com.yyoo.ototag.core

import org.jaudiotagger.kt.AudioTagger
import org.jaudiotagger.kt.tag.Artwork
import org.jaudiotagger.kt.tag.FieldKey
import org.jaudiotagger.kt.tag.Tag
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.io.files.Path as KPath

object TagService {

    fun formatOf(file: File) = when (file.extension.lowercase()) {
        "mp3" -> org.jaudiotagger.kt.AudioFormat.MP3
        "flac" -> org.jaudiotagger.kt.AudioFormat.FLAC
        "m4a", "mp4" -> org.jaudiotagger.kt.AudioFormat.MP4
        "ogg", "oga", "opus" -> org.jaudiotagger.kt.AudioFormat.OGG
        "wav" -> org.jaudiotagger.kt.AudioFormat.WAV
        "aif", "aiff" -> org.jaudiotagger.kt.AudioFormat.AIFF
        "dsf" -> org.jaudiotagger.kt.AudioFormat.DSF
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
            has("RIFF") -> org.jaudiotagger.kt.AudioFormat.WAV
            has("FORM") -> org.jaudiotagger.kt.AudioFormat.AIFF
            has("DSD ") -> org.jaudiotagger.kt.AudioFormat.DSF
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
        org.jaudiotagger.kt.AudioFormat.WAV -> "wav"
        org.jaudiotagger.kt.AudioFormat.AIFF -> "aiff"
        org.jaudiotagger.kt.AudioFormat.DSF -> "dsf"
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
            if (arts.isNotEmpty()) {
                d.cover = arts[0].data
                val (_, w, h) = imageInfo(d.cover!!)
                d.coverW = w; d.coverH = h
            }
        }
        // MP3 workaround: the library writes the recording MBID into a UFID frame
        // with an empty owner, which its own reader then refuses to return.
        // Read the UFID frame ourselves, ignoring the owner.
        if (d.mbRecordingId.isBlank() && file.extension.lowercase() == "mp3") {
            d.mbRecordingId = readMp3Ufid(file) ?: ""
        }
        // Old files (ID3v1-era) often carry Shift_JIS text decoded as
        // Latin-1; repair it when the pattern is unmistakable.
        d.title = Mojibake.fix(d.title)
        d.artist = Mojibake.fix(d.artist)
        d.album = Mojibake.fix(d.album)
        d.albumArtist = Mojibake.fix(d.albumArtist)
        d.composer = Mojibake.fix(d.composer)
        d.genre = Mojibake.fix(d.genre)
        d.comment = Mojibake.fix(d.comment)
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
        // An MP3 carrying only an ID3v1 tag cannot hold most fields
        // (setting TRACK_TOTAL on it throws, and the whole save fails).
        // Write a fresh ID3v2.4 tag instead: every field TagData knows
        // is set from [d] below anyway, and ID3v1 holds nothing beyond
        // those fields, so nothing is lost in the upgrade.
        if (fmt == org.jaudiotagger.kt.AudioFormat.MP3 &&
            t is org.jaudiotagger.kt.tag.id3.Id3v1Tag) {
            t = org.jaudiotagger.kt.tag.id3.Id3v2Tag(
                org.jaudiotagger.kt.tag.id3.Id3v2Version.V24)
        }
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
    /**
     * A backup stores a snapshot of the tags (every text field, the
     * lyrics and the cover image), not the whole audio file: undo only
     * ever restores the tags, and the audio data is the bulk of a song
     * file, so snapshots are a small fraction of the old size. Backups
     * written by older builds hold the whole file; restore detects the
     * snapshot magic and falls back to copying those back as before.
     */
    private val MAGIC = byteArrayOf(
        'O'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 1)

    /** Snapshots the file's tags into backupDir under a stable per-path folder. */
    fun backup(file: File, backupDir: File): File {
        backupDir.mkdirs()
        val key = Integer.toHexString(file.absolutePath.hashCode())
        val dir = File(backupDir, key); dir.mkdirs()
        // Two backups inside the same millisecond must not collapse
        // into one generation: bump the stamp until the name is free.
        var stamp = System.currentTimeMillis()
        var dst = File(dir, stamp.toString() + "_" + file.name)
        while (dst.exists()) {
            stamp++
            dst = File(dir, stamp.toString() + "_" + file.name)
        }
        // Snapshot the tags; if the tags cannot be read at all, fall
        // back to a whole-file copy so the file stays protected.
        val tag = try { TagService.read(file) } catch (e: Exception) { null }
        if (tag != null) {
            // The cover is the bulky part of a snapshot. When the newest
            // existing snapshot already holds the very same cover, the
            // new one stores a reference to it instead of a second copy.
            var coverRef: String? = null
            val cover = tag.cover
            if (cover != null) {
                val prev = (dir.listFiles() ?: emptyArray())
                    .filter { it.isFile && isSnapshot(it) }.maxByOrNull { it.name }
                if (prev != null) {
                    val prevCover = try { readSnapshotFull(prev)?.cover } catch (e: Exception) { null }
                    if (prevCover != null && prevCover.contentEquals(cover)) coverRef = prev.name
                }
            }
            writeSnapshot(dst, tag, coverRef)
        }
        else file.copyTo(dst, overwrite = false)
        return dst
    }

    private fun writeSnapshot(dst: File, tag: TagData, coverRef: String? = null) {
        DataOutputStream(FileOutputStream(dst).buffered()).use { out ->
            out.write(MAGIC)
            fun field(s: String) {
                val b = s.toByteArray(Charsets.UTF_8)
                out.writeInt(b.size); out.write(b)
            }
            field(tag.title); field(tag.artist); field(tag.album); field(tag.albumArtist)
            field(tag.composer); field(tag.genre); field(tag.year); field(tag.track)
            field(tag.trackTotal); field(tag.disc); field(tag.discTotal); field(tag.comment)
            field(tag.isrc); field(tag.lyrics); field(tag.mbRecordingId); field(tag.mbReleaseId)
            val cover = tag.cover
            if (coverRef != null) { out.writeInt(-2); field(coverRef) }
            else if (cover == null) out.writeInt(-1)
            else { out.writeInt(cover.size); out.write(cover) }
        }
    }

    private fun isSnapshot(f: File): Boolean = try {
        FileInputStream(f).use {
            val b = ByteArray(5)
            it.read(b) == 5 && b.contentEquals(MAGIC)
        }
    } catch (e: Exception) { false }

    /** Parses one snapshot; the second value is the cover-reference file name, if any. */
    private fun parseSnapshot(f: File): Pair<TagData, String?>? = try {
        DataInputStream(FileInputStream(f).buffered()).use { inp ->
            val magic = ByteArray(5); inp.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return null
            fun field(): String {
                val n = inp.readInt()
                val b = ByteArray(n); inp.readFully(b)
                return String(b, Charsets.UTF_8)
            }
            val t = TagData(
                title = field(), artist = field(), album = field(), albumArtist = field(),
                composer = field(), genre = field(), year = field(), track = field(),
                trackTotal = field(), disc = field(), discTotal = field(), comment = field(),
                isrc = field(), lyrics = field(), mbRecordingId = field(), mbReleaseId = field()
            )
            var ref: String? = null
            val cn = inp.readInt()
            if (cn >= 0) {
                val b = ByteArray(cn); inp.readFully(b)
                t.cover = b
                val (_, w, h) = TagService.imageInfo(b)
                t.coverW = w; t.coverH = h
            } else if (cn == -2) {
                ref = field()
            }
            t to ref
        }
    } catch (e: Exception) { null }

    private fun readSnapshot(f: File): TagData? = parseSnapshot(f)?.first

    /** Parses one snapshot and resolves a cover reference against its siblings. */
    private fun readSnapshotFull(f: File): TagData? {
        val (tag, ref) = parseSnapshot(f) ?: return null
        if (ref == null) return tag
        val seen = HashSet<String>()
        var name: String? = ref
        var hops = 0
        while (name != null && hops < 32 && seen.add(name)) {
            val sib = File(f.parentFile, name)
            if (!sib.isFile) return tag
            val (sibTag, sibRef) = parseSnapshot(sib) ?: return tag
            if (sibTag.cover != null) {
                tag.cover = sibTag.cover; tag.coverW = sibTag.coverW; tag.coverH = sibTag.coverH
                return tag
            }
            name = sibRef; hops++
        }
        return tag
    }

    /** Applies one backup file to [file]: snapshot → write its tags; legacy → copy back. */
    private fun applyBackup(b: File, file: File): Boolean {
        if (isSnapshot(b)) {
            val snap = readSnapshotFull(b) ?: return false
            TagService.write(file, snap)
            return true
        }
        b.copyTo(file, overwrite = true)
        return true
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
        return applyBackup(b, file)
    }

    /**
     * The distinct backup generations (millis timestamps from the file
     * names) for one original file, newest first.
     */
    fun generations(file: File, backupDir: File): List<Long> {
        val key = Integer.toHexString(file.absolutePath.hashCode())
        val dir = File(backupDir, key)
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray()).filter { it.isFile }
            .mapNotNull { it.name.substringBefore('_').toLongOrNull() }
            .distinct().sortedDescending()
    }

    /** Restores the newest backup created at or before [atOrBefore]. */
    fun restoreAt(file: File, backupDir: File, atOrBefore: Long): Boolean {
        val key = Integer.toHexString(file.absolutePath.hashCode())
        val dir = File(backupDir, key)
        if (!dir.isDirectory) return false
        val b = (dir.listFiles() ?: emptyArray()).filter { it.isFile }
            .filter {
                (it.name.substringBefore('_').toLongOrNull() ?: Long.MAX_VALUE) <= atOrBefore
            }
            .maxByOrNull { it.name } ?: return false
        return applyBackup(b, file)
    }

    /**
     * Keeps the backup store from growing forever: per original file only
     * the newest [keepPerFile] backups survive (Undo always uses the
     * newest, so nothing the user can still reach is lost). Empty folders
     * are removed. Returns how many files were deleted.
     */
    /** Total bytes held by all backups. */
    fun totalSize(backupDir: File): Long {
        if (!backupDir.isDirectory) return 0
        var total = 0L
        backupDir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /** Deletes every backup; returns how many files were removed. */
    fun deleteAll(backupDir: File): Int {
        if (!backupDir.isDirectory) return 0
        var n = 0
        for (child in backupDir.listFiles() ?: emptyArray()) {
            child.walkTopDown().forEach { if (it.isFile) n++ }
            child.deleteRecursively()
        }
        return n
    }

    fun prune(backupDir: File, keepPerFile: Int = 1): Int {
        if (!backupDir.isDirectory) return 0
        var deleted = 0
        for (dir in backupDir.listFiles() ?: emptyArray()) {
            if (!dir.isDirectory) continue
            val files = (dir.listFiles() ?: emptyArray())
                .filter { it.isFile }.sortedByDescending { it.name }
            val keep = HashSet<String>()
            for (f in files.take(keepPerFile)) {
                keep.add(f.name)
                // A kept snapshot may borrow its cover from an older one;
                // that file (and its own chain) must survive too.
                var cur: File? = f
                var hops = 0
                while (cur != null && hops < 32) {
                    val ref = try { parseSnapshot(cur)?.second } catch (e: Exception) { null }
                        ?: break
                    if (!keep.add(ref)) break
                    cur = File(dir, ref); hops++
                }
            }
            for (f in files) {
                if (f.name !in keep && f.delete()) deleted++
            }
            if ((dir.listFiles() ?: emptyArray()).isEmpty()) dir.delete()
        }
        return deleted
    }
}
