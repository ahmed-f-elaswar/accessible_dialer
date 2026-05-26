package com.accessible.dialer.ui.recents

import android.Manifest
import android.provider.CallLog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accessible.dialer.R
import com.accessible.dialer.util.DialerPermissions
import com.accessible.dialer.util.RowActions
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun RecentsScreen(
    permissionsGranted: Boolean,
    onCallNumber: (String) -> Unit,
    onShowInDialpad: (String) -> Unit,
    onOpenContactDetails: (Long) -> Unit = {},
    vm: RecentsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val entries by vm.entries.collectAsState()
    val endReached by vm.endReached.collectAsState()
    val loading by vm.loading.collectAsState()
    // Multi-select state for the call log. Long-press toggles, tap toggles while a
    // selection exists, Back clears.
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // (contactId, displayName) for the contact pending deletion. The action shows a
    // confirmation dialog before the destructive delete fires.
    var deleteContactRequest by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // Holds the entry id that should pull TalkBack / input focus after the
    // user deletes an entire history entry. Cleared by the consuming row once
    // it has requested focus, so a subsequent unrelated recomposition doesn't
    // re-steal focus.
    var focusAfterDeleteId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && DialerPermissions.granted(context, Manifest.permission.READ_CALL_LOG)) {
            vm.load(context)
        }
    }
    // Trim selections that point at entries no longer in the list (after reload/delete).
    LaunchedEffect(entries) {
        val visible = entries.map { it.id }.toSet()
        selectedIds.removeAll { it !in visible }
    }

    val selectionActive = selectedIds.isNotEmpty()
    BackHandler(enabled = selectionActive) { selectedIds.clear() }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_selected_recents_title, selectedIds.size)) },
            text = { Text(stringResource(R.string.delete_selected_recents_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val ids = selectedIds.toList()
                    selectedIds.clear()
                    ids.forEach { vm.deleteEntry(context, it) }
                }) { Text(stringResource(R.string.delete_contact_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deleteContactRequest?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { deleteContactRequest = null },
            title = { Text(stringResource(R.string.delete_contact_title)) },
            text = { Text(stringResource(R.string.delete_contact_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteContactRequest = null
                    if (RowActions.deleteContact(context, id)) {
                        // Refresh the recents view since the contact's display-name
                        // resolution will change too (cached names go stale).
                        vm.load(context)
                    }
                }) { Text(stringResource(R.string.delete_contact_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteContactRequest = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (entries.isEmpty()) {
        EmptyState(text = stringResource(R.string.recents_empty))
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (selectionActive) {
            RecentsSelectionBar(
                count = selectedIds.size,
                onClear = { selectedIds.clear() },
                onSelectAll = {
                    selectedIds.clear()
                    selectedIds.addAll(entries.map { it.id })
                },
                onDelete = { showDeleteConfirm = true },
            )
            HorizontalDivider()
        }
        GroupedRecentsList(
            entries = entries,
            onCallNumber = onCallNumber,
            onShowInDialpad = onShowInDialpad,
            onDelete = { id -> vm.deleteEntry(context, id) },
            onDeleteEntire = { entry ->
                // Compute the entry that should claim TalkBack/input focus once the
                // delete completes and the list rebuilds. The list is sorted DESC by
                // date, so the "next item the user already navigated through" is the
                // row immediately after this one (index + 1). If we just deleted the
                // tail row, fall back to the row immediately above it (index - 1) so
                // focus doesn't get yanked back to the top of the screen.
                val idx = entries.indexOfFirst { it.id == entry.id }
                focusAfterDeleteId = when {
                    idx < 0 -> null
                    idx + 1 < entries.size -> entries[idx + 1].id
                    idx - 1 >= 0 -> entries[idx - 1].id
                    else -> null
                }
                vm.deleteEntireEntry(context, entry)
            },
            selectedIds = selectedIds.toSet(),
            selectionActive = selectionActive,
            onToggleSelect = { id ->
                if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
            },
            endReached = endReached,
            loading = loading,
            onLoadMore = { vm.loadMore(context) },
            onOpenContactDetails = onOpenContactDetails,
            onDeleteContact = { id, name -> deleteContactRequest = id to name },
            focusTargetId = focusAfterDeleteId,
            onFocusConsumed = { focusAfterDeleteId = null },
        )
    }
}

/** Stable, ordered time buckets used to group the call log. */
private enum class RecentBucket(val titleRes: Int) {
    Today(R.string.recents_section_today),
    Yesterday(R.string.recents_section_yesterday),
    ThisWeek(R.string.recents_section_this_week),
    Earlier(R.string.recents_section_earlier),
}

private fun bucketFor(date: Long, now: Calendar): RecentBucket {
    val day = Calendar.getInstance().apply { timeInMillis = date }
    val sameYearAndDay = { other: Calendar ->
        day.get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            day.get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
    }
    if (sameYearAndDay(now)) return RecentBucket.Today
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameYearAndDay(yesterday)) return RecentBucket.Yesterday
    val weekAgo = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
    return if (day.after(weekAgo)) RecentBucket.ThisWeek else RecentBucket.Earlier
}

@Composable
private fun GroupedRecentsList(
    entries: List<CallLogEntry>,
    onCallNumber: (String) -> Unit,
    onShowInDialpad: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onDeleteEntire: (CallLogEntry) -> Unit,
    selectedIds: Set<Long> = emptySet(),
    selectionActive: Boolean = false,
    onToggleSelect: (Long) -> Unit = {},
    endReached: Boolean = true,
    loading: Boolean = false,
    onLoadMore: () -> Unit = {},
    onOpenContactDetails: (Long) -> Unit = {},
    onDeleteContact: (Long, String) -> Unit = { _, _ -> },
    focusTargetId: Long? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val grouped = remember(entries) {
        val now = Calendar.getInstance()
        // entries arrive sorted DESC by date; LinkedHashMap preserves that order per bucket.
        val map = LinkedHashMap<RecentBucket, MutableList<CallLogEntry>>()
        for (e in entries) {
            map.getOrPut(bucketFor(e.date, now)) { mutableListOf() } += e
        }
        // Re-emit in the canonical bucket order so newer sections always appear above.
        RecentBucket.values().mapNotNull { b -> map[b]?.let { b to it } }
    }
    val expanded = remember { mutableStateMapOf<RecentBucket, Boolean>() }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        grouped.forEach { (bucket, items) ->
            val isExpanded = expanded[bucket] ?: true
            item(key = "header_${bucket.name}") {
                RecentsSectionHeader(
                    title = stringResource(bucket.titleRes),
                    count = items.size,
                    expanded = isExpanded,
                    onToggle = { expanded[bucket] = !isExpanded },
                )
            }
            if (isExpanded) {
                items(items, key = { it.id }) { entry ->
                    RecentRow(
                        entry = entry,
                        onCall = { onCallNumber(entry.number) },
                        onDelete = { onDelete(entry.id) },
                        onDeleteEntire = { onDeleteEntire(entry) },
                        isSelected = entry.id in selectedIds,
                        selectionActive = selectionActive,
                        onToggleSelect = { onToggleSelect(entry.id) },
                        onOpenContactDetails = onOpenContactDetails,
                        onDeleteContact = onDeleteContact,
                        focusTargetId = focusTargetId,
                        onFocusConsumed = onFocusConsumed,
                    )
                    HorizontalDivider()
                }
            }
        }
        // Sentinel footer that drives pagination. While more pages remain, this item
        // sits below the last row; when the user scrolls it into view Compose composes
        // it and the LaunchedEffect fires, asking the ViewModel for the next page. Its
        // key includes the current entries.size so each subsequent page load remounts
        // the effect and we don't get stuck after the first append.
        if (!endReached) {
            item(key = "load_more_${entries.size}") {
                LaunchedEffect(entries.size) { onLoadMore() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (loading) R.string.recents_loading_more
                            else R.string.recents_load_more
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentsSectionHeader(
    title: String,
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
            // Merge children so the header is a single TalkBack node. We deliberately do
            // *not* set an explicit contentDescription here — combining one with
            // mergeDescendants causes TalkBack to read the override AND each child Text,
            // which is exactly the "announced twice" behaviour we are removing.
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.recents_section_count, count),
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
private fun RecentRow(
    entry: CallLogEntry,
    onCall: () -> Unit,
    onDelete: () -> Unit,
    onDeleteEntire: () -> Unit,
    isSelected: Boolean = false,
    selectionActive: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onOpenContactDetails: (Long) -> Unit = {},
    onDeleteContact: (Long, String) -> Unit = { _, _ -> },
    focusTargetId: Long? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val displayName = entry.displayName ?: entry.number
    // FocusRequester used to pull input/accessibility focus to this row after a
    // sibling row was just deleted. Combined with the `.focusable()` modifier
    // below and an explicit `announceForAccessibility(displayName)`, TalkBack
    // moves its highlight here instead of snapping back to the top of the list.
    val focusRequester = remember { FocusRequester() }
    if (focusTargetId != null && focusTargetId == entry.id) {
        LaunchedEffect(focusTargetId) {
            runCatching { focusRequester.requestFocus() }
            rootView.announceForAccessibility(displayName)
            onFocusConsumed()
        }
    }
    // Resolve the aggregated contact id for this number off-thread. Null while the
    // lookup is in flight or when no contact matches (private numbers, strangers).
    // Used to drive the "Show contact info" / "Delete contact" custom actions —
    // those actions are filtered out below when [contactId] is null.
    val contactId by androidx.compose.runtime.produceState<Long?>(initialValue = null, entry.number) {
        val n = entry.number
        value = if (n.isBlank()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            RowActions.lookupContactId(context, n)
        }
    }
    val typeLabel = when (entry.type) {
        CallLog.Calls.INCOMING_TYPE -> stringResource(R.string.recents_incoming)
        CallLog.Calls.OUTGOING_TYPE -> stringResource(R.string.recents_outgoing)
        CallLog.Calls.MISSED_TYPE -> stringResource(R.string.recents_missed)
        CallLog.Calls.REJECTED_TYPE -> stringResource(R.string.recents_rejected)
        else -> stringResource(R.string.recents_call_generic)
    }
    val callLabel = stringResource(R.string.recents_call_back, displayName)
    val sendMessageLabel = stringResource(R.string.action_send_message)
    val copyNumberLabel = stringResource(R.string.action_copy_number)
    val deleteLabel = stringResource(R.string.action_delete_recent)
    val deleteAllLabel = stringResource(R.string.action_delete_recent_all)
    val showContactLabel = stringResource(R.string.action_show_contact_info)
    val deleteContactLabel = stringResource(R.string.action_delete_contact)
    val blockLabel = stringResource(R.string.action_block_number)
    val unblockLabel = stringResource(R.string.action_unblock_number)
    val isBlocked = com.accessible.dialer.blocking.BlockedNumbersRepository
        .isBlocked(context, entry.number)

    val selectLabel = stringResource(R.string.selection_long_press_hint)
    // In selection mode the whole row is the checkbox (Role.Checkbox + toggleable),
    // so TalkBack announces it as "checked" / "not checked" and a single tap flips
    // the state. Out of selection mode tap calls back and long-press starts a
    // multi-select.
    val gestureModifier = if (selectionActive) {
        Modifier.toggleable(
            value = isSelected,
            role = Role.Checkbox,
            onValueChange = { onToggleSelect() },
        )
    } else {
        Modifier.combinedClickable(
            onClick = { onCall() },
            onLongClick = { onToggleSelect() },
            onClickLabel = callLabel,
            onLongClickLabel = selectLabel,
            role = Role.Button,
        )
    }
    // Tap calls back. Messaging, copying, and deleting the call-log entry live as
    // discoverable TalkBack custom actions so they're reachable without gesture shortcuts.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusable()
            .then(
                if (isSelected)
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier
            )
            .then(gestureModifier)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                customActions = buildList {
                    add(CustomAccessibilityAction(sendMessageLabel) {
                        RowActions.sendSms(context, entry.number); true
                    })
                    add(CustomAccessibilityAction(copyNumberLabel) {
                        RowActions.copyNumber(context, entry.number); true
                    })
                    // Contact-scoped actions are only meaningful when the number
                    // actually resolves to a contact — hide them for strangers /
                    // private numbers so TalkBack doesn't offer dead options.
                    contactId?.let { cid ->
                        add(CustomAccessibilityAction(showContactLabel) {
                            onOpenContactDetails(cid); true
                        })
                        add(CustomAccessibilityAction(deleteContactLabel) {
                            onDeleteContact(cid, displayName); true
                        })
                    }
                    if (entry.number.isNotBlank()) {
                        if (isBlocked) {
                            add(CustomAccessibilityAction(unblockLabel) {
                                com.accessible.dialer.util.ContactOps.unblockNumber(
                                    context, entry.number
                                ); true
                            })
                        } else {
                            add(CustomAccessibilityAction(blockLabel) {
                                com.accessible.dialer.util.ContactOps.blockNumber(
                                    context, entry.number
                                ); true
                            })
                        }
                    }
                    add(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                    add(CustomAccessibilityAction(deleteAllLabel) { onDeleteEntire(); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionActive) {
            Checkbox(
                checked = isSelected,
                // Row toggleable handles clicks; the checkbox is just an indicator.
                onCheckedChange = null,
            )
            Spacer(Modifier.size(8.dp))
        }
        // Layout order: name first, call-direction icon immediately after the
        // name (so TalkBack speaks "incoming/outgoing/missed" right with the
        // person), then the date pushed to the far right.
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.size(8.dp))
        Icon(
            imageVector = when (entry.type) {
                CallLog.Calls.INCOMING_TYPE -> Icons.Filled.CallReceived
                CallLog.Calls.OUTGOING_TYPE -> Icons.Filled.CallMade
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> Icons.Filled.CallMissed
                else -> Icons.Filled.Call
            },
            contentDescription = typeLabel,
            tint = when (entry.type) {
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = entry.relativeTime,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Show the duration for calls that actually connected (incoming /
            // outgoing with non-zero duration). Missed / rejected calls have
            // duration == 0 so we hide the line entirely rather than print "0s".
            if (entry.duration > 0L) {
                Text(
                    text = formatDuration(entry.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Format a call duration in seconds as a short, screen-reader-friendly string.
 * Uses "Hh Mm Ss" / "Mm Ss" / "Ss" so TalkBack reads it as "1 hour 5 minutes"
 * etc. rather than spelling out a punctuated time code.
 */
internal fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return buildString {
        if (h > 0) append("${h}h ")
        if (h > 0 || m > 0) append("${m}m ")
        append("${s}s")
    }.trim()
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

internal fun formatRelative(date: Date): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)

/** Contextual action bar shown above the call log while one or more rows are selected. */
@Composable
private fun RecentsSelectionBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    val clearLabel = stringResource(R.string.selection_clear)
    val deleteLabel = stringResource(R.string.selection_delete)
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
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = deleteLabel)
        }
    }
}
