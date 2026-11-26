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
    val BG = 0xFFF6F2FF.toInt()       // soft lavender background
    val CARD = 0xFFFFFFFF.toInt()     // list card background
    val INK = 0xFF2A2140.toInt()      // main text, deep purple ink
    val SUB = 0xFF7A7396.toInt()      // secondary text

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

    fun card(ctx: Context, radiusDp: Float = 12f): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dpf(ctx, radiusDp)
    }

    /** White bold text on a coloured rounded background: the house button style. */
    fun styleButton(btn: Button, color: Int, radiusDp: Float = 14f) {
        btn.background = buttonBg(btn.context, color, radiusDp)
        btn.setTextColor(Color.WHITE)
        btn.typeface = Typeface.DEFAULT_BOLD
        btn.stateListAnimator = null
    }
}
