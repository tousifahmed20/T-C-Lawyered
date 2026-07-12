package dev.tclawyered.app.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device OCR via ML Kit (offline). The captured pixels and the recognized
 * text never leave the device here — only a summary is ever uploaded, and only
 * after the user's own LLM produces it. This is the "read what's on screen"
 * half of the Android capture flow.
 *
 * Note (per the mobile design discussion): OCR is strong for short in-app
 * dialogs and consent popups. For long, multi-screen policies the captured
 * frames must be stitched with de-duplication (see [TextStitcher]); OCR noise
 * also means the same policy can hash differently across devices, weakening
 * cross-device hive hits — prefer share/URL extraction for full documents.
 */
class OcrExtractor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Recognize the text in a single captured frame. Returns "" on failure. */
    suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { cont.resume("") }
        }
}

/**
 * Assembles multiple OCR'd frames (from the user scrolling) into one document,
 * dropping lines that overlap between consecutive frames. Line-level de-dup is
 * intentionally simple for v1; fuzzy matching to absorb OCR noise is a TODO.
 */
class TextStitcher {
    private val lines = LinkedHashSet<String>()

    fun addFrame(frameText: String) {
        frameText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { lines.add(it) }
    }

    fun assemble(): String = lines.joinToString("\n")

    fun wordCount(): Int = assemble().split(Regex("\\s+")).count { it.isNotBlank() }
}
