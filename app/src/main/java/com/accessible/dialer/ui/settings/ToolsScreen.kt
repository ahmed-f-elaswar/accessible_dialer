package com.accessible.dialer.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R
import com.accessible.dialer.util.ContactPorting

/**
 * Standalone screen grouping contact-management tools (find duplicates, storage
 * locations, name fix, name normalize, export/import). Each row dispatches to
 * its own full-screen sub-flow hosted by `DialerApp` (or, for export/import,
 * fires a system file picker right here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolsScreen(
    onBack: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenNameFix: () -> Unit,
    onOpenNameNormalize: () -> Unit,
) {
    val context = LocalContext.current
    val backLabel = stringResource(R.string.action_back)
    val title = stringResource(R.string.settings_section_tools)

    // Export / import launchers. Picking a format remembers it so the CreateDocument
    // callback knows which writer to invoke.
    var pendingExport by remember { mutableStateOf<ContactPorting.Format?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val fmt = pendingExport
        pendingExport = null
        if (uri != null && fmt != null) {
            val n = ContactPorting.export(context, uri, fmt)
            val msg = when {
                n < 0 -> context.getString(R.string.settings_export_failed)
                n == 0 -> context.getString(R.string.settings_export_empty)
                else -> context.getString(R.string.settings_export_done, n)
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching { context.startActivity(ContactPorting.importVCardIntent(uri)) }
                .onFailure {
                    Toast.makeText(context, R.string.settings_import_failed, Toast.LENGTH_LONG).show()
                }
        }
    }
    var showExportFormat by remember { mutableStateOf(false) }

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
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsSection(stringResource(R.string.settings_section_tools)) {
                    NavRow(
                        title = stringResource(R.string.settings_find_duplicates),
                        subtitle = stringResource(R.string.settings_find_duplicates_sub),
                        onClick = onOpenDuplicates,
                    )
                    RowDivider()
                    NavRow(
                        title = stringResource(R.string.settings_storage_locations),
                        subtitle = stringResource(R.string.settings_storage_locations_sub),
                        onClick = onOpenStorage,
                    )
                    RowDivider()
                    NavRow(
                        title = stringResource(R.string.settings_name_fix),
                        subtitle = stringResource(R.string.settings_name_fix_sub),
                        onClick = onOpenNameFix,
                    )
                    RowDivider()
                    NavRow(
                        title = stringResource(R.string.settings_name_normalize),
                        subtitle = stringResource(R.string.settings_name_normalize_sub),
                        onClick = onOpenNameNormalize,
                    )
                    RowDivider()
                    NavRow(
                        title = stringResource(R.string.settings_export_contacts),
                        subtitle = stringResource(R.string.settings_export_contacts_sub),
                        onClick = { showExportFormat = true },
                    )
                    RowDivider()
                    NavRow(
                        title = stringResource(R.string.settings_import_contacts),
                        subtitle = stringResource(R.string.settings_import_contacts_sub),
                        onClick = {
                            // .vcf MIME varies between text/x-vcard and text/vcard depending on the
                            // file. Accept both, and also fall back to */* so users can pick a file
                            // that lacks the right extension.
                            importLauncher.launch(arrayOf("text/x-vcard", "text/vcard", "*/*"))
                        },
                    )
                }
            }
        }
    }

    if (showExportFormat) {
        FormatPickerDialog(
            onDismiss = { showExportFormat = false },
            onPick = { fmt ->
                showExportFormat = false
                pendingExport = fmt
                val suggested = "contacts." + fmt.extension
                exportLauncher.launch(suggested)
            },
        )
    }
}
