package com.accessible.dialer.call

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.util.ShakeDetector

/**
 * The system binds to this service whenever there is an active phone call AND this app is
 * the user-selected default dialer. We forward call lifecycle into [OngoingCallHolder] and
 * launch the full-screen [InCallActivity] so the user can interact with the call.
 */
class DialerInCallService : InCallService() {

    private val ringer by lazy { Ringer(applicationContext) }
    // Per-call callback that drives the ringer. Held so we can unregister on remove.
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    // The most recently-added call. We keep a reference (not just state) because the
    // ringer needs the call's PhoneAccountHandle to resolve a per-SIM ringtone
    // override, and the holder only exposes a phone number.
    private var currentCall: Call? = null
    // Accelerometer listener active only while a call is RINGING and the
    // shake-to-answer setting is enabled. We register / unregister tightly around
    // the RINGING state so the sensor isn't hot for active or held calls.
    private var shakeDetector: ShakeDetector? = null

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        OngoingCallHolder.bindService(this)
        OngoingCallHolder.attach(call)
        currentCall = call

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
        stopShakeListener()
        if (currentCall === call) currentCall = null
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
            val handle = runCatching { currentCall?.details?.accountHandle }.getOrNull()
            ringer.start(number, handle)
            startShakeListenerIfEnabled()
        } else {
            ringer.stop()
            stopShakeListener()
        }
    }

    /**
     * If [SettingsRepository.shakeToAnswerEnabled] is on, start watching the
     * accelerometer for a shake gesture and answer the call when one is detected.
     * Re-entrant: a second call while a detector is already running is a no-op.
     */
    private fun startShakeListenerIfEnabled() {
        if (shakeDetector != null) return
        if (!SettingsRepository.shakeToAnswerEnabled.value) return
        val detector = ShakeDetector(onShake = {
            // Answer through the holder so the audio-only / video-call branching
            // logic lives in one place. Wrapped in runCatching because the call may
            // have already been torn down by the time the gesture is recognized.
            runCatching { OngoingCallHolder.answer() }
        })
        if (detector.start(applicationContext)) {
            shakeDetector = detector
        }
    }

    private fun stopShakeListener() {
        shakeDetector?.stop()
        shakeDetector = null
    }
}
