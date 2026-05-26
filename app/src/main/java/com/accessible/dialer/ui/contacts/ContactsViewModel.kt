package com.accessible.dialer.ui.contacts

import android.content.Context
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Contact(
    val id: Long,
    val name: String,
    val number: String,
    val starred: Boolean,
)

/**
 * Shared ViewModel for both the Contacts and Favorites screens. Favorites is just the
 * subset where `starred == true`, so a single load keeps the data source consistent.
 */
class ContactsViewModel : ViewModel() {
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    fun setQuery(q: String) { _query.value = q }

    fun load(context: Context) {
        viewModelScope.launch {
            _contacts.value = withContext(Dispatchers.IO) { query(context) }
        }
    }

    private fun query(context: Context): List<Contact> {
        // Query phone numbers; one row per number, but we dedupe by contact id keeping the
        // primary number to avoid showing the same person multiple times.
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
        )
        val result = LinkedHashMap<Long, Contact>()
        context.contentResolver.query(
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
        return result.values.toList()
    }
}
