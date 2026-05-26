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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accessible.dialer.R
import com.accessible.dialer.util.ContactOps
import com.accessible.dialer.util.RowActions
import kotlinx.coroutines.Dispatchers
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
    val displayName = details?.name?.ifBlank { titleFallback } ?: titleFallback

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
                        Icon(Icons.Filled.ArrowBack, contentDescription = backLabel)
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

            if (recents.isNotEmpty()) {
                item("rc_h") { SectionHeader(stringResource(R.string.contact_details_section_recent_calls)) }
                items(recents, key = { "rc_" + it.id }) { rc ->
                    RecentCallRow(rc)
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
