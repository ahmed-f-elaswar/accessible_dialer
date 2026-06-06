package com.accessible.dialer.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the action buttons fired from the ongoing-call notification posted by
 * [InCallNotifier]. Each action dispatches into [OngoingCallHolder] so the in-app
 * controls and the notification shade controls stay in lock-step.
 *
 * Registered at runtime from [DialerInCallService] (not exported in the manifest)
 * — Android 14+ requires the explicit not-exported flag, which we provide there.
 */
class InCallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> runCatching { OngoingCallHolder.answer() }
            ACTION_HANGUP -> {
                // Hang up handles both "decline a ringing call" and "end the
                // current call" — the underlying [Call] knows which based on
                // its state, so we don't need separate branches.
                runCatching {
                    if (OngoingCallHolder.state.value is CallState.Active &&
                        (OngoingCallHolder.state.value as CallState.Active).telecomState ==
                            android.telecom.Call.STATE_RINGING
                    ) {
                        OngoingCallHolder.reject()
                    } else {
                        OngoingCallHolder.hangup()
                    }
                }
            }
            ACTION_TOGGLE_MUTE -> runCatching {
                OngoingCallHolder.setMuted(!OngoingCallHolder.audio.value.muted)
            }
            ACTION_TOGGLE_SPEAKER -> runCatching {
                OngoingCallHolder.setSpeaker(!OngoingCallHolder.audio.value.speaker)
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.accessible.dialer.action.ANSWER"
        const val ACTION_HANGUP = "com.accessible.dialer.action.HANGUP"
        const val ACTION_TOGGLE_MUTE = "com.accessible.dialer.action.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.accessible.dialer.action.TOGGLE_SPEAKER"
    }
}
