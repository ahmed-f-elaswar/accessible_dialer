package com.accessible.dialer.ui.recents

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
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

    /**
     * Idempotent first-load helper. Used to pre-warm the call log from the app's
     * root composable while the user is still on the Dialpad / Favorites tab, so
     * the Recents tab paints immediately instead of triggering its scan on first
     * compose. Returns without doing any work if a load is already in flight or
     * if [entries] already holds rows.
     */
    fun ensureLoaded(context: Context) {
        if (_loading.value) return
        if (_entries.value.isNotEmpty()) return
        if (rawOffset > 0) return
        load(context)
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
                // Capture the visible entry (and its position) before mutating
                // anything so we can swap in the next-most-recent call for the
                // same number after the deletion. The recents list is deduped,
                // so the visible row represents only the latest call for that
                // contact — when the user picks "Delete latest call" they
                // expect just that one call gone, with the previous call for
                // the same contact taking its place.
                val currentList = _entries.value
                val targetIndex = currentList.indexOfFirst { it.id == id }
                val target = targetIndex.takeIf { it >= 0 }?.let { currentList[it] }
                runCatching {
                    context.contentResolver.delete(
                        CallLog.Calls.CONTENT_URI,
                        "${CallLog.Calls._ID}=?",
                        arrayOf(id.toString()),
                    )
                }
                val replacement = target?.let { findNextCallForNumber(context, it, id) }
                if (target != null && replacement != null) {
                    // Substitute in place — same dedupe key, same list position,
                    // so the LazyColumn keeps its scroll and the entry just
                    // updates its timestamp / type to the previous call.
                    val updated = _entries.value.toMutableList()
                    val idx = updated.indexOfFirst { it.id == id }
                    if (idx >= 0) {
                        updated[idx] = replacement
                        _entries.value = updated
                    }
                } else {
                    // No earlier call for this number — fall back to dropping
                    // the row from the in-memory list in place. We deliberately
                    // avoid resetPaging()+fetch here: replacing _entries with a
                    // fresh list causes LazyColumn to discard every item and
                    // snap scroll back to the top.
                    removeEntriesByIds(setOf(id))
                }
            }
        }
    }

    /**
     * Find the next-most-recent call-log row for the same contact as [target]
     * (matched by the same last-7-digits rule [dedupeKey] uses), excluding the
     * just-deleted [excludeId]. Returns a [CallLogEntry] suitable for swapping
     * into the in-memory list, or null when no earlier call exists. Reuses
     * [target.displayName] so we don't have to re-run PhoneLookup for the
     * substitution; the name belongs to the number, not the row.
     */
    private fun findNextCallForNumber(
        context: Context,
        target: CallLogEntry,
        excludeId: Long,
    ): CallLogEntry? {
        val number = target.number.trim()
        if (number.isEmpty()) return null
        val digits = number.filter { it.isDigit() }
        val suffix = if (digits.length >= 7) digits.takeLast(7) else digits
        if (suffix.isEmpty()) return null
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls._ID}!=?",
                arrayOf(excludeId.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (c.moveToNext()) {
                    val rowDigits = c.getString(numIdx).orEmpty().filter { it.isDigit() }
                    val rowSuffix = if (rowDigits.length >= 7) rowDigits.takeLast(7) else rowDigits
                    if (rowSuffix.isNotEmpty() && rowSuffix == suffix) {
                        val date = c.getLong(dateIdx)
                        val cachedName = c.getString(nameIdx).takeIf { !it.isNullOrBlank() }
                        return@use CallLogEntry(
                            id = c.getLong(idIdx),
                            number = c.getString(numIdx).orEmpty(),
                            displayName = target.displayName ?: cachedName,
                            type = c.getInt(typeIdx),
                            date = date,
                            relativeTime = formatRelative(Date(date)),
                            duration = c.getLong(durIdx),
                        )
                    }
                }
                null
            }
        }.getOrNull()
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
     * Fetch raw call-log rows starting at [rawOffset] and append deduped entries
     * to [_entries]. Loops internally until at least [MIN_NEW_PER_LOAD_MORE]
     * visible new rows have been added or the underlying cursor is drained — with
     * name-based dedup a single 500-row raw page can collapse to zero new
     * contact-rows (a chatty contact called many times in a row), and without
     * this loop the [_entries.size]-keyed pagination sentinel would stop firing
     * even though we have plenty of unseen history left.
     */
    private fun fetchNextPage(context: Context) {
        if (_endReached.value) return
        _loading.value = true
        try {
            var newAppendedSoFar = 0
            while (!_endReached.value && newAppendedSoFar < MIN_NEW_PER_LOAD_MORE) {
                val before = _entries.value.size
                fetchOneRawPage(context)
                val added = _entries.value.size - before
                if (added == 0 && _endReached.value) break
                newAppendedSoFar += added
            }
        } finally {
            _loading.value = false
        }
    }

    private fun fetchOneRawPage(context: Context) {
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
    }

    /**
     * Incremental top-up after an event that may have appended new call-log rows
     * (typically a placed/received call ending). Queries only rows whose [_ID] is
     * greater than the highest [_ID] currently in [_entries] — so the work is
     * proportional to "what's new" rather than "the whole log". Any new row whose
     * dedupe key matches an existing entry replaces that entry in place (so the
     * representative row's timestamp / type / duration updates without the
     * LazyColumn discarding its scroll); rows for previously-unseen contacts are
     * prepended to the top in DESC date order.
     *
     * Safe no-op when [_entries] is empty (first load hasn't happened yet) or no
     * rows are newer than what we've already published.
     */
    fun mergeRecent(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val current = _entries.value
                if (current.isEmpty()) {
                    // Nothing on screen yet — let the normal load() path handle
                    // first paint instead of partially populating from the top.
                    return@withContext
                }
                val maxId = current.maxOf { it.id }
                val projection = arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                )
                val newRows = mutableListOf<CallLogEntry>()
                runCatching {
                    context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        "${CallLog.Calls._ID} > ?",
                        arrayOf(maxId.toString()),
                        "${CallLog.Calls.DATE} DESC",
                    )?.use { c ->
                        val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                        val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                        val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                        val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                        val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                        val durIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                        while (c.moveToNext()) {
                            val date = c.getLong(dateIdx)
                            newRows += CallLogEntry(
                                id = c.getLong(idIdx),
                                number = c.getString(numIdx).orEmpty(),
                                displayName = c.getString(nameIdx).takeIf { !it.isNullOrBlank() },
                                type = c.getInt(typeIdx),
                                date = date,
                                relativeTime = formatRelative(Date(date)),
                                duration = c.getLong(durIdx),
                            )
                        }
                    }
                }
                if (newRows.isEmpty()) return@withContext

                // Live PhoneLookup overlay for the few numbers in this delta.
                val freshNames = livePhoneLookupNames(
                    context,
                    newRows.mapTo(LinkedHashSet()) { it.number }
                        .filter { it.isNotBlank() },
                    phoneLookupCache,
                )
                val overlaid = newRows.map { e ->
                    val fresh = freshNames[e.number]
                    if (!fresh.isNullOrBlank()) e.copy(displayName = fresh) else e
                }

                // Merge: any contact touched by the new rows moves to the top
                // of the list (so a fresh call jumps to the head of Today),
                // while contacts not in the delta keep their relative order.
                // Walk newRows DESC date so older-of-the-new rows can't "win"
                // over newer ones for the same number.
                val updatedByKey = LinkedHashMap<String, CallLogEntry>()
                for (e in overlaid) {
                    val k = dedupeKey(e)
                    updatedByKey.putIfAbsent(k, e)
                    seen.add(k)
                }
                // Drop the old rows for any touched contact; the new row
                // (already newer by date) replaces it at the front. Untouched
                // rows keep their relative ordering.
                val kept = current.filter { dedupeKey(it) !in updatedByKey }
                _entries.value = updatedByKey.values.toList() + kept
                // Subsequent pages query by OFFSET on a DESC-by-date list. Newly
                // appended rows shifted that list, so bump rawOffset to keep our
                // pagination cursor pointing at the same row it pointed at before.
                rawOffset += newRows.size
            }
        }
    }

    /**
     * Empty the in-memory list and reset pagination state without touching the
     * underlying CallLog provider. Used after a bulk delete (e.g. "Clear all call
     * history") so the UI reflects the empty log immediately, instead of waiting
     * for a full re-query that we know will return zero rows.
     */
    fun clearLocally() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resetPaging()
            }
        }
    }

    /**
     * Re-run the live PhoneLookup overlay against every entry currently on screen
     * and apply any name changes in place. Used after the user saves or renames a
     * contact, so the new name shows up in Recents without a full call-log
     * re-query. We deliberately drop [phoneLookupCache] first so the just-saved
     * contact isn't masked by a stale cache hit.
     */
    fun refreshDisplayNames(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val current = _entries.value
                if (current.isEmpty()) return@withContext
                phoneLookupCache.clear()
                val freshNames = livePhoneLookupNames(
                    context,
                    current.mapTo(LinkedHashSet()) { it.number }
                        .filter { it.isNotBlank() },
                    phoneLookupCache,
                )
                if (freshNames.isEmpty()) return@withContext
                var changed = false
                val updated = current.map { e ->
                    val fresh = freshNames[e.number]
                    if (!fresh.isNullOrBlank() && fresh != e.displayName) {
                        changed = true
                        e.copy(displayName = fresh)
                    } else e
                }
                if (changed) _entries.value = updated
            }
        }
    }

    private fun dedupeKey(entry: CallLogEntry): String {
        // Collapse all calls belonging to the same contact onto a single row,
        // including calls placed/received on different numbers (mobile / work
        // / landline) for that contact. Because the live-overlay pass in
        // [fetchNextPage] resolves CACHED_NAME → PhoneLookup display name
        // before this runs, a contact's two numbers will share the same
        // [entry.displayName] and therefore the same dedupe key.
        //
        // Falls back to a number-based key when the call has no associated
        // contact (private/anonymous calls and unsaved numbers), so unsaved
        // numbers still collapse correctly by themselves.
        val name = entry.displayName?.trim().orEmpty()
        if (name.isNotEmpty()) return "name:" + name.lowercase()

        val raw = entry.number.trim()
        if (raw.isEmpty()) {
            // Anonymous / private calls have no number; collapse them into one row.
            return "__private__"
        }
        // For unsaved numbers, use the last 7 digits (Android's MIN_MATCH
        // heuristic, the same rule PhoneNumberUtils.compare uses) so
        // country-code / trunk-prefix variants of the same line collapse
        // into one entry. For short numbers (service codes) fall back to
        // the full digit string so we don't merge unrelated 3-/4-digit
        // codes that happen to share trailing digits.
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return raw
        return "num:" + if (digits.length >= 7) digits.takeLast(7) else digits
    }

    companion object {
        // ~500 raw rows per page strikes a balance between snappy first paint (the dedup
        // pass is O(N) and the IO query is the dominant cost) and not having to round-
        // trip to the call-log provider on every scroll tick.
        private const val PAGE_SIZE = 500

        // Each loadMore must yield at least this many visible new rows before the
        // ViewModel hands control back to the UI — otherwise a chatty contact (many
        // consecutive calls that all dedupe to one name) can cause a single 500-row
        // page to produce zero new rows, stalling pagination.
        private const val MIN_NEW_PER_LOAD_MORE = 20
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
