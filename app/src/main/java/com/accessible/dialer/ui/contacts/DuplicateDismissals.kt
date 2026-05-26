package com.accessible.dialer.ui.contacts

import android.content.Context

/**
 * Persistent set of duplicate-group keys the user has dismissed because they aren't
 * actually duplicates. Keys come from [DuplicateGroup.key] (e.g. "phone:1234567",
 * "fuzzy-pair:42-77", "intra:42"). Once dismissed, a group with the same key is
 * hidden on every subsequent scan until the user clears the list.
 */
internal object DuplicateDismissals {
    private const val FILE = "duplicate_dismissals"
    private const val KEY = "dismissed_keys"

    fun load(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun dismiss(context: Context, groupKey: String) {
        if (groupKey.isBlank()) return
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY, current + groupKey).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
