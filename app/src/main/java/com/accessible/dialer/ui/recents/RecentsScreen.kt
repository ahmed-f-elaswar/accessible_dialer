package com.accessible.dialer.ui.recents

import android.Manifest
import android.provider.CallLog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    vm: RecentsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val entries by vm.entries.collectAsState()

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && DialerPermissions.granted(context, Manifest.permission.READ_CALL_LOG)) {
            vm.load(context)
        }
    }

    if (entries.isEmpty()) {
        EmptyState(text = stringResource(R.string.recents_empty))
        return
    }
    GroupedRecentsList(
        entries = entries,
        onCallNumber = onCallNumber,
        onShowInDialpad = onShowInDialpad,
    )
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
                    )
                    HorizontalDivider()
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
) {
    val context = LocalContext.current
    val displayName = entry.displayName ?: entry.number
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

    // Tap calls back. Messaging and copying live as discoverable TalkBack custom actions
    // (local context menu) so they're reachable without remembering gesture shortcuts.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onCall,
                onClickLabel = callLabel,
                role = Role.Button,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction(sendMessageLabel) {
                        RowActions.sendSms(context, entry.number); true
                    },
                    CustomAccessibilityAction(copyNumberLabel) {
                        RowActions.copyNumber(context, entry.number); true
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name + time go first so TalkBack reads them at the start of the announcement.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.relativeTime,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(16.dp))
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
            modifier = Modifier.size(28.dp),
        )
    }
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
