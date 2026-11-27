package com.yyoo.ototag

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.yyoo.ototag.core.AnimeThemesClient
import com.yyoo.ototag.core.AtSong
import com.yyoo.ototag.core.AudiusClient
import com.yyoo.ototag.core.AudiusTrack
import com.yyoo.ototag.core.DeezerClient
import com.yyoo.ototag.core.DeezerTrack
import com.yyoo.ototag.core.ItunesClient
import com.yyoo.ototag.core.ItunesTrack
import com.yyoo.ototag.core.VgmdbClient
import com.yyoo.ototag.core.VgmdbTrack
import com.yyoo.ototag.core.MbCandidate
import com.yyoo.ototag.core.MbClient
import com.yyoo.ototag.core.NameParser
import com.yyoo.ototag.core.NdlClient
import com.yyoo.ototag.core.NdlRecord
import com.yyoo.ototag.core.ParsedName
import com.yyoo.ototag.core.Strings
import com.yyoo.ototag.core.TagData
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object SearchFlow {

    /**
     * Searches off the UI thread while a progress dialog stays on screen the
     * whole time, until the candidate list replaces it. In a bulk run the
     * caller passes a progressLabel ("2 / 5\nfilename"), onSkip moves to the
     * next file from the chooser, and onStop aborts the run from the
     * progress dialog.
     */
    fun start(
        activity: Activity,
        tag: TagData?,
        parsed: ParsedName?,
        localFile: java.io.File? = null,
        progressLabel: String? = null,
        onSkip: (() -> Unit)? = null,
        onStop: (() -> Unit)? = null,
        bulk: Boolean = false,
        onPicked: (TagData) -> Unit
    ) {
        // The user can type / edit the search words before the search runs.
        val autoQuery = listOfNotNull(
            tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title,
            tag?.artist?.takeIf { it.isNotBlank() } ?: parsed?.artist
        ).joinToString(" ").trim()
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(activity).apply {
            setText(autoQuery)
            setSelection(autoQuery.length)
            hint = Strings.t("enter_query")
            setSingleLine(true)
        }
        val wrap = android.widget.FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(Strings.t("enter_query"))
            .setView(wrap)
            .setPositiveButton(Strings.t("search")) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isBlank()) { onStop?.invoke() }
                else runSearch(activity, tag, parsed, localFile, q, q == autoQuery,
                    progressLabel, onSkip, onStop, bulk, onPicked)
            }
            .setNegativeButton(Strings.t("cancel")) { _, _ -> onStop?.invoke() }
            .show()
    }

    private fun runSearch(
        activity: Activity,
        tag: TagData?,
        parsed: ParsedName?,
        localFile: java.io.File?,
        query: String,
        auto: Boolean,
        progressLabel: String?,
        onSkip: (() -> Unit)?,
        onStop: (() -> Unit)?,
        bulk: Boolean,
        onPicked: (TagData) -> Unit
    ) {
        val stopped = AtomicBoolean(false)
        val progress = showProgress(activity, Strings.t("searching"), progressLabel) {
            stopped.set(true)
            onStop?.invoke()
        }
        thread {
            val found = LinkedHashMap<String, MbCandidate>()
            val fpCands = java.util.Collections.synchronizedList(ArrayList<MbCandidate>())
            var mbError: Exception? = null
            var atSongs: List<AtSong> = emptyList()
            var itunes: List<ItunesTrack> = emptyList()
            var deezer: List<DeezerTrack> = emptyList()
            var vgmdb: List<VgmdbTrack> = emptyList()
            var audius: List<AudiusTrack> = emptyList()
            var ndl: List<NdlRecord> = emptyList()
            // All enabled sources are queried in parallel; the results are
            // still presented in the fixed source order afterwards.
            val jobs = ArrayList<Thread>()
            if (com.yyoo.ototag.core.SearchSources.mb) jobs.add(thread {
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
            if (com.yyoo.ototag.core.SearchSources.animeThemes) jobs.add(thread {
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
            if (com.yyoo.ototag.core.SearchSources.itunes) jobs.add(thread {
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
            if (com.yyoo.ototag.core.SearchSources.deezer) jobs.add(thread {
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
            if (com.yyoo.ototag.core.SearchSources.vgmdb) jobs.add(thread {
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
            if (com.yyoo.ototag.core.SearchSources.audius) jobs.add(thread {
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
            if (com.yyoo.ototag.core.SearchSources.ndl) jobs.add(thread {
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
            // Fingerprint search (AcoustID) runs alongside the text sources
            // when the user has entered their free API key.
            val fpKey = activity.getSharedPreferences("ototag", android.content.Context.MODE_PRIVATE)
                .getString("acoustid_key", "")?.trim() ?: ""
            if (fpKey.isNotBlank() && localFile != null) jobs.add(thread {
                try {
                    val (fp, dur) = Fingerprinter.fingerprint(localFile) ?: return@thread
                    val ids = com.yyoo.ototag.core.AcoustIdClient
                        .lookupRecordingIds(fp, dur, fpKey)
                    for (id in ids.take(5)) {
                        try {
                            MbClient.lookupRecording(id)?.let { fpCands.add(it) }
                        } catch (e: Exception) { }
                    }
                } catch (e: Exception) { }
            })
            // Progressive results: the chooser opens as soon as some
            // source has candidates instead of waiting for the slowest
            // one; sources that finish later append their candidates
            // to the open chooser. (Job keys mirror the creation order.)
            val jobKeys = ArrayList<String>()
            if (com.yyoo.ototag.core.SearchSources.mb) jobKeys.add("mb")
            if (com.yyoo.ototag.core.SearchSources.animeThemes) jobKeys.add("at")
            if (com.yyoo.ototag.core.SearchSources.itunes) jobKeys.add("itunes")
            if (com.yyoo.ototag.core.SearchSources.deezer) jobKeys.add("deezer")
            if (com.yyoo.ototag.core.SearchSources.vgmdb) jobKeys.add("vgmdb")
            if (com.yyoo.ototag.core.SearchSources.audius) jobKeys.add("audius")
            if (com.yyoo.ototag.core.SearchSources.ndl) jobKeys.add("ndl")
            if (fpKey.isNotBlank() && localFile != null) jobKeys.add("fp")
            fun optsFor(key: String): List<Opt> = when (key) {
                "mb" -> buildOpts(mb = synchronized(found) { found.values.toList() })
                "fp" -> buildOpts(fp = synchronized(fpCands) { fpCands.toList() })
                "at" -> buildOpts(at = atSongs)
                "itunes" -> buildOpts(itunes = itunes)
                "deezer" -> buildOpts(deezer = deezer)
                "vgmdb" -> buildOpts(vgmdb = vgmdb)
                "audius" -> buildOpts(audius = audius)
                else -> buildOpts(ndl = ndl)
            }
            val delivered = HashSet<String>()
            var opened = false
            var appendFn: ((List<Opt>) -> Unit)? = null
            var firstAt = 0L
            val pending = ArrayList<Opt>()
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
                    val latch = java.util.concurrent.CountDownLatch(1)
                    activity.runOnUiThread {
                        if (!stopped.get()) {
                            progress.dismiss()
                            appendFn = openChooser(activity, tag, initial, onSkip, bulk, onPicked)
                        }
                        latch.countDown()
                    }
                    latch.await()
                } else if (opened && pending.isNotEmpty()) {
                    val more = ArrayList(pending); pending.clear()
                    activity.runOnUiThread { appendFn?.invoke(more) }
                }
                if (allDone) break
                Thread.sleep(200)
            }
            if (stopped.get()) return@thread
            if (!opened) {
                activity.runOnUiThread {
                    if (stopped.get()) return@runOnUiThread
                    progress.dismiss()
                    if (mbError != null) {
                        Ui.failDialog(activity,
                            "${mbError.javaClass.simpleName}: ${mbError.message}")
                        onStop?.invoke()
                    } else if (jobs.isEmpty()) {
                        Toast.makeText(activity, Strings.t("no_results"), Toast.LENGTH_LONG).show()
                        onSkip?.invoke()
                    } else {
                        showRetryDialog(activity, tag, parsed, localFile,
                            progressLabel, onSkip, onStop, bulk, onPicked)
                    }
                }
            } else if (pending.isNotEmpty()) {
                val more = ArrayList(pending)
                activity.runOnUiThread { appendFn?.invoke(more) }
            }
        }
    }

    /**
     * No candidates at all: offer narrower re-searches with just the
     * title or just the artist before giving up on this file.
     */
    private fun showRetryDialog(
        activity: Activity,
        tag: TagData?,
        parsed: ParsedName?,
        localFile: java.io.File?,
        progressLabel: String?,
        onSkip: (() -> Unit)?,
        onStop: (() -> Unit)?,
        bulk: Boolean,
        onPicked: (TagData) -> Unit
    ) {
        val title = tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title ?: ""
        val artist = tag?.artist?.takeIf { it.isNotBlank() } ?: parsed?.artist ?: ""
        if (title.isBlank() && artist.isBlank()) {
            Toast.makeText(activity, Strings.t("no_results"), Toast.LENGTH_LONG).show()
            onSkip?.invoke()
            return
        }
        val b = AlertDialog.Builder(activity)
            .setTitle(Strings.t("no_results"))
            .setNegativeButton(Strings.t("cancel")) { _, _ -> onSkip?.invoke() }
        if (title.isNotBlank()) b.setPositiveButton(Strings.t("title_only")) { _, _ ->
            runSearch(activity, tag, parsed, localFile, title, false,
                progressLabel, onSkip, onStop, bulk, onPicked)
        }
        if (artist.isNotBlank()) b.setNeutralButton(Strings.t("artist_only")) { _, _ ->
            runSearch(activity, tag, parsed, localFile, artist, false,
                progressLabel, onSkip, onStop, bulk, onPicked)
        }
        b.show()
    }

    /** Indeterminate progress dialog that stays up until dismissed by the flow. */
    private fun showProgress(
        activity: Activity, message: String, label: String?, onCancel: (() -> Unit)?
    ): AlertDialog {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(ProgressBar(activity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.VIOLET)
            })
            addView(TextView(activity).apply {
                text = if (label != null) "$message\n$label" else message
                setPadding(pad, 0, 0, 0)
            })
        }
        val builder = AlertDialog.Builder(activity).setView(row)
        if (onCancel != null) {
            builder.setNegativeButton(Strings.t("cancel")) { _, _ -> onCancel() }
        }
        val dlg = builder.create()
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()
        return dlg
    }

    /** One candidate row: cover thumbnail, big title, smaller artist/album line. */
    private class Opt(val title: String, val sub: String, val iconUrl: String?, val build: () -> TagData)

    // Candidate cover thumbnails: cached for the whole session and decoded
    // at thumbnail size, so reopening a search shows them instantly. The
    // cache is capped (LRU, 120 entries) so a long session cannot grow
    // the app's memory without bound.
    private val iconCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, android.graphics.Bitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(
                e: MutableMap.MutableEntry<String, android.graphics.Bitmap>): Boolean = size > 120
        })
    private val iconLoading = java.util.Collections.synchronizedSet(HashSet<String>())
    private val iconWaiters = java.util.Collections.synchronizedMap(
        HashMap<String, MutableList<() -> Unit>>())

    /**
     * Smallest source variant the CDN offers: the row shows ~52dp, so a
     * small, slightly soft image that arrives fast beats a big sharp one.
     */
    private fun thumbUrl(url: String): String = url
        .replace("600x600bb.jpg", "100x100bb.jpg")
        .replace("1000x1000", "150x150")
        .replace("front-250", "front-120")

    private val iconGate = java.util.concurrent.Semaphore(6)

    private fun loadIcon(url: String, done: () -> Unit) {
        if (iconCache.containsKey(url)) { done(); return }
        synchronized(iconWaiters) { iconWaiters.getOrPut(url) { mutableListOf() }.add(done) }
        if (!iconLoading.add(url)) return
        thread {
            iconGate.acquire()
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val bytes = conn.inputStream.readBytes()
                val bounds = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bmp != null) iconCache[url] = bmp
            } catch (e: Exception) { }
            finally { iconGate.release() }
            iconLoading.remove(url)
            val waiters = synchronized(iconWaiters) { iconWaiters.remove(url) } ?: emptyList()
            for (w in waiters) w()
        }
    }

    /** Builds the chooser rows for whichever sources are passed in. */
    private fun buildOpts(
        mb: List<MbCandidate> = emptyList(),
        fp: List<MbCandidate> = emptyList(),
        at: List<AtSong> = emptyList(),
        itunes: List<ItunesTrack> = emptyList(),
        deezer: List<DeezerTrack> = emptyList(),
        vgmdb: List<VgmdbTrack> = emptyList(),
        audius: List<AudiusTrack> = emptyList(),
        ndl: List<NdlRecord> = emptyList()
    ): MutableList<Opt> {
        val options = ArrayList<Opt>()
        // Fingerprint matches first: they identify this exact recording.
        for (c in fp) {
            if (c.releases.isEmpty()) {
                options.add(Opt(c.title, "${c.artist} · AcoustID", null) {
                    TagData(title = c.title, artist = c.artist, mbRecordingId = c.recordingId)
                })
            }
            for (r in c.releases) {
                val yr = r.date.take(4)
                val meta = listOf(yr, r.country).filter { it.isNotBlank() }.joinToString(", ")
                options.add(Opt(c.title, "${c.artist} — ${r.title} ($meta) · AcoustID",
                    thumbUrl(MbClient.coverUrl(r.id))) {
                    MbClient.buildTag(c, r)
                })
            }
        }
        for (c in mb) {
            if (c.releases.isEmpty()) {
                options.add(Opt(c.title, c.artist, null) {
                    TagData(title = c.title, artist = c.artist, mbRecordingId = c.recordingId)
                })
            }
            for (r in c.releases) {
                val yr = r.date.take(4)
                val meta = listOf(yr, r.country).filter { it.isNotBlank() }.joinToString(", ")
                options.add(Opt(c.title, "${c.artist} — ${r.title} ($meta)", thumbUrl(MbClient.coverUrl(r.id))) {
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
            options.add(Opt(s.title, "${s.artist}$animePart$labelPart · AnimeThemes", null) {
                s.toTag()
            })
        }
        for (t in itunes) {
            val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
            options.add(Opt(t.title, "${t.artist} — ${t.album}$yr · iTunes",
                t.artworkUrl.takeIf { it.isNotBlank() }?.let { thumbUrl(it) }) {
                ItunesClient.buildTag(t)
            })
        }
        for (t in deezer) {
            options.add(Opt(t.title, "${t.artist} — ${t.album} · Deezer",
                t.coverUrl.takeIf { it.isNotBlank() }?.let { thumbUrl(it) }) {
                DeezerClient.buildTag(t)
            })
        }
        for (t in vgmdb) {
            val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
            options.add(Opt(t.title, "${t.artist} — ${t.album}$yr · VGMdb",
                t.coverThumbUrl.ifBlank { null }) {
                VgmdbClient.buildTag(t)
            })
        }
        for (t in audius) {
            val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
            options.add(Opt(t.title, "${t.artist}$yr · Audius",
                t.coverThumbUrl.ifBlank { null }) {
                AudiusClient.buildTag(t)
            })
        }
        for (r in ndl) {
            val yr = if (r.year.isNotBlank()) " (${r.year})" else ""
            options.add(Opt(r.album, "${r.artist}$yr · NDL", null) {
                NdlClient.buildTag(r)
            })
        }
        return options
    }

    /**
     * Shows the candidate chooser for [options] and returns a function
     * that appends more candidates later (progressive search results).
     */
    private fun openChooser(
        activity: Activity,
        currentTag: TagData?,
        options: MutableList<Opt>,
        onSkip: (() -> Unit)?,
        bulk: Boolean,
        onPicked: (TagData) -> Unit
    ): (List<Opt>) -> Unit {
        val d = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        var adapterRef: android.widget.ArrayAdapter<Opt>? = null
        val refreshIcons = { activity.runOnUiThread { adapterRef?.notifyDataSetChanged() } }
        // Start fetching every candidate's cover right away, not on scroll.
        for (o in options) o.iconUrl?.let { loadIcon(it, refreshIcons) }
        val adapter = object : android.widget.ArrayAdapter<Opt>(activity, 0, options) {
            override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val o = options[pos]
                val row = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(d(12), d(10), d(16), d(10))
                }
                row.addView(android.widget.ImageView(activity).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(d(52), d(52))
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    val url = o.iconUrl
                    val bmp = if (url != null) iconCache[url] else null
                    if (bmp != null) setImageBitmap(bmp)
                    else {
                        setImageBitmap(Ui.noCoverBitmap())
                        if (url != null) loadIcon(url, refreshIcons)
                    }
                })
                val texts = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(d(12), 0, 0, 0)
                }
                texts.addView(android.widget.TextView(activity).apply {
                    text = o.title; textSize = 16f
                    setTextColor(Ui.INK)
                })
                texts.addView(android.widget.TextView(activity).apply {
                    text = o.sub; textSize = 13f
                    setTextColor(Ui.SUB)
                })
                row.addView(texts)
                return row
            }
        }
        adapterRef = adapter
        val listView = android.widget.ListView(activity).apply {
            this.adapter = adapter
            dividerHeight = 1
        }
        // No Skip/Cancel button: closing the dialog (back key / outside tap)
        // without picking counts as skipping this file.
        val dlg = AlertDialog.Builder(activity)
            .setTitle(Strings.t("candidates"))
            .setView(listView)
            .create()
        var picked = false
        dlg.setOnDismissListener {
            if (!picked) onSkip?.invoke()
        }
        listView.setOnItemClickListener { _, _, which, _ ->
            picked = true
            dlg.dismiss()
            val progress = showProgress(activity, Strings.t("working"), null, null)
            thread {
                try {
                    val built = options[which].build()
                    activity.runOnUiThread {
                        progress.dismiss()
                        showDiff(activity, currentTag, built, onPicked, onSkip)
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        progress.dismiss()
                        Ui.failDialog(activity, "${e.javaClass.simpleName}: ${e.message}")
                        onSkip?.invoke()
                    }
                }
            }
        }
        // Late sources append their candidates to the open chooser.
        val append: (List<Opt>) -> Unit = { more ->
            if (more.isNotEmpty() && dlg.isShowing) {
                for (o in more) o.iconUrl?.let { loadIcon(it, refreshIcons) }
                options.addAll(more)
                adapter.notifyDataSetChanged()
            }
        }
        dlg.show()
        return append
    }

    /**
     * Pre-apply preview: what the chosen candidate will change versus
     * the file's current tag. Applying proceeds to onPicked with the raw
     * candidate; the caller still does its own partial merge.
     */
    private fun showDiff(
        activity: Activity,
        currentTag: TagData?,
        built: TagData,
        onPicked: (TagData) -> Unit,
        onSkip: (() -> Unit)?
    ) {
        val old = currentTag ?: TagData()
        val merged = if (built.partial) built.mergedOver(old) else built
        val lines = com.yyoo.ototag.core.TagDiff.lines(old, merged)
        AlertDialog.Builder(activity)
            .setTitle(Strings.t("diff_title"))
            .setMessage(if (lines.isEmpty()) Strings.t("no_change")
                else lines.joinToString("\n"))
            .setPositiveButton(Strings.t("apply")) { _, _ -> onPicked(built) }
            .setNegativeButton(Strings.t("cancel")) { _, _ -> onSkip?.invoke() }
            .show()
    }
}
