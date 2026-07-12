package dev.tclawyered.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Holds the live MediaProjection capture pipeline so the floating bubble and the
 * capture service can share it without binding. The service owns lifecycle
 * (start/stop); the bubble drives per-tap [captureFrame] as the user scrolls,
 * and reads the stitched result via [assembledText].
 *
 * SCOPE: this is the wiring that connects bubble taps → OCR → assembled text.
 * OCR quality on long/multi-screen docs is inherently limited (see OcrExtractor);
 * the share/URL path remains the better route for full policy pages.
 */
object CaptureSession {
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private val ocr = OcrExtractor()
    private var stitcher = TextStitcher()

    val isActive: Boolean get() = reader != null

    fun start(context: Context, mediaProjection: MediaProjection) {
        stop() // replace any prior session
        projection = mediaProjection.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stop()
            }, null)
        }
        val metrics = screenMetrics(context)
        reader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2,
        )
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "tc-capture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, null,
        )
        stitcher = TextStitcher()
    }

    /** Capture the current frame, OCR it, add to the stitched doc. Returns word count. */
    suspend fun captureFrame(): Int {
        val image = reader?.acquireLatestImage() ?: return stitcher.wordCount()
        val bitmap = image.toBitmap()
        image.close()
        if (bitmap != null) {
            val text = ocr.recognize(bitmap)
            bitmap.recycle()
            stitcher.addFrame(text)
        }
        return stitcher.wordCount()
    }

    fun assembledText(): String = stitcher.assemble()

    /** Begin a fresh document without tearing down the projection. */
    fun resetDocument() { stitcher = TextStitcher() }

    fun stop() {
        virtualDisplay?.release(); virtualDisplay = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        stitcher = TextStitcher()
    }

    private fun Image.toBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(context: Context): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }
}
