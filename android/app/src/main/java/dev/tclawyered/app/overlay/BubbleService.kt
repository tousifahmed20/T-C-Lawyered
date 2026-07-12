package dev.tclawyered.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.lifecycle.LifecycleService
import dev.tclawyered.app.R
import kotlin.math.abs

/**
 * The floating "read this policy" bubble that hovers over other apps
 * (SYSTEM_ALERT_WINDOW). The user drags it out of the way; a tap triggers a
 * capture of whatever policy screen is currently visible.
 *
 * Requires the "Draw over other apps" permission (Settings.canDrawOverlays),
 * which MainActivity requests before starting this service.
 *
 * SCOPE: the draggable, tappable bubble is fully implemented. The tap currently
 * broadcasts CAPTURE intent; wiring it to an active ScreenCaptureService session
 * is the next slice.
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

    /** Distinguishes a tap (fire capture) from a drag (reposition the bubble). */
    private inner class DragTapListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
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
                    if (moved < TAP_SLOP) onBubbleTapped()
                    return true
                }
            }
            return false
        }
    }

    private fun onBubbleTapped() {
        // TODO(next slice): call the bound ScreenCaptureService.captureOnce(),
        // stitch frames while the user scrolls, then run SummarizePipeline.
        sendBroadcast(Intent(ACTION_CAPTURE).setPackage(packageName))
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun startForegroundNotice() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bubble_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
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
        const val ACTION_CAPTURE = "dev.tclawyered.app.CAPTURE"
        private const val CHANNEL_ID = "bubble"
        private const val NOTIF_ID = 43
        private const val TAP_SLOP = 16f

        fun start(context: Context) {
            val intent = Intent(context, BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }
}
