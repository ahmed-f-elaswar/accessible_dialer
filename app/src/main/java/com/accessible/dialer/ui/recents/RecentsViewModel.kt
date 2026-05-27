package com.accessible.dialer.ui.recents

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

data class CallLogEntry(
    val id: Long,
    val number: String,
    val displayName: String?,
    val type: Int,
    val date: Long,
    val relativeTime: String,
    /** Call duration in seconds as recorded by the system call log. 0 for missed/rejected. */
    val duration: Long,
)

class RecentsViewModel : ViewModel() {
    private val _entries = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val entries: StateFlow<List<CallLogEntry>> = _entries

    // Pagination state. We query the system call log in pages of [PAGE_SIZE] raw rows
    // and dedupe across pages using [seen] so each contact only ever appears once,
    // represented by their most recent call (rows are fetched DESC by date so the first
    // occurrence wins). The screen calls [loadMore] when the user scrolls near the
    // bottom; [endReached] flips true once a page returns fewer than PAGE_SIZE rows.
    private val seen = mutableSetOf<String>()
    private var rawOffset = 0
    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    // LRU cache for PhoneLookup results, keyed by the raw call-log number string.
    // Avoids re-issuing one ContentResolver.query per number on every page load:
    // before this cache, scrolling 200 entries with mostly-stable contacts could
    // burn ~200 queries every page. We cap at 1024 entries (well over any real
    // recents list) and use access-order so frequently-shown numbers stay hot.
    // Stored value can be empty string — that means "PhoneLookup returned no
    // contact for this number" — so we can short-circuit unknowns too.
    // Cleared on every fresh load() because the user may have just added or
    // renamed a contact since the last call-log render.
    private val phoneLookupCache: MutableMap<String, String> =
        object : LinkedHashMap<String, String>(128, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?,
            ): Boolean = size > 1024
        }

    fun load(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resetPaging()
                fetchNextPage(context)
            }
        }
    }

    fun loadMore(context: Context) {
        if (_endReached.value || _loading.value) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fetchNextPage(context) }
        }
    }

    private fun resetPaging() {
        seen.clear()
        rawOffset = 0
        _endReached.value = false
        _entries.value = emptyList()
        // Drop stale name lookups so a freshly-added contact appears on reload.
        phoneLookupCache.clear()
    }

    /**
     * Remove the entries whose ids are in [ids] from the current in-memory list
     * without re-querying the system call log. Also clears their dedupe keys from
     * [seen] so subsequent paginations can re-surface the contact if a new call
     * comes in. Used by deletion actions so the LazyColumn keeps its scroll
     * position instead of snapping to the top after a delete.
     */
    private fun removeEntriesByIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val current = _entries.value
        val removed = current.filter { it.id in ids }
        if (removed.isEmpty()) return
        removed.forEach { seen.remove(dedupeKey(it)) }
        _entries.value = current.filterNot { it.id in ids }
    }

    /**
     * Delete a single call-log entry by its [CallLog.Calls._ID] and refresh.
     * Requires WRITE_CALL_LOG (declared in the manifest); on devices where the
     * caller isn't the default dialer this still succeeds for entries the user
     * owns. Silently no-ops on failure so we don't crash from a SecurityException
     * raised during a TalkBack custom action.
     */
    fun deleteEntry(context: Context, id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.delete(
                        CallLog.Calls.CONTENT_URI,
                        "${CallLog.Calls._ID}=?",
                        arrayOf(id.toString()),
                    )
                }
                // Drop the row from the in-memory list in place. We deliberately
                // avoid resetPaging()+fetch here: replacing _entries with a fresh
                // list causes LazyColumn to discard every item and snap scroll
                // back to the top, which is what the user was complaining about.
                removeEntriesByIds(setOf(id))
            }
        }
    }

    /**
     * Delete every call-log row that shares a phone number with [entry] — i.e. wipe the
     * full history for that contact, not just the latest call. Because the recents list
     * is deduplicated, "the entry" the user sees represents many underlying rows; this
     * is the action that actually removes all of them so the contact disappears from the
     * list entirely. Falls back to deleting just the visible row when the number is
     * blank (private/anonymous calls) so we don't accidentally wipe every blank-number
     * row at once.
     */
    fun deleteEntireEntry(context: Context, entry: CallLogEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val number = entry.number.trim()
                    if (number.isEmpty()) {
                        context.contentResolver.delete(
                            CallLog.Calls.CONTENT_URI,
                            "${CallLog.Calls._ID}=?",
                            arrayOf(entry.id.toString()),
                        )
                    } else {
                        // The grouped row the user sees represents every call-log row
                        // whose number matches by the last 7 digits (same rule as
                        // `dedupeKey`). SQLite has no portable "last N chars" on a
                        // value-with-mixed-formatting, so we scan IDs in Kotlin and
                        // delete by _ID IN (...). The scan walks the full call log
                        // (unbounded) but only touches two small columns, so it stays
                        // cheap even for large logs.
                        val digits = number.filter { it.isDigit() }
                        val suffix = if (digits.length >= 7) digits.takeLast(7) else digits
                        val ids = mutableListOf<Long>()
                        context.contentResolver.query(
                            CallLog.Calls.CONTENT_URI,
                            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER),
                            null,
                            null,
                            null,
                        )?.use { c ->
                            val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                            val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                            while (c.moveToNext()) {
                                val rowDigits = c.getString(numIdx).orEmpty().filter { it.isDigit() }
                                val rowSuffix = if (rowDigits.length >= 7) rowDigits.takeLast(7) else rowDigits
                                if (rowSuffix.isNotEmpty() && rowSuffix == suffix) {
                                    ids += c.getLong(idIdx)
                                }
                            }
                        }
                        if (ids.isNotEmpty()) {
                            val placeholders = ids.joinToString(",") { "?" }
                            context.contentResolver.delete(
                                CallLog.Calls.CONTENT_URI,
                                "${CallLog.Calls._ID} IN ($placeholders)",
                                ids.map { it.toString() }.toTypedArray(),
                            )
                        }
                        // Surface the deletion in the in-memory list without
                        // tearing the LazyColumn down (see deleteEntry comment).
                        // Any of the deleted IDs that happen to currently be the
                        // representative row will be removed below.
                        removeEntriesByIds(ids.toSet())
                        Unit
                    }
                }
            }
        }
    }

    /**
     * Fetch the next [PAGE_SIZE] raw call-log rows starting at [rawOffset], dedupe by
     * contact (using the running [seen] set so cross-page duplicates are also skipped),
     * and append the new entries to [_entries]. Marks [_endReached] when the underlying
     * cursor returns fewer rows than the page size — that's the only reliable signal
     * that we've drained the call log, since SQLite OFFSET past the end just returns 0
     * rows without error.
     */
    private fun fetchNextPage(context: Context) {
        if (_endReached.value) return
        _loading.value = true
        try {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            )
            val pageRaw = mutableListOf<CallLogEntry>()
            var rowsThisPage = 0
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT $PAGE_SIZE OFFSET $rawOffset",
            )?.use { c ->
                val idx = intArrayOf(
                    c.getColumnIndexOrThrow(CallLog.Calls._ID),
                    c.getColumnIndexOrThrow(CallLog.Calls.NUMBER),
                    c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME),
                    c.getColumnIndexOrThrow(CallLog.Calls.TYPE),
                    c.getColumnIndexOrThrow(CallLog.Calls.DATE),
                    c.getColumnIndexOrThrow(CallLog.Calls.DURATION),
                )
                while (c.moveToNext()) {
                    rowsThisPage++
                    val date = c.getLong(idx[4])
                    pageRaw += CallLogEntry(
                        id = c.getLong(idx[0]),
                        number = c.getString(idx[1]).orEmpty(),
                        displayName = c.getString(idx[2]).takeIf { !it.isNullOrBlank() },
                        type = c.getInt(idx[3]),
                        date = date,
                        relativeTime = formatRelative(Date(date)),
                        duration = c.getLong(idx[5]),
                    )
                }
            }
            rawOffset += rowsThisPage
            if (rowsThisPage < PAGE_SIZE) _endReached.value = true

            // CACHED_NAME is what the system caches into the call log at the
            // moment each call ended; it does NOT update when the user later
            // edits or creates a contact. We overlay it with a live
            // PhoneLookup pass so a freshly-saved contact's name appears in
            // Recents immediately (the primary user complaint). Per-page, not
            // per-row, so we run at most one query per distinct number.
            val freshNames = livePhoneLookupNames(
                context,
                pageRaw.mapTo(LinkedHashSet()) { it.number }
                    .filter { it.isNotBlank() },
                phoneLookupCache,
            )
            val overlaid = pageRaw.map { e ->
                val fresh = freshNames[e.number]
                if (!fresh.isNullOrBlank()) e.copy(displayName = fresh) else e
            }

            // Dedupe this page against everything we've already published. The cursor is
            // DESC by DATE, so the first occurrence of any number wins — matches the
            // pre-pagination semantics (each contact shown by their most recent call).
            val appended = ArrayList<CallLogEntry>(overlaid.size)
            for (e in overlaid) {
                if (seen.add(dedupeKey(e))) appended += e
            }
            if (appended.isNotEmpty()) {
                _entries.value = _entries.value + appended
            }
        } finally {
            _loading.value = false
        }
    }

    private fun dedupeKey(entry: CallLogEntry): String {
        val raw = entry.number.trim()
        if (raw.isEmpty()) {
            // Anonymous / private calls have no number; collapse them into one row.
            return "__private__"
        }
        // `normalizeNumber` only strips formatting (spaces, dashes, parentheses) — it
        // does NOT reconcile country-code variants. So "+12025551234" and "2025551234"
        // for the same contact would still hash to different keys and surface as two
        // rows. Use the last 7 digits (Android's MIN_MATCH heuristic, the same rule
        // PhoneNumberUtils.compare uses) so country-code / trunk-prefix variants of the
        // same line collapse into one entry. For short numbers (service codes, short
        // codes) fall back to the full normalized form so we don't merge unrelated
        // 3-/4-digit codes that happen to share trailing digits.
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) {
            val normalized = PhoneNumberUtils.normalizeNumber(raw)
            return normalized.ifEmpty { raw }
        }
        return if (digits.length >= 7) digits.takeLast(7) else digits
    }

    companion object {
        // ~500 raw rows per page strikes a balance between snappy first paint (the dedup
        // pass is O(N) and the IO query is the dominant cost) and not having to round-
        // trip to the call-log provider on every scroll tick.
        private const val PAGE_SIZE = 500
    }
}

/**
 * Resolve a fresh display-name for each input number via [ContactsContract.PhoneLookup].
 * Returns a map keyed by the *input* number string (not by any normalized form) so
 * callers can do `freshNames[entry.number]` without re-normalizing. Numbers with no
 * contact are absent from the map (NOT mapped to null) so callers can use a single
 * `if (!result.isNullOrBlank())` guard.
 *
 * PhoneLookup is the right API here (vs. PhoneNumberUtils.compare against
 * Phone.NUMBER): it understands country-code variants, lets the platform pick the
 * best match for the user's locale, and only requires READ_CONTACTS.
 */
private fun livePhoneLookupNames(
    context: Context,
    numbers: Collection<String>,
    cache: MutableMap<String, String>,
): Map<String, String> {
    if (numbers.isEmpty()) return emptyMap()
    val out = HashMap<String, String>(numbers.size)
    val cr = context.contentResolver
    for (number in numbers) {
        // Cache hit: skip the query. Empty-string sentinel means "known to have
        // no contact" — not added to `out` so callers fall back to CACHED_NAME.
        val cached = cache[number]
        if (cached != null) {
            if (cached.isNotEmpty()) out[number] = cached
            continue
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        var resolved = ""
        runCatching {
            cr.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) resolved = name
                }
            }
        }
        cache[number] = resolved
        if (resolved.isNotEmpty()) out[number] = resolved
    }
    return out
}
