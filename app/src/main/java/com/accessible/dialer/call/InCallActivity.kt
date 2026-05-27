package com.accessible.dialer.call

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.accessible.dialer.MainActivity
import com.accessible.dialer.ui.theme.AccessibleDialerTheme

/**
 * Full-screen UI shown for the active call. Launched by [DialerInCallService] for both
 * incoming and outgoing calls; the [InCallScreen] decides which controls to display based
 * on the call state published by [OngoingCallHolder].
 */
class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hardware volume keys are claimed as accessibility shortcuts while the
        // call is RINGING (see dispatchKeyEvent). Setting the volume control stream
        // to STREAM_RING makes the system route the keys to us instead of letting
        // AudioManager swallow them to adjust the ringer level before they reach
        // dispatchKeyEvent — which is what was happening on some OEM builds.
        volumeControlStream = AudioManager.STREAM_RING

        // Show over the lock screen and turn the screen on for incoming calls.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContent {
            AccessibleDialerTheme {
                InCallScreen(
                    onClose = { finishAndRemoveTask() },
                    onAddCall = {
                        // Launch the dialpad to place a second call. The existing call has
                        // already been put on hold by InCallScreen, and the Telecom stack
                        // keeps it alive while MainActivity is in the foreground. The user
                        // returns to the in-call UI via the call notification.
                        val dial = Intent(this, MainActivity::class.java).apply {
                            action = Intent.ACTION_DIAL
                            data = Uri.parse("tel:")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(dial)
                        finish()
                    },
                )
            }
        }
    }

    /**
     * Hardware volume keys behave as accessibility shortcuts while a call is RINGING:
     *  - Volume Up  → answer the call (no need to find the on-screen button).
     *  - Volume Down → silence the ringer (the system keeps the call ringing on the
     *    other end; the user can still answer afterwards).
     *
     * Both shortcuts are gated to STATE_RINGING so the keys keep their normal volume
     * behavior once the call is connected (the in-call screen still wants Volume Up/
     * Down to change the voice-call stream volume).
     *
     * [TelecomManager.silenceRinger] requires the caller to be the default dialer,
     * which this app already is whenever this activity is on screen.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolumeKey) {
            val active = OngoingCallHolder.state.value as? CallState.Active
            if (active?.telecomState == Call.STATE_RINGING) {
                // Consume BOTH ACTION_DOWN and ACTION_UP so the system never sees a
                // complete key event for these presses; otherwise it can still tick
                // the ringer volume after we've answered/silenced.
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> OngoingCallHolder.answer()
                        KeyEvent.KEYCODE_VOLUME_DOWN -> runCatching {
                            (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
                                ?.silenceRinger()
                        }
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
