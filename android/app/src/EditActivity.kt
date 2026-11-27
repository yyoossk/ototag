package com.yyoo.ototag

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.*
import com.yyoo.ototag.core.*
import java.io.File
import kotlin.concurrent.thread

class EditActivity : Activity() {

    private var item: Item? = null
    private var coverBytes: ByteArray? = null
    private var coverRemoved = false
    /** True when the cover was replaced here (pick or search), so the size cap applies. */
    private var coverChanged = false
    private var player: MediaPlayer? = null
    private var playBtn: Button? = null
    private var lyricsInput: EditText? = null
    private var mxmKeyInput: EditText? = null
    private lateinit var coverView: ImageView
    private val inputs = HashMap<String, EditText>()
    private lateinit var filenameInput: EditText

    private val fieldKeys = listOf(
        "title", "artist", "album", "album_artist", "composer", "genre",
        "year", "track", "track_total", "disc", "disc_total", "comment", "isrc")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(if (Ui.dark) android.R.style.Theme_Material
            else android.R.style.Theme_Material_Light)
        val idx = intent.getIntExtra("index", -1)
        item = Items.list.getOrNull(idx)
        val it = item ?: run { finish(); return }
        val itemName = it.name
        title = it.name

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 24, 40, 24)
            setBackgroundColor(Ui.BG)
        }
        scroll.addView(root)

        thread {
            // The editor works on the FULL tag (cover included); the list
            // only keeps a light copy without the cover bytes.
            var full: TagData? = null
            try {
                SafStore.ensureLocal(this, it)
                full = TagService.read(it.local!!)
                it.setTagLight(full)
            } catch (e: Exception) { it.error = e.message }
            runOnUiThread { fillFields(full ?: TagData()) }
        }

        root.addView(TextView(this).apply { text = Strings.t("filename"); setTextColor(Ui.SUB) })
        filenameInput = EditText(this).apply { setText(it.name.substringBeforeLast('.')) }
        root.addView(filenameInput)
        root.addView(TextView(this).apply { text = "." + it.ext })
        root.addView(Button(this).apply {
            text = Strings.t("from_filename"); Ui.styleButton(this, Ui.ORANGE)
            setOnClickListener {
                val p = NameParser.parse(File(itemName))
                if (p.title.isNotBlank()) inputs["title"]?.setText(p.title)
                if (p.artist.isNotBlank()) inputs["artist"]?.setText(p.artist)
                if (p.trackNo.isNotBlank()) inputs["track"]?.setText(p.trackNo)
                Toast.makeText(this@EditActivity, Strings.t("done"), Toast.LENGTH_SHORT).show()
            }
        })

        for (k in fieldKeys) {
            root.addView(TextView(this).apply { text = Strings.t(k); setTextColor(Ui.SUB) })
            // The genre field offers the common genres as quick picks
            // (still freely editable).
            val et: EditText = if (k == "genre") {
                android.widget.AutoCompleteTextView(this).apply {
                    setAdapter(android.widget.ArrayAdapter(this@EditActivity,
                        android.R.layout.simple_dropdown_item_1line, TagData.GENRES))
                    threshold = 1
                    setOnClickListener { if (text.isEmpty()) showDropDown() }
                }
            } else EditText(this)
            inputs[k] = et; root.addView(et)
        }

        root.addView(TextView(this).apply { text = Strings.t("cover"); setTextColor(Ui.SUB) })
        coverView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(360, 360)
            // Tap: zoom the cover when there is one, otherwise pick an image.
            setOnClickListener {
                val bytes = coverBytes ?: item?.tag?.cover
                if (bytes != null) showCoverZoom(bytes) else pickImage()
            }
        }
        root.addView(coverView)
        val coverBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        coverBtns.addView(Button(this).apply {
            text = Strings.t("pick_image"); Ui.styleButton(this, Ui.MAGENTA)
            setOnClickListener { pickImage() }
        })
        coverBtns.addView(Button(this).apply {
            text = Strings.t("remove_cover"); Ui.styleButton(this, Ui.ORANGE)
            setOnClickListener { coverBytes = null; coverRemoved = true; coverView.setImageBitmap(null) }
        })
        root.addView(coverBtns)

        root.addView(TextView(this).apply { text = Strings.t("lyrics"); setTextColor(Ui.SUB) })
        lyricsInput = EditText(this).apply {
            minLines = 4
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(lyricsInput)
        root.addView(Button(this).apply {
            text = Strings.t("get_lyrics"); Ui.styleButton(this, Ui.TEAL)
            setOnClickListener { fetchLyrics() }
        })
        root.addView(TextView(this).apply { text = Strings.t("mxm_key"); setTextColor(Ui.SUB) })
        mxmKeyInput = EditText(this).apply {
            setText(getSharedPreferences("ototag", MODE_PRIVATE)
                .getString("mxm_key", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        root.addView(mxmKeyInput)

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playBtn = Button(this).apply {
            text = Strings.t("play"); Ui.styleButton(this, Ui.TEAL)
            setOnClickListener { togglePlay() }
        }
        btns.addView(playBtn)
        btns.addView(Button(this).apply {
            text = Strings.t("search"); Ui.styleButton(this, Ui.PINK)
            setOnClickListener {
                val cur = collectFields()
                val parsed = NameParser.parse(File(itemName))
                SearchFlow.start(this@EditActivity, cur, parsed, localFile = item?.local) { picked ->
                    fillFields(picked, fromSearch = true)
                    // clear centred confirmation of what was filled in from the candidate
                    val msg = buildString {
                        append(picked.title)
                        if (picked.artist.isNotBlank()) append("\n${picked.artist}")
                        if (picked.album.isNotBlank()) append("\n${picked.album}")
                    }
                    AlertDialog.Builder(this@EditActivity)
                        .setTitle(Strings.t("applied"))
                        .setMessage(msg)
                        .setPositiveButton(Strings.t("ok"), null)
                        .show()
                }
            }
        })
        btns.addView(Button(this).apply {
            text = Strings.t("save"); Ui.styleButton(this, Ui.VIOLET)
            setOnClickListener { save() }
        })
        root.addView(btns)

        setContentView(scroll)
    }

    private fun togglePlay() {
        if (player != null) { stopPlay(); return }
        val it = item ?: return
        thread {
            try {
                SafStore.ensureLocal(this, it)
                val mp = MediaPlayer()
                mp.setDataSource(it.local!!.absolutePath)
                mp.setOnCompletionListener { runOnUiThread { stopPlay() } }
                mp.prepare(); mp.start()
                player = mp
                runOnUiThread { playBtn?.text = Strings.t("stop") }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "${Strings.t("failed")}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopPlay() {
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        playBtn?.text = Strings.t("play")
    }

    override fun onDestroy() {
        stopPlay()
        super.onDestroy()
    }

    private fun fillFields(d: TagData, fromSearch: Boolean = false) {
        // Partial candidates (Audius / NDL) must not blank out fields they
        // do not know; full candidates replace everything as before.
        fun set(k: String, v: String) {
            if (!d.partial || v.isNotBlank()) inputs[k]?.setText(v)
        }
        set("title", d.title)
        set("artist", d.artist)
        set("album", d.album)
        set("album_artist", d.albumArtist)
        set("composer", d.composer)
        set("genre", d.genre)
        set("year", d.year)
        set("track", d.track)
        set("track_total", d.trackTotal)
        set("disc", d.disc)
        set("disc_total", d.discTotal)
        set("comment", d.comment)
        set("isrc", d.isrc)
        if (!d.partial || d.lyrics.isNotBlank()) lyricsInput?.setText(d.lyrics)
        if (d.cover != null) {
            coverBytes = d.cover; coverRemoved = false
            if (fromSearch) coverChanged = true
            coverView.setImageBitmap(BitmapFactory.decodeByteArray(d.cover, 0, d.cover!!.size))
        }
        // keep MBIDs across a search-pick so they get written on save
        if (!d.partial) pendingMbIds = d.mbRecordingId to d.mbReleaseId
    }

    /**
     * Fetches lyrics for the field values and fills the box: LRCLIB first,
     * then Lyrics.ovh, then Musixmatch when a (free) API key is entered.
     */
    private fun fetchLyrics() {
        val title = inputs["title"]?.text?.toString() ?: ""
        val artist = inputs["artist"]?.text?.toString() ?: ""
        val album = inputs["album"]?.text?.toString() ?: ""
        val dur = item?.tag?.durationSec ?: 0
        val key = mxmKeyInput?.text?.toString()?.trim() ?: ""
        getSharedPreferences("ototag", MODE_PRIVATE).edit()
            .putString("mxm_key", key).apply()
        thread {
            val entry = try {
                LrclibClient.fetchBest(title, artist, album, dur)
            } catch (e: Exception) { null }
            var text: String? = entry?.plainLyrics
            // LRCLIB's timestamped lyrics also land next to the file as .lrc
            var lrcSaved = false
            val it = item
            if (entry != null && entry.syncedLyrics.isNotBlank() && it != null) {
                lrcSaved = SafStore.saveLrc(this, it, entry.syncedLyrics)
            }
            if (text == null) {
                text = LyricsOvhClient.fetchLyrics(artist, title)
            }
            if (text == null && key.isNotBlank()) {
                text = try {
                    MusixmatchClient.apiKey = key
                    MusixmatchClient.fetchLyrics(title, artist)
                } catch (e: Exception) { null }
            }
            val found = text
            runOnUiThread {
                if (found != null) {
                    lyricsInput?.setText(found)
                    Toast.makeText(this,
                        Strings.t(if (lrcSaved) "lrc_saved" else "done"),
                        Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, Strings.t("no_lyrics"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var pendingMbIds: Pair<String, String> = "" to ""

    private fun collectFields(): TagData {
        fun v(k: String) = inputs[k]?.text?.toString() ?: ""
        return TagData(
            title = v("title"), artist = v("artist"), album = v("album"),
            albumArtist = v("album_artist"), composer = v("composer"), genre = v("genre"),
            year = v("year"), track = v("track"), trackTotal = v("track_total"),
            disc = v("disc"), discTotal = v("disc_total"), comment = v("comment"),
            isrc = v("isrc"), lyrics = lyricsInput?.text?.toString() ?: "",
            mbRecordingId = pendingMbIds.first, mbReleaseId = pendingMbIds.second,
            cover = if (coverRemoved) null else coverBytes)
    }

    /** Full-screen view of the current cover art; tap anywhere to close. */
    private fun showCoverZoom(bytes: ByteArray) {
        val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            catch (e: Exception) { null } ?: return
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Ui.BG)
        }
        val dlg = AlertDialog.Builder(this).setView(iv).create()
        iv.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
        }
        startActivityForResult(i, REQ_IMG)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMG && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                coverBytes = SafStore.normalizeCover(this, uri)
                coverRemoved = false; coverChanged = true
                coverBytes?.let { coverView.setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size)) }
            } catch (e: Exception) { }
        }
    }

    private fun save() {
        val it = item ?: return
        val collected = collectFields()
        // The size cap applies only to a cover replaced in this editor;
        // the file's own cover passes through untouched.
        val d = if (coverChanged && collected.cover != null)
            collected.copy(cover = SafStore.capCover(collected.cover,
                getSharedPreferences("ototag", MODE_PRIVATE).getInt("cover_cap", 0)))
        else collected
        val newBase = Renamer.sanitize(filenameInput.text.toString())
        Toast.makeText(this, Strings.t("working"), Toast.LENGTH_SHORT).show()
        thread {
            var err: String? = null
            try {
                SafStore.ensureLocal(this, it)
                Backup.backup(it.local!!, SafStore.backupDir(this))
                TagService.write(it.local!!, d)
                SafStore.writeBack(this, it)
                if (!SafStore.verify(this, it, d.cover != null)) {
                    throw IllegalStateException("verify failed after write-back")
                }
                it.setTagLight(TagService.read(it.local!!))
                if (newBase.isNotBlank() && newBase != it.name.substringBeforeLast('.')) {
                    val newName = newBase + "." + it.ext
                    if (!SafStore.rename(this, it, newName)) err = "rename failed: $newName"
                }
            } catch (e: Exception) {
                err = "${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread {
                if (err == null) {
                    it.checked = false // acted on: drop the check in the list
                    Toast.makeText(this, Strings.t("done"), Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Ui.failDialog(this, err!!)
                }
            }
        }
    }

    companion object { const val REQ_IMG = 10 }
}
