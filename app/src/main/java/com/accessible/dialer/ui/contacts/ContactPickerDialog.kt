package com.accessible.dialer.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accessible.dialer.R

/**
 * Generic full-screen contact picker reused wherever the app needs the user to choose
 * a single contact (speed dial slot, shake-to-call target, add-number-to-existing-contact).
 * Same data source as the Contacts tab via [ContactsViewModel] so the account filter and
 * sort order match what the user already sees elsewhere in the app.
 *
 * Returns the picked contact via [onPick]; the dialog dismisses itself on selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onPick: (Contact) -> Unit,
    excludeContactId: Long? = null,
    viewModelKey: String = "contact_picker",
) {
    val context = LocalContext.current
    val vm: ContactsViewModel = viewModel(key = viewModelKey)
    LaunchedEffect(Unit) { vm.load(context) }
    val displayed by vm.displayed.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(displayed, query, excludeContactId) {
        val q = query.trim()
        displayed.asSequence()
            .filter { excludeContactId == null || it.id != excludeContactId }
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
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
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
                    // Group by first letter with a per-letter expand/collapse map,
                    // matching the main Contacts tab so users get the same affordances
                    // (jump-by-letter, fold long sections) wherever they pick a contact.
                    // Non-alphabetic starts — numbers, symbols, emoji — are bucketed
                    // under '#' and sorted to the bottom, again mirroring ContactsScreen.
                    val grouped = remember(filtered) {
                        filtered
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
                                PickerSectionHeader(
                                    letter = letter,
                                    count = items.size,
                                    expanded = isExpanded,
                                    onToggle = { expanded[letter] = !isExpanded },
                                )
                            }
                            if (isExpanded) {
                                items(items.size, key = { i -> items[i].id }) { i ->
                                    val c = items[i]
                                    ContactPickerRow(c, onPick = { onPick(c) })
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerSectionHeader(
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
            // Merge children so TalkBack reads the header once instead of three
            // separate nodes (letter, count, chevron).
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

@Composable
private fun ContactPickerRow(contact: Contact, onPick: () -> Unit) {
    val display = contact.name.ifBlank { contact.number }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onPick)
            .semantics { contentDescription = display }
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
