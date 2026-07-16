package dev.tclawyered.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import dev.tclawyered.app.capture.CaptureSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private var readJob: Job? = null

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
        readJob?.cancel()
        readJob = scope.launch {
            CaptureSession.resetDocument()
            var lastCount = CaptureSession.captureFrame() // the frame already on screen
            var bottomHits = 0
            for (i in 0 until MAX_SCROLLS) {
                swipeUp()
                delay(SETTLE_MS)
                val count = CaptureSession.captureFrame()
                // T&C pages aren't infinite-scroll, so once the text stops growing we're
                // at the end. Confirm with 3 quick scrolls (~2s total at this cadence);
                // if any of them pulls in new text we weren't at the bottom after all.
                if (count > lastCount) {
                    lastCount = count
                    bottomHits = 0
                } else if (++bottomHits >= BOTTOM_CONFIRM_SCROLLS) {
                    break // 3 scrolls past the bottom with no new text → done
                }
            }
            // If the kill switch fired mid-scroll, don't surface a summary after the
            // user asked us to stop. The suspend points above already abort the swipes;
            // this guards the narrow window between the loop ending and onDone.
            ensureActive()
            onDone(CaptureSession.assembledText())
        }
    }

    /** True while an auto-read is scrolling — used to make a bubble tap cancel it. */
    val isReading: Boolean get() = readJob?.isActive == true

    /** Kill switch: abort an in-flight auto-read so the synthetic swipes stop now. */
    fun cancelRead() {
        readJob?.cancel()
        readJob = null
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
        private const val BOTTOM_CONFIRM_SCROLLS = 3 // ~2s of quick scrolls to confirm the bottom
        private const val SWIPE_MS = 250L
        private const val SETTLE_MS = 650L
    }
}
