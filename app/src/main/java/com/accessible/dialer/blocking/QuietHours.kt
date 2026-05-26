package com.accessible.dialer.blocking

import android.telephony.PhoneNumberUtils
import com.accessible.dialer.settings.SettingsRepository
import java.time.LocalTime

/**
 * Quiet hours logic. The user picks a daily window `[start, end)` (in minutes since
 * midnight); during that window the CallScreeningService silently rejects every
 * incoming call. The window may cross midnight (e.g. 22:00 → 07:00).
 *
 * To make sure the user remains reachable in genuine emergencies, we count repeated
 * calls from the same number within a short rolling window. If a caller hits the
 * configured threshold the quiet rule yields and the call rings normally.
 */
object QuietHours {

    private const val BREAK_WINDOW_MS = 15L * 60L * 1000L // 15 minutes

    /** True when the current time is inside the user's configured quiet window. */
    fun isQuietNow(now: LocalTime = LocalTime.now()): Boolean {
        if (!SettingsRepository.quietEnabled.value) return false
        val start = SettingsRepository.quietStart.value
        val end = SettingsRepository.quietEnd.value
        if (start == end) return false
        val cur = now.hour * 60 + now.minute
        return if (start < end) cur in start until end
        else cur >= start || cur < end // crosses midnight
    }

    /**
     * Records the incoming call attempt from [number] and returns the count of
     * attempts inside the last 15 min (including this one).
     */
    fun recordAndCount(number: String): Int = RecentCallTracker.recordAndCount(number)

    /**
     * Whether [number]'s repeated attempts have crossed the user's bypass threshold.
     * Returns false when the threshold is 0 (bypass disabled).
     */
    fun shouldBypassQuiet(number: String): Boolean {
        val threshold = SettingsRepository.quietBreakThreshold.value
        if (threshold <= 0) return false
        return RecentCallTracker.recordAndCount(number) >= threshold
    }

    /** In-memory rolling tracker keyed by a loosely-normalized phone number. */
    private object RecentCallTracker {
        private val byNumber = HashMap<String, ArrayDeque<Long>>()

        @Synchronized
        fun recordAndCount(number: String): Int {
            val key = normalize(number).ifBlank { return 0 }
            val now = System.currentTimeMillis()
            val cutoff = now - BREAK_WINDOW_MS
            val q = byNumber.getOrPut(key) { ArrayDeque() }
            while (q.isNotEmpty() && q.first() < cutoff) q.removeFirst()
            q.addLast(now)
            // Opportunistic cleanup: if a different key is fully expired, drop it so the
            // map doesn't grow unbounded for a chatty caller log.
            if (byNumber.size > 64) prune(cutoff)
            return q.size
        }

        private fun prune(cutoff: Long) {
            val it = byNumber.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                while (e.value.isNotEmpty() && e.value.first() < cutoff) e.value.removeFirst()
                if (e.value.isEmpty()) it.remove()
            }
        }

        private fun normalize(number: String): String {
            // PhoneNumberUtils.stripSeparators handles spaces/dashes/parens; we keep the
            // leading "+" for E.164 numbers because tow numbers may only differ by it.
            val s = PhoneNumberUtils.stripSeparators(number).orEmpty()
            return s.trim()
        }
    }
}
