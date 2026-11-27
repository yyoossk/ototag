package com.yyoo.ototag

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.yyoo.ototag.core.*
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ItemAdapter
    private var player: MediaPlayer? = null
    private var playingItem: Item? = null

    /** Tapping a row's thumbnail plays that file; tapping again stops. */
    private fun togglePlay(item: Item) {
        if (playingItem == item && player != null) {
            stopPlayer(); adapter.notifyDataSetChanged(); return
        }
        stopPlayer()
        thread {
            try {
                SafStore.ensureLocal(this, item)
                val mp = MediaPlayer()
                mp.setDataSource(item.local!!.absolutePath)
                mp.setOnCompletionListener {
                    runOnUiThread { stopPlayer(); adapter.notifyDataSetChanged() }
                }
                mp.prepare(); mp.start()
                player = mp; playingItem = item
            } catch (e: Exception) {
                runOnUiThread { toast("${Strings.t("failed")}: ${e.message}") }
            }
            runOnUiThread { adapter.notifyDataSetChanged() }
        }
    }

    private fun stopPlayer() {
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null; playingItem = null
    }

    // ---- self-update (GitHub releases) ----

    private var updateDownloadId: Long = -1
    private var updateProgress: AlertDialog? = null
    private var updateReceiver: BroadcastReceiver? = null

    private fun checkUpdate() {
        thread {
            val info = UpdateChecker.fetchLatest() ?: return@thread
            val current = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (e: Exception) { "" }
            if (!UpdateChecker.isNewer(info.tag, current) || info.androidApkUrl.isBlank()) return@thread
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(Strings.t("upd_title"))
                    .setMessage(info.tag)
                    .setPositiveButton(Strings.t("upd_now")) { _, _ -> startUpdateDownload(info) }
                    .setNegativeButton(Strings.t("upd_later"), null)
                    .show()
            }
        }
    }

    private fun startUpdateDownload(info: UpdateInfo) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast(Strings.t("upd_install_perm"))
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            } catch (e: Exception) { }
            return
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(info.androidApkUrl))
            .setTitle("OtoTag ${info.tag}")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "OtoTag-update.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        try { File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "OtoTag-update.apk").delete() } catch (e: Exception) { }
        updateDownloadId = dm.enqueue(req)
        updateProgress = AlertDialog.Builder(this)
            .setMessage(Strings.t("upd_downloading"))
            .setCancelable(false)
            .show()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != updateDownloadId) return
                updateProgress?.dismiss(); updateProgress = null
                val uri = try { dm.getUriForDownloadedFile(updateDownloadId) } catch (e: Exception) { null }
                if (uri == null) { toast(Strings.t("upd_failed")); return }
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(install) } catch (e: Exception) { toast(Strings.t("upd_failed")) }
            }
        }
        updateReceiver = receiver
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    override fun onDestroy() {
        stopPlayer()
        updateReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) { } }
        updateReceiver = null
        super.onDestroy()
    }
    private val prefs by lazy { getSharedPreferences("ototag", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide the system title bar: the graphical in-layout header is the only app name.
        actionBar?.hide()
        Strings.lang = Strings.resolve(prefs.getString("lang", null), Locale.getDefault().toLanguageTag())
        ItunesClient.country = Locale.getDefault().country.ifBlank { "JP" }
        title = "OtoTag"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // header row: graphical app name at the far left, language picker on the right
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(12), dp(4))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        header.addView(TextView(this).apply {
            text = "♪ OtoTag ♫"
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            // gradient lettering (violet → fuchsia → orange), measured once laid out
            post {
                val w = paint.measureText(text.toString())
                paint.shader = android.graphics.LinearGradient(
                    0f, 0f, w, 0f,
                    intArrayOf(Ui.VIOLET, Ui.MAGENTA, Ui.ORANGE),
                    null, android.graphics.Shader.TileMode.CLAMP)
                invalidate()
            }
        })
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        header.addView(TextView(this).apply {
            text = Strings.t("language"); textSize = 13f
            setTextColor(Ui.SUB)
        })
        val langSpinner = Spinner(this)
        langSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            Strings.LANGS.map { Strings.LANG_NAMES[it] })
        langSpinner.setSelection(Strings.LANGS.indexOf(Strings.lang))
        langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val chosen = Strings.LANGS[pos]
                if (chosen != Strings.lang) {
                    prefs.edit().putString("lang", chosen).apply()
                    Strings.lang = chosen
                    recreate()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        header.addView(langSpinner)
        root.addView(header)

        // top row: two equal buttons, same height
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        fun topBtn(label: String, color: Int, fn: () -> Unit) {
            top.addView(Button(this).apply {
                text = label
                textSize = 14f
                isSingleLine = true
                Ui.styleButton(this, color)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { fn() }
            })
        }
        topBtn(Strings.t("choose_folder"), Ui.VIOLET) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(i, REQ_TREE)
        }
        topBtn(Strings.t("choose_files"), Ui.PINK) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(i, REQ_FILES)
        }
        root.addView(top)

        adapter = ItemAdapter()
        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = null
            dividerHeight = 0
            setBackgroundColor(Ui.BG)
        }
        root.addView(listView)

        // bottom action bar: five equal buttons, fixed height, same text size
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(2), dp(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        fun barBtn(label: String, color: Int, fn: () -> Unit) {
            bar.addView(Button(this).apply {
                text = label
                textSize = 13f
                isSingleLine = true
                gravity = Gravity.CENTER
                Ui.styleButton(this, color)
                // shrink the text to fit instead of clipping it (API 26+)
                setAutoSizeTextTypeUniformWithConfiguration(
                    8, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                setPadding(dp(3), 0, dp(3), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { fn() }
            })
        }
        barBtn(Strings.t("bar_edit"), Ui.VIOLET) { editSelected() }
        barBtn(Strings.t("bar_search"), Ui.PINK) { searchApplySelected() }
        barBtn(Strings.t("bar_rename"), Ui.ORANGE) { renameSelected() }
        barBtn(Strings.t("bar_undo"), Ui.TEAL) { undoSelected() }
        root.addView(bar)

        setContentView(root)
        checkUpdate()

        prefs.getString("tree", null)?.let { uriStr ->
            loadTree(Uri.parse(uriStr))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun checkedItems(): List<Item> = Items.list.filter { it.checked }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun editSelected() {
        val sel = checkedItems()
        if (sel.size != 1) { toast(Strings.t("select_one")); return }
        val i = Intent(this, EditActivity::class.java)
        i.putExtra("index", Items.list.indexOf(sel[0]))
        startActivityForResult(i, REQ_EDIT)
    }

    private fun searchApplySelected() {
        val sel = checkedItems()
        if (sel.isEmpty()) { toast(Strings.t("select_some")); return }
        applyNext(sel, 0)
    }

    private fun applyNext(sel: List<Item>, idx: Int) {
        if (idx >= sel.size) { adapter.notifyDataSetChanged(); toast(Strings.t("done")); return }
        val item = sel[idx]
        thread {
            try { SafStore.ensureLocal(this, item); item.tag = TagService.read(item.local!!) }
            catch (e: Exception) { item.error = e.message }
            runOnUiThread {
                val parsed = NameParser.parse(File(item.name))
                SearchFlow.start(
                    this, item.tag, parsed,
                    progressLabel = "${idx + 1} / ${sel.size}\n${item.name}",
                    onSkip = { applyNext(sel, idx + 1) },
                    onStop = { adapter.notifyDataSetChanged() }
                ) { newTag ->
                    thread {
                        var ok = false
                        try {
                            Backup.backup(item.local!!, SafStore.backupDir(this))
                            TagService.write(item.local!!, newTag)
                            SafStore.writeBack(this, item)
                            item.tag = TagService.read(item.local!!)
                            item.error = null
                            if (!SafStore.verify(this, item, newTag.cover != null)) {
                                item.error = Strings.t("failed")
                            } else {
                                // applied via search: uncheck it so a re-run skips it
                                item.checked = false
                                ok = true
                            }
                        } catch (e: Exception) { item.error = e.message }
                        runOnUiThread {
                            if (newTag.coverFetchFailed) toast(Strings.t("cover_failed"))
                            if (ok) showApplied(item, newTag) { applyNext(sel, idx + 1) }
                            else Ui.failDialog(this, "${item.name}\n${item.error ?: ""}") {
                                applyNext(sel, idx + 1)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * After a successful search-apply, shows a clear centred confirmation of
     * what went in (cover, title, artist, album). When a tag-based file name
     * is available, the same dialog offers the rename.
     */
    private fun showApplied(item: Item, newTag: TagData, done: () -> Unit) {
        val base = Renamer.nameFromTag(newTag)
        val newName = if (base.isBlank()) "" else "$base.${item.ext}"
        val canRename = newName.isNotBlank() && newName != item.name
        val msg = buildString {
            append(newTag.title)
            if (newTag.artist.isNotBlank()) append("\n${newTag.artist}")
            if (newTag.album.isNotBlank()) append("\n${newTag.album}")
            if (canRename) append("\n\n${Strings.t("ask_rename")}\n${item.name}\n→ $newName")
        }
        val b = AlertDialog.Builder(this)
            .setTitle(Strings.t("applied"))
            .setMessage(msg)
            .setCancelable(false)
        newTag.cover?.let { bytes ->
            try {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) b.setIcon(android.graphics.drawable.BitmapDrawable(resources, bmp))
            } catch (e: Exception) { }
        }
        if (canRename) {
            b.setPositiveButton(Strings.t("rename")) { _, _ ->
                thread {
                    SafStore.rename(this, item, newName)
                    runOnUiThread { adapter.notifyDataSetChanged(); done() }
                }
            }
            b.setNegativeButton(Strings.t("keep_name")) { _, _ -> done() }
        } else {
            b.setPositiveButton(Strings.t("ok")) { _, _ -> done() }
        }
        b.show()
    }

    private fun bulkEditSelected() {
        val sel = checkedItems()
        if (sel.isEmpty()) { toast(Strings.t("select_some")); return }
        val fields = listOf("album", "album_artist", "genre", "year", "disc", "composer")
        val inputs = HashMap<String, EditText>()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        for (f in fields) {
            layout.addView(TextView(this).apply { text = Strings.t(f) })
            val et = EditText(this); inputs[f] = et; layout.addView(et)
        }
        var coverBytes: ByteArray? = null
        layout.addView(Button(this).apply {
            text = Strings.t("bulk_cover")
            setOnClickListener {
                pendingImagePick = { uri ->
                    try { coverBytes = SafStore.normalizeCover(this@MainActivity, uri) } catch (e: Exception) { }
                }
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                }
                startActivityForResult(i, REQ_IMAGE)
            }
        })
        AlertDialog.Builder(this)
            .setTitle(Strings.t("bulk_edit"))
            .setView(layout)
            .setPositiveButton(Strings.t("apply")) { _, _ ->
                thread {
                    for (item in sel) {
                        try {
                            SafStore.ensureLocal(this, item)
                            val cur = item.tag ?: TagService.read(item.local!!)
                            val d = TagData(
                                title = cur.title, artist = cur.artist,
                                album = inputs["album"]!!.text.toString().ifBlank { cur.album },
                                albumArtist = inputs["album_artist"]!!.text.toString().ifBlank { cur.albumArtist },
                                composer = inputs["composer"]!!.text.toString().ifBlank { cur.composer },
                                genre = inputs["genre"]!!.text.toString().ifBlank { cur.genre },
                                year = inputs["year"]!!.text.toString().ifBlank { cur.year },
                                track = cur.track, trackTotal = cur.trackTotal,
                                disc = inputs["disc"]!!.text.toString().ifBlank { cur.disc },
                                discTotal = cur.discTotal, comment = cur.comment, isrc = cur.isrc,
                                mbRecordingId = cur.mbRecordingId, mbReleaseId = cur.mbReleaseId,
                                cover = coverBytes ?: cur.cover)
                            Backup.backup(item.local!!, SafStore.backupDir(this))
                            TagService.write(item.local!!, d)
                            SafStore.writeBack(this, item)
                            item.tag = TagService.read(item.local!!)
                            item.error = null
                        } catch (e: Exception) { item.error = e.message }
                    }
                    runOnUiThread { adapter.notifyDataSetChanged(); toast(Strings.t("done")) }
                }
            }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

    private fun renameSelected() {
        val sel = checkedItems()
        if (sel.isEmpty()) { toast(Strings.t("select_some")); return }
        val patternInput = EditText(this).apply { setText("%track% - %title%") }
        val preview = TextView(this).apply { setPadding(40, 10, 40, 0) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        layout.addView(TextView(this).apply { text = Strings.t("pattern") })
        layout.addView(patternInput)
        layout.addView(preview)
        fun updatePreview() {
            val sb = StringBuilder()
            for (item in sel) {
                val d = item.tag
                val newBase = if (d != null) Renamer.applyPattern(patternInput.text.toString(), d, item.name.substringBeforeLast('.'))
                    else item.name.substringBeforeLast('.')
                sb.append(item.name).append("  ->  ").append(newBase).append('.').append(item.ext).append('\n')
            }
            preview.text = sb.toString()
        }
        patternInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updatePreview() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        thread {
            for (item in sel) {
                try { SafStore.ensureLocal(this, item); if (item.tag == null) item.tag = TagService.read(item.local!!) }
                catch (e: Exception) { item.error = e.message }
            }
            runOnUiThread { updatePreview() }
        }
        AlertDialog.Builder(this)
            .setTitle(Strings.t("rename_preview"))
            .setView(layout)
            .setPositiveButton(Strings.t("apply")) { _, _ ->
                var failed = 0
                for (item in sel) {
                    val d = item.tag ?: continue
                    val newBase = Renamer.applyPattern(patternInput.text.toString(), d, item.name.substringBeforeLast('.'))
                    val newName = newBase + "." + item.ext
                    if (newName == item.name) continue
                    if (Items.list.any { it !== item && it.name == newName }) { failed++; continue }
                    if (!SafStore.rename(this, item, newName)) failed++
                }
                adapter.notifyDataSetChanged()
                toast(if (failed == 0) Strings.t("done") else "${Strings.t("failed")}: $failed")
            }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

    private fun undoSelected() {
        val sel = checkedItems()
        if (sel.isEmpty()) { toast(Strings.t("select_some")); return }
        thread {
            var restored = 0
            for (item in sel) {
                try {
                    SafStore.ensureLocal(this, item)
                    if (Backup.restore(item.local!!, SafStore.backupDir(this))) {
                        SafStore.writeBack(this, item)
                        item.tag = TagService.read(item.local!!)
                        restored++
                    }
                } catch (e: Exception) { item.error = e.message }
            }
            runOnUiThread {
                adapter.notifyDataSetChanged()
                toast(if (restored > 0) Strings.t("restored") else Strings.t("no_backup"))
            }
        }
    }

    private fun loadTree(treeUri: Uri) {
        stopPlayer()
        toast(Strings.t("working"))
        thread {
            val items = try { SafStore.enumerateTree(this, treeUri) } catch (e: Exception) { emptyList() }
            Items.list.clear(); Items.list.addAll(items)
            for (item in Items.list) {
                try {
                    SafStore.ensureLocal(this, item)
                    item.tag = TagService.read(item.local!!)
                } catch (e: Exception) { item.error = e.message }
                runOnUiThread { adapter.notifyDataSetChanged() }
            }
        }
    }

    private fun loadFiles(uris: List<Uri>) {
        stopPlayer()
        thread {
            for (uri in uris) {
                val name = queryName(uri) ?: continue
                if (Items.list.any { it.docUri == uri }) continue
                // Files added one by one start out checked, ready for the bulk actions.
                val item = Item(uri, name, checked = true)
                Items.list.add(item)
                try {
                    SafStore.ensureLocal(this, item)
                    item.tag = TagService.read(item.local!!)
                } catch (e: Exception) { item.error = e.message }
            }
            runOnUiThread { adapter.notifyDataSetChanged() }
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    var pendingImagePick: ((Uri) -> Unit)? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_TREE -> {
                val uri = data?.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: Exception) { }
                prefs.edit().putString("tree", uri.toString()).apply()
                loadTree(uri)
            }
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
                data?.data?.let { uris.add(it) }
                for (u in uris) {
                    try {
                        contentResolver.takePersistableUriPermission(u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    } catch (e: Exception) { }
                }
                loadFiles(uris.filter { it.toString().substringAfterLast('.', "").lowercase() in AUDIO_EXTS ||
                    (queryName(it)?.substringAfterLast('.', "")?.lowercase() in AUDIO_EXTS) })
            }
            REQ_IMAGE -> {
                val uri = data?.data ?: return
                pendingImagePick?.invoke(uri); pendingImagePick = null
            }
            REQ_EDIT -> adapter.notifyDataSetChanged()
        }
    }

    inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = Items.list.size
        override fun getItem(pos: Int) = Items.list[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val item = Items.list[pos]
            val d = { v: Int -> (v * resources.displayMetrics.density).toInt() }
            // white rounded card on the lavender background
            val outer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(10), d(5), d(10), d(5))
            }
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.card(this@MainActivity)
                setPadding(d(10), d(8), d(6), d(8))
                minimumHeight = d(68)
            }
            outer.addView(row)
            // cover thumbnail (gradient placeholder when there is no cover); tap to play/stop
            val cover = ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(d(52), d(52)).apply { marginEnd = d(10) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (item == playingItem && player != null) {
                    setImageResource(android.R.drawable.ic_media_pause)
                    background = Ui.coverPlaceholder(this@MainActivity)
                } else {
                    val bmp = item.thumbnail()
                    if (bmp != null) setImageBitmap(bmp)
                    else {
                        setImageResource(android.R.drawable.ic_media_play)
                        background = Ui.coverPlaceholder(this@MainActivity)
                    }
                }
                setOnClickListener { togglePlay(item) }
            }
            row.addView(cover)
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this@MainActivity).apply {
                text = item.name; textSize = 15f
                setTextColor(Ui.INK)
                setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val sub = when {
                item.error != null -> "${Strings.t("failed")}: ${item.error}"
                item.tag != null && !item.tag!!.isBasicallyEmpty() ->
                    "${item.tag!!.title} — ${item.tag!!.artist}"
                else -> "—"
            }
            texts.addView(TextView(this@MainActivity).apply {
                text = sub; textSize = 12f
                setTextColor(if (item.error != null) 0xFFD32F2F.toInt() else Ui.SUB)
                setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            })
            // tapping the title area plays/stops the song, like the thumbnail
            texts.setOnClickListener { togglePlay(item) }
            row.setOnClickListener { togglePlay(item) }
            row.addView(texts)
            val cb = CheckBox(this@MainActivity).apply {
                isChecked = item.checked
                setOnCheckedChangeListener { _, v -> item.checked = v }
            }
            row.addView(cb)
            return outer
        }
    }

    companion object {
        const val REQ_TREE = 1
        const val REQ_FILES = 2
        const val REQ_IMAGE = 3
        const val REQ_EDIT = 4
    }
}
