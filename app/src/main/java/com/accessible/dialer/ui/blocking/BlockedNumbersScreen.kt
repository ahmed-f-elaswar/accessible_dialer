package com.accessible.dialer.ui.blocking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R
import com.accessible.dialer.blocking.BlockedNumbersRepository
import com.accessible.dialer.settings.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlockedNumbersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val entries by BlockedNumbersRepository.entries.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { BlockedNumbersRepository.refresh(context) }

    val backLabel = stringResource(R.string.action_back)
    val title = stringResource(R.string.blocked_numbers_title)
    val addLabel = stringResource(R.string.blocked_numbers_add)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(addLabel) },
                modifier = Modifier.semantics { contentDescription = addLabel },
            )
        },
    ) { inner ->
        LazyColumn(contentPadding = inner, modifier = Modifier.fillMaxSize()) {
            if (entries.isEmpty()) {
                item("empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.blocked_numbers_empty),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    BlockedRow(
                        number = entry.displayNumber,
                        onRemove = {
                            BlockedNumbersRepository.unblock(context, entry.id)
                            rootView.announceForAccessibility(
                                context.getString(
                                    R.string.blocked_numbers_unblocked,
                                    entry.displayNumber,
                                )
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAdd) {
        AddBlockedNumberDialog(
            onDismiss = { showAdd = false },
            onConfirm = { typed ->
                val added = BlockedNumbersRepository.block(context, typed)
                showAdd = false
                rootView.announceForAccessibility(
                    context.getString(
                        if (added) R.string.blocked_numbers_blocked
                        else R.string.blocked_numbers_already_blocked,
                        typed,
                    )
                )
            },
        )
    }
}

@Composable
private fun BlockedRow(number: String, onRemove: () -> Unit) {
    val removeLabel = stringResource(R.string.blocked_numbers_unblock)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(number, style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "$removeLabel $number",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AddBlockedNumberDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blocked_numbers_add)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                label = { Text(stringResource(R.string.blocked_numbers_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.action_block_number)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
