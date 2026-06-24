package com.accessible.dialer.call

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.accessible.dialer.MainActivity
import com.accessible.dialer.R
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

    /**
     * Update Picture-in-Picture params whenever the activity is active. On API
     * 31+ we set [PictureInPictureParams.Builder.setAutoEnterEnabled] so the
     * system shrinks us into PIP automatically when the user navigates away
     * (gesture-nav, recents, home button) — unlike [onUserLeaveHint] which
     * doesn't fire reliably on modern gesture-nav builds. Older devices fall
     * back to the [onUserLeaveHint] path below.
     */
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = runCatching { setPictureInPictureParams(pipParams(autoEnter = true)) }.isSuccess
            android.util.Log.d("InCallActivity", "onResume setPictureInPictureParams ok=$ok")
        }
    }

    /**
     * Pre-S devices (or any device where setAutoEnterEnabled isn't honored)
     * still rely on manually entering PIP when the activity is paused. Calling
     * enterPictureInPictureMode here covers cases where onUserLeaveHint doesn't
     * fire (some gesture-nav builds skip it).
     */
    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isInPictureInPictureMode) return
        if (isFinishing) return
        val active = OngoingCallHolder.state.value as? CallState.Active
        if (active == null || active.telecomState == Call.STATE_RINGING) {
            android.util.Log.d(
                "InCallActivity",
                "onPause skip PIP active=${active?.telecomState}"
            )
            return
        }
        val entered = runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
        android.util.Log.d("InCallActivity", "onPause enterPictureInPictureMode entered=$entered")
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        android.util.Log.d(
            "InCallActivity",
            "onPictureInPictureModeChanged inPip=$isInPictureInPictureMode",
        )
    }

    /**
     * When the user leaves the call screen (home button, recents, switching apps),
     * shrink the activity into a Picture-in-Picture window so the call stays
     * visible as a floating control they can tap to return to. Equivalent to
     * Zoom/Teams' floating bubble — PIP is the Android-native way of doing it
     * for media/call activities. Only triggered when a call is currently live;
     * if no call is active we let the activity background normally (the system
     * InCallService is what keeps the call alive, not this activity).
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val active = OngoingCallHolder.state.value as? CallState.Active
        if (active == null) {
            android.util.Log.d("InCallActivity", "onUserLeaveHint no active call, skip PIP")
            return
        }
        // Don't enter PIP while ringing — we want the full-screen incoming UI to
        // stay visible so the user can answer; PIP would shrink it away.
        if (active.telecomState == Call.STATE_RINGING) return
        val entered = runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
        android.util.Log.d("InCallActivity", "onUserLeaveHint entered=$entered")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(autoEnter: Boolean = false): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            // Square aspect: the in-call UI has a portrait orientation but the
            // central content (avatar + buttons) reads fine in a square window
            // and avoids the awkward tall-and-thin look of 9:16.
            .setAspectRatio(Rational(1, 1))
        if (autoEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }
        // A single Hang up action surfaced inside the PIP window so the user can
        // end the call without expanding it back to full screen.
        runCatching {
            val hangupIntent = Intent(this, InCallActionReceiver::class.java)
                .setAction(InCallActionReceiver.ACTION_HANGUP)
            val pi = PendingIntent.getBroadcast(
                this,
                1001,
                hangupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
            builder.setActions(
                listOf(
                    RemoteAction(
                        icon,
                        getString(R.string.call_hangup),
                        getString(R.string.call_hangup),
                        pi,
                    )
                )
            )
        }
        return builder.build()
    }
}
