package dev.tclawyered.app.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tclawyered.app.data.Videos
import dev.tclawyered.app.model.Summary

/**
 * Floating summary card shown as a system overlay (over whatever app is in front)
 * after a screen read finishes — with an ✕ close button and a "watch how to
 * protect your data" YouTube link. Tapping the link opens YouTube and collapses
 * the card to a small pill; tapping the pill re-opens the summary (return-to-popup).
 *
 * View-based on purpose: rendering Compose inside a WindowManager overlay needs
 * lifecycle / saved-state owner plumbing that isn't worth it for a read-only card.
 * // ponytail: duplicates SummaryView's layout in plain Views to skip Compose-in-overlay plumbing; unify only if a third caller appears.
 */
object SummaryOverlay {
    private var card: View? = null
    private var pill: View? = null
    private var last: Shown? = null

    private data class Shown(
        val summary: Summary,
        val source: String,
        val scannedAt: Long,
        val company: String,
    )

    fun showLoading(context: Context) = renderCard(context) { c ->
        c.addView(body(c.context, "Reading and summarizing… up to a minute for a new document."))
    }

    fun showMessage(context: Context, message: String) = renderCard(context) { c ->
        c.addView(body(c.context, message))
    }

    /** One-time disclaimer before auto-scroll. [onContinue] proceeds; ✕/Cancel dismiss. */
    fun showDisclaimer(context: Context, onContinue: () -> Unit) = renderCard(context) { c ->
        val ctx = c.context
        c.addView(TextView(ctx).apply {
            text = "Auto-scroll reading"
            setTextColor(Color.parseColor("#111111"))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(ctx, 8f), 0, dp(ctx, 6f))
        })
        c.addView(body(ctx, "T&C Lawyered will automatically scroll this page and read what's on screen using on-device OCR. Only the extracted text is sent to your AI provider to summarize — nothing else leaves your phone.\n\nThe accessibility permission is used solely to scroll the page; it never reads screen content on its own."))
        c.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 14f), 0, 0)
            addView(TextView(ctx).apply {
                text = "Cancel"
                setTextColor(Color.parseColor("#5B3DF5"))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 8f), dp(ctx, 10f), dp(ctx, 24f), dp(ctx, 10f))
                setOnClickListener { close() }
            })
            addView(TextView(ctx).apply {
                text = "Continue"
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 24f), dp(ctx, 10f), dp(ctx, 24f), dp(ctx, 10f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#5B3DF5"))
                    cornerRadius = dp(ctx, 22f).toFloat()
                }
                setOnClickListener { onContinue() }
            })
        })
    }

    fun showSummary(
        context: Context,
        summary: Summary,
        source: String,
        scannedAt: Long,
        company: String,
    ) {
        last = Shown(summary, source, scannedAt, company)
        renderSummaryCard(context, last!!)
    }

    /** Fully dismiss both the card and the pill. */
    fun close() {
        removeCard()
        removePill()
        last = null
    }

    val isShowing: Boolean get() = card != null || pill != null

    private fun renderSummaryCard(context: Context, shown: Shown) = renderCard(context) { c ->
        val ctx = c.context
        val s = shown.summary
        section(ctx, c, "TL;DR", listOf(s.tldr.ifEmpty { "—" }))
        if (s.keyRisks.isNotEmpty()) section(ctx, c, "Key risks", s.keyRisks.map { "• $it" })
        if (s.dataCollected.isNotEmpty()) {
            section(ctx, c, "Data collected", s.dataCollected.map {
                "• ${it.item}" + if (it.detail.isNotEmpty()) " — ${it.detail}" else ""
            })
        }
        if (s.thirdPartySharing.isNotEmpty()) section(ctx, c, "Third-party sharing", s.thirdPartySharing.map { "• $it" })
        if (s.userRights.isNotEmpty()) section(ctx, c, "Your rights", s.userRights.map { "• $it" })
        if (s.protectionTips.isNotEmpty()) section(ctx, c, "Protect your data", s.protectionTips.map { "• $it" })

        // "How to protect your data" YouTube link (extension parity). Tapping opens
        // YouTube and collapses to a pill so the user can return to the summary.
        c.addView(TextView(ctx).apply {
            text = "▶  Watch how to protect your data on YouTube"
            setTextColor(Color.parseColor("#1A56DB"))
            textSize = 15f
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(ctx, 16f), 0, dp(ctx, 6f))
            setOnClickListener {
                openUrl(ctx, Videos.buildYoutubeSearchUrl(shown.company))
                collapseToPill(ctx)
            }
        })
    }

    private fun collapseToPill(context: Context) {
        removeCard()
        showPill(context)
    }

    private fun showPill(context: Context) {
        removePill()
        val ctx = context.applicationContext
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = TextView(ctx).apply {
            text = "📄  Summary"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(ctx, 18f), dp(ctx, 12f), dp(ctx, 18f), dp(ctx, 12f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#5B3DF5"))
                cornerRadius = dp(ctx, 24f).toFloat()
            }
            setOnClickListener {
                val shown = last ?: return@setOnClickListener
                removePill()
                renderSummaryCard(ctx, shown)
            }
        }
        val type = overlayType()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(ctx, 80f)
        }
        wm.addView(view, lp)
        pill = view
    }

    private fun openUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun removeCard() {
        val v = card ?: return
        val wm = v.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
        card = null
    }

    private fun removePill() {
        val v = pill ?: return
        val wm = v.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
        pill = null
    }

    /** Build the scrim + card window, let [fill] add the body, and show it. */
    private fun renderCard(context: Context, fill: (LinearLayout) -> Unit) {
        removeCard()
        removePill()
        val ctx = context.applicationContext
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fill(content)
        val scroll = ScrollView(ctx).apply { addView(content) }

        val header = FrameLayout(ctx).apply {
            addView(TextView(ctx).apply {
                text = "Summary"
                setTextColor(Color.parseColor("#111111"))
                textSize = 20f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.CENTER_VERTICAL,
                )
            })
            addView(TextView(ctx).apply {
                text = "✕"
                setTextColor(Color.parseColor("#111111"))
                textSize = 22f
                setPadding(dp(ctx, 12f), dp(ctx, 2f), dp(ctx, 2f), dp(ctx, 2f))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL,
                )
                setOnClickListener { close() }
            })
        }

        val cardView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(ctx, 18f).toFloat()
            }
            setPadding(dp(ctx, 18f), dp(ctx, 14f), dp(ctx, 18f), dp(ctx, 16f))
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(0x99000000.toInt())
            isFocusableInTouchMode = true
            addView(cardView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ).apply { setMargins(dp(ctx, 16f), dp(ctx, 48f), dp(ctx, 16f), dp(ctx, 48f)) })
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    close(); true
                } else {
                    false
                }
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), 0, PixelFormat.TRANSLUCENT,
        )
        wm.addView(scrim, lp)
        card = scrim
    }

    private fun section(ctx: Context, parent: LinearLayout, title: String, lines: List<String>) {
        parent.addView(TextView(ctx).apply {
            text = title
            setTextColor(Color.parseColor("#111111"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(ctx, 14f), 0, dp(ctx, 4f))
        })
        lines.forEach { line ->
            parent.addView(TextView(ctx).apply {
                text = line
                setTextColor(Color.parseColor("#333333"))
                textSize = 15f
                setPadding(0, dp(ctx, 2f), 0, dp(ctx, 2f))
            })
        }
    }

    private fun body(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.parseColor("#333333"))
        textSize = 15f
        setPadding(0, dp(ctx, 12f), 0, dp(ctx, 12f))
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private val LinearLayout.context: Context get() = getContext()

    private fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
