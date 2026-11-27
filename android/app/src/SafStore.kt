package com.yyoo.ototag

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.yyoo.ototag.core.AUDIO_EXTS
import com.yyoo.ototag.core.TagData
import java.io.File

class Item(
    var docUri: Uri,
    var name: String,
    var local: File? = null,
    var tag: TagData? = null,
    var checked: Boolean = false,
    var error: String? = null
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    /** Folder document (tree picks only) — lets us create sibling files like .lrc. */
    var parentUri: Uri? = null
    var thumb: android.graphics.Bitmap? = null
    var thumbFor: Int = 0
    var thumbLoading: Boolean = false

    /** The cached thumbnail without decoding anything (null when not ready). */
    fun peekThumbnail(): android.graphics.Bitmap? {
        val cover = tag?.cover ?: return null
        return if (thumb != null && thumbFor == cover.contentHashCode()) thumb else null
    }

    /** Decoded list thumbnail for the current cover bytes (cached by content hash). */
    fun thumbnail(): android.graphics.Bitmap? {
        val cover = tag?.cover ?: run { thumb = null; thumbFor = 0; return null }
        val h = cover.contentHashCode()
        if (thumb != null && thumbFor == h) return thumb
        thumbFor = h
        thumb = try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(cover, 0, cover.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 144 || bounds.outHeight / sample > 144) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeByteArray(cover, 0, cover.size, opts)
        } catch (e: Exception) { null }
        return thumb
    }
}

object Items {
    val list = mutableListOf<Item>()
}

object SafStore {

    fun backupDir(ctx: Context): File = File(ctx.filesDir, "backups")

    private fun localFileFor(ctx: Context, uri: Uri, name: String): File {
        val dir = File(ctx.cacheDir, "items"); dir.mkdirs()
        val key = Integer.toHexString(uri.toString().hashCode())
        return File(dir, key + "." + name.substringAfterLast('.', "bin"))
    }

    fun ensureLocal(ctx: Context, item: Item): File {
        item.local?.let { if (it.exists()) return it }
        val f = localFileFor(ctx, item.docUri, item.name)
        ctx.contentResolver.openInputStream(item.docUri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("cannot open ${item.name}")
        item.local = f
        return f
    }

    fun writeBack(ctx: Context, item: Item) {
        val f = item.local ?: return
        ctx.contentResolver.openOutputStream(item.docUri, "wt")?.use { out ->
            f.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("cannot write ${item.name}")
    }

    /** Re-reads the SAF document fresh and checks the tags (and cover) really landed. */
    fun verify(ctx: Context, item: Item, expectCover: Boolean): Boolean {
        return try {
            val tmp = File(ctx.cacheDir, "verify.bin")
            ctx.contentResolver.openInputStream(item.docUri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return false
            // TagService keys off the extension; give the temp file the real one
            val named = File(ctx.cacheDir, "verify." + item.ext)
            tmp.copyTo(named, overwrite = true)
            val d = com.yyoo.ototag.core.TagService.read(named)
            !expectCover || d.cover != null
        } catch (e: Exception) { false }
    }

    /** Decodes a picked image, caps it at 1200px and re-encodes as JPEG (design rule). */
    fun normalizeCover(ctx: Context, uri: Uri): ByteArray? {
        val bytes = ctx.contentResolver.openInputStream(uri)?.readBytes() ?: return null
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val maxDim = maxOf(bmp.width, bmp.height)
        val scaled = if (maxDim > 1200) {
            val ratio = 1200f / maxDim
            android.graphics.Bitmap.createScaledBitmap(
                bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
        } else bmp
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /**
     * Downscales cover bytes so the long edge is at most [cap] px and
     * re-encodes as JPEG. A cap of 0 (or a cover already small enough)
     * returns the bytes unchanged — the default stays "no limit".
     */
    fun capCover(bytes: ByteArray?, cap: Int): ByteArray? {
        if (bytes == null || cap <= 0) return bytes
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return bytes
        val maxDim = maxOf(bmp.width, bmp.height)
        if (maxDim <= cap) return bytes
        val ratio = cap.toFloat() / maxDim
        val scaled = android.graphics.Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1), true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /**
     * Saves synced lyrics as a sibling "<name>.lrc" document (tree picks
     * only — a single-file pick gives no writable parent). Returns true
     * when the file was written.
     */
    fun saveLrc(ctx: Context, item: Item, synced: String): Boolean {
        val parent = item.parentUri ?: return false
        if (synced.isBlank()) return false
        val lrcName = item.name.substringBeforeLast('.') + ".lrc"
        return try {
            val uri = DocumentsContract.createDocument(
                ctx.contentResolver, parent, "text/plain", lrcName) ?: return false
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(synced.toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: Exception) { false }
    }

    fun rename(ctx: Context, item: Item, newName: String): Boolean {
        val newUri = try {
            DocumentsContract.renameDocument(ctx.contentResolver, item.docUri, newName)
        } catch (e: Exception) { null }
        return if (newUri != null) {
            // Mutate in place: replacing the Item would orphan every other
            // reference (selection snapshots, the editor) and lose state
            // changes like unchecking.
            item.docUri = newUri
            item.name = newName
            true
        } else false
    }

    /** Recursively lists audio documents under a tree uri (SAF). */
    fun enumerateTree(ctx: Context, treeUri: Uri): List<Item> {
        val out = mutableListOf<Item>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(ctx, treeUri, rootId, out, 0)
        return out.sortedBy { it.name.lowercase() }
    }

    private fun walk(ctx: Context, treeUri: Uri, parentId: String, out: MutableList<Item>, depth: Int) {
        if (depth > 8 || out.size > 2000) return
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        ctx.contentResolver.query(childUri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(ctx, treeUri, id, out, depth + 1)
                } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXTS) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val item = Item(docUri, name)
                    item.parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
                    out.add(item)
                }
            }
        }
    }
}
