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
    private var player: MediaPlayer? = null
    private var playBtn: Button? = null
    private lateinit var coverView: ImageView
    private val inputs = HashMap<String, EditText>()
    private lateinit var filenameInput: EditText

    private val fieldKeys = listOf(
        "title", "artist", "album", "album_artist", "composer", "genre",
        "year", "track", "track_total", "disc", "disc_total", "comment", "isrc")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            try {
                SafStore.ensureLocal(this, it)
                if (it.tag == null) it.tag = TagService.read(it.local!!)
            } catch (e: Exception) { it.error = e.message }
            runOnUiThread { fillFields(it.tag ?: TagData()) }
        }

        root.addView(TextView(this).apply { text = Strings.t("filename"); setTextColor(Ui.SUB) })
        filenameInput = EditText(this).apply { setText(it.name.substringBeforeLast('.')) }
        root.addView(filenameInput)
        root.addView(TextView(this).apply { text = "." + it.ext })

        for (k in fieldKeys) {
            root.addView(TextView(this).apply { text = Strings.t(k); setTextColor(Ui.SUB) })
            val et = EditText(this); inputs[k] = et; root.addView(et)
        }

        root.addView(TextView(this).apply { text = Strings.t("cover"); setTextColor(Ui.SUB) })
        coverView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(360, 360)
            setOnClickListener { pickImage() }
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
                SearchFlow.start(this@EditActivity, cur, parsed) { picked ->
                    fillFields(picked)
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

    private fun fillFields(d: TagData) {
        inputs["title"]?.setText(d.title)
        inputs["artist"]?.setText(d.artist)
        inputs["album"]?.setText(d.album)
        inputs["album_artist"]?.setText(d.albumArtist)
        inputs["composer"]?.setText(d.composer)
        inputs["genre"]?.setText(d.genre)
        inputs["year"]?.setText(d.year)
        inputs["track"]?.setText(d.track)
        inputs["track_total"]?.setText(d.trackTotal)
        inputs["disc"]?.setText(d.disc)
        inputs["disc_total"]?.setText(d.discTotal)
        inputs["comment"]?.setText(d.comment)
        inputs["isrc"]?.setText(d.isrc)
        if (d.cover != null) {
            coverBytes = d.cover; coverRemoved = false
            coverView.setImageBitmap(BitmapFactory.decodeByteArray(d.cover, 0, d.cover!!.size))
        }
        // keep MBIDs across a search-pick so they get written on save
        pendingMbIds = d.mbRecordingId to d.mbReleaseId
    }

    private var pendingMbIds: Pair<String, String> = "" to ""

    private fun collectFields(): TagData {
        fun v(k: String) = inputs[k]?.text?.toString() ?: ""
        return TagData(
            title = v("title"), artist = v("artist"), album = v("album"),
            albumArtist = v("album_artist"), composer = v("composer"), genre = v("genre"),
            year = v("year"), track = v("track"), trackTotal = v("track_total"),
            disc = v("disc"), discTotal = v("disc_total"), comment = v("comment"),
            isrc = v("isrc"),
            mbRecordingId = pendingMbIds.first, mbReleaseId = pendingMbIds.second,
            cover = if (coverRemoved) null else coverBytes)
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
                coverRemoved = false
                coverBytes?.let { coverView.setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size)) }
            } catch (e: Exception) { }
        }
    }

    private fun save() {
        val it = item ?: return
        val d = collectFields()
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
                it.tag = TagService.read(it.local!!)
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
