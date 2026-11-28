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
    /** The tag as loaded from the file; the baseline for change marks and the save preview. */
    private var original: TagData = TagData()
    private var originalLoaded = false
    /** Whether the file had a cover when the editor opened (its bytes are NOT kept). */
    private var hasOrigCover = false
    /** Decoded preview of the current cover (display only, never written). */
    private var coverPreview: android.graphics.Bitmap? = null
    /** Set once a save succeeded, so leaving does not ask about discarding. */
    private var savedClean = false
    /** Per-field label rows' count labels, keyed like [inputs]. */
    private val counts = HashMap<String, TextView>()
    /** Default backgrounds of the inputs, restored when a field is back to its original value. */
    private val origBg = HashMap<EditText, android.graphics.drawable.Drawable?>()
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
            runOnUiThread {
                original = full ?: TagData()
                originalLoaded = true
                fillFields(original)
            }
        }

        val fnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        fnRow.addView(TextView(this).apply {
            text = Strings.t("filename"); setTextColor(Ui.SUB)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val fnCount = TextView(this).apply { textSize = 11f; setTextColor(Ui.SUB) }
        counts["filename"] = fnCount
        fnRow.addView(fnCount)
        root.addView(fnRow)
        filenameInput = EditText(this).apply { setText(it.name.substringBeforeLast('.')) }
        root.addView(filenameInput)
        watchField("filename", filenameInput)
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
            // Label row: the field name, its character count, and a
            // per-field clear button.
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            labelRow.addView(TextView(this).apply {
                text = Strings.t(k); setTextColor(Ui.SUB)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val count = TextView(this).apply { text = "0"; textSize = 11f; setTextColor(Ui.SUB) }
            counts[k] = count
            labelRow.addView(count)
            labelRow.addView(Button(this).apply {
                text = "✕"; textSize = 11f
                layoutParams = LinearLayout.LayoutParams(96, 72)
                setOnClickListener { inputs[k]?.setText("") }
            })
            root.addView(labelRow)
            // The genre field offers the common genres as quick picks
            // (still freely editable).
            val et: EditText = if (k == "genre") {
                android.widget.AutoCompleteTextView(this).apply {
                    setAdapter(android.widget.ArrayAdapter(this@EditActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        GenreList.visibleDefaults(hiddenGenres()) + customGenres()))
                    threshold = 1
                    setOnClickListener { if (text.isEmpty()) showDropDown() }
                }
            } else EditText(this)
            inputs[k] = et; root.addView(et)
            if (k == "genre") {
                // Pick from the separated lists (own genres with delete,
                // then the built-ins), or save the typed text as an own
                // genre. The field itself stays a plain text input.
                val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                btns.addView(Button(this).apply {
                    text = Strings.t("genre_pick"); Ui.styleButton(this, Ui.TEAL)
                    setOnClickListener {
                        GenrePicker.show(this@EditActivity, customGenres(), hiddenGenres(),
                            removedGenres(), { persistCustomGenres(it) },
                            { persistHiddenGenres(it) }, { persistRemovedGenres(it) }) { g -> et.setText(g) }
                    }
                })
                btns.addView(Button(this).apply {
                    text = Strings.t("genre_save"); Ui.styleButton(this, Ui.VIOLET)
                    setOnClickListener {
                        persistCustomGenres(GenreList.add(customGenres(),
                            et.text.toString()))
                        Toast.makeText(this@EditActivity, Strings.t("done"),
                            Toast.LENGTH_SHORT).show()
                    }
                })
                root.addView(btns)
            }
            watchField(k, et)
        }

        root.addView(TextView(this).apply { text = Strings.t("cover"); setTextColor(Ui.SUB) })
        coverView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(360, 360)
            // Tap: zoom the cover when there is one, otherwise pick an image.
            setOnClickListener {
                val bytes = coverBytes
                if (bytes != null) showCoverZoom(bytes)
                else if (hasOrigCover && !coverRemoved) zoomFileCover()
                else pickImage()
            }
        }
        root.addView(coverView)
        // Two rows of two equal buttons: four in one row squeezed
        // the last one (From clipboard) off the edge.
        val coverBtns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun coverRow(): LinearLayout {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            coverBtns.addView(row)
            return row
        }
        fun coverBtn(row: LinearLayout, label: String, color: Int, fn: () -> Unit) {
            row.addView(Button(this).apply {
                text = label; textSize = 13f; isSingleLine = true
                Ui.styleButton(this, color)
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 12; bottomMargin = 12
                }
                setOnClickListener { fn() }
            })
        }
        val coverRow1 = coverRow()
        coverBtn(coverRow1, Strings.t("pick_image"), Ui.MAGENTA) { pickImage() }
        coverBtn(coverRow1, Strings.t("remove_cover"), Ui.ORANGE) {
            coverBytes = null; coverRemoved = true; coverPreview = null
            hasOrigCover = false
            coverView.setImageBitmap(null)
        }
        val coverRow2 = coverRow()
        coverBtn(coverRow2, Strings.t("from_url"), Ui.TEAL) { promptCoverUrl() }
        coverBtn(coverRow2, Strings.t("from_clipboard"), Ui.VIOLET) {
            pasteCoverFromClipboard()
        }
        root.addView(coverBtns)

        val lyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        lyRow.addView(TextView(this).apply {
            text = Strings.t("lyrics"); setTextColor(Ui.SUB)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val lyCount = TextView(this).apply { text = "0"; textSize = 11f; setTextColor(Ui.SUB) }
        counts["lyrics"] = lyCount
        lyRow.addView(lyCount)
        lyRow.addView(Button(this).apply {
            text = "✕"; textSize = 11f
            layoutParams = LinearLayout.LayoutParams(96, 72)
            setOnClickListener { lyricsInput?.setText("") }
        })
        root.addView(lyRow)
        lyricsInput = EditText(this).apply {
            minLines = 4
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(lyricsInput)
        watchField("lyrics", lyricsInput!!)
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

    private fun customGenres(): List<String> = GenreList.parse(
        getSharedPreferences("ototag", MODE_PRIVATE).getString("custom_genres", ""))

    private fun persistCustomGenres(list: List<String>) {
        getSharedPreferences("ototag", MODE_PRIVATE).edit()
            .putString("custom_genres", GenreList.serialize(list)).apply()
    }

    private fun hiddenGenres(): List<String> = GenreList.parse(
        getSharedPreferences("ototag", MODE_PRIVATE).getString("hidden_genres", ""))

    private fun persistHiddenGenres(list: List<String>) {
        getSharedPreferences("ototag", MODE_PRIVATE).edit()
            .putString("hidden_genres", GenreList.serialize(list)).apply()
    }

    private fun removedGenres(): List<String> = GenreList.parse(
        getSharedPreferences("ototag", MODE_PRIVATE).getString("removed_genres", ""))

    private fun persistRemovedGenres(list: List<String>) {
        getSharedPreferences("ototag", MODE_PRIVATE).edit()
            .putString("removed_genres", GenreList.serialize(list)).apply()
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
            coverRemoved = false
            if (fromSearch) {
                // A picked cover is the new cover: keep its bytes to write.
                coverChanged = true
                coverBytes = d.cover
            } else {
                // The file's own cover: hold only a small decoded preview;
                // the bytes are re-read from the file when saving.
                hasOrigCover = true
                coverBytes = null
            }
            coverPreview = decodePreview(d.cover!!)
            coverView.setImageBitmap(coverPreview)
        }
        // keep MBIDs across a search-pick so they get written on save
        if (!d.partial) pendingMbIds = d.mbRecordingId to d.mbReleaseId
    }

    /** The original value of one field key, for change marks and dirty checks. */
    private fun origValue(k: String): String = when (k) {
        "title" -> original.title; "artist" -> original.artist
        "album" -> original.album; "album_artist" -> original.albumArtist
        "composer" -> original.composer; "genre" -> original.genre
        "year" -> original.year; "track" -> original.track
        "track_total" -> original.trackTotal; "disc" -> original.disc
        "disc_total" -> original.discTotal; "comment" -> original.comment
        "isrc" -> original.isrc; "lyrics" -> original.lyrics
        "filename" -> item?.name?.substringBeforeLast('.') ?: ""
        else -> ""
    }

    /**
     * Watches one input: fields whose value differs from the file's own
     * get a tinted background (and fall back when the value returns), and
     * the character count next to the label stays current.
     */
    private fun watchField(k: String, et: EditText) {
        origBg[et] = et.background
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (et.text.toString() != origValue(k))
                    et.setBackgroundColor(if (Ui.dark) 0xFF6B5510.toInt() else 0xFFFFF0B3.toInt())
                else et.background = origBg[et]
                counts[k]?.text = et.text.length.toString()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    /** True when anything on screen differs from what the file holds. */
    private fun isDirty(): Boolean {
        if (coverChanged || coverRemoved) return true
        if (filenameInput.text.toString() != origValue("filename")) return true
        for (k in fieldKeys) if ((inputs[k]?.text?.toString() ?: "") != origValue(k)) return true
        if ((lyricsInput?.text?.toString() ?: "") != original.lyrics) return true
        return pendingMbIds.first != original.mbRecordingId ||
            pendingMbIds.second != original.mbReleaseId
    }

    override fun onBackPressed() {
        if (!savedClean && originalLoaded && isDirty()) {
            AlertDialog.Builder(this)
                .setMessage(Strings.t("confirm_discard"))
                .setPositiveButton(Strings.t("discard")) { _, _ -> finish() }
                .setNegativeButton(Strings.t("keep_editing"), null)
                .show()
        } else super.onBackPressed()
    }

    /** Small decoded preview of a cover (display only). */
    private fun decodePreview(bytes: ByteArray): android.graphics.Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }

    /** Zooms the file's own cover, reading its bytes only now. */
    private fun zoomFileCover() {
        val it = item ?: return
        thread {
            val cover = try {
                SafStore.ensureLocal(this, it)
                TagService.read(it.local!!).cover
            } catch (e: Exception) { null }
            runOnUiThread { if (cover != null) showCoverZoom(cover) else pickImage() }
        }
    }

    /** Pastes a cover image from the clipboard, when one is there. */
    private fun pasteCoverFromClipboard() {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val uri = cm.primaryClip?.getItemAt(0)?.uri
        if (uri == null) {
            Toast.makeText(this, Strings.t("failed"), Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val raw = contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw IllegalStateException("clipboard image unreadable")
                val normalized = SafStore.normalizeCoverBytes(raw)
                runOnUiThread {
                    coverBytes = normalized; coverRemoved = false; coverChanged = true
                    coverPreview = decodePreview(normalized)
                    coverView.setImageBitmap(coverPreview)
                }
            } catch (e: Exception) {
                runOnUiThread { Ui.failDialog(this, "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    /**
     * Fetches lyrics from every source in parallel. When more than one
     * source has lyrics, the user compares the first lines and picks;
     * the picked text is tidied (blank lines, trailing spaces) on the way in.
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
            var entry: LrcEntry? = null
            var ovh: String? = null
            var mxm: String? = null
            val jobs = ArrayList<Thread>()
            jobs.add(thread {
                entry = try { LrclibClient.fetchBest(title, artist, album, dur) }
                    catch (e: Exception) { null }
            })
            jobs.add(thread {
                ovh = try { LyricsOvhClient.fetchLyrics(artist, title) }
                    catch (e: Exception) { null }
            })
            if (key.isNotBlank()) jobs.add(thread {
                mxm = try {
                    MusixmatchClient.apiKey = key
                    MusixmatchClient.fetchLyrics(title, artist)
                } catch (e: Exception) { null }
            })
            jobs.forEach { it.join() }
            val results = ArrayList<Pair<String, String>>()
            entry?.plainLyrics?.takeIf { it.isNotBlank() }?.let { results.add("LRCLIB" to it) }
            ovh?.takeIf { it.isNotBlank() }?.let { results.add("Lyrics.ovh" to it) }
            mxm?.takeIf { it.isNotBlank() }?.let { results.add("Musixmatch" to it) }
            val lrc = entry
            runOnUiThread {
                when {
                    results.isEmpty() ->
                        Toast.makeText(this, Strings.t("no_lyrics"), Toast.LENGTH_LONG).show()
                    results.size == 1 -> applyLyrics(results[0], lrc)
                    else -> {
                        val labels = results.map { (srcName, txt) ->
                            srcName + "\n" + (txt.lineSequence()
                                .firstOrNull { it.isNotBlank() }?.take(40) ?: "")
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle(Strings.t("lyrics_pick"))
                            .setItems(labels) { _, which -> applyLyrics(results[which], lrc) }
                            .setNegativeButton(Strings.t("cancel"), null)
                            .show()
                    }
                }
            }
        }
    }

    /** Puts picked lyrics into the box (tidied) and saves LRCLIB's .lrc. */
    private fun applyLyrics(picked: Pair<String, String>, lrc: LrcEntry?) {
        lyricsInput?.setText(LyricsText.normalize(picked.second))
        val it = item
        if (picked.first == "LRCLIB" && lrc != null && lrc.syncedLyrics.isNotBlank() && it != null) {
            thread {
                val saved = SafStore.saveLrc(this, it, lrc.syncedLyrics)
                runOnUiThread {
                    Toast.makeText(this,
                        Strings.t(if (saved) "lrc_saved" else "done"),
                        Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, Strings.t("done"), Toast.LENGTH_SHORT).show()
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
        // Decode at display size, not full size: the zoom view is at
        // most ~1024px wide, and a multi-megapixel cover would waste
        // a lot of memory decoded whole.
        val bmp = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) { null } ?: return
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Ui.BG)
        }
        val dlg = AlertDialog.Builder(this).setView(iv).create()
        iv.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    /** Downloads a cover image from a typed URL and uses it as the new cover. */
    private fun promptCoverUrl() {
        val input = EditText(this).apply {
            hint = "https://"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle(Strings.t("cover_url"))
            .setView(input)
            .setPositiveButton(Strings.t("ok")) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                thread {
                    try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                        conn.instanceFollowRedirects = true
                        conn.connectTimeout = 10000
                        conn.readTimeout = 15000
                        val raw = conn.inputStream.readBytes()
                        val normalized = SafStore.normalizeCoverBytes(raw)
                        runOnUiThread {
                            coverBytes = normalized; coverRemoved = false; coverChanged = true
                            coverPreview = decodePreview(normalized)
                            coverView.setImageBitmap(coverPreview)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Ui.failDialog(this, "${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
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
                coverPreview = coverBytes?.let { decodePreview(it) }
                coverView.setImageBitmap(coverPreview)
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
        // Pre-save preview: the same change list the search flow shows.
        val lines = ArrayList(TagDiff.lines(original, d))
        val nameChanges = newBase.isNotBlank() && newBase != it.name.substringBeforeLast('.')
        if (nameChanges)
            lines.add("${Strings.t("filename")}: ${it.name} → $newBase.${it.ext}")
        if (lines.isEmpty()) { doSave(it, d, newBase); return }
        AlertDialog.Builder(this)
            .setTitle(Strings.t("diff_title"))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(Strings.t("apply")) { _, _ -> doSave(it, d, newBase) }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

    private fun doSave(it: Item, d: TagData, newBase: String) {
        Toast.makeText(this, Strings.t("working"), Toast.LENGTH_SHORT).show()
        thread {
            var err: String? = null
            try {
                SafStore.ensureLocal(this, it)
                // An untouched cover is re-read from the file now: the
                // editor only ever held a small preview of it.
                val toWrite = if (!coverChanged && !coverRemoved)
                    d.copy(cover = TagService.read(it.local!!).cover) else d
                Backup.backup(it.local!!, SafStore.backupDir(this))
                TagService.write(it.local!!, toWrite)
                SafStore.writeBack(this, it)
                if (!SafStore.verify(this, it, toWrite.cover != null)) {
                    throw IllegalStateException("verify failed after write-back")
                }
                it.setTagLight(TagService.read(it.local!!))
                if (newBase.isNotBlank() && newBase != it.name.substringBeforeLast('.')) {
                    val newName = newBase + "." + it.ext
                    if (!SafStore.rename(this, it, newName)) {
                        val d = SafStore.lastRenameDetail
                        err = if (d.isBlank()) "rename failed: $newName"
                            else "rename failed: $newName\n$d"
                    }
                }
                // The edit is done and written back: the working copy
                // has served its purpose and is dropped right away.
                if (err == null) SafStore.discardCopy(it)
            } catch (e: Exception) {
                err = "${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread {
                if (err == null) {
                    savedClean = true
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
