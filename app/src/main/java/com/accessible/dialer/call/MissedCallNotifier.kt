package com.accessible.dialer.call

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.accessible.dialer.MainActivity
import com.accessible.dialer.R

/**
 * Posts a standard "Missed call" notification when an incoming call ends without being
 * answered, mirroring the behaviour of stock phone apps. Each distinct caller gets its
 * own notification slot keyed by phone number so multiple unanswered calls from the
 * same person collapse into a single "N missed calls" entry, while different callers
 * stay separate. Tapping the notification opens the Recents tab; the action buttons
 * call the number back or open the SMS composer.
 */
object MissedCallNotifier {

    private const val CHANNEL_ID = "missed_calls"
    private const val GROUP_KEY = "com.accessible.dialer.MISSED_CALLS"

    /** Marker extra used by [MainActivity] to land on the Recents tab. */
    const val EXTRA_OPEN_RECENTS = "com.accessible.dialer.extra.OPEN_RECENTS"
    /**
     * Marker extra carrying the phone number of the missed-call notification the user
     * tapped. The receiving activity uses it to cancel just that notification (so the
     * user doesn't have to swipe it away by hand after acting on it).
     */
    const val EXTRA_DISMISS_MISSED_NUMBER = "com.accessible.dialer.extra.DISMISS_MISSED_NUMBER"

    // Per-number running count of missed calls that haven't been seen / cleared. Lets
    // us render "3 missed calls" instead of three separate notifications for the same
    // caller. Keyed by the same string used for [notificationIdFor] so cancellation
    // and bookkeeping stay in lock-step.
    private val counts = mutableMapOf<String, Int>()

    /**
     * Show a missed-call notification for an incoming call from [number] (which may be
     * blank for private / withheld numbers). [displayName] is the resolved contact
     * label, or null when the caller isn't in the user's contacts — in which case the
     * notification shows the raw number, or a generic "Unknown caller" string when the
     * number is also missing.
     */
    fun notifyMissedCall(context: Context, number: String, displayName: String?) {
        // POST_NOTIFICATIONS is a runtime permission on Android 13+. Without it we
        // silently skip — the call still lands in the system call log so the user
        // can still see the missed call in the Recents tab.
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)

        val key = keyFor(number)
        val count = (counts[key] ?: 0) + 1
        counts[key] = count

        val nm = NotificationManagerCompat.from(context)
        val title = if (count > 1) {
            context.getString(R.string.missed_call_title_count, count)
        } else {
            context.getString(R.string.missed_call_title)
        }
        val callerLabel = displayName?.takeIf { it.isNotBlank() }
            ?: number.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.missed_call_unknown)

        val tapIntent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_RECENTS, true)
            .putExtra(EXTRA_DISMISS_MISSED_NUMBER, key)
        val tapPi = PendingIntent.getActivity(
            context,
            notificationIdFor(key),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle(title)
            .setContentText(callerLabel)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(tapPi)

        if (number.isNotBlank()) {
            // "Call back" — relaunches the dialer with a tel: URI which MainActivity
            // recognises and auto-places. Carries the dismiss extra so the
            // notification clears the moment the user acts on it.
            val callIntent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.fromParts("tel", number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_DISMISS_MISSED_NUMBER, key)
            val callPi = PendingIntent.getActivity(
                context,
                notificationIdFor(key) + 1,
                callIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.sym_action_call,
                context.getString(R.string.missed_call_action_call_back),
                callPi,
            )

            // "Message" — opens the system SMS composer for this number. We
            // intentionally use ACTION_SENDTO with smsto: so any installed SMS app
            // handles it, not just the default dialer.
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val smsPi = PendingIntent.getActivity(
                context,
                notificationIdFor(key) + 2,
                smsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.sym_action_chat,
                context.getString(R.string.missed_call_action_message),
                smsPi,
            )
        }

        runCatching { nm.notify(notificationIdFor(key), builder.build()) }
    }

    /**
     * Cancel a previously-posted notification for [numberKey] (the key produced by
     * [keyFor]). Called by [MainActivity] when the user opens the dialer through one
     * of our notification PendingIntents.
     */
    fun cancelForKey(context: Context, numberKey: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(notificationIdFor(numberKey))
        counts.remove(numberKey)
    }

    /** Reset every running count and pull down every active missed-call notification. */
    fun cancelAll(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        counts.keys.toList().forEach { nm.cancel(notificationIdFor(it)) }
        counts.clear()
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.missed_call_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.missed_call_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun keyFor(number: String): String =
        number.trim().ifEmpty { "__private__" }

    // Stable per-number integer id so re-notifying for the same caller replaces the
    // existing notification rather than stacking. We pad by *3 because every missed
    // call also reserves request codes for its "Call back" and "Message" PendingIntents.
    private fun notificationIdFor(key: String): Int {
        // Avoid negative ids — Android accepts them but it's odd to debug; mask to
        // 31 bits, then offset by a fixed base so we don't collide with any future
        // notification ids the app might use.
        val hash = key.hashCode() and 0x7FFFFFFF
        return 1_000_000 + (hash % 1_000_000) * 3
    }
}
