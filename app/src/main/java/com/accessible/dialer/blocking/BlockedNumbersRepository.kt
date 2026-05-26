package com.accessible.dialer.blocking

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wrapper around the system [BlockedNumberContract] provider.
 *
 * Only the default dialer / SMS app can read & write blocked numbers without
 * needing the (privileged) READ/WRITE_BLOCKED_NUMBERS permissions. This app is
 * registered as the default dialer so all of the operations below succeed
 * directly.
 */
object BlockedNumbersRepository {

    /** A single blocked entry returned by the system provider. */
    data class BlockedEntry(
        val id: Long,
        /** The original (display) number as the user entered it. */
        val originalNumber: String,
        /** The normalised E.164 / digits-only form used for matching. */
        val e164Number: String?,
    ) {
        /** Best-effort label to render in the UI. */
        val displayNumber: String
            get() = originalNumber.ifBlank { e164Number.orEmpty() }
    }

    private val _entries = MutableStateFlow<List<BlockedEntry>>(emptyList())
    val entries: StateFlow<List<BlockedEntry>> = _entries.asStateFlow()

    /** Reloads the cached list from the system provider. Safe to call on the main thread. */
    fun refresh(context: Context) {
        _entries.value = query(context)
    }

    /** Returns true if [number] is currently blocked. */
    fun isBlocked(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        return runCatching {
            BlockedNumberContract.isBlocked(context, number)
        }.getOrDefault(false)
    }

    /**
     * Adds [number] to the system block list. Returns true if a new row was
     * inserted, false if it was already blocked or the input was empty.
     */
    fun block(context: Context, number: String): Boolean {
        val trimmed = number.trim()
        if (trimmed.isBlank()) return false
        if (isBlocked(context, trimmed)) {
            refresh(context)
            return false
        }
        val values = ContentValues().apply {
            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, trimmed)
            // Best-effort E.164 normalisation; the provider also normalises on its own.
            PhoneNumberUtils.normalizeNumber(trimmed)?.takeIf { it.isNotBlank() }?.let {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER, it)
            }
        }
        val uri = runCatching {
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                values,
            )
        }.getOrNull()
        refresh(context)
        return uri != null
    }

    /** Removes the entry with the given row id. */
    fun unblock(context: Context, id: Long) {
        runCatching {
            context.contentResolver.delete(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                "${BlockedNumberContract.BlockedNumbers.COLUMN_ID}=?",
                arrayOf(id.toString()),
            )
        }
        refresh(context)
    }

    /** Removes any entry whose original or E.164 form matches [number]. */
    fun unblockByNumber(context: Context, number: String) {
        val trimmed = number.trim()
        if (trimmed.isBlank()) return
        val matches = _entries.value.filter {
            PhoneNumberUtils.compare(context, it.originalNumber, trimmed) ||
                PhoneNumberUtils.compare(context, it.e164Number.orEmpty(), trimmed)
        }
        if (matches.isEmpty()) {
            // Fall back to a direct provider query in case the in-memory cache is stale.
            refresh(context)
            val live = _entries.value.filter {
                PhoneNumberUtils.compare(context, it.originalNumber, trimmed) ||
                    PhoneNumberUtils.compare(context, it.e164Number.orEmpty(), trimmed)
            }
            live.forEach { unblock(context, it.id) }
            return
        }
        matches.forEach { unblock(context, it.id) }
    }

    private fun query(context: Context): List<BlockedEntry> {
        val projection = arrayOf(
            BlockedNumberContract.BlockedNumbers.COLUMN_ID,
            BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
            BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER,
        )
        val out = mutableListOf<BlockedEntry>()
        runCatching {
            context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                projection,
                null,
                null,
                "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += BlockedEntry(
                        id = c.getLong(0),
                        originalNumber = c.getString(1).orEmpty(),
                        e164Number = if (c.isNull(2)) null else c.getString(2),
                    )
                }
            }
        }
        return out
    }
}
