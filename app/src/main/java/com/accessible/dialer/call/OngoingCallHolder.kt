package com.accessible.dialer.call

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide single source of truth for the currently tracked call. The dialer is a
 * single-foreground-call app (basic dialer feature set) so a single nullable slot is
 * sufficient. A more advanced app would model a list of calls and conferences.
 *
 * The bound [DialerInCallService] writes to this; the [InCallActivity] reads.
 */
object OngoingCallHolder {
    private val _state = MutableStateFlow<CallState>(CallState.None)
    val state: StateFlow<CallState> = _state

    // Audio-route + mute state mirrored from the InCallService's CallAudioState callback.
    // Exposed as a flow so the in-call UI can reflect the *real* audio routing instead
    // of a UI-local boolean (which doesn't actually flip the system route on its own —
    // AudioManager.isSpeakerphoneOn has been a no-op for managed dialers for years; the
    // working path is InCallService.setAudioRoute / setMuted).
    private val _audio = MutableStateFlow(AudioState(muted = false, speaker = false))
    val audio: StateFlow<AudioState> = _audio

    @Volatile
    private var current: Call? = null

    @Volatile
    private var inCallService: InCallService? = null

    fun attach(call: Call) {
        current = call
        publish(call)
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, newState: Int) = publish(c)
            override fun onDetailsChanged(c: Call, details: Call.Details?) = publish(c)
        })
    }

    fun detach(call: Call) {
        if (current === call) {
            current = null
            _state.value = CallState.None
        }
    }

    /** Called from [DialerInCallService] so we can route mute/speaker through it. */
    fun bindService(service: InCallService?) {
        inCallService = service
    }

    fun updateAudioState(s: CallAudioState) {
        _audio.value = AudioState(
            muted = s.isMuted,
            speaker = (s.route and CallAudioState.ROUTE_SPEAKER) != 0,
        )
    }

    fun setSpeaker(on: Boolean) {
        val svc = inCallService ?: return
        val route = if (on) CallAudioState.ROUTE_SPEAKER
                    else CallAudioState.ROUTE_EARPIECE
        svc.setAudioRoute(route)
    }

    fun setMuted(muted: Boolean) {
        inCallService?.setMuted(muted)
    }

    fun answer() { current?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY) }
    fun reject() { current?.reject(false, null) }
    fun hangup() { current?.disconnect() }
    fun hold() { current?.hold() }
    fun resume() { current?.unhold() }
    fun playDtmf(digit: Char) { current?.playDtmfTone(digit) }
    fun stopDtmf() { current?.stopDtmfTone() }

    private fun publish(call: Call) {
        val details = call.details
        val handle = details?.handle?.schemeSpecificPart
        // connectTimeMillis is 0 until the call actually connects; expose as null in
        // that case so the UI can keep showing "Ringing / Dialing" instead of 0:00.
        val connect = details?.connectTimeMillis?.takeIf { it > 0 }
        _state.value = CallState.Active(
            number = handle,
            telecomState = call.state,
            connectTimeMillis = connect,
        )
    }
}

sealed interface CallState {
    data object None : CallState
    data class Active(
        val number: String?,
        val telecomState: Int,
        val connectTimeMillis: Long? = null,
    ) : CallState
}

data class AudioState(val muted: Boolean, val speaker: Boolean)
