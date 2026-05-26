package com.accessible.dialer.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import com.accessible.dialer.R

/** Shared helpers used by accessibility actions on contact / recents rows. */
object RowActions {
    /** Opens the system SMS composer pre-filled with [number]. */
    fun sendSms(context: Context, number: String) {
        if (number.isBlank()) return
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** Copies [number] to the clipboard with a short confirmation toast. */
    fun copyNumber(context: Context, number: String) {
        if (number.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("phone", number))
        Toast.makeText(context, context.getString(R.string.number_copied), Toast.LENGTH_SHORT).show()
    }

    /** Opens the system contact details viewer for [contactId]. */
    fun openContactDetails(context: Context, contactId: Long) {
        if (contactId <= 0L) return
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Resolve the aggregated Contacts row id for [number], or `null` if no contact
     * matches. Uses [ContactsContract.PhoneLookup] which is the cheap, E.164-tolerant
     * index designed exactly for this "given a dialed/received number, find the contact"
     * use case. Must be called off the main thread.
     */
    fun lookupContactId(context: Context, number: String): Long? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
    }

    /**
     * Delete the aggregated contact identified by [contactId]. This removes every
     * RawContact under that aggregate (Contacts.CONTENT_URI honors the delete cascade
     * via the contacts provider) so the person disappears from every list. Returns
     * whether at least one row was deleted; callers should refresh their UI on true.
     */
    fun deleteContact(context: Context, contactId: Long): Boolean {
        if (contactId <= 0L) return false
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        return runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }
}
