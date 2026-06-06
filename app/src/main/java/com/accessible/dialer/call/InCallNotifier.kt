package com.accessible.dialer.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telecom.Call
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.accessible.dialer.R

/**
 * Posts an ongoing-call notification that mirrors the current call state and exposes
 * action buttons in the notification shade so the user can answer / reject / mute /
 * toggle the speaker / hang up without having to bring the [InCallActivity] back to
 * the foreground.
 *
 * The notification is also the "back to call" affordance: tapping the body re-opens
 * [InCallActivity]. We post a single notification at [NOTIFICATION_ID] — when call
 * state changes we re-post with the same id so the system updates the existing entry
 * rather than stacking new ones.
 */
object InCallNotifier {

    private const val CHANNEL_ID = "ongoing_call"
    const val NOTIFICATION_ID = 4242

    /**
     * Refresh the ongoing-call notification to match [state] and [audio]. If [state]
     * has no live call we cancel the notification — the system InCallService
     * lifecycle separately tears down the call so no further updates are needed.
     */
    fun refresh(context: Context, state: CallState, audio: AudioState) {
        val nm = NotificationManagerCompat.from(context)
        if (state !is CallState.Active) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(context)

        val title = state.number?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.call_unknown)
        val statusRes = when (state.telecomState) {
            Call.STATE_RINGING -> R.string.notif_call_incoming
            Call.STATE_DIALING, Call.STATE_CONNECTING -> R.string.notif_call_dialing
            Call.STATE_HOLDING -> R.string.notif_call_on_hold
            Call.STATE_ACTIVE -> R.string.notif_call_active
            else -> R.string.notif_call_active
        }

        // Back to call: re-launch InCallActivity (singleTask) so tapping the
        // notification body always brings the in-call screen to the front.
        val contentIntent = Intent(context, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentPi = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(context.getString(statusRes))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColorized(true)
            .setContentIntent(contentPi)

        when (state.telecomState) {
            Call.STATE_RINGING -> {
                builder.addAction(
                    0,
                    context.getString(R.string.call_answer),
                    actionPi(context, InCallActionReceiver.ACTION_ANSWER, 1),
                )
                builder.addAction(
                    0,
                    context.getString(R.string.call_decline),
                    actionPi(context, InCallActionReceiver.ACTION_HANGUP, 2),
                )
            }
            else -> {
                // Mid-call: mute / speaker toggles + hang up. Labels reflect the
                // current state so the user knows what tapping will do (e.g.
                // "Unmute" when the call is already muted).
                val muteLabel = context.getString(
                    if (audio.muted) R.string.call_unmute else R.string.call_mute
                )
                val speakerLabel = context.getString(
                    if (audio.speaker) R.string.call_speaker_off else R.string.call_speaker_on
                )
                builder.addAction(
                    0,
                    muteLabel,
                    actionPi(context, InCallActionReceiver.ACTION_TOGGLE_MUTE, 3),
                )
                builder.addAction(
                    0,
                    speakerLabel,
                    actionPi(context, InCallActionReceiver.ACTION_TOGGLE_SPEAKER, 4),
                )
                builder.addAction(
                    0,
                    context.getString(R.string.call_hangup),
                    actionPi(context, InCallActionReceiver.ACTION_HANGUP, 5),
                )
            }
        }

        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun actionPi(context: Context, action: String, requestCode: Int): PendingIntent {
        // Explicit intent (component set) so the receiver fires even though it is
        // registered at runtime — Android 13+ ignores implicit broadcasts to apps
        // in the background.
        val intent = Intent(action).setClass(context, InCallActionReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_ongoing_call),
            // LOW: the notification is the "back to call" handle and audio controls,
            // not a heads-up alert. The system in-call UI / our InCallActivity is
            // the actual full-screen alerter.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_ongoing_call_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }
}
