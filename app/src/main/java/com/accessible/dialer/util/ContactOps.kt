package com.accessible.dialer.util

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.widget.Toast
import com.accessible.dialer.R
import com.accessible.dialer.blocking.BlockedNumbersRepository

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

    /**
     * Shares a single vCard containing every contact in [contactIds] via the
     * system share sheet. Uses the platform's multi-vCard URI (lookup keys
     * joined by ':'), which the framework resolves to a single combined .vcf
     * stream. Falls back to no-op when nothing resolves to a lookup key.
     */
    fun shareContacts(context: Context, contactIds: Collection<Long>) {
        if (contactIds.isEmpty()) return
        val cr = context.contentResolver
        val lookupKeys = contactIds.mapNotNull { id ->
            if (id <= 0L) return@mapNotNull null
            runCatching {
                cr.query(
                    ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id),
                    arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }.filter { !it.isNullOrBlank() }
        if (lookupKeys.isEmpty()) return
        if (lookupKeys.size == 1) {
            shareContact(context, contactIds.first { it > 0L })
            return
        }
        val shareUri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI,
            android.net.Uri.encode(lookupKeys.joinToString(":")),
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ContactsContract.Contacts.CONTENT_VCARD_TYPE
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.action_share_contact))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    /**
     * Blocks [number] via [BlockedNumbersRepository] (which talks to the system
     * `BlockedNumberContract` provider). Shows a toast confirming the outcome.
     * Falls back to launching the system blocked-numbers UI if the direct insert
     * fails (e.g. the app has lost its default-dialer role).
     */
    fun blockNumber(context: Context, number: String) {
        if (number.isBlank()) return
        val added = runCatching {
            BlockedNumbersRepository.block(context, number)
        }.getOrNull()
        when (added) {
            true -> Toast.makeText(
                context,
                R.string.blocked_numbers_blocked_short,
                Toast.LENGTH_SHORT,
            ).show()
            false -> Toast.makeText(
                context,
                R.string.blocked_numbers_already_blocked_short,
                Toast.LENGTH_SHORT,
            ).show()
            null -> {
                // Provider was unavailable — open the system UI as a fallback.
                val intent = Intent("android.telecom.action.SHOW_BLOCKED_NUMBERS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }.onFailure {
                    Toast.makeText(context, R.string.action_block_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Removes [number] from the system block list. */
    fun unblockNumber(context: Context, number: String) {
        if (number.isBlank()) return
        BlockedNumbersRepository.refresh(context)
        BlockedNumbersRepository.unblockByNumber(context, number)
        Toast.makeText(
            context,
            R.string.blocked_numbers_unblocked_short,
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** Opens the contact editor so the user can pick a custom ringtone. */
    fun setRingtone(context: Context, contactId: Long) {
        // The dedicated ringtone field lives inside the system contact editor; jumping
        // straight there keeps us out of building a custom RingtoneManager picker UI.
        editContact(context, contactId)
    }

    /**
     * Erases every CallLog row whose number matches any of [numbers] using
     * [PhoneNumberUtils.compare]. Returns the number of rows deleted. Cheap enough to
     * run on the main thread because the call log is bounded; callers should still
     * wrap in IO dispatcher when called from a Composable to keep the UI thread free.
     */
    fun eraseCallHistory(context: Context, numbers: List<String>): Int {
        if (numbers.isEmpty()) return 0
        val cr = context.contentResolver
        val toDelete = mutableListOf<Long>()
        runCatching {
            cr.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER),
                null, null, null,
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                while (c.moveToNext()) {
                    val row = c.getString(numIdx).orEmpty()
                    if (row.isBlank()) continue
                    @Suppress("DEPRECATION")
                    if (numbers.any { PhoneNumberUtils.compare(it, row) }) {
                        toDelete += c.getLong(idIdx)
                    }
                }
            }
        }
        var deleted = 0
        toDelete.forEach { id ->
            val rows = runCatching {
                cr.delete(
                    ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, id),
                    null, null,
                )
            }.getOrDefault(0)
            deleted += rows
        }
        return deleted
    }

    /**
     * Erases every entry in the device call log. Returns the number of rows deleted, or
     * -1 if the delete itself failed (e.g. missing WRITE_CALL_LOG). Cheap enough to run
     * on the main thread because the call log is bounded.
     */
    fun eraseAllCallHistory(context: Context): Int {
        return runCatching {
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
        }.getOrDefault(-1)
    }

    /** Opens the default SMS app composer addressed to [number]. */
    fun sendSms(context: Context, number: String) {
        if (number.isBlank()) return
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, R.string.action_sms_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens a WhatsApp chat to [number] using the public `https://wa.me/<digits>` URL.
     * This works whether or not WhatsApp is installed (falls back to the browser, which
     * will prompt the user to install).
     */
    fun openWhatsApp(context: Context, number: String) {
        if (number.isBlank()) return
        // WhatsApp's wa.me handler requires digits only (with country code, no '+').
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, R.string.action_whatsapp_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Reads the current SEND_TO_VOICEMAIL flag for [contactId]. */
    fun isSendToVoicemail(context: Context, contactId: Long): Boolean {
        if (contactId <= 0L) return false
        return runCatching {
            context.contentResolver.query(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                arrayOf(ContactsContract.Contacts.SEND_TO_VOICEMAIL),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) == 1 else false } ?: false
        }.getOrDefault(false)
    }

    /**
     * Toggles SEND_TO_VOICEMAIL for [contactId]. When enabled, the system routes all
     * incoming calls from this contact straight to voicemail without ringing.
     */
    fun setSendToVoicemail(context: Context, contactId: Long, enabled: Boolean): Boolean {
        if (contactId <= 0L) return false
        val values = android.content.ContentValues().apply {
            put(ContactsContract.Contacts.SEND_TO_VOICEMAIL, if (enabled) 1 else 0)
        }
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val updated = runCatching { context.contentResolver.update(uri, values, null, null) }
            .getOrDefault(0)
        return updated > 0
    }

    /**
     * Builds a minimal vCard 3.0 string suitable for encoding into a QR code. Includes
     * the display name, organization, and all phone/email rows. Returns null if the
     * contact has no usable data.
     */
    fun buildVCard(context: Context, contactId: Long): String? {
        if (contactId <= 0L) return null
        val cr = context.contentResolver
        var name = ""
        cr.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) name = c.getString(0).orEmpty() }

        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        var organization = ""
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(0)
                val d1 = c.getString(1).orEmpty()
                if (d1.isBlank()) continue
                when (mime) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones += d1
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails += d1
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE ->
                        organization = d1
                }
            }
        }
        if (name.isBlank() && phones.isEmpty() && emails.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        if (name.isNotBlank()) {
            sb.append("FN:").append(name).append("\r\n")
            sb.append("N:").append(name).append(";;;;\r\n")
        }
        if (organization.isNotBlank()) sb.append("ORG:").append(organization).append("\r\n")
        phones.forEach { sb.append("TEL:").append(it).append("\r\n") }
        emails.forEach { sb.append("EMAIL:").append(it).append("\r\n") }
        sb.append("END:VCARD\r\n")
        return sb.toString()
    }
}
