package dev.tclawyered.app.control

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.tclawyered.app.capture.ScreenCaptureService
import dev.tclawyered.app.overlay.BubbleService
import dev.tclawyered.app.overlay.SummaryOverlay

/**
 * Kill switch. Stops screen capture (recording) and the hovering bubble, and
 * dismisses any summary overlay — everything the reader put on screen. Fired from
 * the "Stop" action on the foreground notifications so the user can shut it all
 * off from the notification shade at any time.
 */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SummaryOverlay.close()
        BubbleService.stop(context)
        ScreenCaptureService.stop(context)
    }

    companion object {
        private const val ACTION_STOP = "dev.tclawyered.app.action.STOP_ALL"

        fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, StopReceiver::class.java).setAction(ACTION_STOP)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
