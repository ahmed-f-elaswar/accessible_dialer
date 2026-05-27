package com.accessible.dialer.ui.contacts

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accessible.dialer.R
import com.accessible.dialer.util.ContactOps
import com.accessible.dialer.util.RowActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * In-app contact details view — the full-fat replacement for the system Contacts viewer.
 *
 * Sections rendered (in order; empty sections are skipped):
 *  - Header: large initials avatar, name, organization/title, starred indicator.
 *  - Phone numbers — tap to call; TalkBack actions for SMS / Copy / Show in keypad.
 *  - Email addresses — tap to compose; TalkBack action for Copy.
 *  - Postal addresses — tap to open in Maps.
 *  - Websites — tap to open in browser.
 *  - Important dates (birthday + anniversary + custom events).
 *  - Nickname.
 *  - Notes.
 *  - Recent calls with this contact (last 10 from the system call log, matched by the
 *    Android `MIN_MATCH` last-7-digits rule so country-code variants still line up).
 *  - Bottom action bar: Edit, Set ringtone, Pin to home screen, Share, Delete.
 *  - Top-bar Favorite toggle.
 *
 * Photo: the system contact photo is fetched in `loadDetails` as a `ContactPhoto` model
 * holding the high-res URI; we render via Coil-less `rememberAsyncImagePainter` would
 * pull in another dep, so for keeping the dep graph small we render an initials avatar
 * (a circle with the contact's initials in primary tint). That's the same a11y story —
 * TalkBack just ignores the avatar (decoration) and reads the name from the title.
 */
internal data class ContactDetails(
    val id: Long,
    val name: String,
    val starred: Boolean,
    val organization: String,
    val phones: List<PhoneItem>,
    val emails: List<EmailItem>,
    val addresses: List<AddressItem>,
    val websites: List<WebsiteItem>,
    val events: List<EventItem>,
    val nickname: String,
    val note: String,
    val storageLabels: List<String>,
)

internal data class PhoneItem(val number: String, val typeLabel: String)
internal data class EmailItem(val address: String, val typeLabel: String)
internal data class AddressItem(val formatted: String, val typeLabel: String)
internal data class WebsiteItem(val url: String, val typeLabel: String)
internal data class EventItem(val date: String, val typeLabel: String)
internal data class RecentCallItem(val id: Long, val type: Int, val date: Long, val relative: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactDetailsScreen(
    contactId: Long,
    onBack: () -> Unit,
    onCallNumber: (String) -> Unit,
    onShowInDialpad: (String) -> Unit,
    onEdit: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    var reloadKey by remember { mutableStateOf(0) }
    var details by remember(contactId) { mutableStateOf<ContactDetails?>(null) }
    var recents by remember(contactId) { mutableStateOf<List<RecentCallItem>>(emptyList()) }

    LaunchedEffect(contactId, reloadKey) {
        val pair = withContext(Dispatchers.IO) {
            val d = loadDetails(context, contactId)
            // Match recent calls by last 7 digits — the same MIN_MATCH heuristic the
            // recents list uses for deduplication, so a call shown here is the same call
            // the user sees in the Recents tab.
            val numbers = d?.phones?.map { it.number }.orEmpty()
            val rc = if (numbers.isNotEmpty()) loadRecentCalls(context, numbers) else emptyList()
            d to rc
        }
        details = pair.first
        recents = pair.second
    }

    val backLabel = stringResource(R.string.action_back)
    val editLabel = stringResource(R.string.action_edit_contact)
    val shareLabel = stringResource(R.string.action_share_contact)
    val deleteLabel = stringResource(R.string.action_delete_contact)
    val starLabel = stringResource(R.string.action_add_favorite)
    val unstarLabel = stringResource(R.string.action_remove_favorite)
    val ringtoneLabel = stringResource(R.string.action_set_ringtone)
    val pinLabel = stringResource(R.string.action_pin_home)
    val titleFallback = stringResource(R.string.contact_details_title)

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    // Recent calls render under a collapsible "History" header. Default collapsed —
    // most users will be opening the screen for contact info, not the call log, and a
    // collapsed default keeps the actions reachable without scrolling on small screens.
    var historyExpanded by remember { mutableStateOf(false) }
    // In-app picker for choosing the second contact to merge with.
    var showMergePicker by remember { mutableStateOf(false) }
    var pendingMergeTarget by remember { mutableStateOf<Contact?>(null) }
    // Secondary actions (Set ringtone, Pin to home screen, Share) are hidden behind a
    // single "More actions" row to keep the bottom action list short and easier to
    // scan. Tapping More expands them inline.
    var moreExpanded by remember { mutableStateOf(false) }
    val displayName = details?.name?.ifBlank { titleFallback } ?: titleFallback

    // Confirmation + overlays for the new "Erase history", "QR code" and the SMS /
    // WhatsApp number pickers when the contact has more than one phone.
    var showEraseHistoryConfirm by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    // Which inline number picker is active. "sms" / "whatsapp" / null.
    var numberPickerAction by remember { mutableStateOf<String?>(null) }
    // SEND_TO_VOICEMAIL flag tracked locally so the row label flips immediately.
    var sendToVoicemail by remember(contactId) { mutableStateOf(false) }
    LaunchedEffect(contactId, reloadKey) {
        sendToVoicemail = withContext(Dispatchers.IO) {
            ContactOps.isSendToVoicemail(context, contactId)
        }
    }
    val scope = rememberCoroutineScope()

    if (showShareSheet) {
        AlertDialog(
            onDismissRequest = { showShareSheet = false },
            title = { Text(stringResource(R.string.share_dialog_title)) },
            text = { Text(stringResource(R.string.share_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showShareSheet = false
                    ContactOps.shareContactAsText(context, contactId)
                }) { Text(stringResource(R.string.share_as_text)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showShareSheet = false
                    ContactOps.shareContact(context, contactId)
                }) { Text(stringResource(R.string.share_as_file)) }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_contact_title)) },
            text = { Text(stringResource(R.string.delete_contact_message, displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    if (ContactOps.deleteContact(context, contactId)) onBack()
                }) { Text(stringResource(R.string.delete_contact_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showMergePicker) {
        MergeContactPickerDialog(
            currentContactId = contactId,
            onDismiss = { showMergePicker = false },
            onPick = { picked ->
                showMergePicker = false
                pendingMergeTarget = picked
            },
        )
    }

    pendingMergeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingMergeTarget = null },
            title = { Text(stringResource(R.string.merge_contact_title)) },
            text = {
                Text(stringResource(R.string.merge_contact_message, displayName, target.name.ifBlank { target.number }))
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetId = target.id
                    pendingMergeTarget = null
                    scope.launch {
                        // mergeContacts hits the ContactsProvider with applyBatch on the
                        // calling thread; punt to IO so we don't block the frame.
                        val ok = withContext(Dispatchers.IO) {
                            mergeContacts(context, listOf(contactId, targetId))
                        }
                        // After merging, the system may reassign the aggregated contact id;
                        // back out of the details screen so the caller refreshes its list.
                        if (ok) onBack()
                    }
                }) { Text(stringResource(R.string.merge_contact_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMergeTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showEraseHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showEraseHistoryConfirm = false },
            title = { Text(stringResource(R.string.erase_history_title)) },
            text = { Text(stringResource(R.string.erase_history_message, displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    showEraseHistoryConfirm = false
                    val numbers = details?.phones?.map { it.number }.orEmpty()
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            ContactOps.eraseCallHistory(context, numbers)
                        }
                        val msgRes =
                            if (deleted > 0) R.string.erase_history_done
                            else R.string.erase_history_none
                        android.widget.Toast.makeText(
                            context,
                            if (deleted > 0) context.getString(msgRes, deleted)
                            else context.getString(msgRes),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        // Refresh the recents list under the History header.
                        reloadKey++
                    }
                }) { Text(stringResource(R.string.erase_history_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showEraseHistoryConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showQrDialog) {
        QrCodeDialog(
            title = stringResource(R.string.qr_dialog_title, displayName),
            payload = remember(contactId, reloadKey) {
                ContactOps.buildVCard(context, contactId)
            },
            onClose = { showQrDialog = false },
        )
    }

    numberPickerAction?.let { action ->
        val phones = details?.phones.orEmpty()
        NumberPickerDialog(
            phones = phones,
            onDismiss = { numberPickerAction = null },
            onPick = { number ->
                numberPickerAction = null
                when (action) {
                    "sms" -> ContactOps.sendSms(context, number)
                    "whatsapp" -> ContactOps.openWhatsApp(context, number)
                }
            },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                    }
                },
                actions = {
                    val isStarred = details?.starred == true
                    IconButton(
                        onClick = {
                            ContactOps.toggleFavorite(context, contactId, isStarred)
                            reloadKey += 1
                        },
                        modifier = Modifier.semantics {
                            contentDescription = if (isStarred) unstarLabel else starLabel
                        },
                    ) {
                        Icon(
                            imageVector = if (isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { inner ->
        val d = details
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.contact_details_loading))
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(inner)) {
            item("header") {
                ContactHeader(name = displayName, organization = d.organization)
                HorizontalDivider()
            }

            if (d.phones.isEmpty() && d.emails.isEmpty() && d.addresses.isEmpty() &&
                d.websites.isEmpty() && d.events.isEmpty() && d.nickname.isBlank() && d.note.isBlank()
            ) {
                item("empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.contact_details_no_data),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (d.phones.isNotEmpty()) {
                item("ph_h") { SectionHeader(stringResource(R.string.contact_details_section_phones)) }
                items(d.phones, key = { "ph_" + it.number + it.typeLabel }) { phone ->
                    PhoneRow(
                        phone = phone,
                        onCall = { onCallNumber(phone.number) },
                        onShowInDialpad = { onShowInDialpad(phone.number) },
                    )
                    HorizontalDivider()
                }
            }

            if (d.emails.isNotEmpty()) {
                item("em_h") { SectionHeader(stringResource(R.string.contact_details_section_emails)) }
                items(d.emails, key = { "em_" + it.address + it.typeLabel }) { email ->
                    EmailRow(email = email)
                    HorizontalDivider()
                }
            }

            if (d.addresses.isNotEmpty()) {
                item("ad_h") { SectionHeader(stringResource(R.string.contact_details_section_addresses)) }
                items(d.addresses, key = { "ad_" + it.formatted + it.typeLabel }) { addr ->
                    AddressRow(addr)
                    HorizontalDivider()
                }
            }

            if (d.websites.isNotEmpty()) {
                item("ws_h") { SectionHeader(stringResource(R.string.contact_details_section_websites)) }
                items(d.websites, key = { "ws_" + it.url + it.typeLabel }) { web ->
                    WebsiteRow(web)
                    HorizontalDivider()
                }
            }

            if (d.events.isNotEmpty()) {
                item("ev_h") { SectionHeader(stringResource(R.string.contact_details_section_events)) }
                items(d.events, key = { "ev_" + it.date + it.typeLabel }) { ev ->
                    EventRow(ev)
                    HorizontalDivider()
                }
            }

            if (d.nickname.isNotBlank()) {
                item("nick") {
                    DetailRow(
                        leadingIcon = Icons.Filled.Person,
                        primary = d.nickname,
                        secondary = stringResource(R.string.contact_details_nickname),
                        onTap = null,
                        tapLabel = null,
                    )
                    HorizontalDivider()
                }
            }

            if (d.note.isNotBlank()) {
                item("note_h") { SectionHeader(stringResource(R.string.contact_details_section_notes)) }
                item("note") {
                    DetailRow(
                        leadingIcon = Icons.Filled.Notes,
                        primary = d.note,
                        secondary = "",
                        onTap = null,
                        tapLabel = null,
                    )
                    HorizontalDivider()
                }
            }

            // Always show the History section when this contact has phone numbers,
            // even if the call log is empty \u2014 otherwise users can't tell the section
            // exists. When empty we render a single placeholder row instead of items.
            if (d.phones.isNotEmpty()) {
                item("rc_h") {
                    CollapsibleSectionHeader(
                        text = stringResource(R.string.contact_details_section_history),
                        expanded = historyExpanded,
                        onToggle = { historyExpanded = !historyExpanded },
                    )
                }
                if (historyExpanded) {
                    if (recents.isEmpty()) {
                        item("rc_empty") {
                            Text(
                                text = stringResource(R.string.contact_details_history_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                            HorizontalDivider()
                        }
                    } else {
                        items(recents, key = { "rc_" + it.id }) { rc ->
                            RecentCallRow(rc)
                            HorizontalDivider()
                        }
                    }
                }
            }

            if (d.storageLabels.isNotEmpty()) {
                item("st_h") { SectionHeader(stringResource(R.string.contact_details_section_storage)) }
                items(d.storageLabels, key = { "st_" + it }) { label ->
                    DetailRow(
                        leadingIcon = Icons.Filled.Notes,
                        primary = label,
                        secondary = "",
                        onTap = null,
                        tapLabel = null,
                    )
                    HorizontalDivider()
                }
            }

            item("bottom_actions") {
                Spacer(Modifier.size(8.dp))
                SectionHeader(stringResource(R.string.contact_details_section_actions))
                // Stacked full-width rows instead of a cramped Row of TextButtons — with
                // five actions, the labels were truncating into ellipses on the device
                // ("Delete" became "De…"). Each row is a single TalkBack focusable with
                // its label as the click description.
                ActionRow(Icons.Filled.Person, stringResource(R.string.action_edit_contact)) {
                    onEdit(contactId)
                }
                HorizontalDivider()
                // Quick chat actions for the contact's primary number (or a picker if
                // there are multiple). Promoted out of the More group because users
                // reach for "Message" almost as often as "Call".
                if (d.phones.isNotEmpty()) {
                    ActionRow(Icons.Filled.Sms, stringResource(R.string.action_send_message)) {
                        if (d.phones.size == 1) ContactOps.sendSms(context, d.phones.first().number)
                        else numberPickerAction = "sms"
                    }
                    HorizontalDivider()
                    ActionRow(Icons.Filled.Chat, stringResource(R.string.action_whatsapp)) {
                        if (d.phones.size == 1) ContactOps.openWhatsApp(context, d.phones.first().number)
                        else numberPickerAction = "whatsapp"
                    }
                    HorizontalDivider()
                }
                ActionRow(
                    icon = Icons.Filled.MoreHoriz,
                    label = stringResource(
                        if (moreExpanded) R.string.action_more_collapse
                        else R.string.action_more_expand
                    ),
                ) { moreExpanded = !moreExpanded }
                HorizontalDivider()
                if (moreExpanded) {
                    ActionRow(Icons.Filled.MusicNote, stringResource(R.string.action_set_ringtone)) {
                        ContactOps.setRingtone(context, contactId)
                    }
                    HorizontalDivider()
                    ActionRow(Icons.Filled.PushPin, stringResource(R.string.action_pin_home)) {
                        pinContactShortcut(context, contactId, displayName)
                    }
                    HorizontalDivider()
                    ActionRow(Icons.Filled.Email, stringResource(R.string.action_share_contact)) {
                        showShareSheet = true
                    }
                    HorizontalDivider()
                    ActionRow(Icons.Filled.QrCode2, stringResource(R.string.action_show_qr)) {
                        showQrDialog = true
                    }
                    HorizontalDivider()
                    // Voicemail toggle: flips ContactsContract.Contacts.SEND_TO_VOICEMAIL.
                    ActionRow(
                        icon = Icons.Filled.Voicemail,
                        label = stringResource(
                            if (sendToVoicemail) R.string.action_stop_send_to_voicemail
                            else R.string.action_send_to_voicemail
                        ),
                    ) {
                        val next = !sendToVoicemail
                        if (ContactOps.setSendToVoicemail(context, contactId, next)) {
                            sendToVoicemail = next
                            android.widget.Toast.makeText(
                                context,
                                context.getString(
                                    if (next) R.string.voicemail_enabled
                                    else R.string.voicemail_disabled
                                ),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    HorizontalDivider()
                    if (d.phones.isNotEmpty()) {
                        ActionRow(
                            icon = Icons.Filled.DeleteSweep,
                            label = stringResource(R.string.action_erase_history),
                            destructive = true,
                        ) { showEraseHistoryConfirm = true }
                        HorizontalDivider()
                    }
                }
                if (d.phones.isNotEmpty()) {
                    val anyBlocked = d.phones.any {
                        com.accessible.dialer.blocking.BlockedNumbersRepository
                            .isBlocked(context, it.number)
                    }
                    ActionRow(
                        icon = Icons.Filled.Notes,
                        label = stringResource(
                            if (anyBlocked) R.string.action_unblock_number
                            else R.string.action_block_number
                        ),
                    ) {
                        d.phones.forEach { p ->
                            if (anyBlocked) ContactOps.unblockNumber(context, p.number)
                            else ContactOps.blockNumber(context, p.number)
                        }
                    }
                    HorizontalDivider()
                }
                ActionRow(
                    icon = Icons.Filled.MergeType,
                    label = stringResource(R.string.action_merge_contact),
                ) { showMergePicker = true }
                HorizontalDivider()
                ActionRow(
                    icon = Icons.Filled.Notes,
                    label = stringResource(R.string.action_delete_contact),
                    destructive = true,
                ) { showDeleteConfirm = true }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ContactHeader(name: String, organization: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            // Group the avatar + name + organization into one TalkBack announcement so
            // the screen reader doesn't read the decorative initials twice.
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InitialsAvatar(name = name)
        Spacer(Modifier.size(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (organization.isNotBlank()) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = organization,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InitialsAvatar(name: String) {
    val initials = remember(name) {
        name.split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
    }
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 36.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Section header that doubles as a toggle. The whole row is a single TalkBack focusable
 * with a Button role so screen-reader users get the expand/collapse semantics for free.
 */
@Composable
private fun CollapsibleSectionHeader(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val expandLabel = stringResource(R.string.action_more_expand)
    val collapseLabel = stringResource(R.string.action_more_collapse)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = "$text, ${if (expanded) collapseLabel else expandLabel}"
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * In-app contact picker for the "Merge with another contact" action. Reuses
 * [ContactsViewModel] so the visible list matches the Contacts tab (same account filter,
 * same sort order). The current contact is excluded so it can't be picked as its own
 * merge target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeContactPickerDialog(
    currentContactId: Long,
    onDismiss: () -> Unit,
    onPick: (Contact) -> Unit,
) {
    val context = LocalContext.current
    val vm: ContactsViewModel = viewModel(key = "merge_picker_$currentContactId")
    LaunchedEffect(Unit) { vm.load(context) }
    val displayed by vm.displayed.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val filtered = remember(displayed, query, currentContactId) {
        val q = query.trim()
        displayed.asSequence()
            .filter { it.id != currentContactId }
            .filter { c ->
                q.isEmpty() ||
                    c.name.contains(q, ignoreCase = true) ||
                    c.number.contains(q)
            }
            .toList()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.merge_picker_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.contacts_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.contacts_empty))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { c ->
                            MergePickerRow(c, onPick = { onPick(c) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MergePickerRow(contact: Contact, onPick: () -> Unit) {
    val display = contact.name.ifBlank { contact.number }
    val tapLabel = stringResource(R.string.merge_pick_contact, display)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onPick)
            .semantics { contentDescription = tapLabel }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(display, style = MaterialTheme.typography.bodyLarge)
            if (contact.name.isNotBlank() && contact.number.isNotBlank()) {
                Text(
                    contact.number,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Lightweight number picker shown when the user taps Message / WhatsApp on a contact
 * with more than one phone number. Each row is a single tap target so TalkBack just
 * announces the number and type.
 */
@Composable
private fun NumberPickerDialog(
    phones: List<PhoneItem>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_pick_number)) },
        text = {
            Column {
                phones.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onPick(p.number) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.number, style = MaterialTheme.typography.bodyLarge)
                            if (p.typeLabel.isNotBlank()) {
                                Text(
                                    p.typeLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Full-screen QR code overlay. We render the contact as a vCard 3.0 payload using
 * ZXing's [com.google.zxing.qrcode.QRCodeWriter]; the resulting BitMatrix is converted
 * to a Bitmap on first composition and cached for the lifetime of the dialog.
 */
@Composable
private fun QrCodeDialog(title: String, payload: String?, onClose: () -> Unit) {
    val closeLabel = stringResource(R.string.qr_dialog_close)
    val bitmap = remember(payload) { payload?.let { qrToBitmap(it, 800) } }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics {
                            contentDescription = closeLabel
                            role = Role.Button
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                }
                Spacer(Modifier.size(16.dp))
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(320.dp),
                    )
                    Spacer(Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.qr_dialog_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.qr_dialog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Encodes [text] as a QR code BitMatrix and converts to a square ARGB Bitmap. Returns
 * null if encoding fails (e.g. payload too large for the symbol).
 */
private fun qrToBitmap(text: String, sizePx: Int): android.graphics.Bitmap? = runCatching {
    val writer = com.google.zxing.qrcode.QRCodeWriter()
    val matrix = writer.encode(text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    val black = android.graphics.Color.BLACK
    val white = android.graphics.Color.WHITE
    for (y in 0 until h) {
        val rowOffset = y * w
        for (x in 0 until w) {
            pixels[rowOffset + x] = if (matrix.get(x, y)) black else white
        }
    }
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    bmp
}.getOrNull()

@Composable
private fun PhoneRow(
    phone: PhoneItem,
    onCall: () -> Unit,
    onShowInDialpad: () -> Unit,
) {
    val context = LocalContext.current
    val callLabel = stringResource(R.string.contacts_call, phone.number)
    val sendMessageLabel = stringResource(R.string.action_send_message)
    val copyNumberLabel = stringResource(R.string.action_copy_number)
    val showInKeypadLabel = stringResource(R.string.contacts_show_in_keypad, phone.number)
    val actions = listOf(
        CustomAccessibilityAction(sendMessageLabel) {
            RowActions.sendSms(context, phone.number); true
        },
        CustomAccessibilityAction(copyNumberLabel) {
            RowActions.copyNumber(context, phone.number); true
        },
        CustomAccessibilityAction(showInKeypadLabel) {
            onShowInDialpad(); true
        },
    )
    DetailRow(
        leadingIcon = Icons.Filled.Call,
        primary = phone.number,
        secondary = phone.typeLabel,
        onTap = onCall,
        tapLabel = callLabel,
        accessibilityActions = actions,
    )
}

@Composable
private fun EmailRow(email: EmailItem) {
    val context = LocalContext.current
    val sendEmailLabel = stringResource(R.string.action_send_email)
    val copyLabel = stringResource(R.string.action_copy_email)
    val actions = listOf(
        CustomAccessibilityAction(copyLabel) {
            RowActions.copyNumber(context, email.address); true
        },
    )
    DetailRow(
        leadingIcon = Icons.Filled.Email,
        primary = email.address,
        secondary = email.typeLabel,
        onTap = { sendEmail(context, email.address) },
        tapLabel = sendEmailLabel,
        accessibilityActions = actions,
    )
}

@Composable
private fun AddressRow(addr: AddressItem) {
    val context = LocalContext.current
    val openLabel = stringResource(R.string.action_open_map)
    DetailRow(
        leadingIcon = Icons.Filled.LocationOn,
        primary = addr.formatted,
        secondary = addr.typeLabel,
        onTap = { openMap(context, addr.formatted) },
        tapLabel = openLabel,
    )
}

@Composable
private fun WebsiteRow(web: WebsiteItem) {
    val context = LocalContext.current
    val openLabel = stringResource(R.string.action_open_website)
    DetailRow(
        leadingIcon = Icons.Filled.Public,
        primary = web.url,
        secondary = web.typeLabel,
        onTap = { openWebsite(context, web.url) },
        tapLabel = openLabel,
    )
}

@Composable
private fun EventRow(ev: EventItem) {
    DetailRow(
        leadingIcon = if (ev.typeLabel.equals("Birthday", ignoreCase = true)) {
            Icons.Filled.Cake
        } else Icons.Filled.Event,
        primary = ev.date,
        secondary = ev.typeLabel,
        onTap = null,
        tapLabel = null,
    )
}

@Composable
private fun RecentCallRow(rc: RecentCallItem) {
    val typeLabel = when (rc.type) {
        CallLog.Calls.INCOMING_TYPE -> stringResource(R.string.recents_incoming)
        CallLog.Calls.OUTGOING_TYPE -> stringResource(R.string.recents_outgoing)
        CallLog.Calls.MISSED_TYPE -> stringResource(R.string.recents_missed)
        CallLog.Calls.REJECTED_TYPE -> stringResource(R.string.recents_rejected)
        else -> stringResource(R.string.recents_call_generic)
    }
    DetailRow(
        leadingIcon = Icons.Filled.History,
        primary = "$typeLabel · ${rc.relative}",
        secondary = "",
        onTap = null,
        tapLabel = null,
    )
}

@Composable
private fun DetailRow(
    leadingIcon: ImageVector,
    primary: String,
    secondary: String,
    onTap: (() -> Unit)?,
    tapLabel: String?,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
) {
    val baseModifier = Modifier
        .fillMaxWidth()
        .let {
            if (onTap != null && tapLabel != null) {
                it.clickable(onClick = onTap, onClickLabel = tapLabel, role = Role.Button)
            } else it
        }
        .padding(horizontal = 16.dp, vertical = 14.dp)
        .semantics(mergeDescendants = true) {
            if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
        }
    Row(modifier = baseModifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (secondary.isNotBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BottomAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text(label)
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, onClickLabel = label, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (destructive) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.primary
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Spacer(Modifier.size(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onBackground,
        )
    }
}

private fun sendEmail(context: Context, address: String) {
    if (address.isBlank()) return
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:$address")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun openMap(context: Context, address: String) {
    if (address.isBlank()) return
    val q = Uri.encode(address)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun openWebsite(context: Context, url: String) {
    if (url.isBlank()) return
    // Some saved contact URLs omit the scheme ("example.com"); fall back to https.
    val normalized = if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
        url
    } else "https://$url"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * Ask the launcher to pin a shortcut that opens this contact's details view directly.
 * Uses the standard ACTION_VIEW + people content URI; MainActivity already declares it
 * can handle that intent. Falls back silently on launchers that don't support pinning
 * (older / non-stock launchers).
 */
private fun pinContactShortcut(context: Context, contactId: Long, displayName: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val sm = context.getSystemService(ShortcutManager::class.java) ?: return
    if (!sm.isRequestPinShortcutSupported) return
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        data = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        // Route the pinned shortcut back to our app so it opens the in-app details view.
        setPackage(context.packageName)
    }
    val shortcut = ShortcutInfo.Builder(context, "contact_$contactId")
        .setShortLabel(displayName.ifBlank { context.getString(R.string.contact_details_title) })
        .setIcon(AndroidIcon.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(viewIntent)
        .build()
    runCatching { sm.requestPinShortcut(shortcut, null) }
}

/* ---------------- IO ---------------- */

private fun loadDetails(context: Context, contactId: Long): ContactDetails? {
    if (contactId <= 0L) return null
    val cr = context.contentResolver
    val resources = context.resources

    var name = ""
    var starred = false
    cr.query(
        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
        arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.STARRED,
        ),
        null, null, null,
    )?.use { c ->
        if (c.moveToFirst()) {
            name = c.getString(0).orEmpty()
            starred = c.getInt(1) == 1
        }
    } ?: return null

    val phones = mutableListOf<PhoneItem>()
    cr.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        ),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
        arrayOf(contactId.toString()),
        null,
    )?.use { c ->
        while (c.moveToNext()) {
            val number = c.getString(0).orEmpty()
            if (number.isBlank()) continue
            val type = c.getInt(1)
            val customLabel = c.getString(2).orEmpty()
            val typeLabel = ContactsContract.CommonDataKinds.Phone
                .getTypeLabel(resources, type, customLabel).toString()
            phones += PhoneItem(number, typeLabel)
        }
    }

    val emails = mutableListOf<EmailItem>()
    cr.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.TYPE,
            ContactsContract.CommonDataKinds.Email.LABEL,
        ),
        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?",
        arrayOf(contactId.toString()),
        null,
    )?.use { c ->
        while (c.moveToNext()) {
            val address = c.getString(0).orEmpty()
            if (address.isBlank()) continue
            val type = c.getInt(1)
            val customLabel = c.getString(2).orEmpty()
            val typeLabel = ContactsContract.CommonDataKinds.Email
                .getTypeLabel(resources, type, customLabel).toString()
            emails += EmailItem(address, typeLabel)
        }
    }

    val addresses = mutableListOf<AddressItem>()
    cr.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.LABEL,
        ),
        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
        arrayOf(
            contactId.toString(),
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
        ),
        null,
    )?.use { c ->
        while (c.moveToNext()) {
            val formatted = c.getString(0).orEmpty()
            if (formatted.isBlank()) continue
            val type = c.getInt(1)
            val customLabel = c.getString(2).orEmpty()
            val typeLabel = ContactsContract.CommonDataKinds.StructuredPostal
                .getTypeLabel(resources, type, customLabel).toString()
            addresses += AddressItem(formatted, typeLabel)
        }
    }

    val websites = mutableListOf<WebsiteItem>()
    cr.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Website.URL,
            ContactsContract.CommonDataKinds.Website.TYPE,
            ContactsContract.CommonDataKinds.Website.LABEL,
        ),
        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
        arrayOf(
            contactId.toString(),
            ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
        ),
        null,
    )?.use { c ->
        while (c.moveToNext()) {
            val url = c.getString(0).orEmpty()
            if (url.isBlank()) continue
            val type = c.getInt(1)
            val customLabel = c.getString(2).orEmpty()
            // Website.getTypeLabel isn't a public static helper across all platform
            // versions; format manually using the small known set.
            val typeLabel = when (type) {
                ContactsContract.CommonDataKinds.Website.TYPE_HOMEPAGE ->
                    context.getString(R.string.website_homepage)
                ContactsContract.CommonDataKinds.Website.TYPE_BLOG ->
                    context.getString(R.string.website_blog)
                ContactsContract.CommonDataKinds.Website.TYPE_PROFILE ->
                    context.getString(R.string.website_profile)
                ContactsContract.CommonDataKinds.Website.TYPE_HOME ->
                    context.getString(R.string.website_home)
                ContactsContract.CommonDataKinds.Website.TYPE_WORK ->
                    context.getString(R.string.website_work)
                ContactsContract.CommonDataKinds.Website.TYPE_FTP ->
                    context.getString(R.string.website_ftp)
                ContactsContract.CommonDataKinds.Website.TYPE_CUSTOM -> customLabel
                else -> context.getString(R.string.website_other)
            }
            websites += WebsiteItem(url, typeLabel)
        }
    }

    val events = mutableListOf<EventItem>()
    cr.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Event.START_DATE,
            ContactsContract.CommonDataKinds.Event.TYPE,
            ContactsContract.CommonDataKinds.Event.LABEL,
        ),
        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
        arrayOf(
            contactId.toString(),
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
        ),
        null,
    )?.use { c ->
        while (c.moveToNext()) {
            val rawDate = c.getString(0).orEmpty()
            if (rawDate.isBlank()) continue
            val type = c.getInt(1)
            val customLabel = c.getString(2).orEmpty()
            val typeLabel = when (type) {
                ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY ->
                    context.getString(R.string.event_birthday)
                ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY ->
                    context.getString(R.string.event_anniversary)
                ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM -> customLabel
                else -> context.getString(R.string.event_other)
            }
            events += EventItem(rawDate, typeLabel)
        }
    }

    var nickname = ""
    cr.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Nickname.NAME),
        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
        arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE),
        null,
    )?.use { c ->
        if (c.moveToFirst()) nickname = c.getString(0).orEmpty()
    }

    var note = ""
    cr.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
        arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
        null,
    )?.use { c ->
        if (c.moveToFirst()) note = c.getString(0).orEmpty()
    }

    var organization = ""
    cr.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Organization.COMPANY,
            ContactsContract.CommonDataKinds.Organization.TITLE,
        ),
        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
        arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
        null,
    )?.use { c ->
        if (c.moveToFirst()) {
            val company = c.getString(0).orEmpty()
            val title = c.getString(1).orEmpty()
            organization = listOf(title, company).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    val storageKeys = linkedSetOf<String>()
    cr.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.ACCOUNT_NAME,
        ),
        "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
        arrayOf(contactId.toString()),
        null,
    )?.use { c ->
        while (c.moveToNext()) {
            val type = c.getString(0) ?: "null"
            val accountName = c.getString(1) ?: "null"
            storageKeys += "$type|$accountName"
        }
    }
    val storageLabels = storageKeys.map { friendlyAccountLabel(it) }

    return ContactDetails(
        id = contactId,
        name = name,
        starred = starred,
        organization = organization,
        phones = phones,
        emails = emails,
        addresses = addresses,
        websites = websites,
        events = events,
        nickname = nickname,
        note = note,
        storageLabels = storageLabels,
    )
}

/**
 * Pull the most recent 10 calls whose number matches any of [numbers] by the last 7
 * digits. Uses a single LIMIT 200 query and filters client-side because SQLite has no
 * portable "last-N-chars of formatted number" predicate.
 */
private fun loadRecentCalls(context: Context, numbers: List<String>): List<RecentCallItem> {
    if (numbers.isEmpty()) return emptyList()
    val suffixes = numbers.mapNotNull { n ->
        val digits = n.filter { it.isDigit() }
        if (digits.isEmpty()) null
        else if (digits.length >= 7) digits.takeLast(7) else digits
    }.toHashSet()
    if (suffixes.isEmpty()) return emptyList()

    val out = mutableListOf<RecentCallItem>()
    val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    context.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
        null, null,
        "${CallLog.Calls.DATE} DESC LIMIT 200",
    )?.use { c ->
        val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
        val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
        val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
        val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
        while (c.moveToNext() && out.size < 10) {
            val rowDigits = c.getString(numIdx).orEmpty().filter { it.isDigit() }
            val rowSuffix = if (rowDigits.length >= 7) rowDigits.takeLast(7) else rowDigits
            if (rowSuffix.isEmpty() || rowSuffix !in suffixes) continue
            // PhoneNumberUtils.compare is the canonical Android matcher; we only get here
            // when the cheap suffix check passes, so the extra check is a defensive
            // confirmation rather than a hot-path cost.
            @Suppress("DEPRECATION")
            val matches = numbers.any { PhoneNumberUtils.compare(it, c.getString(numIdx).orEmpty()) }
            if (!matches) continue
            val date = c.getLong(dateIdx)
            out += RecentCallItem(
                id = c.getLong(idIdx),
                type = c.getInt(typeIdx),
                date = date,
                relative = df.format(Date(date)),
            )
        }
    }
    return out
}
