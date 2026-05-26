package com.accessible.dialer.call

import android.telecom.Call
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

    @Volatile
    private var current: Call? = null

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
        _state.value = CallState.Active(
            number = handle,
            telecomState = call.state,
        )
    }
}

sealed interface CallState {
    data object None : CallState
    data class Active(val number: String?, val telecomState: Int) : CallState
}
