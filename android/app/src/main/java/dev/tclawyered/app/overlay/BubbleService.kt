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
import dev.tclawyered.app.capture.CaptureSession
import dev.tclawyered.app.ui.MainActivity
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating "read this policy" bubble (SYSTEM_ALERT_WINDOW). Drag to reposition.
 *   • Tap        → capture + OCR the current screen (repeat while scrolling).
 *   • Long-press → finish: hand the stitched text to the pipeline (opens the app).
 *
 * Capture needs an active [CaptureSession] (start "Read the current screen" in the
 * app first); a tap without one nudges the user to enable it.
 */
class BubbleService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private lateinit var params: WindowManager.LayoutParams

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

    private fun onCapture() {
        if (!CaptureSession.isActive) {
            toast("Start \"Read the current screen\" in the app first.")
            return
        }
        lifecycleScope.launch {
            val words = CaptureSession.captureFrame()
            toast("Captured — $words words so far. Long-press to summarize.")
        }
    }

    private fun onFinish() {
        if (!CaptureSession.isActive) {
            toast("Nothing captured yet.")
            return
        }
        val text = CaptureSession.assembledText()
        CaptureSession.resetDocument()
        if (text.isBlank()) {
            toast("No text captured. Tap over the policy first.")
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_SHARED_IS_URL, false)
                putExtra(MainActivity.EXTRA_SHARED_PAYLOAD, text)
                putExtra(MainActivity.EXTRA_SHARED_DOMAIN, "")
            },
        )
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
