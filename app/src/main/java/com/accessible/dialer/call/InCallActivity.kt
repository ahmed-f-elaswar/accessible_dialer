package com.accessible.dialer.call

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
}
