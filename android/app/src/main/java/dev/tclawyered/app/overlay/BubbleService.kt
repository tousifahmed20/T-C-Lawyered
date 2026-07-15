package dev.tclawyered.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.tclawyered.app.R
import dev.tclawyered.app.accessibility.ScrollReaderService
import dev.tclawyered.app.capture.CaptureSession
import dev.tclawyered.app.data.SettingsRepository
import dev.tclawyered.app.data.Videos
import dev.tclawyered.app.data.hive.HiveClient
import dev.tclawyered.app.data.local.LocalStore
import dev.tclawyered.app.data.safety.ReputationClient
import dev.tclawyered.app.model.PolicyType
import dev.tclawyered.app.pipeline.PipelineResult
import dev.tclawyered.app.pipeline.PolicyInput
import dev.tclawyered.app.pipeline.SummarizePipeline
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating "read this policy" bubble (SYSTEM_ALERT_WINDOW). Drag to reposition.
 *   • Tap        → auto-scroll the whole page, OCR it, summarize into the overlay card.
 *   • Long-press → manual one-shot: summarize just the current screen (no accessibility needed).
 *
 * Both need an active [CaptureSession] (start "Read the current screen" first).
 * Auto-scroll additionally needs [ScrollReaderService]; a tap without it nudges the
 * user to Accessibility settings, and a one-time disclaimer gates the first run.
 */
class BubbleService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private val store by lazy { LocalStore(applicationContext) }
    private val settings by lazy { SettingsRepository(applicationContext) }
    private val hive by lazy { HiveClient() }
    private val reputation by lazy { ReputationClient(applicationContext) }
    private val pipeline by lazy {
        SummarizePipeline(store, settings, hive, lifecycleScope, reputation)
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotice()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addBubble()
    }

    private fun addBubble() {
        val view = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            contentDescription = getString(R.string.app_name)
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }
        view.setOnTouchListener(DragTapListener())
        windowManager.addView(view, params)
        bubble = view
    }

    /** Distinguishes drag (move) from tap (capture) from long-press (finish). */
    private inner class DragTapListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var downAt = 0L

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    downAt = SystemClock.uptimeMillis()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(v, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - touchX) + abs(event.rawY - touchY)
                    if (moved >= TAP_SLOP) return true // it was a drag
                    val held = SystemClock.uptimeMillis() - downAt
                    if (held >= LONG_PRESS_MS) onFinish() else onCapture()
                    return true
                }
            }
            return false
        }
    }

    /** Tap → auto-scroll + read the whole page (needs the accessibility service). */
    private fun onCapture() {
        if (!CaptureSession.isActive) {
            toast("Start \"Read the current screen\" in the app first.")
            return
        }
        val reader = ScrollReaderService.instance
        if (reader == null) {
            toast("Turn on T&C Lawyered under Settings → Accessibility to auto-scroll.")
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        lifecycleScope.launch {
            if (settings.autoScrollAck()) {
                startAutoRead(reader)
            } else {
                SummaryOverlay.showDisclaimer(this@BubbleService) {
                    lifecycleScope.launch {
                        settings.setAutoScrollAck(true)
                        startAutoRead(reader)
                    }
                }
            }
        }
    }

    private fun startAutoRead(reader: ScrollReaderService) {
        SummaryOverlay.close() // don't obscure the screen while it scrolls + OCRs
        toast("Auto-reading — scrolling the page…")
        reader.autoRead { text ->
            if (text.isBlank()) toast("Couldn't read the page. Try a long-press to capture the current screen.")
            else summarizeAndShow(text)
        }
    }

    /** Long-press → manual one-shot: summarize just the current screen (works without accessibility). */
    private fun onFinish() {
        if (!CaptureSession.isActive) {
            toast("Start \"Read the current screen\" in the app first.")
            return
        }
        lifecycleScope.launch {
            CaptureSession.resetDocument()
            CaptureSession.captureFrame()
            val text = CaptureSession.assembledText()
            if (text.isBlank()) toast("No text captured. Point at the policy first.")
            else summarizeAndShow(text)
        }
    }

    /** Run the summarize pipeline on [text] and render it into the overlay card. */
    private fun summarizeAndShow(text: String) {
        SummaryOverlay.showLoading(this)
        lifecycleScope.launch {
            val result = try {
                pipeline.run(PolicyInput(text, "", PolicyType.guess(text, "")))
            } catch (e: Exception) {
                PipelineResult.Failed(e.message ?: "Something went wrong.")
            }
            when (result) {
                is PipelineResult.Ready ->
                    SummaryOverlay.showSummary(
                        this@BubbleService, result.summary, result.source, result.scannedAt,
                        Videos.companyFromDomain(result.domain),
                    )
                is PipelineResult.NeedsProvider ->
                    SummaryOverlay.showMessage(this@BubbleService, "Add an AI key in the app to summarize (OpenRouter gives a free one — no card).")
                is PipelineResult.Failed ->
                    SummaryOverlay.showMessage(this@BubbleService, humanize(result.message))
            }
        }
    }

    private fun humanize(message: String): String = when {
        message.startsWith("INVALID_API_KEY") -> "Your API key was rejected. Check it in Settings."
        message.startsWith("RATE_LIMITED") -> "Provider rate limit hit. Wait a moment and retry."
        message.startsWith("TIMEOUT") -> "The request timed out. Try again."
        message.startsWith("NETWORK") -> "Couldn't reach the provider. Check your connection."
        message.startsWith("EMPTY_TEXT") -> "No policy text was captured. Tap over the policy first."
        else -> message
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun startForegroundNotice() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bubble_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val CHANNEL_ID = "bubble"
        private const val NOTIF_ID = 43
        private const val TAP_SLOP = 16f
        private const val LONG_PRESS_MS = 600L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }
}
