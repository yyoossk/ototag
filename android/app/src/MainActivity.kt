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

/** View references of one recycled song row. */
private class SongHolder(
    val cover: ImageView, val title: TextView, val sub: TextView, val cb: CheckBox)

class MainActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ItemAdapter
    private var player: MediaPlayer? = null
    private var playingItem: Item? = null
    /** List filter: 0 = all, 1 = no tags, 2 = no cover. */
    private var filterMode: Int = 0
    /** List sort: 0 = file name, 1 = title, 2 = album, 3 = artist. */
    private var sortMode: Int = 0
    /** Sort direction flip (the ↑↓ button next to the sort spinner). */
    private var sortReverse: Boolean = false
    /** Quick filter set by tapping an artist/album in a row; null = off. */
    private var quickFilter: String? = null
    private lateinit var quickBar: TextView
    /** Bumped whenever an item's tag/error changes; keys the list caches. */
    private var dataVersion: Int = 0
    private var shownCache: List<Item>? = null
    private var shownCacheKey: String = ""
    private var rowsCache: List<Any>? = null
    /** Folders currently collapsed in the grouped list. */
    private val collapsedFolders = HashSet<String>()

    /** One display row: either a folder header or a song. */
    private class HeaderRow(val folder: String, val count: Int)
    private class SongRow(val item: Item)
    // Thumbnail decoding runs on two workers fed by a priority queue:
    // rows near the visible window decode first, so fast scrolling does
    // not leave a backlog of stale decodes ahead of what is on screen.
    private class ThumbTask(val rank: Int, val body: () -> Unit) : Comparable<ThumbTask> {
        override fun compareTo(other: ThumbTask): Int = rank.compareTo(other.rank)
    }
    private val thumbQueue = java.util.concurrent.PriorityBlockingQueue<ThumbTask>()
    @Volatile private var visibleCenter: Int = 0
    @Volatile private var thumbWorkersStarted: Boolean = false
    private fun enqueueThumb(position: Int, body: () -> Unit) {
        if (!thumbWorkersStarted) {
            synchronized(this) {
                if (!thumbWorkersStarted) {
                    thumbWorkersStarted = true
                    repeat(2) {
                        thread(isDaemon = true) {
                            while (true) {
                                val t = thumbQueue.take()
                                try { t.body() } catch (e: Exception) { }
                            }
                        }
                    }
                }
            }
        }
        thumbQueue.put(ThumbTask(kotlin.math.abs(position - visibleCenter), body))
    }

    /**
     * The items the list currently shows, after the filter and sort.
     * The result is memoized: scrolling calls this constantly, and the
     * filter/sort only has to run again when the inputs actually change
     * (filter, sort, direction, quick filter, or the data version).
     */
    private fun shownItems(): List<Item> {
        val key = "$filterMode|$sortMode|$sortReverse|$quickFilter|$dataVersion|${Items.list.size}"
        shownCache?.let { if (shownCacheKey == key) return it }
        var filtered = when (filterMode) {
            1 -> Items.list.filter { it.tag == null || it.tag!!.isBasicallyEmpty() }
            2 -> Items.list.filter { !it.hasCover }
            3 -> Items.list.filter { it.error != null }
            4 -> Items.list.filter { it.tag?.title.isNullOrBlank() }
            5 -> Items.list.filter { it.tag?.artist.isNullOrBlank() }
            6 -> Items.list.filter { it.tag?.year.isNullOrBlank() }
            7 -> Items.list.filter { it.tag?.lyrics.isNullOrBlank() }
            else -> Items.list
        }
        quickFilter?.let { q ->
            filtered = filtered.filter { it.tag?.artist == q || it.tag?.album == q }
        }
        val sorted = when (sortMode) {
            1 -> filtered.sortedBy { (it.tag?.title?.ifBlank { null } ?: it.name).lowercase() }
            2 -> filtered.sortedBy { (it.tag?.album?.ifBlank { null } ?: it.name).lowercase() }
            3 -> filtered.sortedBy { (it.tag?.artist?.ifBlank { null } ?: it.name).lowercase() }
            4 -> filtered.sortedByDescending { it.lastModified }
            else -> filtered.sortedBy { it.name.lowercase() }
        }
        val result = if (sortReverse) sorted.reversed() else sorted
        shownCache = result; shownCacheKey = key
        rowsCache = null
        return result
    }

    /**
     * Display rows for the adapter: when the songs come from more than
     * one folder, folder headers are interleaved (tap to collapse), so a
     * big tree stays navigable. Single-folder lists get no headers.
     */
    private fun displayRows(): List<Any> {
        // shownItems() first: it clears rowsCache when its inputs changed.
        // Checking rowsCache first returned the cached rows forever —
        // including the empty rows from startup, so a picked folder or
        // files never appeared in the list.
        val shown = shownItems()
        rowsCache?.let { return it }
        val folders = shown.map { it.folder }.distinct()
        val rows: List<Any> = if (folders.size <= 1) {
            shown.map { SongRow(it) }
        } else {
            val byFolder = LinkedHashMap<String, MutableList<Item>>()
            for (it in shown) byFolder.getOrPut(it.folder) { ArrayList() }.add(it)
            val out = ArrayList<Any>()
            for ((folder, items) in byFolder) {
                out.add(HeaderRow(folder, items.size))
                if (folder !in collapsedFolders) for (it in items) out.add(SongRow(it))
            }
            out
        }
        rowsCache = rows
        return rows
    }

    /** Loads the per-source on/off switches into the core search flags. */
    private fun loadSourcePrefs() {
        SearchSources.mb = prefs.getBoolean("src_mb", true)
        SearchSources.animeThemes = prefs.getBoolean("src_at", true)
        SearchSources.itunes = prefs.getBoolean("src_itunes", true)
        SearchSources.deezer = prefs.getBoolean("src_deezer", true)
        SearchSources.vgmdb = prefs.getBoolean("src_vgmdb", true)
        SearchSources.audius = prefs.getBoolean("src_audius", true)
        SearchSources.ndl = prefs.getBoolean("src_ndl", true)
    }

    /** Settings dialog: the cover size cap and the search source switches. */
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
        val sourceKeys = listOf(
            "src_mb" to "MusicBrainz", "src_at" to "AnimeThemes",
            "src_itunes" to "iTunes", "src_deezer" to "Deezer",
            "src_vgmdb" to "VGMdb", "src_audius" to "Audius", "src_ndl" to "NDL")
        val boxes = sourceKeys.map { (key, name) ->
            CheckBox(this).apply {
                text = name
                isChecked = prefs.getBoolean(key, true)
                layout.addView(this)
            }
        }
        layout.addView(TextView(this).apply { text = Strings.t("acoustid_key") })
        val acoustidInput = EditText(this).apply {
            setText(prefs.getString("acoustid_key", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        layout.addView(acoustidInput)
        val darkBox = CheckBox(this).apply {
            text = Strings.t("dark_mode")
            isChecked = Ui.dark
        }
        layout.addView(darkBox)
        val subBox = CheckBox(this).apply {
            text = Strings.t("include_subfolders")
            isChecked = prefs.getBoolean("subfolders", true)
        }
        layout.addView(subBox)
        val backupLabel = TextView(this).apply {
            text = "${Strings.t("backup")}: ${fmtSize(Backup.totalSize(SafStore.backupDir(this@MainActivity)))}"
        }
        layout.addView(backupLabel)
        layout.addView(Button(this).apply {
            text = Strings.t("delete_all"); Ui.styleButton(this, Ui.ORANGE)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(Strings.t("confirm_delete_all"))
                    .setPositiveButton(Strings.t("delete_all")) { _, _ ->
                        thread {
                            Backup.deleteAll(SafStore.backupDir(this@MainActivity))
                            runOnUiThread {
                                backupLabel.text = "${Strings.t("backup")}: " +
                                    fmtSize(Backup.totalSize(SafStore.backupDir(this@MainActivity)))
                            }
                        }
                    }
                    .setNegativeButton(Strings.t("cancel"), null)
                    .show()
            }
        })
        AlertDialog.Builder(this)
            .setTitle(Strings.t("settings"))
            .setView(layout)
            .setPositiveButton(Strings.t("ok")) { _, _ ->
                val e = prefs.edit()
                e.putInt("cover_cap", caps[spinner.selectedItemPosition])
                sourceKeys.forEachIndexed { i, (key, _) -> e.putBoolean(key, boxes[i].isChecked) }
                e.putString("acoustid_key", acoustidInput.text.toString().trim())
                val darkChanged = darkBox.isChecked != Ui.dark
                if (darkChanged) e.putBoolean("dark", darkBox.isChecked)
                e.putBoolean("subfolders", subBox.isChecked)
                e.apply()
                loadSourcePrefs()
                if (darkChanged) recreate()
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
        // Dark palette: the saved choice wins; until then follow the system.
        val systemDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        Ui.dark = if (prefs.contains("dark")) prefs.getBoolean("dark", false) else systemDark
        setTheme(if (Ui.dark) android.R.style.Theme_Material
            else android.R.style.Theme_Material_Light)
        ItunesClient.country = Locale.getDefault().country.ifBlank { "JP" }
        loadSourcePrefs()
        ThumbCache.init(this)
        // Housekeeping is deferred: startup stays light, and the prune +
        // previous-session copy cleanup run half a minute later instead.
        thread {
            try { Thread.sleep(30_000) } catch (e: Exception) { }
            try { Backup.prune(SafStore.backupDir(this)) } catch (e: Exception) { }
            SafStore.cleanupCache(this,
                Items.list.mapNotNull { it.local?.absolutePath }.toSet())
        }
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
            setBackgroundColor(Ui.CARD)
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

        // second header row: history / settings / help as equal buttons
        val headerBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Ui.CARD)
        }
        fun headBtn(label: String, color: Int, fn: () -> Unit) {
            headerBtns.addView(Button(this).apply {
                text = label
                textSize = 14f
                isSingleLine = true
                Ui.styleButton(this, color)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { fn() }
            })
        }
        headBtn(Strings.t("history"), Ui.TEAL) {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        headBtn(Strings.t("settings"), Ui.MAGENTA) { showSettings() }
        headBtn(Strings.t("help_title"), Ui.VIOLET) {
            AlertDialog.Builder(this)
                .setTitle(Strings.t("help_title"))
                .setMessage(Strings.t("help_body"))
                .setPositiveButton(Strings.t("ok"), null)
                .show()
        }
        root.addView(headerBtns)

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
        topBtn(Strings.t("choose_folder"), Ui.VIOLET) { pickFolderOrHistory() }
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
            listOf(Strings.t("filter_all"), Strings.t("filter_notag"), Strings.t("filter_nocover"),
                Strings.t("filter_error"), Strings.t("filter_no_title"), Strings.t("filter_no_artist"),
                Strings.t("filter_no_year"), Strings.t("filter_no_lyrics")))
        filterSpinner.setSelection(filterMode)
        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (pos != filterMode) { filterMode = pos; adapter.notifyDataSetChanged() }
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        filterSpinner.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        filterRow.addView(filterSpinner)
        val sortSpinner = Spinner(this)
        sortSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(Strings.t("sort_name"), Strings.t("sort_title"),
                Strings.t("sort_album"), Strings.t("sort_artist"), Strings.t("sort_updated")))
        sortSpinner.setSelection(sortMode)
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (pos != sortMode) { sortMode = pos; adapter.notifyDataSetChanged() }
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        filterRow.addView(TextView(this).apply {
            text = Strings.t("sort"); setTextColor(Ui.SUB); setPadding(dp(12), 0, 0, 0)
        })
        sortSpinner.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        filterRow.addView(sortSpinner)
        filterRow.addView(Button(this).apply {
            text = "↑↓"
            setOnClickListener {
                sortReverse = !sortReverse
                adapter.notifyDataSetChanged()
            }
        })
        filterRow.addView(Button(this).apply {
            text = Strings.t("back_to_top")
            setOnClickListener { listView.setSelection(0) }
        })
        root.addView(filterRow)

        // Quick-filter bar (set by tapping an artist/album in a row).
        quickBar = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(Ui.VIOLET)
            setPadding(dp(16), dp(4), dp(12), dp(4))
            setOnClickListener {
                quickFilter = null; updateQuickBar(); adapter.notifyDataSetChanged()
            }
        }
        root.addView(quickBar)

        adapter = ItemAdapter()
        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT)
            dividerHeight = dp(6)
            setBackgroundColor(Ui.BG)
            // Thumbnail decoding prioritizes the rows around this window.
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScroll(view: android.widget.AbsListView, first: Int,
                    visible: Int, total: Int) {
                    visibleCenter = first + visible / 2
                    ThumbCache.centerPos = visibleCenter
                }
                override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) {}
            })
        }
        root.addView(listView)

        // bottom action bar: five equal buttons, fixed height, same text size
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(2), dp(8))
            setBackgroundColor(Ui.CARD)
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
        barBtn(Strings.t("delete_file"), android.graphics.Color.rgb(190, 40, 40)) { deleteSelected() }
        root.addView(bar)

        setContentView(root)
        maybeAskInstallPermission()
        checkUpdate()

        prefs.getString("tree", null)?.let { uriStr ->
            loadTree(Uri.parse(uriStr))
        }
        // A share / open-with that launched the app replaces the restore.
        if (intent?.action == Intent.ACTION_SEND ||
            intent?.action == Intent.ACTION_SEND_MULTIPLE ||
            intent?.action == Intent.ACTION_VIEW) {
            Items.list.clear()
            adapter.notifyDataSetChanged()
            handleIncoming(intent)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Shows or hides the quick-filter bar to match [quickFilter]. */
    private fun updateQuickBar() {
        val q = quickFilter
        if (q == null) quickBar.visibility = View.GONE
        else { quickBar.visibility = View.VISIBLE; quickBar.text = "» $q ✕" }
    }

    private fun fmtSize(bytes: Long): String =
        if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else "${bytes / 1024} KB"

    private fun checkedItems(): List<Item> = Items.list.filter { it.checked }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private var lastEdited: Item? = null

    /** Deletes the checked files themselves (with a confirmation). */
    private fun deleteSelected() {
        val sel = checkedItems()
        if (sel.isEmpty()) { toast(Strings.t("select_some")); return }
        AlertDialog.Builder(this)
            .setTitle(Strings.t("delete_file"))
            .setMessage(Strings.t("confirm_delete") + " (${sel.size})")
            .setPositiveButton(Strings.t("delete_file")) { _, _ ->
                stopPlayer()
                thread {
                    var failed = 0
                    var lastErr: Exception? = null
                    for (item in sel) {
                        try {
                            val ok = DocumentsContract.deleteDocument(contentResolver, item.docUri)
                            if (!ok) throw IllegalStateException("deleteDocument returned false")
                            ThumbCache.forget(item)
                            Items.list.remove(item)
                        } catch (e: Exception) { failed++; lastErr = e }
                    }
                    runOnUiThread {
                        adapter.notifyDataSetChanged()
                        if (failed > 0) Ui.failDialog(this,
                            "${lastErr?.javaClass?.simpleName}: ${lastErr?.message} ($failed/${sel.size})")
                    }
                }
            }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

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
            // The search and the partial merge need the FULL tag (with
            // cover); the list itself only keeps the light copy.
            var fullTag: TagData? = null
            try {
                SafStore.ensureLocal(this, item)
                fullTag = TagService.read(item.local!!)
                item.setTagLight(fullTag)
            } catch (e: Exception) { item.error = e.message }
            runOnUiThread {
                val parsed = NameParser.parse(File(item.name))
                SearchFlow.start(
                    this, fullTag, parsed,
                    localFile = item.local,
                    progressLabel = "${idx + 1} / ${sel.size}\n${item.name}",
                    onSkip = { applyNext(sel, idx + 1) },
                    onStop = { adapter.notifyDataSetChanged() },
                    bulk = sel.size > 1
                ) { picked ->
                    // Partial sources (Audius / NDL) only fill what they know;
                    // keep the file's own values for everything else.
                    val merged = if (picked.partial)
                        picked.mergedOver(fullTag ?: TagData()) else picked
                    // The cover cap applies to covers the candidate brought;
                    // a cover the file already had is left as it is.
                    val newTag = if (picked.cover != null)
                        merged.copy(cover = SafStore.capCover(merged.cover,
                            prefs.getInt("cover_cap", 0)))
                    else merged
                    thread {
                        var ok = false
                        try {
                            SafStore.ensureLocal(this@MainActivity, item)
                            Backup.backup(item.local!!, SafStore.backupDir(this))
                            TagService.write(item.local!!, newTag)
                            SafStore.writeBack(this, item)
                            item.setTagLight(TagService.read(item.local!!))
                            item.error = null
                            if (!SafStore.verify(this, item, newTag.cover != null)) {
                                item.error = Strings.t("failed")
                            } else {
                                // applied via search: uncheck it so a re-run skips it
                                item.checked = false
                                ok = true
                                // The working copy has served its purpose.
                                SafStore.discardCopy(item)
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
        // The same offer with the track number stripped, when the
        // tag-based name carries one and the result differs.
        val strippedBase = Renamer.stripTrackPrefix(base)
        val strippedName = if (strippedBase != base) "$strippedBase.${item.ext}" else ""
        val hasStrip = strippedName.isNotBlank() && strippedName != item.name
        val msg = buildString {
            append(newTag.title)
            if (newTag.artist.isNotBlank()) append("\n${newTag.artist}")
            if (newTag.album.isNotBlank()) append("\n${newTag.album}")
            if (canRename) append("\n\n${Strings.t("ask_rename")}\n${item.name}\n→ $newName")
            if (canRename && hasStrip) append("\n→ $strippedName")
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
            if (hasStrip) {
                b.setNeutralButton(Strings.t("rename_no_num")) { _, _ ->
                    thread {
                        SafStore.rename(this, item, strippedName)
                        runOnUiThread { adapter.notifyDataSetChanged(); done() }
                    }
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
                            val cur = TagService.read(item.local!!)
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
                            item.setTagLight(TagService.read(item.local!!))
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
        val dlg = AlertDialog.Builder(this)
            .setTitle(Strings.t("rename"))
            .setView(scroll)
            .setNeutralButton(Strings.t("strip_num"), null)
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
            .create()
        dlg.show()
        // The strip button rewrites every field in place (a preview the
        // user can still edit) instead of closing the dialog.
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            for ((_, et) in fields)
                et.setText(Renamer.stripTrackPrefix(et.text.toString()))
        }
    }

    private fun undoSelected() {
        val sel = checkedItems()
        if (sel.isEmpty()) { toast(Strings.t("select_some")); return }
        AlertDialog.Builder(this)
            .setMessage(Strings.t("confirm_undo"))
            .setPositiveButton(Strings.t("undo")) { _, _ -> pickUndoGeneration(sel) }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

    /** With several backup generations, let the user choose which one. */
    private fun pickUndoGeneration(sel: List<Item>) {
        thread {
            val gens = sortedSetOf<Long>(reverseOrder())
            for (item in sel) {
                try {
                    SafStore.ensureLocal(this, item)
                    gens.addAll(Backup.generations(item.local!!, SafStore.backupDir(this)))
                } catch (e: Exception) { }
            }
            val list = gens.take(5)
            runOnUiThread {
                if (list.size <= 1) { doUndo(sel, null); return@runOnUiThread }
                val fmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                val labels = list.map { fmt.format(java.util.Date(it)) }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(Strings.t("pick_backup"))
                    .setItems(labels) { _, which -> doUndo(sel, list[which]) }
                    .setNegativeButton(Strings.t("cancel"), null)
                    .show()
            }
        }
    }

    private fun doUndo(sel: List<Item>, generation: Long?) {
        thread {
            var restored = 0
            for (item in sel) {
                try {
                    SafStore.ensureLocal(this, item)
                    val ok = if (generation == null)
                        Backup.restore(item.local!!, SafStore.backupDir(this))
                    else Backup.restoreAt(item.local!!, SafStore.backupDir(this), generation)
                    if (ok) {
                        SafStore.writeBack(this, item)
                        item.setTagLight(TagService.read(item.local!!))
                        item.checked = false // acted on: drop the check
                        SafStore.discardCopy(item)
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

    /** Recently opened folders (tree URIs, newest first, up to 5). */
    private fun recentTrees(): List<String> =
        (prefs.getString("recent_trees", "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun rememberTree(uri: Uri) {
        val rest = recentTrees().filter { it != uri.toString() }
        prefs.edit().putString("recent_trees",
            (listOf(uri.toString()) + rest).take(5).joinToString("\n")).apply()
    }

    /** A short display name for a tree URI (its folder name). */
    private fun treeLabel(uriStr: String): String = try {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(uriStr))
        id.substringAfterLast('/').ifBlank { id }
    } catch (e: Exception) { uriStr }

    /** Folders pinned by the user (tree URIs), kept apart from the history. */
    private fun pinnedTrees(): List<String> =
        (prefs.getString("pinned_trees", "") ?: "").split("\n").filter { it.isNotBlank() }

    /** Folder button: pinned folders first, then the recent ones. */
    private fun pickFolderOrHistory() {
        val pinned = pinnedTrees()
        val recent = recentTrees().filter { it !in pinned }
        if (pinned.isEmpty() && recent.isEmpty()) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
            return
        }
        val all = pinned + recent
        val labels = all.mapIndexed { i, uriStr ->
            (if (i < pinned.size) "★ " else "") + treeLabel(uriStr)
        } + Strings.t("pick_new_folder")
        AlertDialog.Builder(this)
            .setTitle(Strings.t("recent_folders"))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < all.size) {
                    val uri = Uri.parse(all[which])
                    prefs.edit().putString("tree", all[which]).apply()
                    rememberTree(uri)
                    loadTree(uri)
                } else {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
                }
            }
            .setNeutralButton(Strings.t("pin_folder")) { _, _ -> showPinToggle() }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

    /** Multi-choice dialog that pins/unpins folders from the known ones. */
    private fun showPinToggle() {
        val known = (pinnedTrees() + recentTrees()).distinct()
        if (known.isEmpty()) return
        val pinned = pinnedTrees().toMutableSet()
        val checked = known.map { it in pinned }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(Strings.t("pinned_folders"))
            .setMultiChoiceItems(known.map { treeLabel(it) }.toTypedArray(), checked) { _, which, on ->
                if (on) pinned.add(known[which]) else pinned.remove(known[which])
            }
            .setPositiveButton(Strings.t("ok")) { _, _ ->
                prefs.edit().putString("pinned_trees",
                    known.filter { it in pinned }.joinToString("\n")).apply()
            }
            .setNegativeButton(Strings.t("cancel"), null)
            .show()
    }

    /**
     * Files handed to us by another app (share sheet / open-with). The
     * grants are temporary, so the files are copied into the working
     * cache by the normal load path while the grant lasts.
     */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val u = if (android.os.Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                u?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (android.os.Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                list?.let { uris.addAll(it) }
            }
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
            else -> return
        }
        if (uris.isEmpty()) return
        loadFiles(uris.filter { u ->
            val n = queryName(u)
            n?.substringAfterLast('.', "")?.lowercase() in AUDIO_EXTS ||
                u.toString().substringAfterLast('.', "").lowercase() in AUDIO_EXTS
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun loadTree(treeUri: Uri) {
        stopPlayer()
        toast(Strings.t("working"))
        SafStore.includeSubfolders = prefs.getBoolean("subfolders", true)
        // A different folder: the search session caches belong to the old one.
        SearchFlow.onFolderChanged()
        collapsedFolders.clear()
        quickFilter = null
        runOnUiThread { if (::quickBar.isInitialized) updateQuickBar() }
        thread {
            val items = try { SafStore.enumerateTree(this, treeUri) } catch (e: Exception) { emptyList() }
            Items.list.clear(); Items.list.addAll(items)
            dataVersion++
            runOnUiThread { adapter.notifyDataSetChanged() }
            // Read tags in parallel so thumbnails and titles fill in
            // fast; the pool size follows the folder size (a handful of
            // files does not need six readers, a big tree wants them).
            val next = java.util.concurrent.atomic.AtomicInteger(0)
            val nWorkers = when { items.size < 20 -> 2; items.size <= 100 -> 4; else -> 6 }
            val workers = (1..nWorkers).map {
                thread {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= items.size) break
                        val item = items[i]
                        try {
                            SafStore.ensureLocal(this, item)
                            item.setTagLight(TagService.read(item.local!!))
                        } catch (e: Exception) { item.error = e.message }
                        dataVersion++
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
                item.lastModified = queryLastModified(uri)
                Items.list.add(item)
                try {
                    SafStore.ensureLocal(this, item)
                    item.setTagLight(TagService.read(item.local!!))
                } catch (e: Exception) { item.error = e.message }
                dataVersion++
                runOnUiThread { adapter.notifyDataSetChanged() }
            }
        }
    }

    private fun queryLastModified(uri: Uri): Long {
        try {
            contentResolver.query(uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (e: Exception) { }
        return 0
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
                rememberTree(uri)
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
                dataVersion++
                adapter.notifyDataSetChanged()
            }
        }
    }

    inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = displayRows().size
        override fun getItem(pos: Int) = displayRows()[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(pos: Int) =
            if (displayRows()[pos] is HeaderRow) 0 else 1

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val rowObj = displayRows()[pos]
            if (rowObj is HeaderRow) {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    textSize = 13f
                    setPadding(dp(16), dp(10), dp(12), dp(6))
                }
                val arrow = if (rowObj.folder in collapsedFolders) "\u25B8" else "\u25BE"
                tv.text = "$arrow ${rowObj.folder.ifBlank { "/" }} (${rowObj.count})"
                tv.setTextColor(Ui.VIOLET)
                tv.setOnClickListener {
                    if (rowObj.folder in collapsedFolders) collapsedFolders.remove(rowObj.folder)
                    else collapsedFolders.add(rowObj.folder)
                    rowsCache = null
                    notifyDataSetChanged()
                }
                return tv
            }
            val item = (rowObj as SongRow).item
            // Flat, recycled row: one horizontal layout per song (no
            // nested wrappers), rebound on reuse instead of rebuilt.
            val row: LinearLayout
            val h: SongHolder
            if (convertView is LinearLayout && convertView.tag is SongHolder) {
                row = convertView; h = convertView.tag as SongHolder
            } else {
                val cover = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                        marginEnd = dp(10)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                val title = TextView(this@MainActivity).apply {
                    textSize = 15f; setTextColor(Ui.INK)
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val sub = TextView(this@MainActivity).apply {
                    textSize = 12f
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val texts = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(title); addView(sub)
                }
                val cb = CheckBox(this@MainActivity)
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Ui.card(this@MainActivity)
                    setPadding(dp(10), dp(8), dp(6), dp(8))
                    minimumHeight = dp(68)
                    addView(cover); addView(texts); addView(cb)
                }
                h = SongHolder(cover, title, sub, cb)
                row.tag = h
            }
            h.title.text = item.name
            bindSub(h.sub, item)
            h.cb.setOnCheckedChangeListener(null)
            h.cb.isChecked = item.checked
            h.cb.setOnCheckedChangeListener { _, v -> item.checked = v }
            h.cover.setOnClickListener { togglePlay(item) }
            row.setOnClickListener { togglePlay(item) }
            if (item == playingItem && player != null) {
                h.cover.setImageResource(android.R.drawable.ic_media_pause)
                h.cover.background = Ui.coverPlaceholder(this@MainActivity)
            } else {
                h.cover.background = null
                val bmp = item.peekThumbnail()
                if (bmp != null) h.cover.setImageBitmap(bmp)
                else {
                    h.cover.setImageBitmap(Ui.noCoverBitmap())
                    if (item.hasCover && !item.thumbLoading) {
                        item.thumbLoading = true
                        enqueueThumb(pos) {
                            try { item.thumbnail() } catch (e: Exception) { }
                            item.thumbLoading = false
                            runOnUiThread { adapter.notifyDataSetChanged() }
                        }
                    }
                }
            }
            return row
        }

        /**
         * The row's second line: "title — artist · album", with badges
         * for a sidecar .lrc ([LRC]) and a non-square cover (▭), and the
         * artist / album tappable to quick-filter the list by that value.
         */
        private fun bindSub(sub: TextView, item: Item) {
            if (item.error != null) {
                sub.text = "${Strings.t("failed")}: ${item.error}"
                sub.setTextColor(0xFFD32F2F.toInt())
                sub.movementMethod = null
                return
            }
            val tag = item.tag
            val badges = StringBuilder()
            if (item.hasLrc) badges.append(" [LRC]")
            if (item.hasCover && tag != null && tag.coverW > 0 && tag.coverW != tag.coverH)
                badges.append(" \u25AD")
            if (tag == null || tag.isBasicallyEmpty()) {
                sub.text = "\u2014" + badges.toString()
                sub.setTextColor(Ui.SUB)
                sub.movementMethod = null
                return
            }
            val titlePart = tag.title.ifBlank { "\u2014" }
            val full = buildString {
                append(titlePart)
                if (tag.artist.isNotBlank()) append(" \u2014 ").append(tag.artist)
                if (tag.album.isNotBlank()) append(" \u00B7 ").append(tag.album)
                append(badges)
            }
            val span = android.text.SpannableString(full)
            fun link(value: String) {
                if (value.isBlank()) return
                val startAt = full.indexOf(value, titlePart.length)
                if (startAt < 0) return
                span.setSpan(object : android.text.style.ClickableSpan() {
                    override fun onClick(w: View) {
                        quickFilter = value; updateQuickBar(); notifyDataSetChanged()
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.color = Ui.VIOLET; ds.isUnderlineText = false
                    }
                }, startAt, startAt + value.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            link(tag.artist); link(tag.album)
            sub.text = span
            sub.setTextColor(Ui.SUB)
            sub.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
    }

    companion object {
        const val REQ_TREE = 1
        const val REQ_FILES = 2
        const val REQ_IMAGE = 3
        const val REQ_EDIT = 4
    }
}
