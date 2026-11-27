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
import com.yyoo.ototag.core.ItunesClient
import com.yyoo.ototag.core.ItunesTrack
import com.yyoo.ototag.core.MbCandidate
import com.yyoo.ototag.core.MbClient
import com.yyoo.ototag.core.NameParser
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
        progressLabel: String? = null,
        onSkip: (() -> Unit)? = null,
        onStop: (() -> Unit)? = null,
        onPicked: (TagData) -> Unit
    ) {
        val stopped = AtomicBoolean(false)
        val progress = showProgress(activity, Strings.t("searching"), progressLabel) {
            stopped.set(true)
            onStop?.invoke()
        }
        thread {
            val found = LinkedHashMap<String, MbCandidate>()
            var mbError: Exception? = null
            try {
                if (tag != null && !tag.isBasicallyEmpty()) {
                    for (c in MbClient.searchByTagsOrName(tag.title, tag.artist, tag.album))
                        found.putIfAbsent(c.recordingId, c)
                }
                if (found.isEmpty() && parsed != null) {
                    for ((t, a, al) in NameParser.queries(parsed)) {
                        for (c in MbClient.searchByTagsOrName(t, a, al))
                            found.putIfAbsent(c.recordingId, c)
                        if (found.isNotEmpty()) break
                    }
                }
            } catch (e: Exception) { mbError = e }
            // AnimeThemes (anime OP/ED) as a second source; best effort only.
            val atSongs = try {
                val q1 = tag?.title?.takeIf { it.isNotBlank() } ?: parsed?.title ?: ""
                val q2 = tag?.artist?.takeIf { it.isNotBlank() } ?: parsed?.artist ?: ""
                val first = if (q1.isNotBlank()) AnimeThemesClient.searchSongs(q1) else emptyList()
                if (first.isNotEmpty() || q2.isBlank() || q2 == q1) first
                else AnimeThemesClient.searchSongs(q2)
            } catch (e: Exception) { emptyList() }
            // iTunes (Apple Music catalog) as a third source; best effort only.
            val itunes = try {
                val q1 = listOfNotNull(
                    tag?.title?.takeIf { it.isNotBlank() },
                    tag?.artist?.takeIf { it.isNotBlank() }).joinToString(" ")
                val q2 = listOfNotNull(parsed?.title, parsed?.artist).joinToString(" ")
                val first = if (q1.isNotBlank()) ItunesClient.search(q1) else emptyList()
                if (first.isNotEmpty() || q2.isBlank() || q2 == q1) first
                else ItunesClient.search(q2)
            } catch (e: Exception) { emptyList() }
            if (stopped.get()) return@thread
            val cands = found.values.toList()
            activity.runOnUiThread {
                if (stopped.get()) return@runOnUiThread
                progress.dismiss()
                if (cands.isEmpty() && atSongs.isEmpty() && itunes.isEmpty()) {
                    if (mbError != null) {
                        Toast.makeText(activity, "${Strings.t("failed")}: ${mbError.message}", Toast.LENGTH_LONG).show()
                        onStop?.invoke()
                    } else {
                        Toast.makeText(activity, Strings.t("no_results"), Toast.LENGTH_LONG).show()
                        onSkip?.invoke()
                    }
                } else showChooser(activity, cands, atSongs, itunes, onSkip, onPicked)
            }
        }
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

    /** One candidate row: big title line, smaller artist/album line under it. */
    private class Opt(val title: String, val sub: String, val build: () -> TagData)

    private fun showChooser(
        activity: Activity,
        cands: List<MbCandidate>,
        atSongs: List<AtSong>,
        itunes: List<ItunesTrack>,
        onSkip: (() -> Unit)?,
        onPicked: (TagData) -> Unit
    ) {
        val options = ArrayList<Opt>()
        for (c in cands) {
            if (c.releases.isEmpty()) {
                options.add(Opt(c.title, c.artist) {
                    TagData(title = c.title, artist = c.artist, mbRecordingId = c.recordingId)
                })
            }
            for (r in c.releases) {
                val yr = r.date.take(4)
                val meta = listOf(yr, r.country).filter { it.isNotBlank() }.joinToString(", ")
                options.add(Opt(c.title, "${c.artist} — ${r.title} ($meta)") {
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
            options.add(Opt(s.title, "${s.artist}$animePart$labelPart · AnimeThemes") {
                s.toTag()
            })
        }
        for (t in itunes) {
            val yr = if (t.year.isNotBlank()) " (${t.year})" else ""
            options.add(Opt(t.title, "${t.artist} — ${t.album}$yr · iTunes") {
                ItunesClient.buildTag(t)
            })
        }
        val d = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val adapter = object : android.widget.ArrayAdapter<Opt>(activity, 0, options) {
            override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val o = options[pos]
                val row = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(d(16), d(10), d(16), d(10))
                }
                row.addView(android.widget.TextView(activity).apply {
                    text = o.title; textSize = 16f
                    setTextColor(Ui.INK)
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                })
                row.addView(android.widget.TextView(activity).apply {
                    text = o.sub; textSize = 13f
                    setTextColor(Ui.SUB)
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                })
                return row
            }
        }
        val listView = android.widget.ListView(activity).apply {
            this.adapter = adapter
            dividerHeight = 1
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(Strings.t("candidates"))
            .setView(listView)
        if (onSkip != null) {
            builder.setNegativeButton(Strings.t("skip")) { _, _ -> onSkip() }
        } else {
            builder.setNegativeButton(Strings.t("cancel"), null)
        }
        val dlg = builder.create()
        listView.setOnItemClickListener { _, _, which, _ ->
            dlg.dismiss()
            val progress = showProgress(activity, Strings.t("working"), null, null)
            thread {
                try {
                    val tag = options[which].build()
                    activity.runOnUiThread {
                        progress.dismiss()
                        onPicked(tag)
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        progress.dismiss()
                        Toast.makeText(activity, "${Strings.t("failed")}: ${e.message}", Toast.LENGTH_LONG).show()
                        onSkip?.invoke()
                    }
                }
            }
        }
        dlg.show()
    }
}
