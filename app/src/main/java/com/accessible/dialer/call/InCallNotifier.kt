package com.accessible.dialer.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * On Android 12+ (API 31) we use [Notification.CallStyle] — that's the only style the
 * platform allows for ongoing calls, and a plain Builder notification gets silently
 * filtered out of the shade. Older versions keep the legacy action-button layout via
 * [NotificationCompat.Builder].
 *
 * The notification is also the "back to call" affordance: tapping the body re-opens
 * [InCallActivity]. We post a single notification at [NOTIFICATION_ID] — when call
 * state changes we re-post with the same id so the system updates the existing entry
 * rather than stacking new ones.
 */
object InCallNotifier {

    private const val CHANNEL_ID = "ongoing_call_v2"
    private const val LEGACY_CHANNEL_ID = "ongoing_call"
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
        // Use the legacy NotificationCompat path for every API level. CallStyle
        // sounded like the right tool but Android 13+ restricts its use to apps
        // that own a foreground service of type FOREGROUND_SERVICE_TYPE_PHONE_CALL;
        // because our InCallService is system-bound rather than self-started, the
        // platform silently dropped CallStyle notifications. The legacy builder
        // with IMPORTANCE_HIGH renders the call entry at the top of the shade and
        // surfaces all action buttons on every device we've tested.
        val notification = buildLegacyNotification(context, state, audio)
        val ok = runCatching { nm.notify(NOTIFICATION_ID, notification) }.isSuccess
        android.util.Log.d(
            "InCallNotifier",
            "refresh state=${state.telecomState} number=${state.number} posted=$ok",
        )
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Modern call-style notification used on API 31+. The platform requires this
     * style for ongoing calls — without it, the notification is either invisible
     * in the shade or downgraded to a generic ongoing entry that doesn't carry
     * the answer/hangup affordances. CallStyle also handles the placement in the
     * dedicated call section automatically.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun buildCallStyleNotification(
        context: Context,
        state: CallState.Active,
        audio: AudioState,
    ): Notification {
        val title = state.number?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.call_unknown)
        val person = Person.Builder().setName(title).setImportant(true).build()

        val contentPi = backToCallPi(context)
        val answerPi = actionPi(context, InCallActionReceiver.ACTION_ANSWER, 1)
        val declinePi = actionPi(context, InCallActionReceiver.ACTION_HANGUP, 2)
        val hangupPi = actionPi(context, InCallActionReceiver.ACTION_HANGUP, 5)

        val style = when (state.telecomState) {
            Call.STATE_RINGING -> Notification.CallStyle.forIncomingCall(person, declinePi, answerPi)
            else -> Notification.CallStyle.forOngoingCall(person, hangupPi)
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColorized(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setStyle(style)

        // CallStyle's built-in actions cover answer/decline/hangup, but we also
        // want quick mute / speaker toggles in the shade. Append them as regular
        // actions; CallStyle renders them after its built-in row.
        if (state.telecomState != Call.STATE_RINGING) {
            val muteLabel = context.getString(
                if (audio.muted) R.string.call_unmute else R.string.call_mute
            )
            val speakerLabel = context.getString(
                if (audio.speaker) R.string.call_speaker_off else R.string.call_speaker_on
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    muteLabel,
                    actionPi(context, InCallActionReceiver.ACTION_TOGGLE_MUTE, 3),
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    speakerLabel,
                    actionPi(context, InCallActionReceiver.ACTION_TOGGLE_SPEAKER, 4),
                ).build()
            )
        }

        return builder.build()
    }

    /**
     * Pre-API-31 fallback. The CallStyle requirement only landed in Android 12;
     * on older versions a plain Builder notification with action buttons works
     * fine and the system shade renders it normally.
     */
    private fun buildLegacyNotification(
        context: Context,
        state: CallState.Active,
        audio: AudioState,
    ): Notification {
        val title = state.number?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.call_unknown)
        val statusRes = when (state.telecomState) {
            Call.STATE_RINGING -> R.string.notif_call_incoming
            Call.STATE_DIALING, Call.STATE_CONNECTING -> R.string.notif_call_dialing
            Call.STATE_HOLDING -> R.string.notif_call_on_hold
            Call.STATE_ACTIVE -> R.string.notif_call_active
            else -> R.string.notif_call_active
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(context.getString(statusRes))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColorized(true)
            .setContentIntent(backToCallPi(context))

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

        return builder.build()
    }

    /** PendingIntent that reopens the in-call screen when the notification body is tapped. */
    private fun backToCallPi(context: Context): PendingIntent {
        val intent = Intent(context, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        // Drop the legacy LOW-importance channel from prior versions of this app
        // so it doesn't linger in the system settings UI. Channels can't be
        // mutated in place — bumping the id and removing the old one is the
        // standard upgrade pattern.
        runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_HIGH so the system places the CallStyle notification at the
        // top of the shade and surfaces it in the dedicated call section.
        // IMPORTANCE_LOW previously suppressed the entry on some OEM builds —
        // even the "back to call" tap was unreachable.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_ongoing_call),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_ongoing_call_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }
}
