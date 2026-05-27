package com.accessible.dialer.ui.storage

import android.content.Context
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R
import com.accessible.dialer.util.ContactAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Tools → "Where contacts are stored".
 *
 * Two-level navigation:
 *   1. **Account list** — one row per device account that owns (or could own) a
 *      contact, with count. Tapping drills into…
 *   2. **Per-account contact list** — a multi-select list of every aggregated
 *      contact whose raw rows live in that account. Selection enables a top-bar
 *      action menu with Delete and Move-to-account buttons. Move opens a
 *      single-choice dialog picking from the full account list (minus self).
 *
 * Both levels share one top-level [Scaffold]; the inner `selectedAccount` state
 * decides which view to render. This avoids fragile nested navigation while
 * keeping the back button predictable: from level 2 we clear the selection
 * (returning to level 1) and only from level 1 do we propagate [onBack] up to
 * the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageLocationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedAccount by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    androidx.activity.compose.BackHandler(enabled = selectedAccount != null) {
        selectedAccount = null
    }

    if (selectedAccount == null) {
        StorageAccountList(
            context = context,
            refreshTick = refreshTick,
            onBack = onBack,
            onOpen = { selectedAccount = it },
        )
    } else {
        StorageAccountDetail(
            context = context,
            accountKey = selectedAccount!!,
            onBack = {
                // Returning to the list — bump the refresh tick so counts reflect
                // any deletes / moves that happened in the detail screen.
                refreshTick += 1
                selectedAccount = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageAccountList(
    context: Context,
    refreshTick: Int,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var entries by remember { mutableStateOf<List<ContactAccounts.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(refreshTick) {
        loading = true
        // ContactsContract queries on the UI thread are technically OK on modern
        // devices but the user has many accounts on test devices; off-thread keeps
        // the screen responsive when the list grows.
        entries = withContext(Dispatchers.IO) { ContactAccounts.list(context) }
        loading = false
    }

    val backLabel = stringResource(R.string.action_back)
    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.storage_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                }
            },
        )
    }) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            Text(
                stringResource(R.string.storage_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            val total = entries.sumOf { it.count }
            Text(
                stringResource(R.string.storage_total, total),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.duplicates_scanning))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.key }) { entry ->
                        AccountRow(entry = entry, onClick = { onOpen(entry.key) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(entry: ContactAccounts.Entry, onClick: () -> Unit) {
    val opener = stringResource(R.string.storage_open_account)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = opener, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.label, style = MaterialTheme.typography.titleMedium)
            val countText = when (entry.count) {
                0 -> stringResource(R.string.storage_count_zero)
                1 -> stringResource(R.string.storage_count_one)
                else -> stringResource(R.string.storage_count, entry.count)
            }
            Text(
                countText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Account detail ───────────────────────────────────────────────────────────

private data class StorageContact(val id: Long, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageAccountDetail(
    context: Context,
    accountKey: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<StorageContact>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Selection lives in a SnapshotStateList so per-row Checkbox state stays
    // cheap (Compose only invalidates rows whose membership changed).
    val selected = remember { mutableStateListOf<Long>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        val ids = withContext(Dispatchers.IO) {
            ContactAccounts.contactIdsIn(context, accountKey)
        }
        val items = withContext(Dispatchers.IO) { lookupNames(context, ids) }
        contacts = items
        // Drop any selected ids that no longer exist (e.g. just deleted).
        val present = items.mapTo(HashSet()) { it.id }
        selected.removeAll { it !in present }
        loading = false
    }
    LaunchedEffect(accountKey) { reload() }
    // Toast must NOT live in the composition body \u2014 doing so fires a fresh
    // Toast on every recomposition and stacks them up in the system queue.
    // Triggering it from a keyed LaunchedEffect runs exactly once per status
    // value and clears the state from inside the effect to avoid re-firing.
    LaunchedEffect(statusMessage) {
        val msg = statusMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        statusMessage = null
    }

    val backLabel = stringResource(R.string.action_back)
    val cancelSel = stringResource(R.string.storage_cancel_select)
    val selectAll = stringResource(R.string.storage_select_all)
    val deleteSel = stringResource(R.string.storage_delete_selected)
    val moveSel = stringResource(R.string.storage_move_selected)
    val inSelectMode = selected.isNotEmpty()

    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    if (inSelectMode)
                        stringResource(R.string.storage_selected_n, selected.size)
                    else ContactAccounts.friendlyLabel(accountKey),
                )
            },
            navigationIcon = {
                if (inSelectMode) {
                    IconButton(
                        onClick = { selected.clear() },
                        modifier = Modifier.semantics { contentDescription = cancelSel },
                    ) { Icon(Icons.Filled.Close, contentDescription = cancelSel) }
                } else {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel) }
                }
            },
            actions = {
                if (inSelectMode) {
                    IconButton(
                        onClick = { showMovePicker = true },
                        modifier = Modifier.semantics { contentDescription = moveSel },
                    ) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = moveSel) }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.semantics { contentDescription = deleteSel },
                    ) { Icon(Icons.Filled.Delete, contentDescription = deleteSel) }
                } else if (contacts.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            selected.clear()
                            selected.addAll(contacts.map { it.id })
                        },
                        modifier = Modifier.semantics { contentDescription = selectAll },
                    ) { Text(selectAll) }
                }
            },
        )
    }) { inner ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.duplicates_scanning))
            }
        } else if (contacts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.storage_account_empty))
            }
        } else {
            LazyColumn(contentPadding = inner, modifier = Modifier.fillMaxSize()) {
                items(contacts, key = { it.id }) { c ->
                    ContactSelectableRow(
                        name = c.name,
                        checked = c.id in selected,
                        onToggle = {
                            if (c.id in selected) selected.remove(c.id) else selected.add(c.id)
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Delete confirmation dialog. Runs deletes on IO and reports an aggregate
    // count via Toast so the user sees what actually happened (some rows may
    // belong to a read-only sync adapter and silently refuse to delete).
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.storage_delete_confirm_title)) },
            text = {
                Text(stringResource(
                    R.string.storage_delete_confirm_message, selected.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val ids = selected.toList()
                    scope.launch {
                        val count = withContext(Dispatchers.IO) {
                            ids.count { ContactAccounts.deleteContact(context, it) }
                        }
                        statusMessage = context.getString(R.string.storage_delete_done, count)
                        reload()
                    }
                }) { Text(stringResource(R.string.delete_contact_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showMovePicker) {
        MoveAccountPicker(
            context = context,
            excludeKey = accountKey,
            onDismiss = { showMovePicker = false },
            onPick = { destKey ->
                showMovePicker = false
                val ids = selected.toList()
                scope.launch {
                    val moved = withContext(Dispatchers.IO) {
                        ids.count { ContactAccounts.moveContact(context, it, destKey) }
                    }
                    statusMessage = if (moved == ids.size) {
                        context.getString(R.string.storage_move_done, moved)
                    } else {
                        context.getString(R.string.storage_move_partial, moved, ids.size)
                    }
                    reload()
                }
            },
        )
    }
}

@Composable
private fun ContactSelectableRow(name: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Box(modifier = Modifier.size(8.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveAccountPicker(
    context: Context,
    excludeKey: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var entries by remember { mutableStateOf<List<ContactAccounts.Entry>>(emptyList()) }
    var pick by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) {
            ContactAccounts.list(context).filter { it.key != excludeKey }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_move_title)) },
        text = {
            LazyColumn {
                items(entries, key = { it.key }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { pick = entry.key }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = pick == entry.key,
                            onClick = { pick = entry.key },
                        )
                        Text(entry.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pick != null,
                onClick = { pick?.let(onPick) },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Resolve display names for [ids] in a single round-trip. We could query
 * Contacts.CONTENT_URI once and iterate, but the in-clause is simpler and
 * preserves the input ordering when we sort post-hoc.
 */
private fun lookupNames(context: Context, ids: List<Long>): List<StorageContact> {
    if (ids.isEmpty()) return emptyList()
    val nameByid = HashMap<Long, String>(ids.size)
    runCatching {
        // Split into chunks to keep the SQL IN(...) clause under SQLite limits.
        // Bind the ids as selectionArgs (rather than concatenating) so the
        // query stays the same parsed statement regardless of input.
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                ),
                "${ContactsContract.Contacts._ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    nameByid[c.getLong(0)] = c.getString(1) ?: ""
                }
            }
        }
    }
    return ids.map { StorageContact(it, nameByid[it].orEmpty()) }
        .sortedBy { it.name.lowercase() }
}
