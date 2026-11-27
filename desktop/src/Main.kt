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

/** "No image" placeholder icon for rows without cover art. */
val noCoverIcon: ImageIcon by lazy {
    val img = java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = java.awt.Color(233, 229, 242); g.fillRect(0, 0, 40, 40)
    g.color = java.awt.Color(167, 155, 203)
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
    private var icon: ImageIcon? = null
    private var iconFor: Int = 0

    fun coverIcon(): ImageIcon? {
        val cover = tag?.cover ?: run { icon = null; iconFor = 0; return null }
        val h = cover.contentHashCode()
        if (icon != null && iconFor == h) return icon
        iconFor = h
        icon = try {
            val img = ImageIO.read(cover.inputStream())
            if (img == null) null else ImageIcon(img.getScaledInstance(40, 40, Image.SCALE_SMOOTH))
        } catch (e: Exception) { null }
        return icon
    }
}

val rows = mutableListOf<Row>()
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
    override fun getRowCount() = rows.size
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
        0 -> rows[r].checked
        1 -> rows[r].coverIcon() ?: noCoverIcon
        2 -> rows[r].file.name
        3 -> rows[r].tag?.title ?: ""
        4 -> rows[r].tag?.artist ?: ""
        5 -> rows[r].tag?.album ?: ""
        else -> rows[r].status
    }
    override fun setValueAt(v: Any?, r: Int, c: Int) {
        if (c == 0) { rows[r].checked = v as Boolean; fireTableCellUpdated(r, c) }
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
            try { row.tag = TagService.read(f) } catch (e: Exception) { row.status = e.message ?: "" }
            rows.add(row)
        }
        refresh()
    }
}

fun checkedRows(): List<Row> = rows.filter { it.checked }

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

/** Candidate chooser shared by search-apply and the edit dialog. */
fun searchAndPick(
    tag: TagData?, parsed: ParsedName?, progressLabel: String? = null,
    onSkip: (() -> Unit)? = null, onStop: (() -> Unit)? = null,
    bulk: Boolean = false,
    onPicked: (TagData) -> Unit
) {
    // The user can type / edit the search words before the search runs.
    val autoQuery = listOfNotNull(
        tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title,
        tag?.artist?.takeIf { it.isNotBlank() } ?: parsed?.artist
    ).joinToString(" ").trim()
    val typed = JOptionPane.showInputDialog(frame, Strings.t("enter_query"), autoQuery)
    if (typed == null || typed.isBlank()) { onStop?.invoke(); return }
    val query = typed.trim()
    val auto = query == autoQuery
    val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    var progress: JDialog? = null // only touched on the EDT (creation and disposal are both invokeLater)
    SwingUtilities.invokeLater {
        progress = progressDialog(Strings.t("searching"), progressLabel) {
            stopped.set(true); onStop?.invoke()
        }.also { it.isVisible = true }
    }
    thread {
        val found = LinkedHashMap<String, MbCandidate>()
        var mbError: Exception? = null
        try {
            if (!auto) {
                for (c in MbClient.searchFreeText(query))
                    found.putIfAbsent(c.recordingId, c)
            } else {
                if (tag != null && !tag.isBasicallyEmpty()) {
                    for (c in MbClient.searchByTagsOrName(tag.title, tag.artist, tag.album))
                        found.putIfAbsent(c.recordingId, c)
                }
                if (found.isEmpty() && parsed != null) {
                    for ((t, a, al) in NameParser.queries(parsed)) {
                        for (c in MbClient.searchByTagsOrName(t, a, al)) found.putIfAbsent(c.recordingId, c)
                        if (found.isNotEmpty()) break
                    }
                }
            }
        } catch (e: Exception) { mbError = e }
        // AnimeThemes (anime OP/ED) as a second source; best effort only.
        val atSongs = try {
            if (!auto) AnimeThemesClient.searchSongs(query)
            else {
                val q1 = tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title ?: ""
                val q2 = tag?.artist?.takeIf { it.isNotBlank() } ?: parsed?.artist ?: ""
                val first = if (q1.isNotBlank()) AnimeThemesClient.searchSongs(q1) else emptyList()
                if (first.isNotEmpty() || q2.isBlank() || q2 == q1) first
                else AnimeThemesClient.searchSongs(q2)
            }
        } catch (e: Exception) { emptyList() }
        // iTunes (Apple Music catalog) as a third source; best effort only.
        val itunes = try {
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
        // Deezer as a fourth source; best effort only.
        val deezer = try {
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
        // VGMdb (game / anime soundtracks) as a fifth source; best effort only.
        val vgmdb = try {
            if (!auto) VgmdbClient.search(query)
            else {
                val q1 = tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title ?: ""
                val q2 = tag?.album?.takeIf { it.isNotBlank() } ?: parsed?.albumHint ?: ""
                val first = if (q1.isNotBlank()) VgmdbClient.search(q1) else emptyList()
                if (first.isNotEmpty() || q2.isBlank() || q2 == q1) first
                else VgmdbClient.search(q2)
            }
        } catch (e: Exception) { emptyList() }
        if (stopped.get()) return@thread
        val cands = found.values.toList()
        SwingUtilities.invokeLater {
            progress?.dispose()
            if (stopped.get()) return@invokeLater
            if (cands.isEmpty() && atSongs.isEmpty() && itunes.isEmpty() &&
                deezer.isEmpty() && vgmdb.isEmpty()) {
                if (mbError != null) { fail("${mbError.javaClass.simpleName}: ${mbError.message}"); onStop?.invoke() }
                else { info(Strings.t("no_results")); onSkip?.invoke() }
                return@invokeLater
            }
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            class DlgOpt(val title: String, val sub: String, val iconUrl: String?, val build: () -> TagData)
            val options = ArrayList<DlgOpt>()
            for (c in cands) {
                if (c.releases.isEmpty()) {
                    options.add(DlgOpt(c.title, c.artist, null) {
                        TagData(title = c.title, artist = c.artist, mbRecordingId = c.recordingId)
                    })
                }
                for (r in c.releases) {
                    val meta = listOf(r.date.take(4), r.country).filter { it.isNotBlank() }.joinToString(", ")
                    options.add(DlgOpt(c.title, "${c.artist} — ${r.title} ($meta)", MbClient.coverUrl(r.id)) {
                        MbClient.buildTag(c, r)
                    })
                }
            }
            for (s in atSongs) {
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
                    t.artworkUrl.ifBlank { null }?.replace("600x600bb.jpg", "200x200bb.jpg")) {
                    ItunesClient.buildTag(t)
                })
            }
            for (t in deezer) {
                options.add(DlgOpt(t.title, "${t.artist} — ${t.album} · Deezer",
                    t.coverUrl.ifBlank { null }?.replace("1000x1000", "250x250")) {
                    DeezerClient.buildTag(t)
                })
            }
            for (t in vgmdb) {
                val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
                options.add(DlgOpt(t.title, "${t.artist} — ${t.album}$yr · VGMdb",
                    t.coverUrl.ifBlank { null }) {
                    VgmdbClient.buildTag(t)
                })
            }
            // Cover thumbnails load in the background; rows repaint as they land.
            val iconCache = java.util.Collections.synchronizedMap(HashMap<String, ImageIcon>())
            val iconLoading = java.util.Collections.synchronizedSet(HashSet<String>())
            val list = JList(options.toTypedArray()).apply {
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                selectedIndex = 0
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
                            "<font color='#666666'>${esc(value.sub)}</font></div></html>")
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
            val ok = JButton(Strings.t("apply")).apply {
                addActionListener {
                    val idx = list.selectedIndex
                    dlg.dispose()
                    if (idx >= 0) {
                        val wp = progressDialog(Strings.t("working"), null, null)
                        wp.isVisible = true
                        thread {
                            try {
                                val t = options[idx].build()
                                SwingUtilities.invokeLater { wp.dispose(); onPicked(t) }
                            } catch (e: Exception) {
                                SwingUtilities.invokeLater { wp.dispose() }
                                fail("${e.javaClass.simpleName}: ${e.message}")
                                onSkip?.invoke()
                            }
                        }
                    } else onSkip?.invoke()
                }
            }
            val cancel = JButton(if (bulk) Strings.t("skip") else Strings.t("cancel")).apply {
                addActionListener { dlg.dispose(); onSkip?.invoke() }
            }
            val btns = JPanel().apply { add(ok); add(cancel) }
            dlg.contentPane.layout = BorderLayout()
            dlg.add(JScrollPane(list), BorderLayout.CENTER)
            dlg.add(btns, BorderLayout.SOUTH)
            dlg.setSize(640, 420); dlg.setLocationRelativeTo(frame); dlg.isVisible = true
        }
    }
}

fun applySearchTo(row: Row, label: String?, bulk: Boolean, onSkip: () -> Unit, onStop: () -> Unit, done: () -> Unit) {
    searchAndPick(row.tag, NameParser.parse(row.file), label, onSkip, onStop, bulk) { newTag ->
        thread {
            var ok = false
            try {
                Backup.backup(row.file, backupDir)
                TagService.write(row.file, newTag)
                row.tag = TagService.read(row.file)
                row.status = Strings.t("done")
                // applied via search: uncheck it so a re-run skips it
                row.checked = false
                ok = true
            } catch (e: Exception) { row.status = "${Strings.t("failed")}: ${e.message}" }
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
        addField("title", d.title); addField("artist", d.artist); addField("album", d.album)
        addField("album_artist", d.albumArtist); addField("composer", d.composer)
        addField("genre", d.genre); addField("year", d.year)
        addField("track", d.track); addField("track_total", d.trackTotal)
        addField("disc", d.disc); addField("disc_total", d.discTotal)
        addField("comment", d.comment); addField("isrc", d.isrc)

        gc.gridx = 0; gc.gridy = y; panel.add(JLabel(Strings.t("cover")), gc)
        gc.gridx = 1; panel.add(coverLabel, gc); y++
        updateCoverLabel()
        coverLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { pickCoverFile() }
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
                searchAndPick(collect(), NameParser.parse(row.file)) { picked ->
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

    private fun setCover(bytes: ByteArray) {
        coverBytes = bytes; coverRemoved = false; updateCoverLabel()
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

    private fun fillFrom(d: TagData) {
        fun set(k: String, v: String) { fields[k]?.text = v }
        set("title", d.title); set("artist", d.artist); set("album", d.album)
        set("album_artist", d.albumArtist); set("composer", d.composer); set("genre", d.genre)
        set("year", d.year); set("track", d.track); set("track_total", d.trackTotal)
        set("disc", d.disc); set("disc_total", d.discTotal); set("comment", d.comment); set("isrc", d.isrc)
        mbIds = d.mbRecordingId to d.mbReleaseId
        if (d.cover != null) setCover(d.cover!!)
    }

    private fun collect(): TagData {
        fun v(k: String) = fields[k]?.text ?: ""
        return TagData(
            title = v("title"), artist = v("artist"), album = v("album"),
            albumArtist = v("album_artist"), composer = v("composer"), genre = v("genre"),
            year = v("year"), track = v("track"), trackTotal = v("track_total"),
            disc = v("disc"), discTotal = v("disc_total"), comment = v("comment"),
            isrc = v("isrc"), mbRecordingId = mbIds.first, mbReleaseId = mbIds.second,
            cover = if (coverRemoved) null else coverBytes)
    }

    private fun save() {
        val d = collect()
        val newBase = Renamer.sanitize(fields["filename"]?.text ?: "")
        thread {
            var fail: String? = null
            try {
                Backup.backup(row.file, backupDir)
                TagService.write(row.file, d)
                row.tag = TagService.read(row.file)
                row.status = Strings.t("done")
                if (newBase.isNotBlank() && newBase != row.file.nameWithoutExtension) {
                    val renamed = Renamer.rename(row.file, newBase)
                    if (renamed != null) {
                        Backup.move(row.file, renamed, backupDir)
                        val idx = rows.indexOf(row)
                        if (idx >= 0) rows[idx] = Row(renamed).also {
                            it.checked = false; it.tag = row.tag; it.status = row.status
                        }
                    } else fail = Strings.t("collision")
                }
            } catch (e: Exception) { fail = "${e.javaClass.simpleName}: ${e.message}" }
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
                MbClient.isImageFile(fc.selectedFile)) coverBytes = fc.selectedFile.readBytes()
        }
    })
    val res = JOptionPane.showConfirmDialog(frame, panel, Strings.t("bulk_edit"),
        JOptionPane.OK_CANCEL_OPTION)
    if (res != JOptionPane.OK_OPTION) return
    thread {
        for (row in sel) {
            try {
                val cur = row.tag ?: TagService.read(row.file)
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
                row.tag = TagService.read(row.file)
                row.status = Strings.t("done")
            } catch (e: Exception) { row.status = "${Strings.t("failed")}: ${e.message}" }
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

fun undoSelected() {
    val sel = checkedRows()
    if (sel.isEmpty()) { info(Strings.t("select_some")); return }
    thread {
        var restored = 0
        for (row in sel) {
            try {
                if (Backup.restore(row.file, backupDir)) {
                    row.tag = TagService.read(row.file)
                    row.checked = false // acted on: drop the check
                    row.status = Strings.t("restored"); restored++
                }
            } catch (e: Exception) { row.status = "${Strings.t("failed")}: ${e.message}" }
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
        if (!UpdateChecker.isNewer(info.tag, current) || info.windowsZipUrl.isBlank()) return@thread
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
    MbClient.userAgent = "OtoTag/0.1 ( https://github.com/yyoossk/ototag )"

    SwingUtilities.invokeLater {
        frame = JFrame("OtoTag")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        model = RowModel()
        table = JTable(model)
        table.columnModel.getColumn(0).maxWidth = 32
        table.columnModel.getColumn(1).maxWidth = 48
        table.columnModel.getColumn(2).preferredWidth = 240
        table.rowHeight = 44
        // vivid theme: lavender panels, coloured buttons, violet table header
        val themeBg = Color(0xF6, 0xF2, 0xFF)
        fun vivid(btn: JButton, hex: Int) {
            btn.background = Color(hex); btn.foreground = Color.WHITE
            btn.isOpaque = true; btn.isBorderPainted = false
            btn.font = btn.font.deriveFont(Font.BOLD)
        }
        (table.tableHeader.defaultRenderer as? javax.swing.table.DefaultTableCellRenderer)?.let {
            it.background = Color(0x6D, 0x28, 0xD9); it.foreground = Color.WHITE
        }
        table.selectionBackground = Color(0xED, 0xE9, 0xFF)

        val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply { background = themeBg }
        top.add(JButton(Strings.t("choose_folder")).apply { vivid(this, 0x6D28D9) }.apply {
            addActionListener {
                val fc = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) addFiles(listOf(fc.selectedFile))
            }
        })
        top.add(JButton(Strings.t("choose_files")).apply { vivid(this, 0xDB2777) }.apply {
            addActionListener {
                val fc = JFileChooser().apply { isMultiSelectionEnabled = true }
                if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) addFiles(fc.selectedFiles.toList(), startChecked = true)
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
                            val bytes = images[0].readBytes()
                            thread {
                                for (row in sel) {
                                    try {
                                        val cur = row.tag ?: TagService.read(row.file)
                                        val d = cur.copy(cover = bytes)
                                        Backup.backup(row.file, backupDir)
                                        TagService.write(row.file, d)
                                        row.tag = TagService.read(row.file)
                                    } catch (e: Exception) { row.status = "${Strings.t("failed")}: ${e.message}" }
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
