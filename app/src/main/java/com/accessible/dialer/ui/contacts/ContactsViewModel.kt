package com.accessible.dialer.ui.contacts

import android.content.Context
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.settings.SettingsRepository.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Contact(
    val id: Long,
    val name: String,
    val number: String,
    val starred: Boolean,
    val company: String = "",
    // "<account_type>|<account_name>" keys this contact's raw rows belong to. A contact
    // can be aggregated across multiple accounts, so this is a set.
    val accountKeys: Set<String> = emptySet(),
)

/**
 * Shared ViewModel for both the Contacts and Favorites screens. Favorites is just the
 * subset where `starred == true`, so a single load keeps the data source consistent.
 *
 * The UI consumes [displayed], a derived flow that applies the user-picked sort order
 * and "include contacts without phone numbers" toggle.
 */
class ContactsViewModel : ViewModel() {
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    /** Raw contact list as queried from the system provider. */
    val contacts: StateFlow<List<Contact>> = _contacts

    /** Sorted/filtered view consumed by the UI. */
    val displayed: StateFlow<List<Contact>> = combine(
        _contacts,
        SettingsRepository.sortOrder,
        SettingsRepository.showNoPhone,
        SettingsRepository.accountFilter,
    ) { list, sort, showNoPhone, accountFilter ->
        var filtered = if (showNoPhone) list else list.filter { it.number.isNotBlank() }
        if (accountFilter.isNotEmpty()) {
            filtered = filtered.filter { c -> c.accountKeys.any { it in accountFilter } }
        }
        when (sort) {
            SortOrder.FirstName -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            SortOrder.LastName -> filtered.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { c -> c.name.substringAfterLast(' ', c.name) }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    fun setQuery(q: String) { _query.value = q }

    fun load(context: Context) {
        viewModelScope.launch {
            _contacts.value = withContext(Dispatchers.IO) { query(context) }
        }
    }

    private fun query(context: Context): List<Contact> {
        val cr = context.contentResolver
        // 1) Phone numbers — one row per number; dedupe by contact id, prefer IS_PRIMARY.
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
        )
        val result = LinkedHashMap<Long, Contact>()
        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val starIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
            val primaryIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val existing = result[id]
                val isPrimary = c.getInt(primaryIdx) == 1
                if (existing == null || isPrimary) {
                    result[id] = Contact(
                        id = id,
                        name = c.getString(nameIdx).orEmpty(),
                        number = c.getString(numIdx).orEmpty(),
                        starred = c.getInt(starIdx) == 1,
                    )
                }
            }
        }

        // 2) Organization rows — fill in company per contact so the search box can match
        // against employer names. One extra query is cheap at typical address-book sizes.
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.CommonDataKinds.Organization.COMPANY,
            ),
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val coIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Organization.COMPANY)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val company = c.getString(coIdx).orEmpty()
                if (company.isBlank()) continue
                val existing = result[id]
                if (existing != null) {
                    result[id] = existing.copy(company = company)
                }
            }
        }

        // 3) Contacts without phone numbers — fetched unconditionally so the displayed
        // flow can toggle them on/off without re-running the whole query.
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ),
            null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val starIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
            val hasPhoneIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                if (result.containsKey(id)) continue
                if (c.getInt(hasPhoneIdx) != 0) continue
                result[id] = Contact(
                    id = id,
                    name = c.getString(nameIdx).orEmpty(),
                    number = "",
                    starred = c.getInt(starIdx) == 1,
                )
            }
        }

        // 4) Account membership per contact — read RawContacts and map every contact_id
        // to the set of "<type>|<name>" keys covering its raw rows. Used to filter the
        // displayed list when the user picks specific storage locations.
        cr.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts.CONTACT_ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.ACCOUNT_NAME,
            ),
            "${ContactsContract.RawContacts.DELETED} = 0",
            null,
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
            val typeIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val existing = result[id] ?: continue
                val key = "${c.getString(typeIdx) ?: "null"}|${c.getString(nameIdx) ?: "null"}"
                result[id] = existing.copy(accountKeys = existing.accountKeys + key)
            }
        }

        return result.values.toList()
    }
}
