package com.yyoo.ototag.desktop

import com.yyoo.ototag.core.*
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Locale
import java.util.Properties
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.table.AbstractTableModel
import kotlin.concurrent.thread

/** Dark palette switch (settings prop "dark"; applies from startup). */
var darkMode = false

/** Fully transparent icon for folder header rows. */
val emptyIcon: ImageIcon by lazy {
    ImageIcon(java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_ARGB))
}

/** "No image" placeholder icon for rows without cover art. */
val noCoverIcon: ImageIcon by lazy {
    val img = java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = if (darkMode) java.awt.Color(46, 39, 80) else java.awt.Color(233, 229, 242)
    g.fillRect(0, 0, 40, 40)
    g.color = if (darkMode) java.awt.Color(122, 111, 163) else java.awt.Color(167, 155, 203)
    g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 24)
    val fm = g.fontMetrics; val s = "♪"
    g.drawString(s, (40 - fm.stringWidth(s)) / 2, (40 - fm.height) / 2 + fm.ascent)
    g.dispose()
    ImageIcon(img)
}

class Row(val file: File) {
    /** Display name of the containing folder (for the grouped list). */
    val folder: String get() = file.parentFile?.name ?: ""
    /** A sibling "<name>.lrc" file exists next to this song. */
    val hasLrc: Boolean by lazy {
        File(file.parentFile, file.nameWithoutExtension + ".lrc").isFile
    }
    var checked = false
    var tag: TagData? = null
    var status: String = ""
    /** True when the last operation on this file failed. */
    var failed: Boolean = false
    /** Whether the file has cover art (the bytes themselves are not kept). */
    var hasCover: Boolean = false
    private var coverKey: Int = 0
    private var icon: ImageIcon? = null
    private var iconFor: Int = 0

    /**
     * Adopts a freshly read tag for list display, dropping the (often
     * megabytes large) cover bytes from memory. Flows that really need
     * the full tag re-read it from the file.
     */
    fun setTagLight(full: TagData?) {
        if (full == null) {
            tag = null; hasCover = false; coverKey = 0; icon = null; iconFor = 0
            return
        }
        hasCover = full.cover != null
        coverKey = full.cover?.contentHashCode() ?: 0
        tag = full.copy(cover = null)
        if (iconFor != coverKey) { icon = null; iconFor = 0 }
    }

    /** Decodes the 40px list icon from cover bytes already in hand. */
    fun primeIcon(cover: ByteArray) {
        iconFor = coverKey
        icon = decodeIcon(cover)
        touchIcon(this)
    }

    companion object {
        /**
         * Bounds how many decoded list icons stay in memory. The cap
         * follows the heap the JVM may use, and beyond it the icons of
         * the rows farthest from the visible window are dropped first
         * (they decode again when scrolled back to).
         */
        private val ICON_CAP: Int =
            when {
                Runtime.getRuntime().maxMemory() >= 512L * 1024 * 1024 -> 64
                Runtime.getRuntime().maxMemory() >= 256L * 1024 * 1024 -> 48
                else -> 32
            }
        private val iconLru = LinkedHashMap<Row, Unit>(64)

        @Synchronized fun touchIcon(row: Row) {
            iconLru.remove(row); iconLru[row] = Unit
            if (iconLru.size <= ICON_CAP) return
            val order = shownRows()
            val pos = HashMap<Row, Int>(order.size)
            order.forEachIndexed { i, r -> pos[r] = i }
            while (iconLru.size > ICON_CAP) {
                var victim: Row? = null
                var victimDist = -1
                for (k in iconLru.keys) {
                    if (k === row) continue
                    val idx = pos[k]
                    val dist = if (idx == null) Int.MAX_VALUE
                        else kotlin.math.abs(idx - visibleCenterRow)
                    if (dist > victimDist) { victimDist = dist; victim = k }
                }
                val v = victim ?: break
                iconLru.remove(v)
                v.icon = null; v.iconFor = 0
            }
        }
    }

    private fun decodeIcon(cover: ByteArray): ImageIcon? = try {
        // Subsampled decode: a 40px icon must not decode a 3000px cover.
        val iis = ImageIO.createImageInputStream(cover.inputStream())
        val readers = ImageIO.getImageReaders(iis)
        if (!readers.hasNext()) null
        else {
            val reader = readers.next()
            reader.input = iis
            val w = reader.getWidth(0); val hh = reader.getHeight(0)
            var sub = 1
            while (w / sub > 80 || hh / sub > 80) sub *= 2
            val param = reader.defaultReadParam
            if (sub > 1) param.setSourceSubsampling(sub, sub, 0, 0)
            val img = reader.read(0, param)
            reader.dispose()
            ImageIcon(img.getScaledInstance(40, 40, Image.SCALE_SMOOTH))
        }
    } catch (e: Exception) { null }

    fun coverIcon(): ImageIcon? {
        if (!hasCover) { icon = null; iconFor = 0; return null }
        if (icon != null && iconFor == coverKey) { touchIcon(this); return icon }
        val cover = tag?.cover ?: try { TagService.read(file).cover }
            catch (e: Exception) { null } ?: run { icon = null; iconFor = 0; return null }
        iconFor = coverKey
        icon = decodeIcon(cover)
        touchIcon(this)
        return icon
    }
}

val rows = mutableListOf<Row>()

/** List filter: 0 = all, 1 = no tags, 2 = no cover. */
var filterMode = 0

/** List sort: 0 = file name, 1 = title, 2 = album, 3 = artist. */
var sortMode = 0

/** Sort direction flip (the ↑↓ button next to the sort box). */
var sortReverse = false

/** Quick filter set by clicking an artist/album cell; null = off. */
var quickFilter: String? = null
lateinit var quickBar: JLabel

/** Centre of the visible table window, for icon eviction. */
@Volatile var visibleCenterRow: Int = 0

/** Bumped by refresh(); keys the shown/display list caches. */
private var listVersion = 0
// Only the newest search may touch the screen: a superseded search
// (its file skipped, the next one already searching) must not open a
// chooser, append candidates into the new chooser, or fire callbacks.
private val searchGen = java.util.concurrent.atomic.AtomicInteger(0)
private var shownCache: List<Row>? = null
private var shownCacheKey = ""
private var displayCache: List<Any>? = null
private var displayCacheKey = ""

/** Folders currently collapsed in the grouped list. */
val collapsedFolders = HashSet<String>()

/** One display row: either a folder header (String) or a song (Row). */

/**
 * The rows the table shows, after the filter and sort. Memoized: the
 * table model asks constantly while painting, and the filter/sort only
 * runs again when an input actually changed.
 */
fun shownRows(): List<Row> {
    val key = "$filterMode|$sortMode|$sortReverse|$quickFilter|$listVersion|${rows.size}"
    shownCache?.let { if (shownCacheKey == key) return it }
    var filtered = when (filterMode) {
        1 -> rows.filter { it.tag == null || it.tag!!.isBasicallyEmpty() }
        2 -> rows.filter { !it.hasCover }
        3 -> rows.filter { it.failed }
        4 -> rows.filter { it.tag?.title.isNullOrBlank() }
        5 -> rows.filter { it.tag?.artist.isNullOrBlank() }
        6 -> rows.filter { it.tag?.year.isNullOrBlank() }
        7 -> rows.filter { it.tag?.lyrics.isNullOrBlank() }
        else -> rows
    }
    quickFilter?.let { q ->
        filtered = filtered.filter { it.tag?.artist == q || it.tag?.album == q }
    }
    val sorted = when (sortMode) {
        1 -> filtered.sortedBy { (it.tag?.title?.ifBlank { null } ?: it.file.name).lowercase() }
        2 -> filtered.sortedBy { (it.tag?.album?.ifBlank { null } ?: it.file.name).lowercase() }
        3 -> filtered.sortedBy { (it.tag?.artist?.ifBlank { null } ?: it.file.name).lowercase() }
        4 -> filtered.sortedByDescending { it.file.lastModified() }
        else -> filtered.sortedBy { it.file.name.lowercase() }
    }
    val result = if (sortReverse) sorted.reversed() else sorted
    shownCache = result; shownCacheKey = key
    displayCache = null
    return result
}

/**
 * Display rows for the table model: when the songs come from more than
 * one folder, folder headers are interleaved (click to collapse).
 */
fun displayRows(): List<Any> {
    // shownRows() first: it recomputes shownCacheKey when the data
    // changed. Keying the display cache on the not-yet-refreshed key
    // returned the previous display forever — after startup that was
    // the empty table, so an opened folder never appeared.
    val shown = shownRows()
    val dkey = shownCacheKey + "|" + collapsedFolders.hashCode()
    displayCache?.let { if (displayCacheKey == dkey) return it }
    val folders = shown.map { it.folder }.distinct()
    val out: List<Any> = if (folders.size <= 1) shown
    else {
        val byFolder = LinkedHashMap<String, MutableList<Row>>()
        for (r in shown) byFolder.getOrPut(r.folder) { ArrayList() }.add(r)
        val list = ArrayList<Any>()
        for ((folder, items) in byFolder) {
            list.add(folder as Any)
            if (folder !in collapsedFolders) list.addAll(items)
        }
        list
    }
    displayCache = out; displayCacheKey = dkey
    return out
}

/** The embedded-cover size cap from the settings (0 = no limit, the default). */
fun coverCapPx(): Int = loadProps().getProperty("cover_cap")?.toIntOrNull() ?: 0

/**
 * Downscales cover bytes so the long edge is at most [cap] px and
 * re-encodes as JPEG. A cap of 0 (or a cover already small enough)
 * returns the bytes unchanged.
 */
fun capCoverBytes(bytes: ByteArray?, cap: Int): ByteArray? {
    if (bytes == null || cap <= 0) return bytes
    val img = try { ImageIO.read(bytes.inputStream()) } catch (e: Exception) { null }
        ?: return bytes
    val maxDim = maxOf(img.width, img.height)
    if (maxDim <= cap) return bytes
    val ratio = cap.toDouble() / maxDim
    val w = (img.width * ratio).toInt().coerceAtLeast(1)
    val h = (img.height * ratio).toInt().coerceAtLeast(1)
    val scaled = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = scaled.createGraphics()
    g.drawImage(img.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null)
    g.dispose()
    val out = java.io.ByteArrayOutputStream()
    ImageIO.write(scaled, "jpg", out)
    return out.toByteArray()
}
val backupDir: File by lazy {
    val base = System.getenv("LOCALAPPDATA")?.let { File(it, "OtoTag") }
        ?: File(System.getProperty("user.home"), ".ototag")
    File(base, "backups").apply { mkdirs() }
}
val propsFile: File by lazy {
    val base = System.getenv("LOCALAPPDATA")?.let { File(it, "OtoTag") }
        ?: File(System.getProperty("user.home"), ".ototag")
    base.mkdirs(); File(base, "settings.properties")
}

fun loadProps(): Properties {
    val p = Properties()
    if (propsFile.exists()) propsFile.inputStream().use { p.load(it) }
    return p
}

fun saveProp(key: String, value: String) {
    val p = loadProps(); p.setProperty(key, value)
    propsFile.outputStream().use { p.store(it, "OtoTag") }
}

lateinit var frame: JFrame
lateinit var table: JTable
lateinit var model: RowModel

class RowModel : AbstractTableModel() {
    override fun getRowCount() = displayRows().size
    override fun getColumnCount() = 7
    override fun getColumnName(c: Int) = when (c) {
        2 -> Strings.t("filename"); 3 -> Strings.t("title"); 4 -> Strings.t("artist")
        5 -> Strings.t("album"); else -> ""
    }
    override fun getColumnClass(c: Int) = when (c) {
        0 -> java.lang.Boolean::class.java
        1 -> ImageIcon::class.java
        else -> String::class.java
    }
    override fun isCellEditable(r: Int, c: Int) =
        c == 0 && displayRows()[r] is Row
    override fun getValueAt(r: Int, c: Int): Any {
        val disp = displayRows()[r]
        if (disp is String) {
            // Folder header row.
            val count = shownRows().count { it.folder == disp }
            val arrow = if (disp in collapsedFolders) "\u25B8" else "\u25BE"
            return when (c) {
                0 -> false
                1 -> emptyIcon
                2 -> "$arrow ${disp.ifBlank { "/" }} ($count)"
                else -> ""
            }
        }
        val row = disp as Row
        return when (c) {
            0 -> row.checked
            1 -> row.coverIcon() ?: noCoverIcon
            2 -> row.file.name + badgesOf(row)
            3 -> row.tag?.title ?: ""
            4 -> row.tag?.artist ?: ""
            5 -> row.tag?.album ?: ""
            else -> row.status
        }
    }
    override fun setValueAt(v: Any?, r: Int, c: Int) {
        val disp = displayRows()[r]
        if (c == 0 && disp is Row) {
            disp.checked = v as Boolean; fireTableCellUpdated(r, c)
            updateSelectAllLabelWin()
        }
    }
}

/** Row badges: [LRC] for a sidecar lyrics file, \u25AD for a non-square cover. */
fun badgesOf(row: Row): String {
    val sb = StringBuilder()
    if (row.hasLrc) sb.append(" [LRC]")
    val t = row.tag
    if (row.hasCover && t != null && t.coverW > 0 && t.coverW != t.coverH) sb.append(" \u25AD")
    return sb.toString()
}

fun refresh() {
    listVersion++
    SwingUtilities.invokeLater { model.fireTableDataChanged() }
    updateSelectAllLabelWin()
}

/** Shows or hides the quick-filter bar to match [quickFilter]. */
fun updateQuickBar() {
    if (!::quickBar.isInitialized) return
    val q = quickFilter
    if (q == null) quickBar.isVisible = false
    else { quickBar.text = "\u00BB $q \u2715"; quickBar.isVisible = true }
    quickBar.parent?.revalidate()
}

fun addFiles(files: List<File>, startChecked: Boolean = false) {
    thread {
        val includeSubs = loadProps().getProperty("subfolders")
            ?.toBooleanStrictOrNull() ?: true
        val found = mutableListOf<File>()
        for (f in files) {
            if (f.isDirectory && !includeSubs) {
                // Direct children only; non-audio names are dropped by
                // the extension check before any file work happens.
                f.listFiles()?.forEach { if (it.isAudio()) found.add(it) }
            } else collectAudio(f, found)
        }
        val sorted = found.sortedBy { it.name.lowercase() }
        val primed = java.util.concurrent.atomic.AtomicInteger(0)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        // The reader pool follows the folder size, like the Android list.
        val nWorkers = when { sorted.size < 20 -> 2; sorted.size <= 100 -> 4; else -> 6 }
        val workers = (1..nWorkers).map {
            thread {
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= sorted.size) break
                    val f = sorted[i]
                    synchronized(rows) {
                        if (rows.any { it.file.absolutePath == f.absolutePath }) continue
                    }
                    // Files added one by one start out checked; folder loads do not.
                    val row = Row(f).apply { checked = startChecked }
                    try {
                        val full = TagService.read(f)
                        row.setTagLight(full)
                        // Prime only the first screenful of icons; the
                        // rest decode on demand when scrolled into view.
                        if (full.cover != null && primed.getAndIncrement() < 24)
                            row.primeIcon(full.cover!!)
                    } catch (e: Exception) { row.status = e.message ?: ""; row.failed = true }
                    synchronized(rows) { rows.add(row) }
                }
            }
        }
        workers.forEach { it.join() }
        refresh()
    }
}

fun checkedRows(): List<Row> = rows.filter { it.checked }

fun fmtSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else "${bytes / 1024} KB"

/** Recently opened folders (absolute paths, newest first, up to 5). */
fun recentDirs(): List<String> =
    (loadProps().getProperty("recent_dirs") ?: "").split("\n").filter { it.isNotBlank() }

fun rememberDir(dir: File) {
    val rest = recentDirs().filter { it != dir.absolutePath }
    saveProp("recent_dirs", (listOf(dir.absolutePath) + rest).take(5).joinToString("\n"))
}

/** Folders pinned by the user (absolute paths), kept apart from the history. */
fun pinnedDirs(): List<String> =
    (loadProps().getProperty("pinned_dirs") ?: "").split("\n").filter { it.isNotBlank() }

/** Pin/unpin dialog over the known folders (pinned + recent). */
fun showPinToggle() {
    val known = (pinnedDirs() + recentDirs()).distinct()
        .map { File(it) }.filter { it.isDirectory }
    if (known.isEmpty()) return
    val pinned = pinnedDirs().toMutableSet()
    val checks = known.map { dir ->
        JCheckBox(dir.absolutePath, dir.absolutePath in pinned)
    }
    val panel = JPanel(GridLayout(checks.size, 1))
    checks.forEach { panel.add(it) }
    val res = JOptionPane.showConfirmDialog(frame, panel, Strings.t("pinned_folders"),
        JOptionPane.OK_CANCEL_OPTION)
    if (res == JOptionPane.OK_OPTION) {
        val now = known.filterIndexed { i, _ -> checks[i].isSelected }
            .map { it.absolutePath }
        saveProp("pinned_dirs", now.joinToString("\n"))
    }
}

fun openFolder(dir: File) {
    // A different folder: the search session cache belongs to the old one.
    SearchCache.clear()
    collapsedFolders.clear()
    rememberDir(dir)
    addFiles(listOf(dir))
}

/** Folder button: pinned folders first, then the recent ones. */
fun pickFolderOrHistory() {
    val pinned = pinnedDirs().map { File(it) }.filter { it.isDirectory }
    val recent = recentDirs().map { File(it) }.filter { it.isDirectory }
        .filter { it.absolutePath !in pinnedDirs() }
    if (pinned.isEmpty() && recent.isEmpty()) {
        val fc = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) openFolder(fc.selectedFile)
        return
    }
    val all = pinned + recent
    val labels = all.mapIndexed { i, dir ->
        (if (i < pinned.size) "\u2605 " else "") + dir.absolutePath
    } + listOf(Strings.t("pick_new_folder"), Strings.t("pin_folder"))
    val pick = JOptionPane.showInputDialog(frame, Strings.t("recent_folders"),
        Strings.t("choose_folder"), JOptionPane.PLAIN_MESSAGE, null,
        labels.toTypedArray(), labels[0]) as? String ?: return
    when {
        pick == Strings.t("pick_new_folder") -> {
            val fc = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) openFolder(fc.selectedFile)
        }
        pick == Strings.t("pin_folder") -> showPinToggle()
        else -> openFolder(File(pick.removePrefix("\u2605 ")))
    }
}

fun info(msg: String) = SwingUtilities.invokeLater {
    JOptionPane.showMessageDialog(frame, msg, "OtoTag", JOptionPane.INFORMATION_MESSAGE)
}

/** Centred failure dialog: a clear "failed" title plus the error details. */
fun fail(details: String) = SwingUtilities.invokeLater {
    JOptionPane.showMessageDialog(frame, details, Strings.t("failed_title"),
        JOptionPane.ERROR_MESSAGE)
}

/** Non-modal progress dialog that stays up until the flow dismisses it. */
fun progressDialog(message: String, label: String?, onCancel: (() -> Unit)?): JDialog {
    val dlg = JDialog(frame, "OtoTag", false)
    val panel = JPanel(BorderLayout(8, 8)).apply {
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(JLabel(if (label != null) "$message  $label" else message), BorderLayout.NORTH)
        add(JProgressBar().apply { isIndeterminate = true }, BorderLayout.CENTER)
    }
    if (onCancel != null) {
        panel.add(JButton(Strings.t("cancel")).apply {
            addActionListener { dlg.dispose(); onCancel() }
        }, BorderLayout.SOUTH)
    }
    dlg.contentPane = panel
    dlg.setSize(400, 140)
    dlg.setLocationRelativeTo(frame)
    dlg.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
    return dlg
}

/** Fingerprint via the bundled fpcalc.exe (placed next to the jar). */
fun fingerprintViaFpcalc(file: File): Pair<String, Int>? {
    val jarDir = try {
        File(object {}.javaClass.protectionDomain.codeSource.location.toURI()).parentFile
    } catch (e: Exception) { null }
    val exe = listOfNotNull(jarDir?.let { File(it, "fpcalc.exe") }, File("fpcalc.exe"))
        .firstOrNull { it.isFile } ?: return null
    return try {
        val proc = ProcessBuilder(exe.absolutePath, "-json", file.absolutePath)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly(); return null
        }
        val root = JsonParser(out).parse().asObj()
        val fp = root["fingerprint"].asStr()
        val dur = (root["duration"] as? Double)?.toInt() ?: 0
        if (fp.isBlank()) null else fp to dur
    } catch (e: Exception) { null }
}

/** Candidate chooser shared by search-apply and the edit dialog. */
fun searchAndPick(
    tag: TagData?, parsed: ParsedName?, localFile: File? = null, progressLabel: String? = null,
    onSkip: (() -> Unit)? = null, onStop: (() -> Unit)? = null,
    bulk: Boolean = false,
    presetQuery: String? = null,
    onPicked: (TagData) -> Unit
) {
    // The user can type / edit the search words before the search runs.
    val autoQuery = SearchText.normalizeQuery(listOfNotNull(
        tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title,
        tag?.artist?.takeIf { it.isNotBlank() } ?: parsed?.artist
    ).joinToString(" "))
    val query: String
    val auto: Boolean
    var detail: Pair<String, String>? = null
    if (presetQuery != null) {
        // A narrowed re-search (title only / artist only): no prompt.
        query = SearchText.normalizeQuery(presetQuery)
        auto = false
    } else {
        val qField = JTextField(autoQuery, 34)
        val dTitle = JTextField(34).apply { isEnabled = false }
        val dArtist = JTextField(34).apply { isEnabled = false }
        val dBox = JCheckBox(Strings.t("detailed_search")).apply {
            addActionListener {
                dTitle.isEnabled = isSelected; dArtist.isEnabled = isSelected
            }
        }
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(qField); add(dBox)
            add(JPanel(BorderLayout(6, 0)).apply {
                add(JLabel(Strings.t("title")), BorderLayout.WEST); add(dTitle, BorderLayout.CENTER)
            })
            add(JPanel(BorderLayout(6, 0)).apply {
                add(JLabel(Strings.t("artist")), BorderLayout.WEST); add(dArtist, BorderLayout.CENTER)
            })
        }
        val res = JOptionPane.showConfirmDialog(frame, panel, Strings.t("enter_query"),
            JOptionPane.OK_CANCEL_OPTION)
        if (res != JOptionPane.OK_OPTION) { onStop?.invoke(); return }
        if (dBox.isSelected) {
            val dt = SearchText.normalizeQuery(dTitle.text)
            val da = SearchText.normalizeQuery(dArtist.text)
            if (dt.isBlank() && da.isBlank()) { onStop?.invoke(); return }
            detail = dt to da
            query = listOf(dt, da).filter { it.isNotBlank() }.joinToString(" ")
            auto = false
        } else {
            // Width/case differences in the typed words are folded away.
            val typed = SearchText.normalizeQuery(qField.text)
            if (typed.isBlank()) { onStop?.invoke(); return }
            query = typed
            auto = query == autoQuery
        }
    }
    val trackKey = localFile?.name
        ?: ((tag?.title ?: parsed?.title ?: "") + "|" + (tag?.artist ?: parsed?.artist ?: ""))
    val myGen = searchGen.incrementAndGet()
    val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    var progress: JDialog? = null // only touched on the EDT (creation and disposal are both invokeLater)
    SwingUtilities.invokeLater {
        progress = progressDialog(Strings.t("searching"), progressLabel) {
            stopped.set(true); onStop?.invoke()
        }.also { it.isVisible = true }
    }
    class DlgOpt(val title: String, var sub: String, val iconUrl: String?,
                 val key: String, val build: () -> TagData)
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    /** Builds the chooser rows for whichever sources are passed in. */
    fun buildOpts(
        mb: List<MbCandidate> = emptyList(), fp: List<MbCandidate> = emptyList(),
        at: List<AtSong> = emptyList(), itunes: List<ItunesTrack> = emptyList(),
        deezer: List<DeezerTrack> = emptyList(), vgmdb: List<VgmdbTrack> = emptyList(),
        audius: List<AudiusTrack> = emptyList(), ndl: List<NdlRecord> = emptyList()
    ): MutableList<DlgOpt> {
        val options = ArrayList<DlgOpt>()
        // Fingerprint matches first: they identify this exact recording.
        for (c in fp) {
            if (c.releases.isEmpty()) {
                options.add(DlgOpt(c.title, "${c.artist} · AcoustID", null, "fp|${c.recordingId}|") {
                    TagData(title = c.title, artist = c.artist, mbRecordingId = c.recordingId)
                })
            }
            for (r in c.releases) {
                val meta = listOf(r.date.take(4), r.country).filter { it.isNotBlank() }.joinToString(", ")
                options.add(DlgOpt(c.title, "${c.artist} — ${r.title} ($meta) · AcoustID",
                    MbClient.coverUrl(r.id).replace("front-250", "front-120"), "fp|${c.recordingId}|${r.id}") {
                    MbClient.buildTag(c, r)
                })
            }
        }
        for (c in mb) {
            if (c.releases.isEmpty()) {
                options.add(DlgOpt(c.title, c.artist, null, "mb|${c.recordingId}|") {
                    TagData(title = c.title, artist = c.artist, mbRecordingId = c.recordingId)
                })
            }
            for (r in c.releases) {
                val meta = listOf(r.date.take(4), r.country).filter { it.isNotBlank() }.joinToString(", ")
                options.add(DlgOpt(c.title, "${c.artist} — ${r.title} ($meta)",
                    MbClient.coverUrl(r.id).replace("front-250", "front-120"), "mb|${c.recordingId}|${r.id}") {
                    MbClient.buildTag(c, r)
                })
            }
        }
        for (s in at) {
            val animePart = when {
                s.anime.isBlank() -> ""
                s.year.isNotBlank() -> " — ${s.anime} (${s.year})"
                else -> " — ${s.anime}"
            }
            val labelPart = if (s.themeLabel.isNotBlank()) " · ${s.themeLabel}" else ""
            options.add(DlgOpt(s.title, "${s.artist}$animePart$labelPart · AnimeThemes", null, "at|${s.title}|${s.artist}|${s.anime}") {
                s.toTag()
            })
        }
        for (t in itunes) {
            val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
            options.add(DlgOpt(t.title, "${t.artist} — ${t.album}$yr · iTunes",
                t.artworkUrl.ifBlank { null }?.replace("600x600bb.jpg", "100x100bb.jpg"), "itunes|${t.title}|${t.artist}|${t.album}") {
                ItunesClient.buildTag(t)
            })
        }
        for (t in deezer) {
            options.add(DlgOpt(t.title, "${t.artist} — ${t.album} · Deezer",
                t.coverUrl.ifBlank { null }?.replace("1000x1000", "150x150"), "deezer|${t.title}|${t.artist}|${t.album}") {
                DeezerClient.buildTag(t)
            })
        }
        for (t in vgmdb) {
            val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
            options.add(DlgOpt(t.title, "${t.artist} — ${t.album}$yr · VGMdb",
                t.coverThumbUrl.ifBlank { null }, "vgmdb|${t.title}|${t.artist}|${t.album}") {
                VgmdbClient.buildTag(t)
            })
        }
        for (t in audius) {
            val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
            options.add(DlgOpt(t.title, "${t.artist}$yr · Audius",
                t.coverThumbUrl.ifBlank { null }, "audius|${t.title}|${t.artist}") {
                AudiusClient.buildTag(t)
            })
        }
        for (r in ndl) {
            val yr = if (r.year.isNotBlank()) " (${r.year})" else ""
            options.add(DlgOpt(r.album, "${r.artist}$yr · NDL", null, "ndl|${r.album}|${r.artist}") {
                NdlClient.buildTag(r)
            })
        }
        return options
    }
    var chooserAppend: ((List<DlgOpt>) -> Unit)? = null
    /** Opens the candidate chooser; later sources append via chooserAppend. */
    fun openChooser(initial: MutableList<DlgOpt>) {
        // A candidate chosen for this same file before is offered first.
        if (trackKey.isNotBlank()) {
            val remembered = (loadProps().getProperty("cand_memory") ?: "")
                .split("\n").firstOrNull { it.startsWith(trackKey + "\t") }
                ?.substringAfter("\t")
            if (remembered != null) {
                val idx = initial.indexOfFirst { it.key == remembered }
                if (idx >= 0) {
                    val o = initial.removeAt(idx)
                    o.sub = o.sub + " · " + Strings.t("prev_choice")
                    initial.add(0, o)
                }
            }
        }
        val model = javax.swing.DefaultListModel<DlgOpt>()
        initial.forEach { model.addElement(it) }
        SwingUtilities.invokeLater {
            progress?.dispose()
            // Cover thumbnails load in the background; rows repaint as they land.
            val iconCache = java.util.Collections.synchronizedMap(HashMap<String, ImageIcon>())
            val iconLoading = java.util.Collections.synchronizedSet(HashSet<String>())
            val list = JList(model).apply {
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                if (model.size > 0) selectedIndex = 0
            }
            fun loadIcon(url: String) {
                if (iconCache.containsKey(url) || !iconLoading.add(url)) return
                thread {
                    try {
                        val img = javax.imageio.ImageIO.read(java.net.URL(url))
                        if (img != null) {
                            iconCache[url] = ImageIcon(
                                img.getScaledInstance(48, 48, java.awt.Image.SCALE_SMOOTH))
                            SwingUtilities.invokeLater { list.repaint() }
                        }
                    } catch (e: Exception) { }
                    iconLoading.remove(url)
                }
            }
            list.cellRenderer = object : ListCellRenderer<DlgOpt> {
                override fun getListCellRendererComponent(
                    l: JList<out DlgOpt>, value: DlgOpt, index: Int,
                    selected: Boolean, focus: Boolean
                ): java.awt.Component {
                    val iconLabel = JLabel().apply {
                        preferredSize = java.awt.Dimension(48, 48)
                        val u = value.iconUrl
                        if (u != null) {
                            val ic = iconCache[u]
                            if (ic != null) icon = ic else loadIcon(u)
                        }
                    }
                    // Candidates without any cover art say so on the row.
                    val subShown = if (value.iconUrl == null)
                        value.sub + " · " + Strings.t("cand_no_cover") else value.sub
                    val text = JLabel(
                        "<html><div style='width:440px'><b>${esc(value.title)}</b><br>" +
                            "<font color='${if (darkMode) "#B8B0D0" else "#666666"}'>${esc(subShown)}</font></div></html>")
                    return JPanel(BorderLayout(8, 0)).apply {
                        border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
                        add(iconLabel, BorderLayout.WEST)
                        add(text, BorderLayout.CENTER)
                        background = if (selected) l.selectionBackground else l.background
                        text.foreground = if (selected) l.selectionForeground else l.foreground
                        isOpaque = true
                    }
                }
            }
            val dlg = JDialog(frame, Strings.t("candidates"), true)
            // A Cancel button sits at the bottom next to Apply; closing
            // the window without applying counts as skipping this file.
            var picked = false
            dlg.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            dlg.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosed(e: java.awt.event.WindowEvent) {
                    // The chooser is gone: its cover thumbnails go too.
                    iconCache.clear()
                    if (!picked && myGen == searchGen.get()) onSkip?.invoke()
                }
            })
            val ok = JButton(Strings.t("apply")).apply {
                addActionListener {
                    val idx = list.selectedIndex
                    val opt = if (idx in 0 until model.size) model.getElementAt(idx) else null
                    if (opt != null) picked = true
                    dlg.dispose()
                    if (opt != null) {
                        if (trackKey.isNotBlank()) {
                            val lines = (loadProps().getProperty("cand_memory") ?: "")
                                .split("\n").filter {
                                    it.isNotBlank() && !it.startsWith(trackKey + "\t")
                                }
                            saveProp("cand_memory",
                                (listOf(trackKey + "\t" + opt.key) + lines).take(200)
                                    .joinToString("\n"))
                        }
                        val wp = progressDialog(Strings.t("working"), null, null)
                        wp.isVisible = true
                        thread {
                            try {
                                val t = opt.build()
                                SwingUtilities.invokeLater {
                                    wp.dispose()
                                    // Pre-apply preview of what will change.
                                    val old = tag ?: TagData()
                                    val merged = if (t.partial) t.mergedOver(old) else t
                                    val lines = TagDiff.lines(old, merged)
                                    val msg = if (lines.isEmpty()) Strings.t("no_change")
                                        else lines.joinToString("\n")
                                    val okDiff = JOptionPane.showConfirmDialog(frame, msg,
                                        Strings.t("diff_title"), JOptionPane.OK_CANCEL_OPTION)
                                    if (okDiff == JOptionPane.OK_OPTION) onPicked(t)
                                    else onSkip?.invoke()
                                }
                            } catch (e: Exception) {
                                SwingUtilities.invokeLater { wp.dispose() }
                                fail("${e.javaClass.simpleName}: ${e.message}")
                                onSkip?.invoke()
                            }
                        }
                    } else onSkip?.invoke()
                }
            }
            val cancel = JButton(Strings.t("cancel")).apply {
                addActionListener { dlg.dispose() }
            }
            val btns = JPanel().apply { add(ok); add(cancel) }
            dlg.contentPane.layout = BorderLayout()
            dlg.add(JScrollPane(list), BorderLayout.CENTER)
            dlg.add(btns, BorderLayout.SOUTH)
            dlg.setSize(640, 420); dlg.setLocationRelativeTo(frame)
            // Late sources append their candidates to the open chooser.
            chooserAppend = { more ->
                SwingUtilities.invokeLater {
                    if (dlg.isShowing && myGen == searchGen.get()) {
                        val wasEmpty = model.size == 0
                        more.forEach { model.addElement(it) }
                        if (wasEmpty && model.size > 0) list.selectedIndex = 0
                    }
                }
            }
            dlg.isVisible = true
        }
    }
    thread {
        val found = LinkedHashMap<String, MbCandidate>()
        var mbError: Exception? = null
        var atSongs: List<AtSong> = emptyList()
        var itunes: List<ItunesTrack> = emptyList()
        var deezer: List<DeezerTrack> = emptyList()
        var vgmdb: List<VgmdbTrack> = emptyList()
        var audius: List<AudiusTrack> = emptyList()
        var ndl: List<NdlRecord> = emptyList()
        val fpCands = java.util.Collections.synchronizedList(ArrayList<MbCandidate>())
        // All enabled sources are queried in parallel; the results are
        // still presented in the fixed source order afterwards.
        val jobs = ArrayList<Thread>()
        // Fingerprint search (AcoustID) when the user has entered their key.
        val fpKey = loadProps().getProperty("acoustid_key")?.trim() ?: ""
        if (fpKey.isNotBlank() && localFile != null) jobs.add(thread {
            try {
                val (fp, dur) = fingerprintViaFpcalc(localFile) ?: return@thread
                val ids = AcoustIdClient.lookupRecordingIds(fp, dur, fpKey)
                for (id in ids.take(5)) {
                    try { MbClient.lookupRecording(id)?.let { fpCands.add(it) } }
                    catch (e: Exception) { }
                }
            } catch (e: Exception) { }
        })
        if (SearchSources.mb) jobs.add(thread {
            try {
                if (detail != null) {
                    for (c in MbClient.searchByTagsOrName(detail.first, detail.second, ""))
                        synchronized(found) { found.putIfAbsent(c.recordingId, c) }
                } else if (!auto) {
                    for (c in MbClient.searchFreeText(query))
                        synchronized(found) { found.putIfAbsent(c.recordingId, c) }
                } else {
                    if (tag != null && !tag.isBasicallyEmpty()) {
                        for (c in MbClient.searchByTagsOrName(tag.title, tag.artist, tag.album))
                            synchronized(found) { found.putIfAbsent(c.recordingId, c) }
                    }
                    if (found.isEmpty() && parsed != null) {
                        for ((t, a, al) in NameParser.queries(parsed)) {
                            for (c in MbClient.searchByTagsOrName(t, a, al))
                                synchronized(found) { found.putIfAbsent(c.recordingId, c) }
                            if (found.isNotEmpty()) break
                        }
                    }
                }
            } catch (e: Exception) { mbError = e }
        })
        if (SearchSources.animeThemes) jobs.add(thread {
            atSongs = try {
                if (!auto) AnimeThemesClient.searchSongs(query)
                else {
                    val q1 = tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title ?: ""
                    val q2 = tag?.artist?.takeIf { it.isNotBlank() } ?: parsed?.artist ?: ""
                    val first = if (q1.isNotBlank()) AnimeThemesClient.searchSongs(q1) else emptyList()
                    if (first.isNotEmpty() || q2.isBlank() || q2 == q1) first
                    else AnimeThemesClient.searchSongs(q2)
                }
            } catch (e: Exception) { emptyList() }
        })
        if (SearchSources.itunes) jobs.add(thread {
            itunes = try {
                if (!auto) ItunesClient.search(query)
                else {
                    val q1 = listOfNotNull(
                        tag?.title?.takeIf { it.isNotBlank() },
                        tag?.artist?.takeIf { it.isNotBlank() }).joinToString(" ")
                    val q2 = listOfNotNull(parsed?.title, parsed?.artist).joinToString(" ")
                    val first = if (q1.isNotBlank()) ItunesClient.search(q1) else emptyList()
                    if (first.isNotEmpty() || q2.isBlank() || q2 == q1) first
                    else ItunesClient.search(q2)
                }
            } catch (e: Exception) { emptyList() }
        })
        if (SearchSources.deezer) jobs.add(thread {
            deezer = try {
                if (!auto) DeezerClient.search(query)
                else {
                    val q1 = listOfNotNull(
                        tag?.title?.takeIf { it.isNotBlank() },
                        tag?.artist?.takeIf { it.isNotBlank() }).joinToString(" ")
                    val q2 = listOfNotNull(parsed?.title, parsed?.artist).joinToString(" ")
                    val first = if (q1.isNotBlank()) DeezerClient.search(q1) else emptyList()
                    if (first.isNotEmpty() || q2.isBlank() || q2 == q1) first
                    else DeezerClient.search(q2)
                }
            } catch (e: Exception) { emptyList() }
        })
        if (SearchSources.vgmdb) jobs.add(thread {
            vgmdb = try {
                if (!auto) VgmdbClient.search(query)
                else {
                    val q1 = tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title ?: ""
                    val q2 = tag?.album?.takeIf { it.isNotBlank() } ?: parsed?.albumHint ?: ""
                    val first = if (q1.isNotBlank()) VgmdbClient.search(q1) else emptyList()
                    if (first.isNotEmpty() || q2.isBlank() || q2 == q1) first
                    else VgmdbClient.search(q2)
                }
            } catch (e: Exception) { emptyList() }
        })
        if (SearchSources.audius) jobs.add(thread {
            audius = try {
                if (!auto) AudiusClient.search(query)
                else {
                    val q1 = listOf(tag?.title, tag?.artist)
                        .filter { !it.isNullOrBlank() }.joinToString(" ")
                        .ifBlank { parsed?.title ?: "" }
                    val q2 = parsed?.title ?: ""
                    val first = if (q1.isNotBlank()) AudiusClient.search(q1) else emptyList()
                    if (first.isNotEmpty() || q2.isBlank() || q2 == q1) first
                    else AudiusClient.search(q2)
                }
            } catch (e: Exception) { emptyList() }
        })
        if (SearchSources.ndl) jobs.add(thread {
            ndl = try {
                if (!auto) NdlClient.search(query)
                else {
                    val q1 = tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title ?: ""
                    val q2 = tag?.album?.takeIf { it.isNotBlank() } ?: parsed?.albumHint ?: ""
                    val first = if (q1.isNotBlank()) NdlClient.search(q1) else emptyList()
                    if (first.isNotEmpty() || q2.isBlank() || q2 == q1) first
                    else NdlClient.search(q2)
                }
            } catch (e: Exception) { emptyList() }
        })
        // Progressive results: the chooser opens as soon as some source
        // has candidates instead of waiting for the slowest one; sources
        // that finish later append their candidates to the open chooser.
        // (Job keys mirror the creation order above: fingerprint first.)
        val jobKeys = ArrayList<String>()
        if (fpKey.isNotBlank() && localFile != null) jobKeys.add("fp")
        if (SearchSources.mb) jobKeys.add("mb")
        if (SearchSources.animeThemes) jobKeys.add("at")
        if (SearchSources.itunes) jobKeys.add("itunes")
        if (SearchSources.deezer) jobKeys.add("deezer")
        if (SearchSources.vgmdb) jobKeys.add("vgmdb")
        if (SearchSources.audius) jobKeys.add("audius")
        if (SearchSources.ndl) jobKeys.add("ndl")
        fun optsFor(key: String): List<DlgOpt> = when (key) {
            "fp" -> buildOpts(fp = synchronized(fpCands) { fpCands.toList() })
            "mb" -> buildOpts(mb = synchronized(found) { found.values.toList() })
            "at" -> buildOpts(at = atSongs)
            "itunes" -> buildOpts(itunes = itunes)
            "deezer" -> buildOpts(deezer = deezer)
            "vgmdb" -> buildOpts(vgmdb = vgmdb)
            "audius" -> buildOpts(audius = audius)
            else -> buildOpts(ndl = ndl)
        }
        val delivered = HashSet<String>()
        var opened = false
        var firstAt = 0L
        val pending = ArrayList<DlgOpt>()
        while (true) {
            if (stopped.get()) return@thread
            if (myGen != searchGen.get()) return@thread
            var allDone = delivered.size >= jobs.size
            for (i in jobs.indices) {
                val key = jobKeys.getOrNull(i) ?: continue
                if (key in delivered) continue
                if (jobs[i].isAlive) { allDone = false; continue }
                delivered.add(key)
                val opts = optsFor(key)
                if (opts.isNotEmpty()) {
                    if (firstAt == 0L) firstAt = System.currentTimeMillis()
                    pending.addAll(opts)
                }
            }
            if (!opened && pending.isNotEmpty() &&
                (allDone || System.currentTimeMillis() - firstAt >= 1500)) {
                opened = true
                val initial = ArrayList(pending); pending.clear()
                openChooser(initial)
                while (chooserAppend == null && !stopped.get()) Thread.sleep(50)
            } else if (opened && pending.isNotEmpty()) {
                val more = ArrayList(pending); pending.clear()
                chooserAppend?.invoke(more)
            }
            if (allDone) break
            Thread.sleep(200)
        }
        if (stopped.get()) return@thread
        if (!opened) {
            SwingUtilities.invokeLater {
                progress?.dispose()
                if (stopped.get()) return@invokeLater
                if (myGen != searchGen.get()) return@invokeLater
                if (mbError != null) { fail("${mbError.javaClass.simpleName}: ${mbError.message}"); onStop?.invoke() }
                else {
                    // Offer narrower re-searches before giving up.
                    val title = tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title ?: ""
                    val artist = tag?.artist?.takeIf { it.isNotBlank() } ?: parsed?.artist ?: ""
                    val labels = ArrayList<String>()
                    val queries = ArrayList<String>()
                    if (title.isNotBlank() && title != query) {
                        labels.add(Strings.t("title_only")); queries.add(title)
                    }
                    if (artist.isNotBlank() && artist != query) {
                        labels.add(Strings.t("artist_only")); queries.add(artist)
                    }
                    if (labels.isEmpty()) { info(Strings.t("no_results")); onSkip?.invoke() }
                    else {
                        labels.add(Strings.t("cancel"))
                        val pick = JOptionPane.showOptionDialog(frame, Strings.t("no_results"),
                            "OtoTag", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                            null, labels.toTypedArray(), labels.last())
                        if (pick in queries.indices) {
                            searchAndPick(tag, parsed, localFile, progressLabel,
                                onSkip, onStop, bulk, queries[pick], onPicked)
                        } else onSkip?.invoke()
                    }
                }
            }
        } else if (pending.isNotEmpty()) {
            chooserAppend?.invoke(ArrayList(pending))
        }
    }
}

/** Update history window: the published GitHub releases, newest first. */
fun showHistory() {
    val dlg = JDialog(frame, Strings.t("history"), true)
    val area = JTextArea(Strings.t("loading")).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
    }
    dlg.contentPane.layout = BorderLayout()
    dlg.add(JScrollPane(area), BorderLayout.CENTER)
    dlg.setSize(640, 520); dlg.setLocationRelativeTo(frame)
    thread {
        val releases = try { UpdateChecker.fetchReleases() } catch (e: Exception) { emptyList() }
        SwingUtilities.invokeLater {
            area.text = if (releases.isEmpty()) Strings.t("no_results")
            else releases.joinToString("\n\n") { r ->
                "${r.tag}   ${r.publishedAt}\n${UpdateChecker.localizedBody(r.body, Strings.lang)}"
            }
            area.caretPosition = 0
        }
    }
    dlg.isVisible = true
}

/** Loads the per-source on/off switches into the core search flags. */
fun loadSourcePrefs() {
    val p = loadProps()
    fun on(key: String) = p.getProperty(key)?.toBooleanStrictOrNull() ?: true
    SearchSources.mb = on("src_mb")
    SearchSources.animeThemes = on("src_at")
    SearchSources.itunes = on("src_itunes")
    SearchSources.deezer = on("src_deezer")
    SearchSources.vgmdb = on("src_vgmdb")
    SearchSources.audius = on("src_audius")
    SearchSources.ndl = on("src_ndl")
}

/** Settings dialog: the cover size cap and the search source switches. */
fun showSettings() {
    val caps = listOf(0, 2000, 1200, 800)
    val labels = arrayOf(Strings.t("cap_none"), "2000px", "1200px", "800px")
    val box = JComboBox(labels)
    box.selectedIndex = caps.indexOf(coverCapPx()).coerceAtLeast(0)
    val panel = JPanel(BorderLayout(6, 6))
    val topRow = JPanel(BorderLayout(6, 6))
    topRow.add(JLabel(Strings.t("cover_cap")), BorderLayout.WEST)
    topRow.add(box, BorderLayout.CENTER)
    panel.add(topRow, BorderLayout.NORTH)
    val sourceKeys = listOf(
        "src_mb" to "MusicBrainz", "src_at" to "AnimeThemes",
        "src_itunes" to "iTunes", "src_deezer" to "Deezer",
        "src_vgmdb" to "VGMdb", "src_audius" to "Audius", "src_ndl" to "NDL")
    val props = loadProps()
    val checks = sourceKeys.map { (key, name) ->
        JCheckBox(name, props.getProperty(key)?.toBooleanStrictOrNull() ?: true)
    }
    val srcPanel = JPanel(GridLayout(checks.size, 1))
    checks.forEach { srcPanel.add(it) }
    panel.add(srcPanel, BorderLayout.CENTER)
    val bottom = JPanel(GridLayout(3, 2, 6, 6))
    val acoustidField = JTextField(props.getProperty("acoustid_key") ?: "", 24)
    bottom.add(JLabel(Strings.t("acoustid_key"))); bottom.add(acoustidField)
    val darkBox = JCheckBox(Strings.t("dark_mode"), darkMode)
    bottom.add(darkBox)
    val subBox = JCheckBox(Strings.t("include_subfolders"),
        props.getProperty("subfolders")?.toBooleanStrictOrNull() ?: true)
    bottom.add(subBox)
    val backupLabel = JLabel("${Strings.t("backup")}: ${fmtSize(Backup.totalSize(backupDir))}")
    bottom.add(backupLabel)
    bottom.add(JButton(Strings.t("delete_all")).apply {
        addActionListener {
            if (JOptionPane.showConfirmDialog(frame, Strings.t("confirm_delete_all"),
                    Strings.t("backup"), JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                Backup.deleteAll(backupDir)
                backupLabel.text = "${Strings.t("backup")}: ${fmtSize(Backup.totalSize(backupDir))}"
            }
        }
    })
    panel.add(bottom, BorderLayout.SOUTH)
    val res = JOptionPane.showConfirmDialog(frame, panel, Strings.t("settings"),
        JOptionPane.OK_CANCEL_OPTION)
    if (res == JOptionPane.OK_OPTION) {
        saveProp("cover_cap", caps[box.selectedIndex].toString())
        sourceKeys.forEachIndexed { i, (key, _) -> saveProp(key, checks[i].isSelected.toString()) }
        saveProp("acoustid_key", acoustidField.text.trim())
        saveProp("subfolders", subBox.isSelected.toString())
        if (darkBox.isSelected != darkMode) {
            saveProp("dark", darkBox.isSelected.toString())
            info(Strings.t("restart_note"))
        }
        loadSourcePrefs()
    }
}

fun applySearchTo(row: Row, label: String?, bulk: Boolean, onSkip: () -> Unit, onStop: () -> Unit, done: () -> Unit) {
    // The search, the diff preview and the partial merge need the FULL
    // tag (with cover); the list itself only keeps the light copy.
    // The read, the merge and the cover cap run off the event thread:
    // capping decodes and re-encodes the cover, which froze the window
    // for big covers (the same bug the Android build fixed in 1908).
    thread {
        val fullOld = try { TagService.read(row.file) } catch (e: Exception) { row.tag }
        SwingUtilities.invokeLater {
            searchAndPick(fullOld, NameParser.parse(row.file), row.file, label, onSkip, onStop, bulk) { picked ->
                val cap = coverCapPx()
                thread {
                    // Partial sources (Audius / NDL) only fill what they know.
                    val merged = if (picked.partial)
                        picked.mergedOver(fullOld ?: TagData()) else picked
                    // The cover cap applies to covers the candidate
                    // brought; a cover the file already had is left as is.
                    val newTag = if (picked.cover != null)
                        merged.copy(cover = capCoverBytes(merged.cover, cap))
                    else merged
            var ok = false
            try {
                Backup.backup(row.file, backupDir)
                TagService.write(row.file, newTag)
                row.setTagLight(TagService.read(row.file))
                row.status = Strings.t("done")
                row.failed = false
                // applied via search: uncheck it so a re-run skips it
                row.checked = false
                ok = true
            } catch (e: Exception) {
                row.status = "${Strings.t("failed")}: ${e.message}"
                row.failed = true
            }
            refresh()
            if (!ok) {
                fail("${row.file.name}\n${row.status}")
                done(); return@thread
            }
            // Clear centred confirmation of what went in; offers the rename too.
            SwingUtilities.invokeLater {
                val summary = buildString {
                    append(newTag.title)
                    if (newTag.artist.isNotBlank()) append("\n${newTag.artist}")
                    if (newTag.album.isNotBlank()) append("\n${newTag.album}")
                }
                val base = Renamer.nameFromTag(newTag)
                val newName = if (base.isBlank()) "" else "$base.${row.file.extension}"
                if (newName.isBlank() || newName == row.file.name ||
                    java.io.File(row.file.parentFile, newName).exists()) {
                    JOptionPane.showMessageDialog(frame, summary, Strings.t("applied"),
                        JOptionPane.INFORMATION_MESSAGE)
                    done(); return@invokeLater
                }
                // The same offer with the track number stripped, when
                // the tag-based name carries one and the result differs.
                val strippedBase = Renamer.stripTrackPrefix(base)
                val strippedName = if (strippedBase != base)
                    "$strippedBase.${row.file.extension}" else ""
                val hasStrip = strippedName.isNotBlank() && strippedName != row.file.name &&
                    !java.io.File(row.file.parentFile, strippedName).exists()
                fun doRename(useBase: String) {
                    thread {
                        val renamed = Renamer.rename(row.file, useBase)
                        if (renamed != null) {
                            Backup.move(row.file, renamed, backupDir)
                            val idx = rows.indexOf(row)
                            if (idx >= 0) rows[idx] = Row(renamed).also {
                                it.checked = false; it.tag = row.tag; it.status = Strings.t("done")
                            }
                            refresh()
                        }
                        done()
                    }
                }
                if (hasStrip) {
                    val opts = arrayOf(Strings.t("rename"),
                        Strings.t("rename_no_num"), Strings.t("keep_name"))
                    val pick = JOptionPane.showOptionDialog(frame,
                        "$summary\n\n${Strings.t("ask_rename")}\n${row.file.name}\n→ $newName\n→ $strippedName",
                        Strings.t("applied"), JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE, null, opts, opts[0])
                    when (pick) {
                        0 -> doRename(base)
                        1 -> doRename(strippedBase)
                        else -> done()
                    }
                } else {
                    val res = JOptionPane.showConfirmDialog(frame,
                        "$summary\n\n${Strings.t("ask_rename")}\n${row.file.name}\n→ $newName",
                        Strings.t("applied"), JOptionPane.YES_NO_OPTION)
                    if (res == JOptionPane.YES_OPTION) doRename(base) else done()
                }
            }
                }
            }
        }
    }
}

fun searchApplySelected() {
    val sel = checkedRows()
    if (sel.isEmpty()) { info(Strings.t("select_some")); return }
    fun next(i: Int) {
        if (i >= sel.size) return
        applySearchTo(sel[i], "${i + 1} / ${sel.size} — ${sel[i].file.name}", sel.size > 1,
            onSkip = { next(i + 1) }, onStop = { }) { next(i + 1) }
    }
    next(0)
}

class EditDialog(private val row: Row) : JDialog(frame, row.file.name, true) {
    private val fields = HashMap<String, JTextField>()
    private val counts = HashMap<String, JLabel>()
    private val origFieldBg = HashMap<javax.swing.text.JTextComponent, Color>()
    /**
     * The file's tag as the dialog opened, WITHOUT the cover bytes: the
     * editor holds only a small decoded preview of the cover, and the
     * real bytes are re-read from the file when saving. (Before, the
     * dialog started from the list's light tag, so the cover never
     * showed and a save cleared it.)
     */
    private val original: TagData
    /** Whether the file had a cover when the dialog opened. */
    private var hasOrigCover = false
    private var coverPreviewIcon: ImageIcon? = null
    init {
        val full = try { TagService.read(row.file) }
            catch (e: Exception) { row.tag ?: TagData() }
        hasOrigCover = full.cover != null
        coverPreviewIcon = full.cover?.let { bytes ->
            try {
                val img = ImageIO.read(bytes.inputStream())
                if (img == null) null
                else ImageIcon(img.getScaledInstance(180, 180, Image.SCALE_SMOOTH))
            } catch (e: Exception) { null }
        }
        original = full.copy(cover = null)
    }
    private val lyricsArea = JTextArea(original.lyrics, 6, 34).apply {
        lineWrap = true; wrapStyleWord = true
    }
    private val mxmKeyField = JTextField(
        loadProps().getProperty("musixmatch_key") ?: "", 34)
    private var coverBytes: ByteArray? = null
    private var coverRemoved = false
    private var mbIds = original.mbRecordingId to original.mbReleaseId
    private var savedClean = false
    private val coverLabel = JLabel("", SwingConstants.CENTER).apply {
        preferredSize = Dimension(180, 180); border = BorderFactory.createLineBorder(Color.GRAY)
    }

    init {
        val d = original
        val panel = JPanel(GridBagLayout())
        val gc = GridBagConstraints().apply { insets = Insets(3, 6, 3, 6); anchor = GridBagConstraints.WEST }
        var y = 0
        fun addField(key: String, value: String, labelKey: String = key) {
            // Label cell: the name, a character count, a clear button.
            val labelPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            labelPanel.add(JLabel(Strings.t(labelKey)))
            val count = JLabel("0").apply { foreground = Color.GRAY }
            counts[key] = count
            labelPanel.add(count)
            labelPanel.add(JButton("\u2715").apply {
                margin = Insets(0, 4, 0, 4)
                addActionListener { fields[key]?.text = "" }
            })
            gc.gridx = 0; gc.gridy = y
            panel.add(labelPanel, gc)
            val tf = JTextField(value, 34)
            gc.gridx = 1; panel.add(tf, gc)
            fields[key] = tf; y++
            watchField(key, tf)
        }
        addField("filename", row.file.nameWithoutExtension)
        gc.gridx = 1; gc.gridy = y
        panel.add(JButton(Strings.t("from_filename")).apply {
            addActionListener {
                val p = NameParser.parse(row.file)
                if (p.title.isNotBlank()) fields["title"]?.text = p.title
                if (p.artist.isNotBlank()) fields["artist"]?.text = p.artist
                if (p.trackNo.isNotBlank()) fields["track"]?.text = p.trackNo
            }
        }, gc); y++
        addField("title", d.title); addField("artist", d.artist); addField("album", d.album)
        addField("album_artist", d.albumArtist); addField("composer", d.composer)
        addField("genre", d.genre)
        // Genre picks next to the (still freely editable) field: the
        // chooser shows the user's own genres (deletable) apart from
        // the built-ins, and the typed text can be saved as an own genre.
        gc.gridx = 1; gc.gridy = y
        val genreBtns = JPanel(FlowLayout(FlowLayout.LEFT))
        genreBtns.add(JButton(Strings.t("genre_pick")).apply {
            addActionListener { pickGenreWin { g -> fields["genre"]?.text = g } }
        })
        genreBtns.add(JButton(Strings.t("genre_save")).apply {
            addActionListener {
                persistCustomGenresWin(GenreList.add(customGenresWin(),
                    fields["genre"]?.text ?: ""))
                info(Strings.t("done"))
            }
        })
        panel.add(genreBtns, gc); y++
        addField("year", d.year)
        addField("track", d.track); addField("track_total", d.trackTotal)
        addField("disc", d.disc); addField("disc_total", d.discTotal)
        addField("comment", d.comment); addField("isrc", d.isrc)

        val lyLabel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        lyLabel.add(JLabel(Strings.t("lyrics")))
        val lyCount = JLabel("0").apply { foreground = Color.GRAY }
        counts["lyrics"] = lyCount
        lyLabel.add(lyCount)
        lyLabel.add(JButton("\u2715").apply {
            margin = Insets(0, 4, 0, 4)
            addActionListener { lyricsArea.text = "" }
        })
        gc.gridx = 0; gc.gridy = y; panel.add(lyLabel, gc)
        gc.gridx = 1; panel.add(JScrollPane(lyricsArea), gc); y++
        watchField("lyrics", lyricsArea)
        gc.gridx = 1; gc.gridy = y
        panel.add(JButton(Strings.t("get_lyrics")).apply {
            addActionListener { fetchLyrics() }
        }, gc); y++
        gc.gridx = 0; gc.gridy = y; panel.add(JLabel(Strings.t("mxm_key")), gc)
        gc.gridx = 1; panel.add(mxmKeyField, gc); y++

        gc.gridx = 0; gc.gridy = y; panel.add(JLabel(Strings.t("cover")), gc)
        gc.gridx = 1; panel.add(coverLabel, gc); y++
        updateCoverLabel()
        coverLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Click: zoom the cover when there is one, else pick a file.
                val b = coverBytes
                if (b != null) showCoverZoom(b)
                else if (hasOrigCover && !coverRemoved) {
                    val cur = try { TagService.read(row.file).cover }
                        catch (ex: Exception) { null }
                    if (cur != null) showCoverZoom(cur) else pickCoverFile()
                }
                else pickCoverFile()
            }
        })
        DropTarget(coverLabel, object : DropTargetAdapter() {
            override fun drop(ev: DropTargetDropEvent) {
                try {
                    ev.acceptDrop(DnDConstants.ACTION_COPY)
                    val files = ev.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    files.firstOrNull { MbClient.isImageFile(it) }?.let { setCover(it.readBytes()) }
                    ev.dropComplete(true)
                } catch (ex: Exception) { ev.dropComplete(false) }
            }
        })

        val btns = JPanel()
        btns.add(JButton(Strings.t("pick_image")).apply { addActionListener { pickCoverFile() } })
        btns.add(JButton(Strings.t("from_url")).apply { addActionListener { pickCoverUrl() } })
        btns.add(JButton(Strings.t("from_clipboard")).apply {
            addActionListener { pasteCoverFromClipboard() }
        })
        btns.add(JButton(Strings.t("remove_cover")).apply {
            addActionListener {
                coverBytes = null; coverRemoved = true; hasOrigCover = false
                coverPreviewIcon = null; updateCoverLabel()
            }
        })
        btns.add(JButton(Strings.t("search")).apply {
            addActionListener {
                searchAndPick(collect(), NameParser.parse(row.file), row.file) { picked ->
                    fillFrom(picked)
                }
            }
        })
        btns.add(JButton(Strings.t("save")).apply { addActionListener { save() } })
        btns.add(JButton(Strings.t("cancel")).apply { addActionListener { tryClose() } })

        contentPane.layout = BorderLayout()
        add(JScrollPane(panel), BorderLayout.CENTER)
        add(btns, BorderLayout.SOUTH)
        setSize(560, 640); setLocationRelativeTo(frame)
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) { tryClose() }
        })
    }

    private var coverChanged = false

    /** The original value of one field key, for change marks and dirty checks. */
    private fun origValueOf(k: String): String = when (k) {
        "title" -> original.title; "artist" -> original.artist
        "album" -> original.album; "album_artist" -> original.albumArtist
        "composer" -> original.composer; "genre" -> original.genre
        "year" -> original.year; "track" -> original.track
        "track_total" -> original.trackTotal; "disc" -> original.disc
        "disc_total" -> original.discTotal; "comment" -> original.comment
        "isrc" -> original.isrc; "lyrics" -> original.lyrics
        "filename" -> row.file.nameWithoutExtension
        else -> ""
    }

    /**
     * Watches one input: a value that differs from the file's own gets a
     * tinted background (and falls back when it returns), and the
     * character count next to the label stays current.
     */
    private fun watchField(k: String, comp: javax.swing.text.JTextComponent) {
        origFieldBg[comp] = comp.background
        comp.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            fun upd() {
                comp.background = if (comp.text != origValueOf(k))
                    (if (darkMode) Color(0x6B, 0x55, 0x10) else Color(0xFF, 0xF0, 0xB3))
                else origFieldBg[comp]
                counts[k]?.text = comp.text.length.toString()
            }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = upd()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = upd()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = upd()
        })
        counts[k]?.text = comp.text.length.toString()
    }

    /** True when anything in the dialog differs from what the file holds. */
    private fun isDirty(): Boolean {
        if (coverChanged || coverRemoved) return true
        for ((k, tf) in fields) if (tf.text != origValueOf(k)) return true
        if (lyricsArea.text != original.lyrics) return true
        return mbIds.first != original.mbRecordingId ||
            mbIds.second != original.mbReleaseId
    }

    /** Closing with unsaved changes asks first. */
    private fun tryClose() {
        if (!savedClean && isDirty()) {
            val res = JOptionPane.showConfirmDialog(this, Strings.t("confirm_discard"),
                "OtoTag", JOptionPane.OK_CANCEL_OPTION)
            if (res != JOptionPane.OK_OPTION) return
        }
        dispose()
    }

    /** Pastes a cover image from the clipboard, when one is there. */
    private fun pasteCoverFromClipboard() {
        try {
            val img = Toolkit.getDefaultToolkit().systemClipboard
                .getData(DataFlavor.imageFlavor) as? Image
            if (img == null) { info(Strings.t("failed")); return }
            val w = img.getWidth(null); val h = img.getHeight(null)
            if (w <= 0 || h <= 0) { info(Strings.t("failed")); return }
            val bi = java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = bi.createGraphics(); g.drawImage(img, 0, 0, null); g.dispose()
            val out = java.io.ByteArrayOutputStream()
            ImageIO.write(bi, "png", out)
            setCover(out.toByteArray())
        } catch (e: Exception) { fail("${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun setCover(bytes: ByteArray) {
        coverBytes = bytes; coverRemoved = false; coverChanged = true; updateCoverLabel()
    }

    private fun updateCoverLabel() {
        val b = coverBytes
        if (b == null && hasOrigCover && !coverRemoved && coverPreviewIcon != null) {
            coverLabel.icon = coverPreviewIcon; coverLabel.text = ""
            return
        }
        if (b != null) {
            try {
                val img = ImageIO.read(b.inputStream())
                if (img != null) {
                    val scaled = img.getScaledInstance(180, 180, Image.SCALE_SMOOTH)
                    coverLabel.icon = ImageIcon(scaled); coverLabel.text = ""
                    return
                }
            } catch (e: Exception) { }
        }
        coverLabel.icon = null; coverLabel.text = Strings.t("pick_image")
    }

    private fun pickCoverFile() {
        val fc = JFileChooser()
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val f = fc.selectedFile
            if (MbClient.isImageFile(f)) setCover(f.readBytes())
        }
    }

    /** Downloads a cover image from a typed URL and uses it as the new cover. */
    private fun pickCoverUrl() {
        val url = JOptionPane.showInputDialog(this, Strings.t("cover_url"),
            Strings.t("from_url"), JOptionPane.PLAIN_MESSAGE)?.trim().orEmpty()
        if (url.isBlank()) return
        thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val bytes = conn.inputStream.readBytes()
                SwingUtilities.invokeLater { setCover(bytes) }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    fail("${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** Large view of the given cover art. */
    private fun showCoverZoom(b: ByteArray) {
        // Subsampled decode near the display size (at most 620px on
        // screen): a multi-megapixel cover must not decode whole.
        val img = try {
            val iis = ImageIO.createImageInputStream(b.inputStream())
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) null
            else {
                val reader = readers.next()
                reader.input = iis
                val w0 = reader.getWidth(0); val h0 = reader.getHeight(0)
                var sub = 1
                while (w0 / sub > 1240 || h0 / sub > 1240) sub *= 2
                val param = reader.defaultReadParam
                if (sub > 1) param.setSourceSubsampling(sub, sub, 0, 0)
                val im = reader.read(0, param)
                reader.dispose()
                im
            }
        } catch (e: Exception) { null } ?: return
        val maxDim = 620
        val ratio = minOf(1.0, maxDim.toDouble() / maxOf(img.width, img.height))
        val w = (img.width * ratio).toInt().coerceAtLeast(1)
        val h = (img.height * ratio).toInt().coerceAtLeast(1)
        val dlg = JDialog(this, Strings.t("cover"), true)
        dlg.contentPane.add(JLabel(ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH))))
        dlg.pack(); dlg.setLocationRelativeTo(this); dlg.isVisible = true
    }

    private fun fillFrom(d: TagData) {
        // Partial candidates (Audius / NDL) must not blank out unknown fields.
        fun set(k: String, v: String) { if (!d.partial || v.isNotBlank()) fields[k]?.text = v }
        set("title", d.title); set("artist", d.artist); set("album", d.album)
        set("album_artist", d.albumArtist); set("composer", d.composer); set("genre", d.genre)
        set("year", d.year); set("track", d.track); set("track_total", d.trackTotal)
        set("disc", d.disc); set("disc_total", d.discTotal); set("comment", d.comment); set("isrc", d.isrc)
        if (!d.partial || d.lyrics.isNotBlank()) lyricsArea.text = d.lyrics
        if (!d.partial) mbIds = d.mbRecordingId to d.mbReleaseId
        if (d.cover != null) setCover(d.cover!!)
    }

    /**
     * Fetches lyrics from every source in parallel. When more than one
     * source has lyrics, the user compares the first lines and picks;
     * the picked text is tidied on the way in. LRCLIB's timestamped
     * lyrics also land next to the file as .lrc (an identical existing
     * file is left untouched).
     */
    private fun fetchLyrics() {
        val title = fields["title"]?.text ?: ""
        val artist = fields["artist"]?.text ?: ""
        val album = fields["album"]?.text ?: ""
        val dur = original.durationSec
        val key = mxmKeyField.text.trim()
        saveProp("musixmatch_key", key)
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
            SwingUtilities.invokeLater {
                when {
                    results.isEmpty() -> info(Strings.t("no_lyrics"))
                    results.size == 1 -> applyLyrics(results[0], lrc)
                    else -> {
                        val labels = results.map { (srcName, txt) ->
                            srcName + " \u2014 " + (txt.lineSequence()
                                .firstOrNull { it.isNotBlank() }?.take(40) ?: "")
                        }.toTypedArray()
                        val pick = JOptionPane.showInputDialog(this,
                            Strings.t("lyrics_pick"), Strings.t("lyrics"),
                            JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]) as? String
                        if (pick != null) applyLyrics(results[labels.indexOf(pick)], lrc)
                    }
                }
            }
        }
    }

    /** Puts picked lyrics into the area (tidied) and saves LRCLIB's .lrc. */
    private fun applyLyrics(picked: Pair<String, String>, lrc: LrcEntry?) {
        lyricsArea.text = LyricsText.normalize(picked.second)
        if (picked.first == "LRCLIB" && lrc != null && lrc.syncedLyrics.isNotBlank()) {
            thread {
                try {
                    val f = File(row.file.parentFile,
                        row.file.nameWithoutExtension + ".lrc")
                    val cur = if (f.isFile) f.readText(Charsets.UTF_8) else null
                    if (cur != lrc.syncedLyrics) {
                        f.writeText(lrc.syncedLyrics, Charsets.UTF_8)
                        SwingUtilities.invokeLater { info(Strings.t("lrc_saved")) }
                    }
                } catch (e: Exception) { }
            }
        }
    }

    private fun collect(): TagData {
        fun v(k: String) = fields[k]?.text ?: ""
        return TagData(
            title = v("title"), artist = v("artist"), album = v("album"),
            albumArtist = v("album_artist"), composer = v("composer"), genre = v("genre"),
            year = v("year"), track = v("track"), trackTotal = v("track_total"),
            disc = v("disc"), discTotal = v("disc_total"), comment = v("comment"),
            isrc = v("isrc"), lyrics = lyricsArea.text,
            mbRecordingId = mbIds.first, mbReleaseId = mbIds.second,
            cover = if (coverRemoved) null else coverBytes)
    }

    private fun save() {
        val collected = collect()
        // The diff preview compares against the collected values; the
        // cover itself is capped inside the save thread below (capping
        // decodes and re-encodes the image — not EDT work).
        val d = collected
        val newBase = Renamer.sanitize(fields["filename"]?.text ?: "")
        // Pre-save preview: the same change list the search flow shows.
        val lines = ArrayList(TagDiff.lines(original, d))
        val nameChanges = newBase.isNotBlank() && newBase != row.file.nameWithoutExtension
        if (nameChanges)
            lines.add("${Strings.t("filename")}: ${row.file.name} \u2192 $newBase.${row.file.extension}")
        if (lines.isNotEmpty()) {
            val res = JOptionPane.showConfirmDialog(this, lines.joinToString("\n"),
                Strings.t("diff_title"), JOptionPane.OK_CANCEL_OPTION)
            if (res != JOptionPane.OK_OPTION) return
        }
        thread {
            var fail: String? = null
            try {
                // The size cap applies only to a cover replaced in this
                // editor; the file's own cover passes through untouched.
                val capped = if (coverChanged && d.cover != null)
                    d.copy(cover = capCoverBytes(d.cover, coverCapPx()))
                else d
                // An untouched cover is re-read from the file now: the
                // dialog only ever held a small preview of it.
                val toWrite = if (!coverChanged && !coverRemoved) {
                    val cur = TagService.read(row.file)
                    capped.copy(cover = cur.cover)
                } else capped
                Backup.backup(row.file, backupDir)
                TagService.write(row.file, toWrite)
                row.setTagLight(TagService.read(row.file))
                row.checked = false // acted on: drop the check
                row.status = Strings.t("done")
                row.failed = false
                if (newBase.isNotBlank() && newBase != row.file.nameWithoutExtension) {
                    val renamed = Renamer.rename(row.file, newBase)
                    if (renamed != null) {
                        Backup.move(row.file, renamed, backupDir)
                        val idx = rows.indexOf(row)
                        if (idx >= 0) rows[idx] = Row(renamed).also {
                            it.checked = false; it.status = row.status
                            it.setTagLight(try { TagService.read(renamed) }
                                catch (e: Exception) { null })
                        }
                    } else fail = Strings.t("collision")
                }
            } catch (e: Exception) {
                row.failed = true
                fail = "${e.javaClass.simpleName}: ${e.message}"
            }
            SwingUtilities.invokeLater {
                refresh()
                if (fail == null) { savedClean = true; dispose() } else fail(fail!!)
            }
        }
    }
}

var selectAllButton: JButton? = null

/** The toggle's label follows the checks: anything checked (by hand
 *  or by the button) offers Deselect all, otherwise Select all. */
fun updateSelectAllLabelWin() {
    val b = selectAllButton ?: return
    val anyChecked = shownRows().any { it.checked }
    SwingUtilities.invokeLater {
        b.text = Strings.t(if (anyChecked) "select_none" else "select_all")
    }
}

fun customGenresWin(): List<String> =
    GenreList.parse(loadProps().getProperty("custom_genres"))

fun persistCustomGenresWin(list: List<String>) =
    saveProp("custom_genres", GenreList.serialize(list))

/**
 * Genre chooser: the user's own genres first (each with a delete
 * button), then the built-in TagData.GENRES, the groups kept apart.
 */
fun hiddenGenresWin(): List<String> =
    GenreList.parse(loadProps().getProperty("hidden_genres"))

fun persistHiddenGenresWin(list: List<String>) =
    saveProp("hidden_genres", GenreList.serialize(list))

/**
 * Genre chooser: the user's own genres first, then the built-in
 * TagData.GENRES, the groups kept apart. Both groups can be deleted
 * (with a confirmation); removed built-ins collect in a third group
 * where they can be restored.
 */
fun removedGenresWin(): List<String> =
    GenreList.parse(loadProps().getProperty("removed_genres"))

fun persistRemovedGenresWin(list: List<String>) =
    saveProp("removed_genres", GenreList.serialize(list))

/**
 * The removed genres live on their own dialog (opened from the genre
 * picker): Restore all at the top, each genre with its own Restore
 * button, and every restore asks first.
 */
fun showRestoreWin() {
    val dlg = JDialog(frame, Strings.t("genre_removed"), true)
    val col = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    fun confirm(message: String, action: () -> Unit) {
        val res = JOptionPane.showConfirmDialog(dlg, message,
            Strings.t("genre_restore"), JOptionPane.YES_NO_OPTION)
        if (res == JOptionPane.YES_OPTION) action()
    }
    fun renderRestore() {
        col.removeAll()
        val defs = hiddenGenresWin().filter { it in TagData.GENRES }
        val trashed = removedGenresWin()
        if (defs.isEmpty() && trashed.isEmpty()) { dlg.dispose(); return }
        col.add(JButton(Strings.t("genre_restore_all")).apply {
            addActionListener {
                confirm(Strings.t("genre_restore_all_confirm")) {
                    persistHiddenGenresWin(emptyList())
                    persistRemovedGenresWin(emptyList())
                    renderRestore()
                }
            }
        })
        if (trashed.isNotEmpty()) {
            col.add(JLabel(Strings.t("genre_mine")))
            for (g in trashed) {
                val row = JPanel(FlowLayout(FlowLayout.LEFT))
                row.add(JLabel(g))
                row.add(JButton(Strings.t("genre_restore")).apply {
                    addActionListener {
                        confirm(Strings.t("genre_restore_confirm")) {
                            persistRemovedGenresWin(
                                GenreList.remove(removedGenresWin(), g))
                            persistCustomGenresWin(
                                GenreList.add(customGenresWin(), g))
                            renderRestore()
                        }
                    }
                })
                col.add(row)
            }
        }
        if (defs.isNotEmpty()) {
            col.add(JLabel(Strings.t("genre_defaults")))
            for (g in defs) {
                val row = JPanel(FlowLayout(FlowLayout.LEFT))
                row.add(JLabel(g))
                row.add(JButton(Strings.t("genre_restore")).apply {
                    addActionListener {
                        confirm(Strings.t("genre_restore_confirm")) {
                            persistHiddenGenresWin(
                                GenreList.unhide(hiddenGenresWin(), g))
                            renderRestore()
                        }
                    }
                })
                col.add(row)
            }
        }
        col.revalidate(); col.repaint()
    }
    renderRestore()
    dlg.contentPane.add(JScrollPane(col))
    dlg.setSize(360, 480)
    dlg.setLocationRelativeTo(frame)
    dlg.isVisible = true
}

fun pickGenreWin(onPick: (String) -> Unit) {
    val dlg = JDialog(frame, Strings.t("genre_pick"), true)
    val col = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    fun confirmDelete(name: String, action: () -> Unit) {
        val res = JOptionPane.showConfirmDialog(dlg,
            Strings.t("genre_del_confirm"), name, JOptionPane.YES_NO_OPTION)
        if (res == JOptionPane.YES_OPTION) action()
    }
    fun render() {
        col.removeAll()
        // Add a genre right here: type a name and click Add.
        val addRow = JPanel(FlowLayout(FlowLayout.LEFT))
        val input = JTextField(16)
        addRow.add(input)
        addRow.add(JButton(Strings.t("genre_add")).apply {
            addActionListener {
                val now = GenreList.add(customGenresWin(), input.text)
                if (now != customGenresWin()) {
                    persistCustomGenresWin(now)
                    persistRemovedGenresWin(
                        GenreList.remove(removedGenresWin(), input.text.trim()))
                    render()
                }
            }
        })
        col.add(addRow)
        val mine = customGenresWin()
        if (mine.isNotEmpty()) {
            col.add(JLabel(Strings.t("genre_mine")))
            for (g in mine) {
                val row = JPanel(FlowLayout(FlowLayout.LEFT))
                row.add(JButton(g).apply {
                    addActionListener { dlg.dispose(); onPick(g) }
                })
                row.add(JButton("\u2715").apply {
                    addActionListener {
                        confirmDelete(g) {
                            persistCustomGenresWin(
                                GenreList.remove(customGenresWin(), g))
                            persistRemovedGenresWin(
                                GenreList.trash(removedGenresWin(), g))
                            render()
                        }
                    }
                })
                col.add(row)
            }
        }
        col.add(JLabel(Strings.t("genre_defaults")))
        for (g in GenreList.visibleDefaults(hiddenGenresWin())) {
            val row = JPanel(FlowLayout(FlowLayout.LEFT))
            row.add(JButton(g).apply {
                addActionListener { dlg.dispose(); onPick(g) }
            })
            row.add(JButton("\u2715").apply {
                addActionListener {
                    confirmDelete(g) {
                        persistHiddenGenresWin(
                            GenreList.hide(hiddenGenresWin(), g))
                        render()
                    }
                }
            })
            col.add(row)
        }
        // Removed genres stay out of this list: one button opens
        // their own dialog, where they can be restored.
        if (removedGenresWin().isNotEmpty() ||
            hiddenGenresWin().any { it in TagData.GENRES }) {
            col.add(JButton(Strings.t("genre_removed")).apply {
                addActionListener { showRestoreWin(); render() }
            })
        }
        col.revalidate(); col.repaint()
    }
    render()
    dlg.contentPane.add(JScrollPane(col))
    dlg.setSize(360, 480)
    dlg.setLocationRelativeTo(frame)
    dlg.isVisible = true
}

fun bulkEdit() {
    val sel = checkedRows()
    if (sel.isEmpty()) { info(Strings.t("select_some")); return }
    val keys = listOf("artist", "album", "album_artist", "composer", "genre", "year", "disc")
    val inputs = HashMap<String, JTextField>()
    val panel = JPanel(GridLayout(0, 2, 6, 6))
    for (k in keys) {
        panel.add(JLabel(Strings.t(k)))
        val tf = JTextField(24); inputs[k] = tf; panel.add(tf)
        if (k == "genre") {
            panel.add(JLabel(""))
            val btns = JPanel(FlowLayout(FlowLayout.LEFT))
            btns.add(JButton(Strings.t("genre_pick")).apply {
                addActionListener { pickGenreWin { g -> inputs["genre"]?.text = g } }
            })
            btns.add(JButton(Strings.t("genre_save")).apply {
                addActionListener {
                    persistCustomGenresWin(GenreList.add(customGenresWin(),
                        inputs["genre"]?.text ?: ""))
                    info(Strings.t("done"))
                }
            })
            panel.add(btns)
        }
    }
    var coverBytes: ByteArray? = null
    panel.add(JLabel(Strings.t("cover")))
    panel.add(JButton(Strings.t("bulk_cover")).apply {
        addActionListener {
            val fc = JFileChooser()
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION &&
                MbClient.isImageFile(fc.selectedFile)) {
                val f = fc.selectedFile; val cap = coverCapPx()
                thread {
                    val b = try { capCoverBytes(f.readBytes(), cap) }
                        catch (e: Exception) { null }
                    SwingUtilities.invokeLater { coverBytes = b }
                }
            }
        }
    })
    val res = JOptionPane.showConfirmDialog(frame, panel, Strings.t("bulk_edit"),
        JOptionPane.OK_CANCEL_OPTION)
    if (res != JOptionPane.OK_OPTION) return
    thread {
        for (row in sel) {
            try {
                // Full read: the write needs the cover bytes the list drops.
                val cur = TagService.read(row.file)
                fun v(k: String) = inputs[k]!!.text.ifBlank { when (k) {
                    "artist" -> cur.artist
                    "album" -> cur.album; "album_artist" -> cur.albumArtist
                    "genre" -> cur.genre; "year" -> cur.year; "disc" -> cur.disc
                    else -> cur.composer } }
                val d = TagData(
                    title = cur.title, artist = v("artist"), album = v("album"),
                    albumArtist = v("album_artist"), composer = v("composer"), genre = v("genre"),
                    year = v("year"), track = cur.track, trackTotal = cur.trackTotal,
                    disc = v("disc"), discTotal = cur.discTotal, comment = cur.comment,
                    isrc = cur.isrc, mbRecordingId = cur.mbRecordingId, mbReleaseId = cur.mbReleaseId,
                    cover = coverBytes ?: cur.cover)
                Backup.backup(row.file, backupDir)
                TagService.write(row.file, d)
                row.setTagLight(TagService.read(row.file))
                row.status = Strings.t("done")
                row.failed = false
            } catch (e: Exception) {
                row.status = "${Strings.t("failed")}: ${e.message}"
                row.failed = true
            }
            refresh()
        }
    }
}

fun bulkRename() {
    val sel = checkedRows()
    if (sel.isEmpty()) { info(Strings.t("select_some")); return }
    // Plain file-name editing: one text field per file with its current
    // name (extension fixed). No patterns.
    val fields = ArrayList<Pair<Row, JTextField>>()
    val form = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    for (row in sel) {
        val line = JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        }
        val tf = JTextField(row.file.nameWithoutExtension, 44)
        line.add(tf, BorderLayout.CENTER)
        line.add(JLabel("." + row.file.extension), BorderLayout.EAST)
        form.add(line)
        fields.add(row to tf)
    }
    val stripBtn = JButton(Strings.t("strip_num")).apply {
        addActionListener {
            for ((_, tf) in fields) tf.text = Renamer.stripTrackPrefix(tf.text)
        }
    }
    val panel = JPanel(BorderLayout(6, 6))
    panel.add(JPanel().apply { add(stripBtn) }, BorderLayout.NORTH)
    panel.add(JScrollPane(form).apply { preferredSize = java.awt.Dimension(560, 320) },
        BorderLayout.CENTER)
    val res = JOptionPane.showConfirmDialog(frame, panel, Strings.t("rename"),
        JOptionPane.OK_CANCEL_OPTION)
    if (res != JOptionPane.OK_OPTION) return
    thread {
        var failed = 0
        val notes = ArrayList<String>()
        val targets = HashSet<String>()
        for ((row, tf) in fields) {
            val newBase = Renamer.sanitize(tf.text)
            if (newBase.isBlank()) { failed++; continue }
            val newName = newBase + "." + row.file.extension
            if (newName == row.file.name) continue
            // Name clashes get their reason spelled out, not just a count.
            val targetFile = java.io.File(row.file.parentFile, newName)
            if (!targets.add(newName.lowercase()) ||
                (targetFile.exists() &&
                    targetFile.absolutePath != row.file.absolutePath)) {
                failed++
                notes.add("${row.file.name}\n${Strings.t("rename_exists")}: $newName")
                continue
            }
            val renamed = Renamer.rename(row.file, newBase)
            if (renamed != null) {
                Backup.move(row.file, renamed, backupDir)
                val idx = rows.indexOf(row)
                if (idx >= 0) rows[idx] = Row(renamed).also {
                    it.checked = false; it.tag = row.tag; it.status = Strings.t("done")
                }
            } else failed++
        }
        refresh()
        if (notes.isNotEmpty()) fail(notes.joinToString("\n\n"))
        else info(if (failed == 0) Strings.t("done") else "${Strings.t("failed")}: $failed")
    }
}

/** Deletes the checked rows' files themselves (with a confirmation). */
fun deleteSelected() {
    val sel = checkedRows()
    if (sel.isEmpty()) { info(Strings.t("select_some")); return }
    val res = JOptionPane.showConfirmDialog(frame,
        Strings.t("confirm_delete") + " (${sel.size})",
        Strings.t("delete_file"), JOptionPane.OK_CANCEL_OPTION)
    if (res != JOptionPane.OK_OPTION) return
    thread {
        var failed = 0
        for (row in sel) {
            try {
                if (!row.file.delete()) throw IllegalStateException("delete returned false")
                SwingUtilities.invokeLater { rows.remove(row) }
            } catch (e: Exception) { failed++ }
        }
        SwingUtilities.invokeLater {
            refresh()
            if (failed > 0) fail("${Strings.t("failed")}: $failed/${sel.size}")
        }
    }
}

fun undoSelected() {
    val sel = checkedRows()
    if (sel.isEmpty()) { info(Strings.t("select_some")); return }
    val res = JOptionPane.showConfirmDialog(frame, Strings.t("confirm_undo"),
        Strings.t("undo"), JOptionPane.OK_CANCEL_OPTION)
    if (res != JOptionPane.OK_OPTION) return
    // With several backup generations, let the user choose which one.
    val gens = sortedSetOf<Long>(reverseOrder())
    for (row in sel) gens.addAll(Backup.generations(row.file, backupDir))
    val genList = gens.take(5)
    var generation: Long? = null
    if (genList.size > 1) {
        val fmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val labels = genList.map { fmt.format(java.util.Date(it)) }.toTypedArray()
        val pick = JOptionPane.showInputDialog(frame, Strings.t("pick_backup"),
            Strings.t("undo"), JOptionPane.PLAIN_MESSAGE, null, labels, labels[0])
            as? String ?: return
        generation = genList[labels.indexOf(pick)]
    }
    val gen = generation
    thread {
        var restored = 0
        for (row in sel) {
            try {
                val ok = if (gen == null) Backup.restore(row.file, backupDir)
                    else Backup.restoreAt(row.file, backupDir, gen)
                if (ok) {
                    row.setTagLight(TagService.read(row.file))
                    row.checked = false // acted on: drop the check
                    row.status = Strings.t("restored"); row.failed = false; restored++
                }
            } catch (e: Exception) {
                row.status = "${Strings.t("failed")}: ${e.message}"
                row.failed = true
            }
        }
        refresh()
        info(if (restored > 0) Strings.t("restored") else Strings.t("no_backup"))
    }
}

// ---- self-update (GitHub releases) ----

fun appVersion(): String = try {
    object {}.javaClass.getResourceAsStream("/ototag-version.txt")
        ?.readBytes()?.toString(Charsets.UTF_8)?.trim() ?: ""
} catch (e: Exception) { "" }

fun checkUpdateDesktop() {
    val current = appVersion()
    if (current.isBlank()) return
    thread {
        val info = UpdateChecker.fetchLatest() ?: return@thread
        if (!UpdateChecker.isNewer(info.tag, current) ||
            (info.windowsZipUrl.isBlank() && info.windowsJarUrl.isBlank())) return@thread
        SwingUtilities.invokeLater {
            val pick = JOptionPane.showOptionDialog(frame, info.tag, Strings.t("upd_title"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                arrayOf(Strings.t("upd_now"), Strings.t("upd_later")), Strings.t("upd_now"))
            if (pick == 0) applyUpdateDesktop(info)
        }
    }
}

fun applyUpdateDesktop(info: UpdateInfo) {
    val progress = JDialog(frame, Strings.t("upd_title"), false)
    progress.contentPane.add(JLabel("  " + Strings.t("upd_downloading") + "  "))
    progress.pack(); progress.setLocationRelativeTo(frame); progress.isVisible = true
    thread {
        try {
            val jarFile = File(object {}.javaClass.protectionDomain.codeSource.location.toURI())
            val appDir = jarFile.parentFile
            // The update writes next to the jar. If that folder is not
            // writable (Program Files, a protected folder), say so in
            // plain words instead of dying with a raw access error.
            val probe = File(appDir, ".ototag-write-test")
            val writable = try {
                probe.writeBytes(byteArrayOf(0)); probe.delete(); true
            } catch (e: Exception) { false }
            if (!writable) {
                SwingUtilities.invokeLater {
                    progress.dispose()
                    info(Strings.t("upd_no_perm"))
                }
                return@thread
            }
            // The swap script waits until the running jar can actually
            // be renamed (the JVM may take a moment to exit) instead of
            // firing one rename after a fixed sleep and giving up.
            fun updateBat(swapLine: String): String {
                val lines = listOf(
                    "@echo off",
                    "set /a tries=0",
                    ":waitloop",
                    "if exist \"%~dp0OtoTag.jar.old\" del /Q \"%~dp0OtoTag.jar.old\" >nul 2>nul",
                    "ren \"%~dp0OtoTag.jar\" OtoTag.jar.old >nul 2>nul",
                    "if exist \"%~dp0OtoTag.jar.old\" goto swapped",
                    "set /a tries+=1",
                    "if %tries% GEQ 30 goto failed",
                    "ping -n 2 127.0.0.1 >nul",
                    "goto waitloop",
                    ":swapped",
                    "if exist \"%~dp0ototag-*.jsa\" del /Q \"%~dp0ototag-*.jsa\" >nul 2>nul",
                    swapLine,
                    "start \"\" \"%~dp0OtoTag.exe\"",
                    "goto done",
                    ":failed",
                    "start \"\" \"%~dp0OtoTag.exe\"",
                    ":done")
                return lines.joinToString("\r\n") + "\r\n"
            }
            fun runBatAndExit(swapLine: String) {
                val bat = File(appDir, "apply-update.bat")
                bat.writeText(updateBat(swapLine))
                // cmd /c on the bat directly: "start /min <path>" could
                // swallow a quoted path as a window title and never run.
                ProcessBuilder("cmd", "/c", bat.absolutePath)
                    .directory(appDir).start()
                kotlin.system.exitProcess(0)
            }
            // Small path: when the release carries the jar on its own,
            // download just that (~1 MB) instead of the whole zip.
            if (info.windowsJarUrl.isNotBlank()) {
                val newJar = File(appDir, "OtoTag.jar.new")
                UpdateChecker.download(info.windowsJarUrl, newJar)
                runBatAndExit("ren \"%~dp0OtoTag.jar.new\" OtoTag.jar")
            }
            val zipFile = File(appDir, "OtoTag-update.zip")
            UpdateChecker.download(info.windowsZipUrl, zipFile)
            val newDir = File(appDir, "update-new")
            newDir.deleteRecursively(); newDir.mkdirs()
            java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (!e.isDirectory && !e.name.startsWith("jre/")) {
                        val out = File(newDir, e.name)
                        if (out.canonicalPath.startsWith(newDir.canonicalPath)) {
                            out.parentFile?.mkdirs(); out.outputStream().use { zis.copyTo(it) }
                        }
                    }
                    zis.closeEntry()
                }
            }
            zipFile.delete()
            runBatAndExit("xcopy /E /Y /Q \"%~dp0update-new\\*\" \"%~dp0\" >nul")
        } catch (e: Exception) {
            SwingUtilities.invokeLater {
                progress.dispose()
                info("${Strings.t("upd_failed")}: ${e.message}")
            }
        }
    }
}

fun main() {
    // Startup sweep of self-update leftovers (the previous jar, a
    // half-downloaded one, the extracted update tree): the app folder
    // starts clean on every launch.
    thread {
        try {
            val jarFile = File(object {}.javaClass.protectionDomain.codeSource.location.toURI())
            val dir = jarFile.parentFile
            if (dir != null && File(dir, "OtoTag.exe").isFile) {
                for (name in listOf("OtoTag.jar.old", "OtoTag.jar.new", "OtoTag-update.zip")) {
                    try { File(dir, name).delete() } catch (e: Exception) { }
                }
                try { File(dir, "update-new").deleteRecursively() } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
    }
    val props = loadProps()
    Strings.lang = Strings.resolve(props.getProperty("lang"), Locale.getDefault().toLanguageTag())
    ItunesClient.country = Locale.getDefault().country.ifBlank { "JP" }
    darkMode = props.getProperty("dark")?.toBooleanStrictOrNull() ?: false
    loadSourcePrefs()
    // Housekeeping is deferred so startup stays light: prune old
    // backups half a minute after launch instead of right away.
    thread {
        try { Thread.sleep(30_000) } catch (e: Exception) { }
        try { Backup.prune(backupDir) } catch (e: Exception) { }
    }
    MbClient.userAgent = "OtoTag/0.1 ( https://github.com/yyoossk/ototag )"

    SwingUtilities.invokeLater {
        if (darkMode) {
            val bg = Color(0x1E, 0x1A, 0x2B)
            val panel = Color(0x28, 0x21, 0x3A)
            val ink = Color(0xF0, 0xEB, 0xFA)
            for (k in listOf("Panel.background", "OptionPane.background",
                "ScrollPane.background", "Viewport.background")) UIManager.put(k, bg)
            for (k in listOf("Label.foreground", "OptionPane.messageForeground",
                "CheckBox.foreground", "ComboBox.foreground", "Button.foreground"))
                UIManager.put(k, ink)
            for (k in listOf("CheckBox.background", "ComboBox.background",
                "RadioButton.background")) UIManager.put(k, bg)
            UIManager.put("Table.background", panel)
            UIManager.put("Table.foreground", ink)
            UIManager.put("Table.gridColor", Color(0x44, 0x3A, 0x5E))
            UIManager.put("TextField.background", Color(0x2C, 0x24, 0x44))
            UIManager.put("TextField.foreground", ink)
            UIManager.put("TextField.caretForeground", ink)
            UIManager.put("TextArea.background", Color(0x2C, 0x24, 0x44))
            UIManager.put("TextArea.foreground", ink)
            UIManager.put("TextArea.caretForeground", ink)
            UIManager.put("List.background", panel)
            UIManager.put("List.foreground", ink)
            UIManager.put("List.selectionBackground", Color(0x4C, 0x1D, 0x95))
            UIManager.put("List.selectionForeground", ink)
            UIManager.put("ProgressBar.background", bg)
        }
        frame = JFrame("OtoTag")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        model = RowModel()
        table = JTable(model)
        table.columnModel.getColumn(0).maxWidth = 32
        table.columnModel.getColumn(1).maxWidth = 48
        table.columnModel.getColumn(2).preferredWidth = 240
        table.rowHeight = 44
        // vivid theme: lavender panels, coloured buttons, violet table header
        val themeBg = if (darkMode) Color(0x1E, 0x1A, 0x2B) else Color(0xF6, 0xF2, 0xFF)
        fun vivid(btn: JButton, hex: Int) {
            btn.background = Color(hex); btn.foreground = Color.WHITE
            btn.isOpaque = true; btn.isBorderPainted = false
            btn.font = btn.font.deriveFont(Font.BOLD)
        }
        (table.tableHeader.defaultRenderer as? javax.swing.table.DefaultTableCellRenderer)?.let {
            it.background = Color(0x6D, 0x28, 0xD9); it.foreground = Color.WHITE
        }
        table.selectionBackground = if (darkMode) Color(0x4C, 0x1D, 0x95)
            else Color(0xED, 0xE9, 0xFF)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val r = table.rowAtPoint(e.point)
                val c = table.columnAtPoint(e.point)
                if (r < 0) return
                val disp = displayRows().getOrNull(r) ?: return
                if (disp is String) {
                    // Folder header: collapse / expand its songs.
                    if (disp in collapsedFolders) collapsedFolders.remove(disp)
                    else collapsedFolders.add(disp)
                    refresh()
                } else if (disp is Row && (c == 4 || c == 5)) {
                    // Clicking an artist / album cell quick-filters by it.
                    val v = if (c == 4) disp.tag?.artist else disp.tag?.album
                    if (!v.isNullOrBlank()) {
                        quickFilter = v; updateQuickBar(); refresh()
                    }
                }
            }
        })
        if (darkMode) table.selectionForeground = Color(0xF0, 0xEB, 0xFA)

        val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply { background = themeBg }
        top.add(JButton(Strings.t("choose_folder")).apply { vivid(this, 0x6D28D9) }.apply {
            addActionListener { pickFolderOrHistory() }
        })
        top.add(JButton(Strings.t("choose_files")).apply { vivid(this, 0xDB2777) }.apply {
            addActionListener {
                val fc = JFileChooser().apply { isMultiSelectionEnabled = true }
                if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) addFiles(fc.selectedFiles.toList(), startChecked = true)
            }
        })
        top.add(JButton(Strings.t("history")).apply { vivid(this, 0x0D9488) }.apply {
            addActionListener { showHistory() }
        })
        top.add(JButton(Strings.t("settings")).apply { vivid(this, 0xDB2777) }.apply {
            addActionListener { showSettings() }
        })
        val filterBox = JComboBox(arrayOf(
            Strings.t("filter_all"), Strings.t("filter_notag"), Strings.t("filter_nocover"),
            Strings.t("filter_error"), Strings.t("filter_no_title"), Strings.t("filter_no_artist"),
            Strings.t("filter_no_year"), Strings.t("filter_no_lyrics")))
        filterBox.selectedIndex = filterMode
        filterBox.addActionListener {
            if (filterBox.selectedIndex != filterMode) {
                filterMode = filterBox.selectedIndex
                refresh()
            }
        }
        top.add(filterBox)
        val sortBox = JComboBox(arrayOf(
            Strings.t("sort_name"), Strings.t("sort_title"),
            Strings.t("sort_album"), Strings.t("sort_artist"), Strings.t("sort_updated")))
        sortBox.selectedIndex = sortMode
        sortBox.addActionListener {
            if (sortBox.selectedIndex != sortMode) {
                sortMode = sortBox.selectedIndex
                refresh()
            }
        }
        top.add(JLabel(Strings.t("sort")))
        top.add(sortBox)
        top.add(JButton("\u2191\u2193").apply {
            addActionListener { sortReverse = !sortReverse; refresh() }
        })
        top.add(JButton(Strings.t("back_to_top")).apply {
            addActionListener {
                if (table.rowCount > 0)
                    table.scrollRectToVisible(table.getCellRect(0, 0, true))
            }
        })
        top.add(JButton(Strings.t("help_title")).apply { vivid(this, 0x6D28D9) }.apply {
            addActionListener {
                val area = JTextArea(Strings.t("help_body")).apply {
                    isEditable = false; lineWrap = true; wrapStyleWord = true
                }
                val dlg = JDialog(frame, Strings.t("help_title"), true)
                dlg.contentPane.add(JScrollPane(area))
                dlg.setSize(480, 320); dlg.setLocationRelativeTo(frame)
                dlg.isVisible = true
            }
        })
        val langBox = JComboBox(Strings.LANGS.map { Strings.LANG_NAMES[it] }.toTypedArray())
        langBox.selectedIndex = Strings.LANGS.indexOf(Strings.lang)
        langBox.addActionListener {
            val chosen = Strings.LANGS[langBox.selectedIndex]
            if (chosen != Strings.lang) {
                saveProp("lang", chosen)
                JOptionPane.showMessageDialog(frame, "Language: ${Strings.LANG_NAMES[chosen]} (restart to apply fully)")
                Strings.lang = chosen
            }
        }
        top.add(JLabel(Strings.t("language"))); top.add(langBox)
        top.add(JLabel(Strings.t("drop_here")))

        val actions = JPanel(FlowLayout(FlowLayout.LEFT)).apply { background = themeBg }
        actions.add(JButton(Strings.t("edit")).apply { vivid(this, 0x6D28D9) }.apply {
            addActionListener {
                val sel = checkedRows()
                when {
                    sel.isEmpty() -> info(Strings.t("select_some"))
                    sel.size > 1 -> bulkEdit()
                    else -> EditDialog(sel[0]).isVisible = true
                }
            }
        })
        actions.add(JButton(Strings.t("play")).apply { vivid(this, 0x0D9488) }.apply {
            addActionListener {
                val sel = checkedRows()
                if (sel.size != 1) info(Strings.t("select_one"))
                else try { Desktop.getDesktop().open(sel[0].file) }
                catch (e: Exception) { info("${Strings.t("failed")}: ${e.message}") }
            }
        })
        actions.add(JButton(Strings.t("search_apply")).apply { vivid(this, 0xDB2777); addActionListener { searchApplySelected() } })
        actions.add(JButton(Strings.t("rename")).apply { vivid(this, 0xF97316); addActionListener { bulkRename() } })
        actions.add(JButton(Strings.t("undo")).apply { vivid(this, 0x64748B); addActionListener { undoSelected() } })
        actions.add(JButton(Strings.t("delete_file")).apply { vivid(this, 0xBE2828); addActionListener { deleteSelected() } })
        val selectAllBtn = JButton(Strings.t("select_all"))
        selectAllButton = selectAllBtn
        selectAllBtn.addActionListener {
            // Toggle over what is currently shown (after filters):
            // anything checked clears, nothing checked selects all.
            val shown = shownRows()
            val target = shown.none { it.checked }
            for (r in shown) r.checked = target
            refresh()
            updateSelectAllLabelWin()
        }
        actions.add(selectAllBtn)

        quickBar = JLabel("").apply {
            foreground = Color(0x6D, 0x28, 0xD9)
            isVisible = false
            border = BorderFactory.createEmptyBorder(2, 10, 2, 10)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    quickFilter = null; updateQuickBar(); refresh()
                }
            })
        }
        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = themeBg
            add(top); add(quickBar)
        }
        val scrollPane = JScrollPane(table)
        scrollPane.viewport.addChangeListener {
            val rect = table.visibleRect
            val first = table.rowAtPoint(Point(0, rect.y))
            val last = table.rowAtPoint(Point(0, rect.y + rect.height - 1))
            if (first >= 0 && last >= first) visibleCenterRow = (first + last) / 2
        }
        val root = JPanel(BorderLayout()).apply { background = themeBg }
        root.add(north, BorderLayout.NORTH)
        root.add(scrollPane, BorderLayout.CENTER)
        root.add(actions, BorderLayout.SOUTH)
        frame.contentPane = root
        frame.setSize(880, 560)
        frame.setLocationRelativeTo(null)

        DropTarget(frame, object : DropTargetAdapter() {
            override fun drop(ev: DropTargetDropEvent) {
                try {
                    ev.acceptDrop(DnDConstants.ACTION_COPY)
                    val files = ev.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    val images = files.filter { MbClient.isImageFile(it) }
                    val audio = files.filter { !MbClient.isImageFile(it) }
                    if (audio.isNotEmpty()) {
                        addFiles(audio.filter { it.isFile }, startChecked = true)
                        addFiles(audio.filter { !it.isFile })
                    }
                    if (images.isNotEmpty()) {
                        val sel = checkedRows()
                        if (sel.isNotEmpty() && JOptionPane.showConfirmDialog(frame,
                                Strings.t("bulk_cover"), "OtoTag", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                            val imgFile = images[0]; val cap = coverCapPx()
                            thread {
                                val bytes = capCoverBytes(imgFile.readBytes(), cap)
                                for (row in sel) {
                                    try {
                                        val cur = TagService.read(row.file)
                                        val d = cur.copy(cover = bytes)
                                        Backup.backup(row.file, backupDir)
                                        TagService.write(row.file, d)
                                        row.setTagLight(TagService.read(row.file))
                                        row.failed = false
                                    } catch (e: Exception) {
                                        row.status = "${Strings.t("failed")}: ${e.message}"
                                        row.failed = true
                                    }
                                }
                                refresh()
                            }
                        }
                    }
                    ev.dropComplete(true)
                } catch (ex: Exception) { ev.dropComplete(false) }
            }
        })
        frame.isVisible = true
        checkUpdateDesktop()
    }
}
