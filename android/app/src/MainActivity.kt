package com.yyoo.ototag

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
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
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ItemAdapter
    private var player: MediaPlayer? = null
    private var playingItem: Item? = null
    /** List filter: 0 = all, 1 = no tags, 2 = no cover. */
    private var filterMode: Int = 0
    private val thumbExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    /** The items the list currently shows, after the filter. */
    private fun shownItems(): List<Item> = when (filterMode) {
        1 -> Items.list.filter { it.tag == null || it.tag!!.isBasicallyEmpty() }
        2 -> Items.list.filter { it.tag?.cover == null }
        else -> Items.list
    }

    /** Settings dialog: the embedded-cover size cap (default: no limit). */
    private fun showSettings() {
        val caps = listOf(0, 2000, 1200, 800)
        val labels = listOf(Strings.t("cap_none"), "2000px", "1200px", "800px")
        val cur = prefs.getInt("cover_cap", 0)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0)
        }
        layout.addView(TextView(this).apply { text = Strings.t("cover_cap") })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(caps.indexOf(cur).coerceAtLeast(0))
        layout.addView(spinner)
        AlertDialog.Builder(this)
            .setTitle(Strings.t("settings"))
            .setView(layout)
            .setPositiveButton(Strings.t("ok")) { _, _ ->
                prefs.edit().putInt("cover_cap", caps[spinner.selectedItemPosition]).apply()
            }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

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

    private var updateProgress: AlertDialog? = null

    /** First launch only: auto-update needs the "install unknown apps"
     *  permission, so guide the user to the settings page right away. */
    private fun maybeAskInstallPermission() {
        if (prefs.getBoolean("permAsked", false)) return
        prefs.edit().putBoolean("permAsked", true).apply()
        if (packageManager.canRequestPackageInstalls()) return
        AlertDialog.Builder(this)
            .setTitle(Strings.t("upd_title"))
            .setMessage(Strings.t("upd_perm_msg"))
            .setPositiveButton(Strings.t("upd_perm_open")) { _, _ -> openInstallSettings() }
            .setNegativeButton(Strings.t("upd_later"), null)
            .show()
    }

    private fun openInstallSettings() {
        try {
            startActivity(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) { }
    }

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
            AlertDialog.Builder(this)
                .setTitle(Strings.t("upd_title"))
                .setMessage(Strings.t("upd_perm_msg"))
                .setPositiveButton(Strings.t("upd_perm_open")) { _, _ -> openInstallSettings() }
                .setNegativeButton(Strings.t("upd_later"), null)
                .show()
            return
        }
        // In-app download with a live progress bar. (DownloadManager was
        // dropped: its completion broadcast is not delivered to a
        // RECEIVER_NOT_EXPORTED receiver, so the old dialog hung forever.)
        val cancelled = AtomicBoolean(false)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val label = TextView(this).apply { text = Strings.t("upd_downloading") }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20); setPadding(pad, dp(12), pad, dp(4))
            addView(label)
            addView(bar)
        }
        updateProgress = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton(Strings.t("cancel")) { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .show()
        val dest = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "OtoTag-update.apk")
        thread {
            try {
                downloadApk(info.androidApkUrl, dest, cancelled) { pct ->
                    runOnUiThread {
                        bar.progress = pct
                        label.text = "${Strings.t("upd_downloading")} $pct%"
                    }
                }
                runOnUiThread {
                    updateProgress?.dismiss(); updateProgress = null
                    val uri = Uri.parse("content://com.yyoo.ototag.files/${dest.name}")
                    val install = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { startActivity(install) }
                    catch (e: Exception) { Ui.failDialog(this, "${e.javaClass.simpleName}: ${e.message}") }
                }
            } catch (e: InterruptedIOException) {
                try { dest.delete() } catch (x: Exception) { }
                runOnUiThread { updateProgress?.dismiss(); updateProgress = null }
            } catch (e: Exception) {
                try { dest.delete() } catch (x: Exception) { }
                runOnUiThread {
                    updateProgress?.dismiss(); updateProgress = null
                    Ui.failDialog(this, "${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** Streams the APK to dest, following redirects by hand (the GitHub
     *  release URL 302s to a signed asset URL), reporting percent progress. */
    private fun downloadApk(url: String, dest: File, cancelled: AtomicBoolean, onPct: (Int) -> Unit) {
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "OtoTag")
            val code = conn.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                    ?: throw IOException("redirect without location")
                conn.disconnect()
                if (++redirects > 5) throw IOException("too many redirects")
                current = URL(URL(current), loc).toString()
                continue
            }
            if (code != 200) throw IOException("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { ins ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        if (cancelled.get()) throw InterruptedIOException("cancelled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onPct(pct) }
                        }
                    }
                }
            }
            if (total > 0 && dest.length() != total)
                throw IOException("incomplete download: ${dest.length()} / $total")
            return
        }
    }

    override fun onDestroy() {
        stopPlayer()
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
        header.addView(Button(this).apply {
            text = Strings.t("history"); Ui.styleButton(this, Ui.TEAL)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
            }
        })
        header.addView(Button(this).apply {
            text = Strings.t("settings"); Ui.styleButton(this, Ui.MAGENTA)
            setOnClickListener { showSettings() }
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

        // filter row: all / no tags / no cover
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
        }
        val filterSpinner = Spinner(this)
        filterSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(Strings.t("filter_all"), Strings.t("filter_notag"), Strings.t("filter_nocover")))
        filterSpinner.setSelection(filterMode)
        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (pos != filterMode) { filterMode = pos; adapter.notifyDataSetChanged() }
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        filterRow.addView(filterSpinner)
        root.addView(filterRow)

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
        maybeAskInstallPermission()
        checkUpdate()

        prefs.getString("tree", null)?.let { uriStr ->
            loadTree(Uri.parse(uriStr))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun checkedItems(): List<Item> = Items.list.filter { it.checked }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private var lastEdited: Item? = null

    private fun editSelected() {
        val sel = checkedItems()
        if (sel.size != 1) { toast(Strings.t("select_one")); return }
        lastEdited = sel[0]
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
                    onStop = { adapter.notifyDataSetChanged() },
                    bulk = sel.size > 1
                ) { picked ->
                    // Partial sources (Audius / NDL) only fill what they know;
                    // keep the file's own values for everything else.
                    val merged = if (picked.partial)
                        picked.mergedOver(item.tag ?: TagData()) else picked
                    // The cover cap applies to covers the candidate brought;
                    // a cover the file already had is left as it is.
                    val newTag = if (picked.cover != null)
                        merged.copy(cover = SafStore.capCover(merged.cover,
                            prefs.getInt("cover_cap", 0)))
                    else merged
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
                    try {
                        coverBytes = SafStore.capCover(
                            SafStore.normalizeCover(this@MainActivity, uri),
                            prefs.getInt("cover_cap", 0))
                    } catch (e: Exception) { }
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
        // Plain file-name editing: each checked file gets a text field with
        // its current name (extension fixed), nothing else. No patterns.
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val fields = ArrayList<Pair<Item, EditText>>()
        for (item in sel) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val et = EditText(this).apply {
                setText(item.name.substringBeforeLast('.'))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(et)
            row.addView(TextView(this).apply { text = "." + item.ext; setTextColor(Ui.SUB) })
            layout.addView(row)
            fields.add(item to et)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this)
            .setTitle(Strings.t("rename"))
            .setView(scroll)
            .setPositiveButton(Strings.t("apply")) { _, _ ->
                var failed = 0
                val targets = HashSet<String>()
                for ((item, et) in fields) {
                    val newBase = Renamer.sanitize(et.text.toString())
                    if (newBase.isBlank()) { failed++; continue }
                    val newName = newBase + "." + item.ext
                    if (newName == item.name) continue
                    if (!targets.add(newName.lowercase()) ||
                        Items.list.any { it !== item && it.name.equals(newName, ignoreCase = true) }) {
                        failed++; continue
                    }
                    if (!SafStore.rename(this, item, newName)) failed++
                    else item.checked = false // acted on: drop the check
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
        AlertDialog.Builder(this)
            .setMessage(Strings.t("confirm_undo"))
            .setPositiveButton(Strings.t("undo")) { _, _ -> doUndo(sel) }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

    private fun doUndo(sel: List<Item>) {
        thread {
            var restored = 0
            for (item in sel) {
                try {
                    SafStore.ensureLocal(this, item)
                    if (Backup.restore(item.local!!, SafStore.backupDir(this))) {
                        SafStore.writeBack(this, item)
                        item.tag = TagService.read(item.local!!)
                        item.checked = false // acted on: drop the check
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
            runOnUiThread { adapter.notifyDataSetChanged() }
            // Read tags in parallel so thumbnails and titles fill in fast.
            val next = java.util.concurrent.atomic.AtomicInteger(0)
            val workers = (1..4).map {
                thread {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= items.size) break
                        val item = items[i]
                        try {
                            SafStore.ensureLocal(this, item)
                            item.tag = TagService.read(item.local!!)
                        } catch (e: Exception) { item.error = e.message }
                        runOnUiThread { adapter.notifyDataSetChanged() }
                    }
                }
            }
            workers.forEach { it.join() }
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
                runOnUiThread { adapter.notifyDataSetChanged() }
            }
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
            REQ_EDIT -> {
                // Saved in the editor: the file was acted on, drop its check.
                if (resultCode == RESULT_OK) lastEdited?.checked = false
                adapter.notifyDataSetChanged()
            }
        }
    }

    inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = shownItems().size
        override fun getItem(pos: Int) = shownItems()[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val item = shownItems()[pos]
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
                    // decode thumbnails off the UI thread; the row refreshes
                    // when the bitmap is ready (and it is cached afterwards)
                    val bmp = item.peekThumbnail()
                    if (bmp != null) setImageBitmap(bmp)
                    else {
                        setImageBitmap(Ui.noCoverBitmap())
                        if (item.tag?.cover != null && !item.thumbLoading) {
                            item.thumbLoading = true
                            thumbExecutor.execute {
                                try { item.thumbnail() } catch (e: Exception) { }
                                item.thumbLoading = false
                                runOnUiThread { adapter.notifyDataSetChanged() }
                            }
                        }
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
