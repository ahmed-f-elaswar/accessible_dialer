package com.accessible.dialer.util

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import com.accessible.dialer.R

/**
 * Centralized contact manipulation. Where possible we delegate to the system Contacts app
 * via `Intent.ACTION_INSERT` / `ACTION_EDIT` rather than reimplementing forms — the
 * system app already has full TalkBack support, validation, and account picking.
 * Operations that must happen silently (toggle favorite, delete, block) go through
 * ContentResolver directly.
 */
object ContactOps {
    /** Launches the system contact creator. Optionally prefills a number. */
    fun createContact(context: Context, prefillNumber: String? = null) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            if (!prefillNumber.isNullOrBlank()) {
                putExtra(ContactsContract.Intents.Insert.PHONE, prefillNumber)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** Launches the system contact editor for [contactId]. */
    fun editContact(context: Context, contactId: Long) {
        if (contactId <= 0L) return
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val intent = Intent(Intent.ACTION_EDIT, uri).apply {
            // Hint to land on the ringtone field. The system app honors this on most OEMs;
            // when it doesn't we still open the regular editor which is fine.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** Deletes the contact's raw entries. Requires WRITE_CONTACTS, declared in manifest. */
    fun deleteContact(context: Context, contactId: Long): Boolean {
        if (contactId <= 0L) return false
        val removed = runCatching {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID}=?",
                arrayOf(contactId.toString()),
            )
        }.getOrDefault(0)
        if (removed > 0) {
            Toast.makeText(context, R.string.contact_deleted, Toast.LENGTH_SHORT).show()
            return true
        }
        Toast.makeText(context, R.string.contact_delete_failed, Toast.LENGTH_SHORT).show()
        return false
    }

    /** Toggles the STARRED flag. Returns the new value (true == starred) or null on error. */
    fun toggleFavorite(context: Context, contactId: Long, currentlyStarred: Boolean): Boolean? {
        if (contactId <= 0L) return null
        val values = android.content.ContentValues().apply {
            put(ContactsContract.Contacts.STARRED, if (currentlyStarred) 0 else 1)
        }
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val updated = runCatching { context.contentResolver.update(uri, values, null, null) }
            .getOrDefault(0)
        return if (updated > 0) !currentlyStarred else null
    }

    /**
     * Shares the contact as plain text (name, phones, emails, organization) using
     * the Android share sheet. No system Contacts dependency — we read the data
     * directly from ContactsContract and format it ourselves.
     */
    fun shareContactAsText(context: Context, contactId: Long) {
        if (contactId <= 0L) return
        val cr = context.contentResolver
        var name = ""
        cr.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) name = c.getString(0).orEmpty() }

        val phones = mutableListOf<Pair<String, Int>>()
        val emails = mutableListOf<Pair<String, Int>>()
        var organization = ""
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
            ),
            "${ContactsContract.Data.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(0)
                val d1 = c.getString(1).orEmpty()
                val d2 = c.getInt(2)
                when (mime) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                        if (d1.isNotBlank()) phones += d1 to d2
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                        if (d1.isNotBlank()) emails += d1 to d2
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE ->
                        if (d1.isNotBlank()) organization = d1
                }
            }
        }

        val sb = StringBuilder()
        if (name.isNotBlank()) sb.appendLine(name)
        if (organization.isNotBlank()) sb.appendLine(organization)
        phones.forEach { (num, type) ->
            val label = ContactsContract.CommonDataKinds.Phone
                .getTypeLabel(context.resources, type, "").toString()
            sb.appendLine("$label: $num")
        }
        emails.forEach { (addr, type) ->
            val label = ContactsContract.CommonDataKinds.Email
                .getTypeLabel(context.resources, type, "").toString()
            sb.appendLine("$label: $addr")
        }
        val body = sb.toString().trim()
        if (body.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.action_share_contact))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    /** Shares a vCard for [contactId] via the Android share sheet. */
    fun shareContact(context: Context, contactId: Long) {
        if (contactId <= 0L) return
        // Look up the LOOKUP_KEY so we can build the multi-vcard URI the system expects.
        val lookupKey = runCatching {
            context.contentResolver.query(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: return
        val shareUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ContactsContract.Contacts.CONTENT_VCARD_TYPE
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.action_share_contact))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    /** Opens the system Blocked Numbers screen so the user can block [number]. */
    fun blockNumber(context: Context, number: String) {
        if (number.isBlank()) return
        // The cleanest, permission-free path is to launch the system blocked-numbers UI
        // and let the user confirm. Adding silently requires being the default dialer AND
        // calling BlockedNumberContract directly; we offer the UI route here.
        val intent = Intent("android.telecom.action.SHOW_BLOCKED_NUMBERS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, R.string.action_block_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the contact editor so the user can pick a custom ringtone. */
    fun setRingtone(context: Context, contactId: Long) {
        // The dedicated ringtone field lives inside the system contact editor; jumping
        // straight there keeps us out of building a custom RingtoneManager picker UI.
        editContact(context, contactId)
    }
}
