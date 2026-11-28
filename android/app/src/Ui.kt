package com.yyoo.ototag

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.StateSet
import android.widget.Button

/** Vivid, music-flavoured theme shared by the Android screens. */
object Ui {
    /** Dark palette switch (settings; defaults to the system night mode). */
    var dark = false

    val BG: Int get() = if (dark) 0xFF16121F.toInt() else 0xFFF6F2FF.toInt()
    val CARD: Int get() = if (dark) 0xFF251D36.toInt() else 0xFFFFFFFF.toInt()
    val INK: Int get() = if (dark) 0xFFF0EBFA.toInt() else 0xFF2A2140.toInt()
    val SUB: Int get() = if (dark) 0xFFA79EC4.toInt() else 0xFF7A7396.toInt()

    val VIOLET = 0xFF6D28D9.toInt()
    val PINK = 0xFFDB2777.toInt()
    val MAGENTA = 0xFFC026D3.toInt()
    val ORANGE = 0xFFF97316.toInt()
    val TEAL = 0xFF0D9488.toInt()

    private fun dpf(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    private fun darken(color: Int, factor: Float = 0.82f): Int {
        val r = (Color.red(color) * factor).toInt()
        val g = (Color.green(color) * factor).toInt()
        val b = (Color.blue(color) * factor).toInt()
        return Color.rgb(r, g, b)
    }

    /** Solid rounded background with a darker pressed state. */
    fun buttonBg(ctx: Context, color: Int, radiusDp: Float = 14f): StateListDrawable {
        fun shape(c: Int) = GradientDrawable().apply {
            setColor(c)
            cornerRadius = dpf(ctx, radiusDp)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(darken(color)))
            addState(StateSet.WILD_CARD, shape(color))
        }
    }

    /** Rounded card background used for list rows and history entries. */
    fun cardBg(ctx: Context, radiusDp: Float = 14f): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dpf(ctx, radiusDp)
    }

    /** Sunset gradient band used for the header (violet → fuchsia → orange). */
    fun headerGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(VIOLET, MAGENTA, ORANGE)
    )

    /** Vivid gradient placeholder shown where a cover would go. */
    fun coverPlaceholder(ctx: Context, radiusDp: Float = 10f): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(VIOLET, PINK)
    ).apply { cornerRadius = dpf(ctx, radiusDp) }

    private var noCover: android.graphics.Bitmap? = null
    private var noCoverDark = false

    /**
     * "No image" placeholder for files without cover art: a pale panel with
     * a muted music note, drawn once and reused everywhere.
     */
    fun noCoverBitmap(): android.graphics.Bitmap {
        noCover?.let { if (noCoverDark == dark) return it }
        val size = 156
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(if (dark) 0xFF2E2750.toInt() else 0xFFE9E5F2.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFF7A6FA3.toInt() else 0xFFA79BCB.toInt()
            textSize = 96f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val fm = paint.fontMetrics
        val y = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText("♪", size / 2f, y, paint)
        noCover = bmp
        noCoverDark = dark
        return bmp
    }

    fun card(ctx: Context, radiusDp: Float = 12f): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dpf(ctx, radiusDp)
    }

    /** Big centred failure dialog: a clear "failed" title plus the details. */
    fun failDialog(ctx: Context, details: String, onOk: (() -> Unit)? = null) {
        val d = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }
        val body = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(24), d(8), d(24), d(4))
            addView(android.widget.TextView(ctx).apply {
                text = com.yyoo.ototag.core.Strings.t("failed_title")
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFDC2626.toInt())
                gravity = android.view.Gravity.CENTER
            })
            addView(android.widget.TextView(ctx).apply {
                text = details
                textSize = 14f
                setTextColor(SUB)
                setPadding(0, d(10), 0, 0)
            })
        }
        android.app.AlertDialog.Builder(ctx)
            .setView(body)
            .setPositiveButton("OK") { _, _ -> onOk?.invoke() }
            .show()
    }

    /** White bold text on a coloured rounded background: the house button style. */
    fun styleButton(btn: Button, color: Int, radiusDp: Float = 14f) {
        btn.background = buttonBg(btn.context, color, radiusDp)
        btn.setTextColor(Color.WHITE)
        btn.typeface = Typeface.DEFAULT_BOLD
        btn.stateListAnimator = null
    }
}

/**
 * Genre chooser shared by the editor and the bulk editor: the user's
 * own genres first (each with a delete button), then the built-in
 * TagData.GENRES, the two groups kept apart. Tapping a name picks it.
 */
object GenrePicker {
    fun show(
        ctx: android.app.Activity,
        custom: List<String>,
        hidden: List<String>,
        removedCustom: List<String>,
        persistCustom: (List<String>) -> Unit,
        persistHidden: (List<String>) -> Unit,
        persistRemovedCustom: (List<String>) -> Unit,
        onPick: (String) -> Unit
    ) {
        var mine = custom
        var gone = hidden
        var trashed = removedCustom
        val scroll = android.widget.ScrollView(ctx)
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        scroll.addView(col)
        val dlg = android.app.AlertDialog.Builder(ctx)
            .setTitle(com.yyoo.ototag.core.Strings.t("genre_pick"))
            .setView(scroll)
            .setNegativeButton(com.yyoo.ototag.core.Strings.t("cancel"), null)
            .create()
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
        fun section(parent: android.widget.LinearLayout, text: String) {
            parent.addView(android.widget.TextView(ctx).apply {
                this.text = text; textSize = 13f; setTextColor(Ui.SUB)
                setPadding(0, dp(12), 0, dp(4))
            })
        }
        fun nameRow(name: String, onClick: () -> Unit) =
            android.widget.TextView(ctx).apply {
                text = name; textSize = 17f; setTextColor(Ui.INK)
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { onClick() }
            }
        fun actionRow(name: String, btnText: String, onBtn: () -> Unit,
                      onName: (() -> Unit)?): android.widget.LinearLayout {
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val tv = nameRow(name) { onName?.invoke() }
            tv.layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(tv)
            row.addView(Button(ctx).apply {
                text = btnText
                setOnClickListener { onBtn() }
            })
            return row
        }
        fun confirmDelete(name: String, action: () -> Unit) {
            android.app.AlertDialog.Builder(ctx)
                .setTitle(name)
                .setMessage(com.yyoo.ototag.core.Strings.t("genre_del_confirm"))
                .setPositiveButton(com.yyoo.ototag.core.Strings.t("delete")) { _, _ -> action() }
                .setNegativeButton(com.yyoo.ototag.core.Strings.t("cancel"), null)
                .show()
        }
        fun confirmRestore(message: String, action: () -> Unit) {
            android.app.AlertDialog.Builder(ctx)
                .setMessage(message)
                .setPositiveButton(com.yyoo.ototag.core.Strings.t("genre_restore")) { _, _ -> action() }
                .setNegativeButton(com.yyoo.ototag.core.Strings.t("cancel"), null)
                .show()
        }
        // render() opens the restore screen through this lambda: the
        // two local functions reference each other, which Kotlin only
        // allows one way, so the forward reference goes through a var.
        var openRestore: () -> Unit = {}
        fun render() {
            col.removeAllViews()
            // Add a genre right here: type a name and tap Add.
            val addRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val input = android.widget.EditText(ctx).apply {
                hint = com.yyoo.ototag.core.Strings.t("genre")
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addRow.addView(input)
            addRow.addView(Button(ctx).apply {
                text = com.yyoo.ototag.core.Strings.t("genre_add")
                setOnClickListener {
                    val name = input.text.toString()
                    val now = com.yyoo.ototag.core.GenreList.add(mine, name)
                    if (now != mine) {
                        mine = now
                        trashed = com.yyoo.ototag.core.GenreList.remove(trashed, name.trim())
                        persistCustom(mine); persistRemovedCustom(trashed)
                        render()
                    }
                }
            })
            col.addView(addRow)
            if (mine.isNotEmpty()) {
                section(col, com.yyoo.ototag.core.Strings.t("genre_mine"))
                for (g in mine) {
                    col.addView(actionRow(g, "\u2715", {
                        confirmDelete(g) {
                            mine = com.yyoo.ototag.core.GenreList.remove(mine, g)
                            trashed = com.yyoo.ototag.core.GenreList.trash(trashed, g)
                            persistCustom(mine); persistRemovedCustom(trashed)
                            render()
                        }
                    }) { dlg.dismiss(); onPick(g) })
                }
            }
            section(col, com.yyoo.ototag.core.Strings.t("genre_defaults"))
            for (g in com.yyoo.ototag.core.GenreList.visibleDefaults(gone)) {
                col.addView(actionRow(g, "\u2715", {
                    confirmDelete(g) {
                        gone = com.yyoo.ototag.core.GenreList.hide(gone, g)
                        persistHidden(gone)
                        render()
                    }
                }) { dlg.dismiss(); onPick(g) })
            }
            // Removed genres stay out of this list: one entry opens
            // their own screen, where they can be restored.
            if (trashed.isNotEmpty() || gone.any { it in com.yyoo.ototag.core.TagData.GENRES }) {
                col.addView(nameRow(com.yyoo.ototag.core.Strings.t("genre_removed") + " \u25B8") { openRestore() })
            }
        }
        fun showRestore() {
            val scroll2 = android.widget.ScrollView(ctx)
            val col2 = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 16, 48, 8)
            }
            scroll2.addView(col2)
            val dlg2 = android.app.AlertDialog.Builder(ctx)
                .setTitle(com.yyoo.ototag.core.Strings.t("genre_removed"))
                .setView(scroll2)
                .setNegativeButton(com.yyoo.ototag.core.Strings.t("cancel"), null)
                .create()
            dlg2.setOnDismissListener { render() }
            fun renderRestore() {
                col2.removeAllViews()
                val defs = gone.filter { it in com.yyoo.ototag.core.TagData.GENRES }
                if (defs.isEmpty() && trashed.isEmpty()) { dlg2.dismiss(); return }
                col2.addView(Button(ctx).apply {
                    text = com.yyoo.ototag.core.Strings.t("genre_restore_all")
                    setOnClickListener {
                        confirmRestore(com.yyoo.ototag.core.Strings.t("genre_restore_all_confirm")) {
                            gone = emptyList(); trashed = emptyList()
                            persistHidden(gone); persistRemovedCustom(trashed)
                            renderRestore()
                        }
                    }
                })
                if (trashed.isNotEmpty()) {
                    section(col2, com.yyoo.ototag.core.Strings.t("genre_mine"))
                    for (g in trashed) {
                        col2.addView(actionRow(g, com.yyoo.ototag.core.Strings.t("genre_restore"), {
                            confirmRestore(com.yyoo.ototag.core.Strings.t("genre_restore_confirm")) {
                                trashed = com.yyoo.ototag.core.GenreList.remove(trashed, g)
                                mine = com.yyoo.ototag.core.GenreList.add(mine, g)
                                persistRemovedCustom(trashed); persistCustom(mine)
                                renderRestore()
                            }
                        }, null))
                    }
                }
                if (defs.isNotEmpty()) {
                    section(col2, com.yyoo.ototag.core.Strings.t("genre_defaults"))
                    for (g in defs) {
                        col2.addView(actionRow(g, com.yyoo.ototag.core.Strings.t("genre_restore"), {
                            confirmRestore(com.yyoo.ototag.core.Strings.t("genre_restore_confirm")) {
                                gone = com.yyoo.ototag.core.GenreList.unhide(gone, g)
                                persistHidden(gone)
                                renderRestore()
                            }
                        }, null))
                    }
                }
            }
            renderRestore()
            dlg2.show()
        }
        openRestore = { showRestore() }
        render()
        dlg.show()
    }
}
