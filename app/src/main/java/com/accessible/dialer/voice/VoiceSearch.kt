package com.accessible.dialer.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin Kotlin wrapper around Android's [SpeechRecognizer] that exposes the
 * recognition lifecycle as a single observable [VoiceState] suitable for Compose.
 *
 * We use the *system* recognizer (Google app / OEM service) so we don't ship an ML
 * model. The recognizer streams partial transcriptions back via [onPartialResults] —
 * that's the live-typing effect users see in Google Search / Assistant / the Pixel
 * search bar. When the user stops talking the recognizer fires [onResults] once and
 * we surface that as [VoiceState.Done].
 *
 * Threading: [SpeechRecognizer] must be created and used on the main thread; all
 * callbacks fire on the main thread too. We therefore expose plain `MutableStateFlow`
 * updates without any dispatcher hopping — the Compose collector is on the main
 * thread already.
 */
class VoiceSearchController(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** True if a speech recognition service is installed on this device. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Begins a single recognition session. Idempotent: if already listening the
     * existing session continues. The session ends automatically on end-of-speech
     * (endpointing handled by the recognizer) or when [stop] / [cancel] is called.
     */
    fun start() {
        if (!isAvailable()) {
            _state.value = VoiceState.Error(VoiceErrorKind.NoRecognizer)
            return
        }
        if (recognizer != null) return
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        sr.setRecognitionListener(Listener())
        recognizer = sr
        _state.value = VoiceState.Listening(partial = "", rms = 0f)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // Live partials are what gives the Google-search "live typing" feel.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        sr.startListening(intent)
    }

    /** Asks the recognizer to commit whatever it has heard so far as a final result. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Aborts the session without producing a final result. */
    fun cancel() {
        recognizer?.cancel()
        teardown()
        _state.value = VoiceState.Idle
    }

    fun release() {
        teardown()
        _state.value = VoiceState.Idle
    }

    private fun teardown() {
        recognizer?.destroy()
        recognizer = null
    }

    /**
     * The actual [RecognitionListener]. Kept as an inner class only because we need to
     * call [teardown] on the outer object; otherwise it would be a top-level class.
     */
    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceState.Listening(partial = "", rms = 0f)
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // Normalize ~ -2 .. 10 dB into 0f..1f. Clamp because the API can spike.
            val cur = _state.value
            if (cur is VoiceState.Listening) {
                val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _state.value = cur.copy(rms = norm)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty()
            val cur = _state.value
            if (cur is VoiceState.Listening) {
                _state.value = cur.copy(partial = text)
            } else {
                _state.value = VoiceState.Listening(partial = text, rms = 0f)
            }
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty()
            teardown()
            _state.value = if (text.isBlank()) VoiceState.Error(VoiceErrorKind.NoMatch)
            else VoiceState.Done(text)
        }

        override fun onError(error: Int) {
            teardown()
            // Treat "no match" / "speech timeout" as soft errors so the UI can show a
            // friendly retry message instead of a generic failure.
            val kind = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceErrorKind.NoMatch
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceErrorKind.Permission
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceErrorKind.Network
                else -> VoiceErrorKind.Generic
            }
            _state.value = VoiceState.Error(kind)
        }
    }
}

/** UI-facing recognition state. Immutable so Compose can compare cheaply. */
@Immutable
sealed interface VoiceState {
    object Idle : VoiceState
    data class Listening(val partial: String, val rms: Float) : VoiceState
    data class Done(val text: String) : VoiceState
    data class Error(val kind: VoiceErrorKind) : VoiceState
}

enum class VoiceErrorKind { NoMatch, NoRecognizer, Permission, Network, Generic }

/**
 * Returns true if [text] looks like the user just spoke a phone number ("five five
 * five one two one two", "+1 555 1212", "call 911", etc.). We accept any string whose
 * non-letter content is dominated by digits / dial symbols — used to decide whether
 * to route the voice query to the dialpad or to the contacts search field.
 */
fun looksLikePhoneNumber(text: String): Boolean {
    val trimmed = text.trim().lowercase()
    if (trimmed.isEmpty()) return false
    // Strip command words a user might prepend ("call", "dial").
    val core = trimmed.removePrefix("call ").removePrefix("dial ").trim()
    val digits = core.count { it.isDigit() }
    val letters = core.count { it.isLetter() }
    if (digits < 3) return false
    // Mostly digits + dial separators ⇒ phone number.
    return digits >= letters
}

/**
 * Best-effort conversion of a spoken phone number into a dialable digit string.
 * Handles "+", "*", "#" symbols and strips spaces / dashes / parens. Spelled-out
 * digits are not converted — the system recognizer already returns "555 1212" not
 * "five five five one two one two" for English in our experience.
 */
fun extractDialableDigits(text: String): String {
    val core = text.trim()
        .lowercase()
        .removePrefix("call ")
        .removePrefix("dial ")
    val sb = StringBuilder()
    core.forEach { c ->
        when {
            c.isDigit() -> sb.append(c)
            c == '+' || c == '*' || c == '#' -> sb.append(c)
        }
    }
    return sb.toString()
}
