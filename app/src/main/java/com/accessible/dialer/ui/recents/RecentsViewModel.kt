package com.accessible.dialer.ui.recents

import android.content.Context
import android.provider.CallLog
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
                resetPaging()
                fetchNextPage(context)
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
                        Unit
                    }
                }
                resetPaging()
                fetchNextPage(context)
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

            // Dedupe this page against everything we've already published. The cursor is
            // DESC by DATE, so the first occurrence of any number wins — matches the
            // pre-pagination semantics (each contact shown by their most recent call).
            val appended = ArrayList<CallLogEntry>(pageRaw.size)
            for (e in pageRaw) {
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
