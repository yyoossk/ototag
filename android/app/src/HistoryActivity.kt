package com.yyoo.ototag

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yyoo.ototag.core.Strings
import com.yyoo.ototag.core.UpdateChecker
import kotlin.concurrent.thread

/** Update history: the published GitHub releases, newest first. */
class HistoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(if (Ui.dark) android.R.style.Theme_Material
            else android.R.style.Theme_Material_Light)
        title = Strings.t("history")

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        scroll.addView(root)
        setContentView(scroll)

        val status = TextView(this).apply {
            text = Strings.t("loading"); setTextColor(Ui.SUB); textSize = 15f
        }
        root.addView(status)

        thread {
            val releases = try { UpdateChecker.fetchReleases() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                root.removeView(status)
                if (releases.isEmpty()) {
                    root.addView(TextView(this).apply {
                        text = Strings.t("no_results"); setTextColor(Ui.SUB); textSize = 15f
                    })
                    return@runOnUiThread
                }
                for (r in releases) {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(10), dp(14), dp(12))
                        background = Ui.cardBg(this@HistoryActivity)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(10) }
                    }
                    card.addView(TextView(this).apply {
                        text = "${r.tag}   ${r.publishedAt}"
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Ui.INK)
                    })
                    if (r.body.isNotBlank()) {
                        card.addView(TextView(this).apply {
                            text = UpdateChecker.localizedBody(r.body, Strings.lang)
                            textSize = 14f
                            setTextColor(Ui.INK)
                            setPadding(0, dp(6), 0, 0)
                        })
                    }
                    root.addView(card)
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
