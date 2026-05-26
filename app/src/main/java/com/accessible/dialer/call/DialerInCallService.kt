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

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        OngoingCallHolder.bindService(this)
        OngoingCallHolder.attach(call)

        val intent = Intent(this, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        OngoingCallHolder.detach(call)
        OngoingCallHolder.bindService(null)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        // Mirror the real audio route + mute flag into the holder so the UI reflects the
        // system state and toggles are correctly applied through setAudioRoute/setMuted.
        OngoingCallHolder.updateAudioState(audioState)
    }
}
