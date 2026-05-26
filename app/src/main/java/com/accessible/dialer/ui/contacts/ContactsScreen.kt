package com.accessible.dialer.ui.contacts

import android.Manifest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

/**
 * Contacts directory grouped alphabetically by the first letter of the display name.
 *
 * Accessibility:
 *  - Each contact row is a single TalkBack focusable. `combinedClickable` carries
 *    explicit gesture labels so the reader announces "double-tap to call X,
 *    double-tap-and-hold to show X in keypad".
 *  - Section headers are buttons with "Expand"/"Collapse" labels and a spoken summary
 *    ("A, 5 contacts").
 */
@Composable
fun ContactsScreen(
    permissionsGranted: Boolean,
    onCallNumber: (String) -> Unit,
    onShowInDialpad: (String) -> Unit,
    vm: ContactsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val contacts by vm.contacts.collectAsState()
    val query by vm.query.collectAsState()

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && DialerPermissions.granted(context, Manifest.permission.READ_CONTACTS)) {
            vm.load(context)
        }
    }

    val filtered = if (query.isBlank()) contacts else contacts.filter {
        it.name.contains(query, ignoreCase = true) || it.number.contains(query)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            label = { Text(stringResource(R.string.contacts_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.contacts_empty), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            GroupedContactList(
                contacts = filtered,
                onCallNumber = onCallNumber,
                onShowInDialpad = onShowInDialpad,
            )
        }
    }
}

@Composable
internal fun GroupedContactList(
    contacts: List<Contact>,
    onCallNumber: (String) -> Unit,
    onShowInDialpad: (String) -> Unit,
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

    LazyColumn(Modifier.fillMaxSize()) {
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
                        onTap = { RowActions.openContactDetails(appContext, contact.id) },
                        tapLabel = stringResource(R.string.contacts_open_details, contact.name.ifBlank { contact.number }),
                        onCall = { onCallNumber(contact.number) },
                        onShowInDialpad = { onShowInDialpad(contact.number) },
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
    onShowInDialpad: () -> Unit,
) {
    val context = LocalContext.current
    val displayName = contact.name.ifBlank { contact.number }
    val callLabel = stringResource(R.string.contacts_call, displayName)
    val sendMessageLabel = stringResource(R.string.action_send_message)
    val copyNumberLabel = stringResource(R.string.action_copy_number)
    val showInDialpadLabel = stringResource(R.string.contacts_load_in_dialpad, displayName)
    val favoriteLabel = stringResource(R.string.contacts_favorite_indicator)

    // Tap behavior is controlled by the caller (Contacts opens contact details; Favorites
    // calls). Other actions live in the TalkBack custom action menu so nothing is hidden
    // behind an undocumented gesture.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onTap,
                onClickLabel = tapLabel,
                role = Role.Button,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction(callLabel) { onCall(); true },
                    CustomAccessibilityAction(sendMessageLabel) {
                        RowActions.sendSms(context, contact.number); true
                    },
                    CustomAccessibilityAction(copyNumberLabel) {
                        RowActions.copyNumber(context, contact.number); true
                    },
                    CustomAccessibilityAction(showInDialpadLabel) {
                        onShowInDialpad(); true
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        }
    }
}
