package dev.tclawyered.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Text-to-speech (F-10) using Android's built-in engine — zero cost, offline,
 * no API key. The mobile counterpart of the extension's Web Speech default.
 * Premium OpenAI TTS is a future add-on (see roadmap).
 *
 * Init is async; text requested before the engine is ready is queued and spoken
 * on init. Call [shutdown] when the owning screen goes away.
 */
class Tts(context: Context) {
    private var ready = false
    private var pending: String? = null

    private val engine = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            engine.language = Locale.getDefault()
            pending?.let { speak(it); pending = null }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) { pending = text; return }
        engine.setSpeechRate(rate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tc-summary")
    }

    fun stop() {
        pending = null
        engine.stop()
    }

    fun shutdown() {
        engine.stop()
        engine.shutdown()
    }

    var rate: Float = 1.0f
}
