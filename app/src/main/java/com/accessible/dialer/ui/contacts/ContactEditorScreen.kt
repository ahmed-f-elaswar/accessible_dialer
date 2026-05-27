package com.accessible.dialer.ui.contacts

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure in-app contact editor — no system Contacts app involved. Talks straight to
 * `ContactsContract` via a `ContentProviderOperation` batch.
 *
 * Scope: structured name, phones, emails, addresses, websites, events,
 * nickname, organization (company + title), notes.
 *
 * Edit strategy: pick the first raw contact for the aggregated contact, delete all
 * rows for the managed mimetypes on it, then re-insert. Anything outside this set
 * (account-sync extension rows, GroupMembership, Im, Relation, etc.) is preserved.
 */
private const val MIMETYPE_NAME = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
private const val MIMETYPE_PHONE = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
private const val MIMETYPE_EMAIL = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
private const val MIMETYPE_ADDR = ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE
private const val MIMETYPE_WEB = ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE
private const val MIMETYPE_EVENT = ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE
private const val MIMETYPE_NICK = ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE
private const val MIMETYPE_ORG = ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
private const val MIMETYPE_NOTE = ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE

private val MANAGED_MIMES = arrayOf(
    MIMETYPE_NAME, MIMETYPE_PHONE, MIMETYPE_EMAIL, MIMETYPE_ADDR, MIMETYPE_WEB,
    MIMETYPE_EVENT, MIMETYPE_NICK, MIMETYPE_ORG, MIMETYPE_NOTE,
)

private data class EditablePhone(val key: Int, val number: String, val type: Int)
private data class EditableEmail(val key: Int, val address: String, val type: Int)
private data class EditableAddress(val key: Int, val formatted: String, val type: Int)
private data class EditableWebsite(val key: Int, val url: String, val type: Int)
private data class EditableEvent(val key: Int, val date: String, val type: Int)

private val PHONE_TYPES = listOf(
    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
    ContactsContract.CommonDataKinds.Phone.TYPE_HOME,
    ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
    ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK,
    ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME,
    ContactsContract.CommonDataKinds.Phone.TYPE_PAGER,
    ContactsContract.CommonDataKinds.Phone.TYPE_OTHER,
)
private val EMAIL_TYPES = listOf(
    ContactsContract.CommonDataKinds.Email.TYPE_HOME,
    ContactsContract.CommonDataKinds.Email.TYPE_WORK,
    ContactsContract.CommonDataKinds.Email.TYPE_MOBILE,
    ContactsContract.CommonDataKinds.Email.TYPE_OTHER,
)
private val ADDR_TYPES = listOf(
    ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME,
    ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK,
    ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER,
)
private val WEB_TYPES = listOf(
    ContactsContract.CommonDataKinds.Website.TYPE_HOMEPAGE,
    ContactsContract.CommonDataKinds.Website.TYPE_HOME,
    ContactsContract.CommonDataKinds.Website.TYPE_WORK,
    ContactsContract.CommonDataKinds.Website.TYPE_BLOG,
    ContactsContract.CommonDataKinds.Website.TYPE_PROFILE,
    ContactsContract.CommonDataKinds.Website.TYPE_OTHER,
)
private val EVENT_TYPES = listOf(
    ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY,
    ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY,
    ContactsContract.CommonDataKinds.Event.TYPE_OTHER,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactEditorScreen(
    contactId: Long, // 0 = new
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    /**
     * Optional phone number to pre-populate when creating a new contact (contactId
     * <= 0). Ignored when editing an existing contact. Used by the Recents "Save as
     * new contact" action to hand off an unknown caller's number directly into the
     * editor without an extra paste step.
     */
    prefillNumber: String? = null,
) {
    val context = LocalContext.current
    val isNew = contactId <= 0L

    var loading by remember { mutableStateOf(!isNew) }
    var displayName by remember { mutableStateOf("") }
    var givenName by remember { mutableStateOf("") }
    var middleName by remember { mutableStateOf("") }
    var familyName by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var jobTitle by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    // Collapse advanced sections by default — only name, phones, and emails are shown
    // up front. The user explicitly asked for a "Show more" toggle to reveal the rest.
    var showMore by rememberSaveable { mutableStateOf(false) }
    val phones = remember { mutableStateListOf<EditablePhone>() }
    val emails = remember { mutableStateListOf<EditableEmail>() }
    val addresses = remember { mutableStateListOf<EditableAddress>() }
    val websites = remember { mutableStateListOf<EditableWebsite>() }
    val events = remember { mutableStateListOf<EditableEvent>() }
    var nextKey by remember { mutableStateOf(0) }
    // Account that will own this contact. When [isNew] the user picks from a
    // dropdown; when editing we display the current owner as read-only (moving
    // contacts between accounts is done from Settings → Storage locations,
    // which can copy every field including group memberships in a single
    // batch — too heavyweight to bolt onto every save here).
    var selectedAccountKey by rememberSaveable {
        mutableStateOf(com.accessible.dialer.util.ContactAccounts.LOCAL_KEY)
    }

    LaunchedEffect(contactId) {
        if (isNew) {
            phones.add(EditablePhone(
                nextKey++,
                prefillNumber.orEmpty(),
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            ))
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) { loadForEdit(context, contactId) }
        displayName = loaded.displayName
        givenName = loaded.given
        middleName = loaded.middle
        familyName = loaded.family
        prefix = loaded.prefix
        suffix = loaded.suffix
        organization = loaded.company
        jobTitle = loaded.title
        nickname = loaded.nickname
        note = loaded.note
        loaded.phones.forEach { phones.add(EditablePhone(nextKey++, it.first, it.second)) }
        loaded.emails.forEach { emails.add(EditableEmail(nextKey++, it.first, it.second)) }
        loaded.addresses.forEach { addresses.add(EditableAddress(nextKey++, it.first, it.second)) }
        loaded.websites.forEach { websites.add(EditableWebsite(nextKey++, it.first, it.second)) }
        loaded.events.forEach { events.add(EditableEvent(nextKey++, it.first, it.second)) }
        selectedAccountKey = loaded.accountKey
        // When the editor was opened from "Add to existing contact" with a phone
        // number to merge in, append it as a fresh editable phone row (unless an
        // exact match already exists) so the user can adjust the label/type before
        // saving. Comparison is on the unformatted string — close enough for the
        // dialer flow that fed it.
        if (!prefillNumber.isNullOrBlank() && phones.none { it.number == prefillNumber }) {
            phones.add(EditablePhone(
                nextKey++,
                prefillNumber,
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            ))
        }
        if (phones.isEmpty()) {
            phones.add(EditablePhone(nextKey++, "", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE))
        }
        // Auto-expand the advanced section if the existing contact already has data
        // in any of the hidden fields — otherwise the user would think we dropped it.
        if (middleName.isNotBlank() || prefix.isNotBlank() || suffix.isNotBlank() ||
            nickname.isNotBlank() || note.isNotBlank() ||
            organization.isNotBlank() || jobTitle.isNotBlank() ||
            addresses.isNotEmpty() || websites.isNotEmpty() || events.isNotEmpty()) {
            showMore = true
        }
        loading = false
    }

    val backLabel = stringResource(R.string.action_back)
    val titleNew = stringResource(R.string.editor_title_new)
    val titleEdit = stringResource(R.string.editor_title_edit)
    val saveLabel = stringResource(R.string.editor_save)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (isNew) titleNew else titleEdit) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val newId = saveContact(
                                context = context,
                                existingContactId = contactId,
                                displayName = displayName.trim(),
                                given = givenName.trim(),
                                middle = middleName.trim(),
                                family = familyName.trim(),
                                prefix = prefix.trim(),
                                suffix = suffix.trim(),
                                organization = organization.trim(),
                                jobTitle = jobTitle.trim(),
                                nickname = nickname.trim(),
                                note = note.trim(),
                                phones = phones.toList(),
                                emails = emails.toList(),
                                addresses = addresses.toList(),
                                websites = websites.toList(),
                                events = events.toList(),
                                accountKey = selectedAccountKey,
                            )
                            if (newId != null) {
                                Toast.makeText(context, R.string.editor_saved, Toast.LENGTH_SHORT).show()
                                onSaved(newId)
                            } else {
                                Toast.makeText(context, R.string.editor_save_failed, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = saveLabel },
                    ) { Text(saveLabel) }
                },
            )
        },
    ) { inner ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(inner)) {
                Text(
                    text = stringResource(R.string.contact_details_loading),
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // -------- Storage account --------
            // For a new contact we let the user pick where the row lives —
            // Local, Google, Samsung, etc. For an existing contact this is
            // shown as a read-only label; the move flow lives in
            // Settings → Storage locations so we don't half-implement
            // cross-account migration on the save path.
            AccountSelectorRow(
                accountKey = selectedAccountKey,
                editable = isNew,
                onChange = { selectedAccountKey = it },
            )
            HorizontalDivider()

            // -------- Name --------
            SectionLabel(stringResource(R.string.editor_section_name))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.editor_field_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = givenName,
                onValueChange = { givenName = it },
                label = { Text(stringResource(R.string.editor_field_given)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = familyName,
                onValueChange = { familyName = it },
                label = { Text(stringResource(R.string.editor_field_family)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            // -------- Phones --------
            SectionLabel(stringResource(R.string.contact_details_section_phones))
            phones.forEachIndexed { index, phone ->
                PhoneEditorRow(
                    phone = phone,
                    onNumberChange = { phones[index] = phone.copy(number = it) },
                    onTypeChange = { phones[index] = phone.copy(type = it) },
                    onRemove = { phones.removeAt(index) },
                )
            }
            AddButton(stringResource(R.string.editor_add_phone)) {
                phones.add(EditablePhone(nextKey++, "", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE))
            }

            HorizontalDivider()
            // -------- Emails --------
            SectionLabel(stringResource(R.string.contact_details_section_emails))
            emails.forEachIndexed { index, email ->
                EmailEditorRow(
                    email = email,
                    onAddressChange = { emails[index] = email.copy(address = it) },
                    onTypeChange = { emails[index] = email.copy(type = it) },
                    onRemove = { emails.removeAt(index) },
                )
            }
            AddButton(stringResource(R.string.editor_add_email)) {
                emails.add(EditableEmail(nextKey++, "", ContactsContract.CommonDataKinds.Email.TYPE_HOME))
            }

            HorizontalDivider()
            // -------- Show more / less toggle --------
            TextButton(onClick = { showMore = !showMore }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (showMore) stringResource(R.string.editor_show_less)
                    else stringResource(R.string.editor_show_more)
                )
            }

            if (showMore) {
            OutlinedTextField(
                value = middleName,
                onValueChange = { middleName = it },
                label = { Text(stringResource(R.string.editor_field_middle)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text(stringResource(R.string.editor_field_prefix)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = suffix,
                    onValueChange = { suffix = it },
                    label = { Text(stringResource(R.string.editor_field_suffix)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text(stringResource(R.string.contact_details_nickname)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            // -------- Addresses --------
            SectionLabel(stringResource(R.string.contact_details_section_addresses))
            addresses.forEachIndexed { index, addr ->
                AddressEditorRow(
                    address = addr,
                    onFormattedChange = { addresses[index] = addr.copy(formatted = it) },
                    onTypeChange = { addresses[index] = addr.copy(type = it) },
                    onRemove = { addresses.removeAt(index) },
                )
            }
            AddButton(stringResource(R.string.editor_add_address)) {
                addresses.add(EditableAddress(nextKey++, "", ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME))
            }

            HorizontalDivider()
            // -------- Websites --------
            SectionLabel(stringResource(R.string.contact_details_section_websites))
            websites.forEachIndexed { index, web ->
                WebsiteEditorRow(
                    website = web,
                    onUrlChange = { websites[index] = web.copy(url = it) },
                    onTypeChange = { websites[index] = web.copy(type = it) },
                    onRemove = { websites.removeAt(index) },
                )
            }
            AddButton(stringResource(R.string.editor_add_website)) {
                websites.add(EditableWebsite(nextKey++, "", ContactsContract.CommonDataKinds.Website.TYPE_HOMEPAGE))
            }

            HorizontalDivider()
            // -------- Events --------
            SectionLabel(stringResource(R.string.contact_details_section_events))
            events.forEachIndexed { index, ev ->
                EventEditorRow(
                    event = ev,
                    onDateChange = { events[index] = ev.copy(date = it) },
                    onTypeChange = { events[index] = ev.copy(type = it) },
                    onRemove = { events.removeAt(index) },
                )
            }
            AddButton(stringResource(R.string.editor_add_event)) {
                events.add(EditableEvent(nextKey++, "", ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY))
            }

            HorizontalDivider()
            // -------- Organization --------
            SectionLabel(stringResource(R.string.contact_details_organization))
            OutlinedTextField(
                value = organization,
                onValueChange = { organization = it },
                label = { Text(stringResource(R.string.editor_field_company)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = jobTitle,
                onValueChange = { jobTitle = it },
                label = { Text(stringResource(R.string.editor_field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            // -------- Notes --------
            SectionLabel(stringResource(R.string.contact_details_section_notes))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.editor_field_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
            } // end if (showMore)

            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.size(4.dp))
        Text(label)
    }
}

/**
 * "Saved in" row for the editor. Shows the friendly label of the contact's
 * storage account and \u2014 only when [editable] (i.e. creating a new contact)
 * \u2014 opens a dropdown of every account on the device on click.
 *
 * When not editable we still render the row (so the user knows *where* the
 * contact lives) but disable the click + dropdown.
 */
@Composable
private fun AccountSelectorRow(
    accountKey: String,
    editable: Boolean,
    onChange: (String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<com.accessible.dialer.util.ContactAccounts.Entry>>(emptyList()) }
    // Only load the account list when the picker can actually open it. Keying
    // off `editable` instead of `Unit` means recompositions of the editor (one
    // happens on every keystroke in any text field) don't re-issue the
    // ContentResolver query — it runs exactly once per (new) editor session.
    LaunchedEffect(editable) {
        if (!editable) return@LaunchedEffect
        entries = withContext(Dispatchers.IO) {
            com.accessible.dialer.util.ContactAccounts.list(context)
        }
    }
    val currentLabel = com.accessible.dialer.util.ContactAccounts.friendlyLabel(accountKey)
    SectionLabel(stringResource(R.string.editor_section_account))
    Box {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            enabled = editable,
            label = { Text(stringResource(R.string.editor_field_account)) },
            trailingIcon = {
                if (editable) {
                    val openLabel = stringResource(R.string.editor_pick_account_title)
                    IconButton(
                        onClick = { expanded = true },
                        modifier = Modifier.semantics { contentDescription = openLabel },
                    ) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (editable) {
                    Modifier.clickable { expanded = true }
                } else Modifier),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        expanded = false
                        onChange(entry.key)
                    },
                )
            }
        }
    }
}

@Composable
private fun PhoneEditorRow(
    phone: EditablePhone,
    onNumberChange: (String) -> Unit,
    onTypeChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    FieldRow(
        primaryField = {
            OutlinedTextField(
                value = phone.number,
                onValueChange = onNumberChange,
                label = { Text(stringResource(R.string.editor_field_phone)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        typeChip = {
            TypeChip(
                label = phoneTypeLabel(context, phone.type),
                options = PHONE_TYPES.map { it to phoneTypeLabel(context, it) },
                onSelect = onTypeChange,
            )
        },
        onRemove = onRemove,
    )
}

@Composable
private fun EmailEditorRow(
    email: EditableEmail,
    onAddressChange: (String) -> Unit,
    onTypeChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    FieldRow(
        primaryField = {
            OutlinedTextField(
                value = email.address,
                onValueChange = onAddressChange,
                label = { Text(stringResource(R.string.editor_field_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        typeChip = {
            TypeChip(
                label = emailTypeLabel(context, email.type),
                options = EMAIL_TYPES.map { it to emailTypeLabel(context, it) },
                onSelect = onTypeChange,
            )
        },
        onRemove = onRemove,
    )
}

@Composable
private fun AddressEditorRow(
    address: EditableAddress,
    onFormattedChange: (String) -> Unit,
    onTypeChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    FieldRow(
        primaryField = {
            OutlinedTextField(
                value = address.formatted,
                onValueChange = onFormattedChange,
                label = { Text(stringResource(R.string.editor_field_address)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        typeChip = {
            TypeChip(
                label = addressTypeLabel(context, address.type),
                options = ADDR_TYPES.map { it to addressTypeLabel(context, it) },
                onSelect = onTypeChange,
            )
        },
        onRemove = onRemove,
    )
}

@Composable
private fun WebsiteEditorRow(
    website: EditableWebsite,
    onUrlChange: (String) -> Unit,
    onTypeChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    FieldRow(
        primaryField = {
            OutlinedTextField(
                value = website.url,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.editor_field_website)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        typeChip = {
            TypeChip(
                label = websiteTypeLabel(website.type),
                options = WEB_TYPES.map { it to websiteTypeLabel(it) },
                onSelect = onTypeChange,
            )
        },
        onRemove = onRemove,
    )
}

@Composable
private fun EventEditorRow(
    event: EditableEvent,
    onDateChange: (String) -> Unit,
    onTypeChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    FieldRow(
        primaryField = {
            OutlinedTextField(
                value = event.date,
                onValueChange = onDateChange,
                label = { Text(stringResource(R.string.editor_field_event_date)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        typeChip = {
            TypeChip(
                label = eventTypeLabel(context, event.type),
                options = EVENT_TYPES.map { it to eventTypeLabel(context, it) },
                onSelect = onTypeChange,
            )
        },
        onRemove = onRemove,
    )
}

@Composable
private fun FieldRow(
    primaryField: @Composable () -> Unit,
    typeChip: @Composable () -> Unit,
    onRemove: () -> Unit,
) {
    val removeLabel = stringResource(R.string.editor_remove)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            primaryField()
            typeChip()
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.semantics { contentDescription = removeLabel },
        ) {
            Icon(Icons.Filled.Close, contentDescription = null)
        }
    }
}

@Composable
private fun TypeChip(
    label: String,
    options: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { expanded = true }, label = { Text(label) })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun phoneTypeLabel(context: Context, type: Int): String =
    ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, type, "").toString()

private fun emailTypeLabel(context: Context, type: Int): String =
    ContactsContract.CommonDataKinds.Email.getTypeLabel(context.resources, type, "").toString()

private fun addressTypeLabel(context: Context, type: Int): String =
    ContactsContract.CommonDataKinds.StructuredPostal.getTypeLabel(context.resources, type, "").toString()

private fun eventTypeLabel(context: Context, type: Int): String {
    return when (type) {
        ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY ->
            context.getString(R.string.event_birthday)
        ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY ->
            context.getString(R.string.event_anniversary)
        else -> context.getString(R.string.event_other)
    }
}

private fun websiteTypeLabel(type: Int): String = when (type) {
    ContactsContract.CommonDataKinds.Website.TYPE_HOMEPAGE -> "Homepage"
    ContactsContract.CommonDataKinds.Website.TYPE_BLOG -> "Blog"
    ContactsContract.CommonDataKinds.Website.TYPE_PROFILE -> "Profile"
    ContactsContract.CommonDataKinds.Website.TYPE_HOME -> "Home"
    ContactsContract.CommonDataKinds.Website.TYPE_WORK -> "Work"
    ContactsContract.CommonDataKinds.Website.TYPE_FTP -> "FTP"
    else -> "Website"
}

/* ---------------- IO ---------------- */

private data class LoadedForEdit(
    val displayName: String,
    val given: String,
    val middle: String,
    val family: String,
    val prefix: String,
    val suffix: String,
    val company: String,
    val title: String,
    val nickname: String,
    val note: String,
    val phones: List<Pair<String, Int>>,
    val emails: List<Pair<String, Int>>,
    val addresses: List<Pair<String, Int>>,
    val websites: List<Pair<String, Int>>,
    val events: List<Pair<String, Int>>,
    /**
     * Account that currently owns this contact, encoded as
     * `"<type>|<name>"` with the literal string `"null"` for missing parts.
     * If the contact has multiple raw rows in different accounts, this is the
     * first one encountered — the editor renders the value read-only when
     * editing so the user knows where the data lives but doesn't try to
     * migrate it mid-edit (that flow lives in Settings → Storage locations).
     */
    val accountKey: String,
)

private fun loadForEdit(context: Context, contactId: Long): LoadedForEdit {
    val cr = context.contentResolver
    var displayName = ""
    cr.query(
        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
        arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
        null, null, null,
    )?.use { c -> if (c.moveToFirst()) displayName = c.getString(0).orEmpty() }

    var given = ""; var middle = ""; var family = ""
    var prefix = ""; var suffix = ""
    var company = ""; var title = ""
    var nickname = ""; var note = ""
    val phones = mutableListOf<Pair<String, Int>>()
    val emails = mutableListOf<Pair<String, Int>>()
    val addresses = mutableListOf<Pair<String, Int>>()
    val websites = mutableListOf<Pair<String, Int>>()
    val events = mutableListOf<Pair<String, Int>>()

    cr.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
        ),
        "${ContactsContract.Data.CONTACT_ID}=?",
        arrayOf(contactId.toString()),
        null,
    )?.use { c ->
        while (c.moveToNext()) {
            val mime = c.getString(0)
            val d1 = c.getString(1).orEmpty()
            val d2int = if (c.isNull(2)) 0 else c.getInt(2)
            when (mime) {
                MIMETYPE_NAME -> {
                    // StructuredName columns: DATA2=given DATA3=family DATA4=prefix
                    //                         DATA5=middle DATA6=suffix
                    given = c.getString(2).orEmpty()
                    family = c.getString(3).orEmpty()
                    prefix = c.getString(4).orEmpty()
                    middle = c.getString(5).orEmpty()
                    suffix = c.getString(6).orEmpty()
                }
                MIMETYPE_PHONE -> if (d1.isNotBlank()) phones += d1 to d2int
                MIMETYPE_EMAIL -> if (d1.isNotBlank()) emails += d1 to d2int
                MIMETYPE_ADDR -> if (d1.isNotBlank()) addresses += d1 to d2int
                MIMETYPE_WEB -> if (d1.isNotBlank()) websites += d1 to d2int
                MIMETYPE_EVENT -> if (d1.isNotBlank()) events += d1 to d2int
                MIMETYPE_NICK -> if (d1.isNotBlank()) nickname = d1
                MIMETYPE_ORG -> {
                    if (d1.isNotBlank()) company = d1
                    val t = c.getString(4).orEmpty() // Organization.TITLE = DATA4
                    if (t.isNotBlank()) title = t
                }
                MIMETYPE_NOTE -> if (d1.isNotBlank()) note = d1
            }
        }
    }
    return LoadedForEdit(
        displayName, given, middle, family, prefix, suffix,
        company, title, nickname, note,
        phones, emails, addresses, websites, events,
        accountKey = loadFirstAccountKey(cr, contactId),
    )
}

/**
 * Returns the account key (`"<type>|<name>"`) of the first non-deleted
 * RawContact under [contactId]. Falls back to [com.accessible.dialer.util.ContactAccounts.LOCAL_KEY]
 * when the contact has no raw row (shouldn't happen for an aggregated contact
 * loaded from the UI, but we guard anyway).
 */
private fun loadFirstAccountKey(
    cr: android.content.ContentResolver,
    contactId: Long,
): String {
    cr.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.ACCOUNT_NAME,
        ),
        "${ContactsContract.RawContacts.CONTACT_ID}=? AND " +
            "${ContactsContract.RawContacts.DELETED}=0",
        arrayOf(contactId.toString()),
        null,
    )?.use { c ->
        if (c.moveToFirst()) {
            return "${c.getString(0) ?: "null"}|${c.getString(1) ?: "null"}"
        }
    }
    return com.accessible.dialer.util.ContactAccounts.LOCAL_KEY
}

private fun saveContact(
    context: Context,
    existingContactId: Long,
    displayName: String,
    given: String,
    middle: String,
    family: String,
    prefix: String,
    suffix: String,
    organization: String,
    jobTitle: String,
    nickname: String,
    note: String,
    phones: List<EditablePhone>,
    emails: List<EditableEmail>,
    addresses: List<EditableAddress>,
    websites: List<EditableWebsite>,
    events: List<EditableEvent>,
    /** Account to write to when creating a new contact. Ignored for edits. */
    accountKey: String,
): Long? {
    val cr = context.contentResolver
    val ops = arrayListOf<ContentProviderOperation>()
    val cleanedPhones = phones.filter { it.number.isNotBlank() }
    val cleanedEmails = emails.filter { it.address.isNotBlank() }
    val cleanedAddresses = addresses.filter { it.formatted.isNotBlank() }
    val cleanedWebsites = websites.filter { it.url.isNotBlank() }
    val cleanedEvents = events.filter { it.date.isNotBlank() }

    // Builds Data inserts. backReference=true links to a newly-inserted RawContact at
    // index 0; false sets RAW_CONTACT_ID directly from an existing raw contact id.
    fun addDataInserts(rawContactId: Long?, backReference: Boolean) {
        fun newInsert(): ContentProviderOperation.Builder {
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            if (backReference) {
                b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            } else {
                b.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            }
            return b
        }
        if (displayName.isNotBlank() || given.isNotBlank() || family.isNotBlank() ||
            middle.isNotBlank() || prefix.isNotBlank() || suffix.isNotBlank()
        ) {
            val b = newInsert().withValue(ContactsContract.Data.MIMETYPE, MIMETYPE_NAME)
            if (displayName.isNotBlank())
                b.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
            if (given.isNotBlank())
                b.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, given)
            if (middle.isNotBlank())
                b.withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, middle)
            if (family.isNotBlank())
                b.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, family)
            if (prefix.isNotBlank())
                b.withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, prefix)
            if (suffix.isNotBlank())
                b.withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, suffix)
            ops += b.build()
        }
        cleanedPhones.forEach { p ->
            ops += newInsert()
                .withValue(ContactsContract.Data.MIMETYPE, MIMETYPE_PHONE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, p.number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, p.type)
                .build()
        }
        cleanedEmails.forEach { e ->
            ops += newInsert()
                .withValue(ContactsContract.Data.MIMETYPE, MIMETYPE_EMAIL)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, e.address)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, e.type)
                .build()
        }
        cleanedAddresses.forEach { a ->
            ops += newInsert()
                .withValue(ContactsContract.Data.MIMETYPE, MIMETYPE_ADDR)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, a.formatted)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, a.type)
                .build()
        }
        cleanedWebsites.forEach { w ->
            ops += newInsert()
                .withValue(ContactsContract.Data.MIMETYPE, MIMETYPE_WEB)
                .withValue(ContactsContract.CommonDataKinds.Website.URL, w.url)
                .withValue(ContactsContract.CommonDataKinds.Website.TYPE, w.type)
                .build()
        }
        cleanedEvents.forEach { ev ->
            ops += newInsert()
                .withValue(ContactsContract.Data.MIMETYPE, MIMETYPE_EVENT)
                .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, ev.date)
                .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ev.type)
                .build()
        }
        if (nickname.isNotBlank()) {
            ops += newInsert()
                .withValue(ContactsContract.Data.MIMETYPE, MIMETYPE_NICK)
                .withValue(ContactsContract.CommonDataKinds.Nickname.NAME, nickname)
                .build()
        }
        if (organization.isNotBlank() || jobTitle.isNotBlank()) {
            val b = newInsert().withValue(ContactsContract.Data.MIMETYPE, MIMETYPE_ORG)
            if (organization.isNotBlank())
                b.withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, organization)
            if (jobTitle.isNotBlank())
                b.withValue(ContactsContract.CommonDataKinds.Organization.TITLE, jobTitle)
            ops += b.build()
        }
        if (note.isNotBlank()) {
            ops += newInsert()
                .withValue(ContactsContract.Data.MIMETYPE, MIMETYPE_NOTE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
                .build()
        }
    }

    return runCatching {
        if (existingContactId <= 0L) {
            // Resolve the user's chosen account into the (possibly null)
            // ACCOUNT_TYPE / ACCOUNT_NAME pair the ContentProvider expects.
            // For "Local / Phone only" both are null; for cloud accounts both
            // are non-null; partial keys (one null) are handled too because
            // the AccountManager occasionally returns them.
            val parsed = com.accessible.dialer.util.ContactAccounts.parse(accountKey)
            ops += ContentProviderOperation
                .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, parsed.type)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, parsed.name)
                .build()
            addDataInserts(null, backReference = true)
            val results = cr.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawId = ContentUris.parseId(results[0].uri ?: return@runCatching null)
            cr.query(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } else {
            val rawContactId = cr.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=?",
                arrayOf(existingContactId.toString()),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return@runCatching null

            MANAGED_MIMES.forEach { mime ->
                ops += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                        arrayOf(rawContactId.toString(), mime),
                    )
                    .build()
            }
            addDataInserts(rawContactId, backReference = false)
            cr.applyBatch(ContactsContract.AUTHORITY, ops)
            existingContactId
        }
    }.getOrNull()
}
