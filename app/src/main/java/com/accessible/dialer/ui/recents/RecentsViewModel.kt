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
)

class RecentsViewModel : ViewModel() {
    private val _entries = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val entries: StateFlow<List<CallLogEntry>> = _entries

    fun load(context: Context) {
        viewModelScope.launch {
            _entries.value = withContext(Dispatchers.IO) { query(context) }
        }
    }

    private fun query(context: Context): List<CallLogEntry> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
        )
        val result = mutableListOf<CallLogEntry>()
        // Limit to 200 entries; the system call log can be huge and we don't paginate here.
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC LIMIT 200",
        )?.use { c ->
            val idx = intArrayOf(
                c.getColumnIndexOrThrow(CallLog.Calls._ID),
                c.getColumnIndexOrThrow(CallLog.Calls.NUMBER),
                c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME),
                c.getColumnIndexOrThrow(CallLog.Calls.TYPE),
                c.getColumnIndexOrThrow(CallLog.Calls.DATE),
            )
            while (c.moveToNext()) {
                val date = c.getLong(idx[4])
                result += CallLogEntry(
                    id = c.getLong(idx[0]),
                    number = c.getString(idx[1]).orEmpty(),
                    displayName = c.getString(idx[2]).takeIf { !it.isNullOrBlank() },
                    type = c.getInt(idx[3]),
                    date = date,
                    relativeTime = formatRelative(Date(date)),
                )
            }
        }
        // Collapse the raw log so each contact / phone number appears once, represented by
        // its most recent call. The cursor is sorted DESC by DATE, so the first occurrence
        // of any normalized number is the latest one for that contact.
        val seen = HashSet<String>(result.size)
        val deduped = ArrayList<CallLogEntry>(result.size)
        for (entry in result) {
            val key = dedupeKey(entry)
            if (seen.add(key)) deduped += entry
        }
        return deduped
    }

    private fun dedupeKey(entry: CallLogEntry): String {
        val raw = entry.number.trim()
        if (raw.isEmpty()) {
            // Anonymous / private calls have no number; collapse them into one row.
            return "__private__"
        }
        val normalized = PhoneNumberUtils.normalizeNumber(raw)
        return normalized.ifEmpty { raw }
    }
}
