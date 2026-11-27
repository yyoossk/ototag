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
         * Bounds how many decoded list icons stay in memory: beyond the
         * cap, the least recently shown ones are dropped (they decode
         * again when scrolled back to).
         */
        private const val ICON_CAP = 48
        private val iconLru = object : LinkedHashMap<Row, Unit>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Row, Unit>
            ): Boolean {
                if (size > ICON_CAP) {
                    eldest.key.icon = null
                    eldest.key.iconFor = 0
                    return true
                }
                return false
            }
        }

        @Synchronized fun touchIcon(row: Row) { iconLru[row] = Unit }
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

fun shownRows(): List<Row> {
    val filtered = when (filterMode) {
        1 -> rows.filter { it.tag == null || it.tag!!.isBasicallyEmpty() }
        2 -> rows.filter { !it.hasCover }
        3 -> rows.filter { it.failed }
        4 -> rows.filter { it.tag?.title.isNullOrBlank() }
        5 -> rows.filter { it.tag?.artist.isNullOrBlank() }
        6 -> rows.filter { it.tag?.year.isNullOrBlank() }
        7 -> rows.filter { it.tag?.lyrics.isNullOrBlank() }
        else -> rows
    }
    return when (sortMode) {
        1 -> filtered.sortedBy { (it.tag?.title?.ifBlank { null } ?: it.file.name).lowercase() }
        2 -> filtered.sortedBy { (it.tag?.album?.ifBlank { null } ?: it.file.name).lowercase() }
        3 -> filtered.sortedBy { (it.tag?.artist?.ifBlank { null } ?: it.file.name).lowercase() }
        else -> filtered.sortedBy { it.file.name.lowercase() }
    }
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
    override fun getRowCount() = shownRows().size
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
    override fun isCellEditable(r: Int, c: Int) = c == 0
    override fun getValueAt(r: Int, c: Int): Any = when (c) {
        0 -> shownRows()[r].checked
        1 -> shownRows()[r].coverIcon() ?: noCoverIcon
        2 -> shownRows()[r].file.name
        3 -> shownRows()[r].tag?.title ?: ""
        4 -> shownRows()[r].tag?.artist ?: ""
        5 -> shownRows()[r].tag?.album ?: ""
        else -> shownRows()[r].status
    }
    override fun setValueAt(v: Any?, r: Int, c: Int) {
        if (c == 0) { shownRows()[r].checked = v as Boolean; fireTableCellUpdated(r, c) }
    }
}

fun refresh() { SwingUtilities.invokeLater { model.fireTableDataChanged() } }

fun addFiles(files: List<File>, startChecked: Boolean = false) {
    thread {
        val found = mutableListOf<File>()
        for (f in files) collectAudio(f, found)
        for (f in found.sortedBy { it.name.lowercase() }) {
            if (rows.any { it.file.absolutePath == f.absolutePath }) continue
            // Files added one by one start out checked; folder loads do not.
            val row = Row(f).apply { checked = startChecked }
            try {
                val full = TagService.read(f)
                row.setTagLight(full)
                if (full.cover != null) row.primeIcon(full.cover!!) // icon from bytes in hand
            } catch (e: Exception) { row.status = e.message ?: ""; row.failed = true }
            rows.add(row)
        }
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

/** Folder button: offer the recent folders first when there are any. */
fun pickFolderOrHistory() {
    val recent = recentDirs().map { File(it) }.filter { it.isDirectory }
    if (recent.isEmpty()) {
        val fc = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            rememberDir(fc.selectedFile)
            addFiles(listOf(fc.selectedFile))
        }
        return
    }
    val labels = recent.map { it.absolutePath } + Strings.t("pick_new_folder")
    val pick = JOptionPane.showInputDialog(frame, Strings.t("recent_folders"),
        Strings.t("choose_folder"), JOptionPane.PLAIN_MESSAGE, null,
        labels.toTypedArray(), labels[0]) as? String ?: return
    if (pick == Strings.t("pick_new_folder")) {
        val fc = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            rememberDir(fc.selectedFile)
            addFiles(listOf(fc.selectedFile))
        }
    } else {
        val dir = File(pick)
        rememberDir(dir)
        addFiles(listOf(dir))
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
    val autoQuery = listOfNotNull(
        tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title,
        tag?.artist?.takeIf { it.isNotBlank() } ?: parsed?.artist
    ).joinToString(" ").trim()
    val query: String
    val auto: Boolean
    if (presetQuery != null) {
        // A narrowed re-search (title only / artist only): no prompt.
        query = presetQuery
        auto = false
    } else {
        val typed = JOptionPane.showInputDialog(frame, Strings.t("enter_query"), autoQuery)
        if (typed == null || typed.isBlank()) { onStop?.invoke(); return }
        query = typed.trim()
        auto = query == autoQuery
    }
    val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    var progress: JDialog? = null // only touched on the EDT (creation and disposal are both invokeLater)
    SwingUtilities.invokeLater {
        progress = progressDialog(Strings.t("searching"), progressLabel) {
            stopped.set(true); onStop?.invoke()
        }.also { it.isVisible = true }
    }
    class DlgOpt(val title: String, val sub: String, val iconUrl: String?, val build: () -> TagData)
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
                options.add(DlgOpt(c.title, "${c.artist} · AcoustID", null) {
                    TagData(title = c.title, artist = c.artist, mbRecordingId = c.recordingId)
                })
            }
            for (r in c.releases) {
                val meta = listOf(r.date.take(4), r.country).filter { it.isNotBlank() }.joinToString(", ")
                options.add(DlgOpt(c.title, "${c.artist} — ${r.title} ($meta) · AcoustID",
                    MbClient.coverUrl(r.id).replace("front-250", "front-120")) {
                    MbClient.buildTag(c, r)
                })
            }
        }
        for (c in mb) {
            if (c.releases.isEmpty()) {
                options.add(DlgOpt(c.title, c.artist, null) {
                    TagData(title = c.title, artist = c.artist, mbRecordingId = c.recordingId)
                })
            }
            for (r in c.releases) {
                val meta = listOf(r.date.take(4), r.country).filter { it.isNotBlank() }.joinToString(", ")
                options.add(DlgOpt(c.title, "${c.artist} — ${r.title} ($meta)",
                    MbClient.coverUrl(r.id).replace("front-250", "front-120")) {
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
            options.add(DlgOpt(s.title, "${s.artist}$animePart$labelPart · AnimeThemes", null) {
                s.toTag()
            })
        }
        for (t in itunes) {
            val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
            options.add(DlgOpt(t.title, "${t.artist} — ${t.album}$yr · iTunes",
                t.artworkUrl.ifBlank { null }?.replace("600x600bb.jpg", "100x100bb.jpg")) {
                ItunesClient.buildTag(t)
            })
        }
        for (t in deezer) {
            options.add(DlgOpt(t.title, "${t.artist} — ${t.album} · Deezer",
                t.coverUrl.ifBlank { null }?.replace("1000x1000", "150x150")) {
                DeezerClient.buildTag(t)
            })
        }
        for (t in vgmdb) {
            val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
            options.add(DlgOpt(t.title, "${t.artist} — ${t.album}$yr · VGMdb",
                t.coverThumbUrl.ifBlank { null }) {
                VgmdbClient.buildTag(t)
            })
        }
        for (t in audius) {
            val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
            options.add(DlgOpt(t.title, "${t.artist}$yr · Audius",
                t.coverThumbUrl.ifBlank { null }) {
                AudiusClient.buildTag(t)
            })
        }
        for (r in ndl) {
            val yr = if (r.year.isNotBlank()) " (${r.year})" else ""
            options.add(DlgOpt(r.album, "${r.artist}$yr · NDL", null) {
                NdlClient.buildTag(r)
            })
        }
        return options
    }
    var chooserAppend: ((List<DlgOpt>) -> Unit)? = null
    /** Opens the candidate chooser; later sources append via chooserAppend. */
    fun openChooser(initial: MutableList<DlgOpt>) {
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
                    val text = JLabel(
                        "<html><div style='width:440px'><b>${esc(value.title)}</b><br>" +
                            "<font color='${if (darkMode) "#B8B0D0" else "#666666"}'>${esc(value.sub)}</font></div></html>")
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
            // No Skip/Cancel button: closing the window without applying
            // counts as skipping this file.
            var picked = false
            dlg.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            dlg.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosed(e: java.awt.event.WindowEvent) {
                    if (!picked) onSkip?.invoke()
                }
            })
            val ok = JButton(Strings.t("apply")).apply {
                addActionListener {
                    val idx = list.selectedIndex
                    val opt = if (idx in 0 until model.size) model.getElementAt(idx) else null
                    if (opt != null) picked = true
                    dlg.dispose()
                    if (opt != null) {
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
            val btns = JPanel().apply { add(ok) }
            dlg.contentPane.layout = BorderLayout()
            dlg.add(JScrollPane(list), BorderLayout.CENTER)
            dlg.add(btns, BorderLayout.SOUTH)
            dlg.setSize(640, 420); dlg.setLocationRelativeTo(frame)
            // Late sources append their candidates to the open chooser.
            chooserAppend = { more ->
                SwingUtilities.invokeLater {
                    if (dlg.isShowing) {
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
                if (!auto) {
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
                "${r.tag}   ${r.publishedAt}\n${r.body.trim()}"
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
    bottom.add(darkBox); bottom.add(JLabel(""))
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
    val fullOld = try { TagService.read(row.file) } catch (e: Exception) { row.tag }
    searchAndPick(fullOld, NameParser.parse(row.file), row.file, label, onSkip, onStop, bulk) { picked ->
        // Partial sources (Audius / NDL) only fill what they know.
        val merged = if (picked.partial)
            picked.mergedOver(fullOld ?: TagData()) else picked
        // The cover cap applies to covers the candidate brought; a cover
        // the file already had is left as it is.
        val newTag = if (picked.cover != null)
            merged.copy(cover = capCoverBytes(merged.cover, coverCapPx()))
        else merged
        thread {
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
                if (newName.isBlank() || newName == row.file.name) {
                    JOptionPane.showMessageDialog(frame, summary, Strings.t("applied"),
                        JOptionPane.INFORMATION_MESSAGE)
                    done(); return@invokeLater
                }
                val res = JOptionPane.showConfirmDialog(frame,
                    "$summary\n\n${Strings.t("ask_rename")}\n${row.file.name}\n→ $newName",
                    Strings.t("applied"), JOptionPane.YES_NO_OPTION)
                if (res != JOptionPane.YES_OPTION) { done(); return@invokeLater }
                thread {
                    val renamed = Renamer.rename(row.file, base)
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
    private val lyricsArea = JTextArea(row.tag?.lyrics ?: "", 6, 34).apply {
        lineWrap = true; wrapStyleWord = true
    }
    private val mxmKeyField = JTextField(
        loadProps().getProperty("musixmatch_key") ?: "", 34)
    private var coverBytes: ByteArray? = row.tag?.cover
    private var coverRemoved = false
    private var mbIds = (row.tag?.mbRecordingId ?: "") to (row.tag?.mbReleaseId ?: "")
    private val coverLabel = JLabel("", SwingConstants.CENTER).apply {
        preferredSize = Dimension(180, 180); border = BorderFactory.createLineBorder(Color.GRAY)
    }

    init {
        val d = row.tag ?: TagData()
        val panel = JPanel(GridBagLayout())
        val gc = GridBagConstraints().apply { insets = Insets(3, 6, 3, 6); anchor = GridBagConstraints.WEST }
        var y = 0
        fun addField(key: String, value: String, labelKey: String = key) {
            gc.gridx = 0; gc.gridy = y
            panel.add(JLabel(Strings.t(labelKey)), gc)
            val tf = JTextField(value, 34)
            gc.gridx = 1; panel.add(tf, gc)
            fields[key] = tf; y++
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
        // Quick genre picks next to the (still freely editable) field.
        gc.gridx = 1; gc.gridy = y
        panel.add(JButton("▼ " + Strings.t("genre")).apply {
            addActionListener {
                val pick = JOptionPane.showInputDialog(this@EditDialog,
                    Strings.t("genre"), Strings.t("genre"),
                    JOptionPane.PLAIN_MESSAGE, null,
                    TagData.GENRES.toTypedArray(), fields["genre"]?.text ?: "") as? String
                if (pick != null) fields["genre"]?.text = pick
            }
        }, gc); y++
        addField("year", d.year)
        addField("track", d.track); addField("track_total", d.trackTotal)
        addField("disc", d.disc); addField("disc_total", d.discTotal)
        addField("comment", d.comment); addField("isrc", d.isrc)

        gc.gridx = 0; gc.gridy = y; panel.add(JLabel(Strings.t("lyrics")), gc)
        gc.gridx = 1; panel.add(JScrollPane(lyricsArea), gc); y++
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
                if (coverBytes != null) showCoverZoom() else pickCoverFile()
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
        btns.add(JButton(Strings.t("remove_cover")).apply {
            addActionListener { coverBytes = null; coverRemoved = true; updateCoverLabel() }
        })
        btns.add(JButton(Strings.t("search")).apply {
            addActionListener {
                searchAndPick(collect(), NameParser.parse(row.file), row.file) { picked ->
                    fillFrom(picked)
                }
            }
        })
        btns.add(JButton(Strings.t("save")).apply { addActionListener { save() } })
        btns.add(JButton(Strings.t("cancel")).apply { addActionListener { dispose() } })

        contentPane.layout = BorderLayout()
        add(JScrollPane(panel), BorderLayout.CENTER)
        add(btns, BorderLayout.SOUTH)
        setSize(560, 640); setLocationRelativeTo(frame)
    }

    private var coverChanged = false

    private fun setCover(bytes: ByteArray) {
        coverBytes = bytes; coverRemoved = false; coverChanged = true; updateCoverLabel()
    }

    private fun updateCoverLabel() {
        val b = coverBytes
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

    /** Large view of the current cover art. */
    private fun showCoverZoom() {
        val b = coverBytes ?: return
        val img = try { ImageIO.read(b.inputStream()) } catch (e: Exception) { null } ?: return
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
     * Fetches lyrics for the field values and fills the area: LRCLIB first,
     * then Musixmatch when the user has entered their (free) API key.
     */
    private fun fetchLyrics() {
        val title = fields["title"]?.text ?: ""
        val artist = fields["artist"]?.text ?: ""
        val album = fields["album"]?.text ?: ""
        val dur = row.tag?.durationSec ?: 0
        val key = mxmKeyField.text.trim()
        saveProp("musixmatch_key", key)
        thread {
            val entry = try {
                LrclibClient.fetchBest(title, artist, album, dur)
            } catch (e: Exception) { null }
            var text: String? = entry?.plainLyrics
            // LRCLIB's timestamped lyrics also land next to the file as .lrc
            var lrcSaved = false
            if (entry != null && entry.syncedLyrics.isNotBlank()) {
                lrcSaved = try {
                    File(row.file.parentFile,
                        row.file.nameWithoutExtension + ".lrc")
                        .writeText(entry.syncedLyrics, Charsets.UTF_8)
                    true
                } catch (e: Exception) { false }
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
            SwingUtilities.invokeLater {
                if (found != null) {
                    lyricsArea.text = found
                    if (lrcSaved) info(Strings.t("lrc_saved"))
                }
                else info(Strings.t("no_lyrics"))
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
        // The size cap applies only to a cover replaced in this editor;
        // the file's own cover passes through untouched.
        val d = if (coverChanged && collected.cover != null)
            collected.copy(cover = capCoverBytes(collected.cover, coverCapPx()))
        else collected
        val newBase = Renamer.sanitize(fields["filename"]?.text ?: "")
        thread {
            var fail: String? = null
            try {
                Backup.backup(row.file, backupDir)
                TagService.write(row.file, d)
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
                if (fail == null) dispose() else fail(fail!!)
            }
        }
    }
}

fun bulkEdit() {
    val sel = checkedRows()
    if (sel.isEmpty()) { info(Strings.t("select_some")); return }
    val keys = listOf("album", "album_artist", "genre", "year", "disc", "composer")
    val inputs = HashMap<String, JTextField>()
    val panel = JPanel(GridLayout(keys.size + 1, 2, 6, 6))
    for (k in keys) {
        panel.add(JLabel(Strings.t(k)))
        val tf = JTextField(24); inputs[k] = tf; panel.add(tf)
    }
    var coverBytes: ByteArray? = null
    panel.add(JLabel(Strings.t("cover")))
    panel.add(JButton(Strings.t("bulk_cover")).apply {
        addActionListener {
            val fc = JFileChooser()
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION &&
                MbClient.isImageFile(fc.selectedFile))
                coverBytes = capCoverBytes(fc.selectedFile.readBytes(), coverCapPx())
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
                    "album" -> cur.album; "album_artist" -> cur.albumArtist
                    "genre" -> cur.genre; "year" -> cur.year; "disc" -> cur.disc
                    else -> cur.composer } }
                val d = TagData(
                    title = cur.title, artist = cur.artist, album = v("album"),
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
    val panel = JPanel(BorderLayout(6, 6))
    panel.add(JScrollPane(form).apply { preferredSize = java.awt.Dimension(560, 320) },
        BorderLayout.CENTER)
    val res = JOptionPane.showConfirmDialog(frame, panel, Strings.t("rename"),
        JOptionPane.OK_CANCEL_OPTION)
    if (res != JOptionPane.OK_OPTION) return
    thread {
        var failed = 0
        val targets = HashSet<String>()
        for ((row, tf) in fields) {
            val newBase = Renamer.sanitize(tf.text)
            if (newBase.isBlank()) { failed++; continue }
            if (newBase + "." + row.file.extension == row.file.name) continue
            if (!targets.add((newBase + "." + row.file.extension).lowercase())) { failed++; continue }
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
        info(if (failed == 0) Strings.t("done") else "${Strings.t("failed")}: $failed")
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
            // Small path: when the release carries the jar on its own,
            // download just that (~1 MB) instead of the whole zip.
            if (info.windowsJarUrl.isNotBlank()) {
                val newJar = File(appDir, "OtoTag.jar.new")
                UpdateChecker.download(info.windowsJarUrl, newJar)
                val bat = File(appDir, "apply-update.bat")
                bat.writeText("@echo off\r\n" +
                    "timeout /t 3 /nobreak >nul\r\n" +
                    "if exist \"%~dp0OtoTag.jar.old\" del /Q \"%~dp0OtoTag.jar.old\"\r\n" +
                    "ren \"%~dp0OtoTag.jar\" OtoTag.jar.old\r\n" +
                    "ren \"%~dp0OtoTag.jar.new\" OtoTag.jar\r\n" +
                    "start \"\" \"%~dp0OtoTag.exe\"\r\n")
                ProcessBuilder("cmd", "/c", "start", "/min", bat.absolutePath)
                    .directory(appDir).start()
                kotlin.system.exitProcess(0)
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
            val bat = File(appDir, "apply-update.bat")
            bat.writeText("@echo off\r\n" +
                "timeout /t 3 /nobreak >nul\r\n" +
                "if exist \"%~dp0OtoTag.jar.old\" del /Q \"%~dp0OtoTag.jar.old\"\r\n" +
                "ren \"%~dp0OtoTag.jar\" OtoTag.jar.old\r\n" +
                "xcopy /E /Y /Q \"%~dp0update-new\\*\" \"%~dp0\" >nul\r\n" +
                "start \"\" \"%~dp0OtoTag.exe\"\r\n")
            ProcessBuilder("cmd", "/c", "start", "/min", bat.absolutePath)
                .directory(appDir).start()
            kotlin.system.exitProcess(0)
        } catch (e: Exception) {
            SwingUtilities.invokeLater {
                progress.dispose()
                info("${Strings.t("upd_failed")}: ${e.message}")
            }
        }
    }
}

fun main() {
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
            Strings.t("sort_album"), Strings.t("sort_artist")))
        sortBox.selectedIndex = sortMode
        sortBox.addActionListener {
            if (sortBox.selectedIndex != sortMode) {
                sortMode = sortBox.selectedIndex
                refresh()
            }
        }
        top.add(JLabel(Strings.t("sort")))
        top.add(sortBox)
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
                if (sel.size != 1) info(Strings.t("select_one")) else EditDialog(sel[0]).isVisible = true
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

        val root = JPanel(BorderLayout()).apply { background = themeBg }
        root.add(top, BorderLayout.NORTH)
        root.add(JScrollPane(table), BorderLayout.CENTER)
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
                            val bytes = capCoverBytes(images[0].readBytes(), coverCapPx())
                            thread {
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
