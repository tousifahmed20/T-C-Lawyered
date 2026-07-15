package dev.tclawyered.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import dev.tclawyered.app.capture.CaptureSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Auto-scroll reader. MediaProjection can only *read* the screen — the only way a
 * normal app can *scroll* another app (Chrome, an in-app policy, a modal) is a
 * synthetic swipe from an AccessibilityService. This service does exactly that and
 * nothing else: on request it loops swipe-up → let [CaptureSession] OCR the new
 * frame → repeat until the captured text stops growing (bottom reached).
 *
 * User-gated: it only acts when [autoRead] is called from the bubble; it does not
 * watch or react to accessibility events.
 */
class ScrollReaderService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }

    override fun onInterrupt() { /* not used */ }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Scroll the current page top-to-bottom, OCR-ing each frame, then hand the
     * stitched text to [onDone] on the main thread. Requires an active capture session.
     */
    fun autoRead(onDone: (String) -> Unit) {
        scope.launch {
            CaptureSession.resetDocument()
            CaptureSession.captureFrame() // the frame already on screen
            var lastCount = -1
            var stable = 0
            for (i in 0 until MAX_SCROLLS) {
                swipeUp()
                delay(SETTLE_MS)
                val count = CaptureSession.captureFrame()
                if (count <= lastCount) {
                    if (++stable >= STABLE_LIMIT) break // no new text twice → bottom reached
                } else {
                    stable = 0
                    lastCount = count
                }
            }
            onDone(CaptureSession.assembledText())
        }
    }

    private suspend fun swipeUp() = suspendCancellableCoroutine<Unit> { cont ->
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, m.heightPixels * 0.72f)
            lineTo(x, m.heightPixels * 0.28f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
            .build()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(Unit) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(Unit) }
            },
            null,
        )
        if (!dispatched && cont.isActive) cont.resume(Unit)
    }

    companion object {
        @Volatile
        var instance: ScrollReaderService? = null
            private set

        fun isEnabled(): Boolean = instance != null

        private const val MAX_SCROLLS = 30
        private const val STABLE_LIMIT = 2
        private const val SWIPE_MS = 250L
        private const val SETTLE_MS = 650L
    }
}
