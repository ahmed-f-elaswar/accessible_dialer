package com.accessible.dialer.call

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * The system binds to this service whenever there is an active phone call AND this app is
 * the user-selected default dialer. We forward call lifecycle into [OngoingCallHolder] and
 * launch the full-screen [InCallActivity] so the user can interact with the call.
 */
class DialerInCallService : InCallService() {

    private val ringer by lazy { Ringer(applicationContext) }
    // Per-call callback that drives the ringer. Held so we can unregister on remove.
    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        OngoingCallHolder.bindService(this)
        OngoingCallHolder.attach(call)

        // Drive the ringer from this service (not the holder) — we need a Context, and
        // the holder is process-wide and shouldn't hold one. We add a *second* callback
        // alongside the holder's so both stay independent.
        val cb = object : Call.Callback() {
            override fun onStateChanged(c: Call, newState: Int) = syncRinger(newState)
        }
        call.registerCallback(cb)
        callbacks[call] = cb
        syncRinger(call.state)

        val intent = Intent(this, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        ringer.stop()
        OngoingCallHolder.detach(call)
        OngoingCallHolder.bindService(null)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        // Mirror the real audio route + mute flag into the holder so the UI reflects the
        // system state and toggles are correctly applied through setAudioRoute/setMuted.
        OngoingCallHolder.updateAudioState(audioState)
    }

    private fun syncRinger(state: Int) {
        if (state == Call.STATE_RINGING) {
            val number = OngoingCallHolder.currentNumber()
            ringer.start(number)
        } else {
            ringer.stop()
        }
    }
}
