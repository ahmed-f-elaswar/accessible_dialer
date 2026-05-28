package com.accessible.dialer.ui.contacts

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accessible.dialer.R
import com.accessible.dialer.util.ContactOps
import com.accessible.dialer.voice.VoiceSearchSheet
import com.accessible.dialer.util.DialerPermissions
import com.accessible.dialer.util.RowActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Contacts directory grouped alphabetically by the first letter of the display name.
 *
 * Accessibility:
 *  - Each contact row is a single TalkBack focusable. The tap gesture carries an explicit
 *    label so the reader announces "double-tap to open John Doe's details".
 *  - All non-tap actions live in the TalkBack custom-action menu (Call, Send message,
 *    Copy number, Show in keypad, Edit, Delete, Share, Block, Set ringtone, Favorite).
 *  - Section headers are buttons with "Expand"/"Collapse" labels and a spoken summary.
 */
@Composable
fun ContactsScreen(
    permissionsGranted: Boolean,
    onCallNumber: (String) -> Unit,
    onShowInDialpad: (String) -> Unit,
    onOpenDetails: (Long) -> Unit = {},
    onNewContact: () -> Unit = {},
    onEditContact: (Long) -> Unit = {},
    reloadKey: Int = 0,
    listState: LazyListState = rememberLazyListState(),
    /**
     * If non-null, after the list (re)composes the row matching this contact id
     * should claim TalkBack / input focus and the list should scroll to bring it
     * into view. Used to restore the "where you were" anchor when the user
     * returns from the contact details screen. The screen invokes
     * [onFocusConsumed] once focus has been requested so a stale value doesn't
     * keep stealing focus across unrelated recompositions.
     */
    focusTargetId: Long? = null,
    onFocusConsumed: () -> Unit = {},
    vm: ContactsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val contacts by vm.displayed.collectAsStateWithLifecycle()
    val allContacts by vm.contacts.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val accountFilter by com.accessible.dialer.settings.SettingsRepository.accountFilter.collectAsStateWithLifecycle()
    var showFilterDialog by remember { mutableStateOf(false) }
    // Voice search (Google-style live transcription). The sheet owns its own
    // SpeechRecognizer; this screen only tracks visibility and the permission flow.
    var showVoiceSearch by remember { mutableStateOf(false) }
    val voicePermDeniedMsg = stringResource(R.string.voice_search_perm_denied)
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showVoiceSearch = true
        } else {
            Toast.makeText(context, voicePermDeniedMsg, Toast.LENGTH_SHORT).show()
        }
    }
    // Multi-select state. Long-pressing any row enters selection mode; while it is
    // active a tap toggles selection instead of opening the contact.
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    var showMoveAccountPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(permissionsGranted, reloadKey) {
        if (permissionsGranted && DialerPermissions.granted(context, Manifest.permission.READ_CONTACTS)) {
            vm.load(context)
        }
    }

    val filtered = remember(query, contacts) { filterContacts(contacts, query) }
    // Drop any selections that no longer correspond to a visible contact (e.g. after
    // reload or filter change) so the action bar's count is always meaningful.
    LaunchedEffect(filtered) {
        val visible = filtered.map { it.id }.toSet()
        selectedIds.removeAll { it !in visible }
    }

    val newContactLabel = stringResource(R.string.contacts_new)
    val selectionActive = selectedIds.isNotEmpty()

    BackHandler(enabled = selectionActive) { selectedIds.clear() }

    if (showDeleteSelectedConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirm = false },
            title = { Text(stringResource(R.string.delete_selected_contacts_title, selectedIds.size)) },
            text = { Text(stringResource(R.string.delete_selected_contacts_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteSelectedConfirm = false
                    val ids = selectedIds.toList()
                    selectedIds.clear()
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ids.forEach { ContactOps.deleteContact(context, it) }
                        }
                        vm.load(context)
                    }
                }) { Text(stringResource(R.string.delete_contact_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showMoveAccountPicker) {
        MoveSelectedAccountPicker(
            onDismiss = { showMoveAccountPicker = false },
            onPick = { destKey ->
                showMoveAccountPicker = false
                val ids = selectedIds.toList()
                selectedIds.clear()
                scope.launch {
                    val moved = withContext(Dispatchers.IO) {
                        ids.count {
                            com.accessible.dialer.util.ContactAccounts
                                .moveContact(context, it, destKey)
                        }
                    }
                    val msg = if (moved == ids.size) {
                        context.getString(R.string.storage_move_done, moved)
                    } else {
                        context.getString(R.string.storage_move_partial, moved, ids.size)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    vm.load(context)
                }
            },
        )
    }
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNewContact() },
                icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                text = { Text(newContactLabel) },
                modifier = Modifier.semantics { contentDescription = newContactLabel },
            )
        }
    ) { inner ->
        if (showFilterDialog) {
            AccountFilterDialog(
                allContacts = allContacts,
                initial = accountFilter,
                onDismiss = { showFilterDialog = false },
                onApply = {
                    com.accessible.dialer.settings.SettingsRepository.setAccountFilter(it)
                    showFilterDialog = false
                },
            )
        }
        if (showVoiceSearch) {
            VoiceSearchSheet(
                onResult = { text ->
                    // The recognizer returns the final transcript; route it straight
                    // into the existing search filter so the contact list updates as
                    // if the user had typed it.
                    vm.setQuery(text)
                    showVoiceSearch = false
                },
                onDismiss = { showVoiceSearch = false },
            )
        }
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (selectionActive) {
                SelectionBar(
                    count = selectedIds.size,
                    onClear = { selectedIds.clear() },
                    onSelectAll = {
                        selectedIds.clear()
                        selectedIds.addAll(filtered.map { it.id })
                    },
                    onShare = {
                        val ids = selectedIds.toList()
                        ContactOps.shareContacts(context, ids)
                    },
                    onMove = { showMoveAccountPicker = true },
                    onDelete = { showDeleteSelectedConfirm = true },
                )
                HorizontalDivider()
            }
            val filterLabel = stringResource(R.string.contacts_filter_accounts)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    label = { Text(stringResource(R.string.contacts_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                )
                // Mic icon — opens a live-transcription bottom sheet that drops the
                // recognized text into the search field. Hidden when no speech
                // recognizer is installed (e.g. minimal AOSP builds without Google app).
                val voiceLabel = stringResource(R.string.voice_search)
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    IconButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                showVoiceSearch = true
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .semantics { contentDescription = voiceLabel },
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = { showFilterDialog = true },
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .semantics {
                            contentDescription = if (accountFilter.isEmpty()) filterLabel
                            else "$filterLabel, ${accountFilter.size} selected"
                        },
                ) {
                    Icon(
                        Icons.Filled.FilterList,
                        contentDescription = null,
                        tint = if (accountFilter.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.contacts_empty), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                GroupedContactList(
                    contacts = filtered,
                    onCallNumber = onCallNumber,
                    onShowInDialpad = onShowInDialpad,
                    onContactsChanged = { vm.load(context) },
                    onOpenDetails = onOpenDetails,
                    onEditContact = onEditContact,
                    selectedIds = selectedIds.toSet(),
                    selectionActive = selectionActive,
                    onToggleSelect = { id ->
                        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                    },
                    listState = listState,
                    focusTargetId = focusTargetId,
                    onFocusConsumed = onFocusConsumed,
                )
            }
        }
    }
}

@Composable
private fun AccountFilterDialog(
    allContacts: List<Contact>,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit,
) {
    // Build the option list as the *union* of:
    //   - every account currently owning one of the loaded contacts (so counts
    //     match the visible list), and
    //   - every storage account the device knows about (so accounts that exist
    //     but currently hold zero contacts — e.g. a Google account the user
    //     just added — are still selectable as a filter).
    // Counts are taken from `allContacts` only; accounts coming purely from
    // ContactAccounts.list() show a 0.
    val context = LocalContext.current
    val options = remember(allContacts) {
        val counts = LinkedHashMap<String, Int>()
        allContacts.forEach { c ->
            c.accountKeys.forEach { k -> counts[k] = (counts[k] ?: 0) + 1 }
        }
        com.accessible.dialer.util.ContactAccounts.list(context).forEach { entry ->
            counts.putIfAbsent(entry.key, 0)
        }
        // Sort: accounts with contacts first (by descending count), then
        // empty/synthetic accounts alphabetically so the "Local / Phone only"
        // bucket doesn't outrank a real Google account just because of name.
        counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { friendlyAccountLabel(context, it.key).lowercase() }
        )
    }
    val selection = remember { mutableStateMapOf<String, Boolean>().apply {
        options.forEach { (k, _) -> put(k, k in initial) }
    } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_filter_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.contacts_filter_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                options.forEach { (key, count) ->
                    val checked = selection[key] == true
                    val label = friendlyAccountLabel(context, key)
                    val countLabel = stringResource(R.string.contacts_filter_count_a11y, count)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { selection[key] = it },
                            )
                            .padding(vertical = 8.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "$label, $countLabel"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                        )
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "$count",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val picked = selection.filter { it.value }.keys.toSet()
                onApply(picked)
            }) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onApply(emptySet()) }) {
                    Text(stringResource(R.string.contacts_filter_clear))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

/**
 * Returns a human-readable label for an "<account_type>|<account_name>" key. Keeps
 * the mapping inline (rather than in StorageLocationsScreen) so the dialog stays
 * self-contained.
 *
 * Delegates to [com.accessible.dialer.util.ContactAccounts.friendlyLabel] so the
 * authenticator-based resolution kicks in for OEM / third-party account types we
 * don't recognise statically (otherwise the dialog would show raw package names
 * like "com.android.exchange").
 */
internal fun friendlyAccountLabel(context: android.content.Context, key: String): String =
    com.accessible.dialer.util.ContactAccounts.friendlyLabel(context, key)

/**
 * Multi-field search:
 *  - name (case insensitive)
 *  - company (case insensitive)
 *  - digit-only match against the number (e.g. typing "555" matches "(415) 555-1234")
 */
private fun filterContacts(all: List<Contact>, query: String): List<Contact> {
    if (query.isBlank()) return all
    val q = query.trim()
    val digits = q.filter { it.isDigit() }
    return all.filter { c ->
        c.name.contains(q, ignoreCase = true) ||
            c.company.contains(q, ignoreCase = true) ||
            c.number.contains(q) ||
            (digits.isNotEmpty() && c.number.filter { it.isDigit() }.contains(digits))
    }
}

@Composable
internal fun GroupedContactList(
    contacts: List<Contact>,
    onCallNumber: (String) -> Unit,
    onShowInDialpad: (String) -> Unit,
    onContactsChanged: () -> Unit = {},
    onOpenDetails: (Long) -> Unit = {},
    onEditContact: (Long) -> Unit = {},
    selectedIds: Set<Long> = emptySet(),
    selectionActive: Boolean = false,
    onToggleSelect: (Long) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    focusTargetId: Long? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val appContext = LocalContext.current
    val grouped = remember(contacts) {
        contacts
            .groupBy { c ->
                val first = c.name.trim().firstOrNull()?.uppercaseChar()
                if (first != null && first.isLetter()) first else '#'
            }
            .toSortedMap(compareBy { if (it == '#') Char.MAX_VALUE else it })
    }
    val expanded = remember { mutableStateMapOf<Char, Boolean>() }
    val effectiveListState = listState

    // A single FocusRequester is hoisted here and attached only to the row whose
    // id matches focusTargetId. Doing focus work at this scope lets us drive the
    // entire sequence — expand section → scroll → wait for layout → requestFocus
    // — on one coroutine, so the focus call can't race the row's first
    // composition (which is what previously let the search OutlinedTextField
    // grab default focus).
    val rowFocusRequester = remember { FocusRequester() }

    LaunchedEffect(focusTargetId, contacts) {
        if (focusTargetId == null) return@LaunchedEffect
        var index = 0
        for ((letter, items) in grouped) {
            index++ // section header always takes one slot
            val targetPos = items.indexOfFirst { it.id == focusTargetId }
            if (targetPos >= 0) {
                if (expanded[letter] == false) expanded[letter] = true
                effectiveListState.scrollToItem(index + targetPos)
                // Wait for the scrolled-to row to be composed and laid out so
                // the FocusRequester modifier is attached to a node. 150ms
                // comfortably exceeds two frames at 60Hz.
                kotlinx.coroutines.delay(150)
                val ok = runCatching { rowFocusRequester.requestFocus() }.isSuccess
                if (ok) onFocusConsumed()
                return@LaunchedEffect
            }
            val isExpanded = expanded[letter] ?: true
            if (isExpanded) index += items.size
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), state = effectiveListState) {
        grouped.forEach { (letter, items) ->
            val isExpanded = expanded[letter] ?: true
            item(key = "header_$letter") {
                SectionHeader(
                    letter = letter,
                    count = items.size,
                    expanded = isExpanded,
                    onToggle = { expanded[letter] = !isExpanded },
                )
            }
            if (isExpanded) {
                items(items, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        onTap = { onOpenDetails(contact.id) },
                        tapLabel = stringResource(R.string.contacts_open_details, contact.name.ifBlank { contact.number }),
                        onCall = { onCallNumber(contact.number) },
                        onShowInDialpad = { onShowInDialpad(contact.number) },
                        onContactsChanged = onContactsChanged,
                        onEditContact = onEditContact,
                        isSelected = contact.id in selectedIds,
                        selectionActive = selectionActive,
                        onToggleSelect = { onToggleSelect(contact.id) },
                        externalFocusRequester = if (contact.id == focusTargetId) rowFocusRequester else null,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    letter: Char,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val collapseLabel = stringResource(R.string.contacts_section_collapse)
    val expandLabel = stringResource(R.string.contacts_section_expand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                onClick = onToggle,
                onClickLabel = if (expanded) collapseLabel else expandLabel,
                role = Role.Button,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            // Merge children so TalkBack reads the header once. Setting an explicit
            // contentDescription alongside mergeDescendants causes the system to read the
            // override *and* each merged child Text — the duplicated announcement we are
            // avoiding here.
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.contacts_section_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContactRow(
    contact: Contact,
    onTap: () -> Unit,
    tapLabel: String,
    onCall: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onShowInDialpad: () -> Unit,
    onContactsChanged: () -> Unit = {},
    onEditContact: (Long) -> Unit = {},
    isSelected: Boolean = false,
    selectionActive: Boolean = false,
    onToggleSelect: () -> Unit = {},
    externalFocusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val displayName = contact.name.ifBlank { contact.number }
    val callLabel = stringResource(R.string.contacts_call, displayName)
    val sendMessageLabel = stringResource(R.string.action_send_message)
    val copyNumberLabel = stringResource(R.string.action_copy_number)
    val favoriteLabel = stringResource(R.string.contacts_favorite_indicator)
    val editLabel = stringResource(R.string.action_edit_contact)
    val deleteLabel = stringResource(R.string.action_delete_contact)
    val shareLabel = stringResource(R.string.action_share_contact)
    val blockLabel = stringResource(R.string.action_block_number)
    val ringtoneLabel = stringResource(R.string.action_set_ringtone)
    val starLabel = stringResource(R.string.action_add_favorite)
    val unstarLabel = stringResource(R.string.action_remove_favorite)

    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_contact_title)) },
            text = { Text(stringResource(R.string.delete_contact_message, displayName)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDeleteConfirm = false
                    if (ContactOps.deleteContact(context, contact.id)) onContactsChanged()
                }) { Text(stringResource(R.string.delete_contact_confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Build the action list. Phone-bound actions (call/SMS/copy/show-in-keypad/block) are
    // only added when we actually have a number, so TalkBack users don't hear a "Call"
    // action that would do nothing.
    val hasNumber = contact.number.isNotBlank()
    val actions = buildList {
        if (hasNumber) {
            add(CustomAccessibilityAction(callLabel) { onCall(); true })
            add(CustomAccessibilityAction(sendMessageLabel) {
                RowActions.sendSms(context, contact.number); true
            })
            add(CustomAccessibilityAction(copyNumberLabel) {
                RowActions.copyNumber(context, contact.number); true
            })
            add(CustomAccessibilityAction(blockLabel) {
                ContactOps.blockNumber(context, contact.number); true
            })
        }
        add(CustomAccessibilityAction(if (contact.starred) unstarLabel else starLabel) {
            ContactOps.toggleFavorite(context, contact.id, contact.starred)
                ?.let { onContactsChanged() }
            true
        })
        add(CustomAccessibilityAction(editLabel) {
            onEditContact(contact.id); true
        })
        add(CustomAccessibilityAction(shareLabel) {
            ContactOps.shareContact(context, contact.id); true
        })
        add(CustomAccessibilityAction(ringtoneLabel) {
            ContactOps.setRingtone(context, contact.id); true
        })
        add(CustomAccessibilityAction(deleteLabel) {
            showDeleteConfirm = true; true
        })
    }

    val selectLabel = stringResource(R.string.selection_long_press_hint)
    // When the screen is in multi-select mode the whole row becomes the toggle
    // surface (Role.Checkbox) so TalkBack announces the row as "checked" /
    // "not checked" and a single tap flips it. Outside selection mode we keep the
    // regular button behaviour with long-press to enter selection.
    val gestureModifier = if (selectionActive) {
        Modifier.toggleable(
            value = isSelected,
            role = Role.Checkbox,
            onValueChange = { onToggleSelect() },
        )
    } else {
        Modifier.combinedClickable(
            onClick = { onTap() },
            onLongClick = { onToggleSelect() },
            onClickLabel = tapLabel,
            onLongClickLabel = selectLabel,
            role = Role.Button,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (externalFocusRequester != null)
                    Modifier.focusRequester(externalFocusRequester)
                else Modifier
            )
            .then(
                if (isSelected)
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier
            )
            .then(gestureModifier)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                customActions = actions
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionActive) {
            androidx.compose.material3.Checkbox(
                checked = isSelected,
                // Row-level toggleable handles clicks; the checkbox is a passive
                // indicator so the gesture isn't double-fired.
                onCheckedChange = null,
            )
            Spacer(Modifier.size(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (contact.starred) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = favoriteLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (contact.company.isNotBlank()) {
                Text(
                    text = contact.company,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Contextual action bar shown above the list while the user has one or more rows
 * selected. Provides a count, a clear button, select-all, and the destructive batch
 * action (Delete). Lives inside the screen body rather than the global top-app-bar
 * since the host Scaffold belongs to DialerApp.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val clearLabel = stringResource(R.string.selection_clear)
    val deleteLabel = stringResource(R.string.selection_delete)
    val shareLabel = stringResource(R.string.selection_share)
    val moveLabel = stringResource(R.string.selection_move)
    val selectAllLabel = stringResource(R.string.selection_select_all)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = clearLabel)
        }
        Text(
            text = stringResource(R.string.selection_count, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSelectAll) { Text(selectAllLabel) }
        IconButton(
            onClick = onShare,
            modifier = Modifier.semantics { contentDescription = shareLabel },
        ) {
            Icon(Icons.Filled.Share, contentDescription = shareLabel)
        }
        IconButton(
            onClick = onMove,
            modifier = Modifier.semantics { contentDescription = moveLabel },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.DriveFileMove,
                contentDescription = moveLabel,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = deleteLabel)
        }
    }
}

/**
 * Single-choice account picker used by the contacts multi-select "Move" action.
 * Lists every storage account known to the device; tapping one fires [onPick]
 * with its "<type>|<name>" key, which the caller passes to
 * [com.accessible.dialer.util.ContactAccounts.moveContact] for each selected id.
 */
@Composable
private fun MoveSelectedAccountPicker(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var entries by remember {
        mutableStateOf<List<com.accessible.dialer.util.ContactAccounts.Entry>>(emptyList())
    }
    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) {
            com.accessible.dialer.util.ContactAccounts.list(context)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_move_title)) },
        text = {
            LazyColumn {
                items(entries, key = { it.key }) { entry ->
                    val opener = entry.label
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.Button,
                                onClickLabel = opener,
                                onClick = { onPick(entry.key) },
                            )
                            .padding(vertical = 12.dp, horizontal = 8.dp)
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
