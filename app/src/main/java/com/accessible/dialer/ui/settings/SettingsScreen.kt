package com.accessible.dialer.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.accessible.dialer.util.ContactPorting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.accessible.dialer.BuildConfig
import com.accessible.dialer.R
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.settings.SettingsRepository.SortOrder
import com.accessible.dialer.settings.SettingsRepository.TextScale
import com.accessible.dialer.settings.SettingsRepository.ThemeMode
import com.accessible.dialer.util.PhoneAccounts

@Composable
fun SettingsScreen(
    onOpenDuplicates: () -> Unit = {},
    onOpenNameFix: () -> Unit = {},
    onOpenBlocked: () -> Unit = {},
) {
    val context = LocalContext.current

    val theme by SettingsRepository.theme.collectAsState()
    val textScale by SettingsRepository.textScale.collectAsState()
    val sortOrder by SettingsRepository.sortOrder.collectAsState()
    val showNoPhone by SettingsRepository.showNoPhone.collectAsState()
    val haptic by SettingsRepository.haptic.collectAsState()
    val verbose by SettingsRepository.verboseDigits.collectAsState()
    val savedAccount by SettingsRepository.phoneAccount.collectAsState()
    val quietEnabled by SettingsRepository.quietEnabled.collectAsState()
    val quietStart by SettingsRepository.quietStart.collectAsState()
    val quietEnd by SettingsRepository.quietEnd.collectAsState()
    val quietThreshold by SettingsRepository.quietBreakThreshold.collectAsState()

    val accounts = remember { PhoneAccounts.callable(context) }

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

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item { SectionHeader(stringResource(R.string.settings_section_display)) }
        item {
            RadioGroup(
                title = stringResource(R.string.settings_theme),
                options = ThemeMode.entries.map { it to themeLabel(it) },
                selected = theme,
                onSelect = { SettingsRepository.setTheme(it) },
            )
        }
        item {
            RadioGroup(
                title = stringResource(R.string.settings_text_size),
                options = TextScale.entries.map { it to textScaleLabel(it) },
                selected = textScale,
                onSelect = { SettingsRepository.setTextScale(it) },
            )
        }

        item { SectionHeader(stringResource(R.string.settings_section_contacts)) }
        item {
            RadioGroup(
                title = stringResource(R.string.settings_sort_order),
                options = listOf(
                    SortOrder.FirstName to stringResource(R.string.settings_sort_first),
                    SortOrder.LastName to stringResource(R.string.settings_sort_last),
                ),
                selected = sortOrder,
                onSelect = { SettingsRepository.setSortOrder(it) },
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_show_no_phone),
                checked = showNoPhone,
                onChange = { SettingsRepository.setShowNoPhone(it) },
            )
        }

        item { SectionHeader(stringResource(R.string.settings_section_calling)) }
        if (accounts.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.settings_account_none),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                // Build a single options list: System default + every callable account.
                val systemDefault = stringResource(R.string.settings_account_system_default)
                val accountOptions: List<Pair<SettingsRepository.PhoneAccountRef?, String>> =
                    buildList {
                        add(null to systemDefault)
                        accounts.forEach { acc ->
                            val ref = SettingsRepository.PhoneAccountRef(
                                componentPackage = acc.handle.componentName.packageName,
                                componentClass = acc.handle.componentName.className,
                                id = acc.handle.id,
                            )
                            add(ref to acc.label)
                        }
                    }
                RadioGroup(
                    title = stringResource(R.string.settings_account_label),
                    options = accountOptions,
                    selected = savedAccount,
                    onSelect = { SettingsRepository.setPhoneAccount(it) },
                )
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_accessibility)) }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_haptic),
                subtitle = stringResource(R.string.settings_haptic_sub),
                checked = haptic,
                onChange = { SettingsRepository.setHaptic(it) },
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_verbose_digits),
                subtitle = stringResource(R.string.settings_verbose_digits_sub),
                checked = verbose,
                onChange = { SettingsRepository.setVerboseDigits(it) },
            )
        }

        item { SectionHeader(stringResource(R.string.settings_section_tools)) }
        item {
            NavRow(
                title = stringResource(R.string.settings_find_duplicates),
                subtitle = stringResource(R.string.settings_find_duplicates_sub),
                onClick = onOpenDuplicates,
            )
        }
        item {
            NavRow(
                title = stringResource(R.string.settings_name_fix),
                subtitle = stringResource(R.string.settings_name_fix_sub),
                onClick = onOpenNameFix,
            )
        }
        item {
            NavRow(
                title = stringResource(R.string.settings_blocked_numbers),
                subtitle = stringResource(R.string.settings_blocked_numbers_sub),
                onClick = onOpenBlocked,
            )
        }

        item { SectionHeader(stringResource(R.string.settings_section_quiet)) }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_quiet_enable),
                subtitle = stringResource(R.string.settings_quiet_enable_sub),
                checked = quietEnabled,
                onChange = { SettingsRepository.setQuietEnabled(it) },
            )
        }
        item {
            TimeRow(
                title = stringResource(R.string.settings_quiet_start),
                minute = quietStart,
                onPick = { SettingsRepository.setQuietStart(it) },
                context = context,
            )
        }
        item {
            TimeRow(
                title = stringResource(R.string.settings_quiet_end),
                minute = quietEnd,
                onPick = { SettingsRepository.setQuietEnd(it) },
                context = context,
            )
        }
        item {
            ThresholdRow(
                title = stringResource(R.string.settings_quiet_threshold),
                subtitle = stringResource(R.string.settings_quiet_threshold_sub),
                value = quietThreshold,
            )
        }

        item { SectionHeader(stringResource(R.string.settings_section_porting)) }
        item {
            NavRow(
                title = stringResource(R.string.settings_export_contacts),
                subtitle = stringResource(R.string.settings_export_contacts_sub),
                onClick = { showExportFormat = true },
            )
        }
        item {
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

        item { SectionHeader(stringResource(R.string.settings_section_about)) }
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_about_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@Composable
private fun TimeRow(title: String, minute: Int, onPick: (Int) -> Unit, context: Context) {
    val label = formatTime(minute)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = title,
                role = Role.Button,
                onClick = {
                    val h = minute / 60
                    val m = minute % 60
                    TimePickerDialog(
                        context,
                        { _, hh, mm -> onPick(hh * 60 + mm) },
                        h, m, android.text.format.DateFormat.is24HourFormat(context),
                    ).show()
                },
            )
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .semantics { contentDescription = "$title, $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(minutesSinceMidnight: Int): String {
    val h = minutesSinceMidnight / 60
    val m = minutesSinceMidnight % 60
    return "%02d:%02d".format(h, m)
}

@Composable
private fun ThresholdRow(title: String, subtitle: String, value: Int) {
    val choices = listOf(0, 2, 3, 5, 10)
    var expanded by remember { mutableStateOf(false) }
    val current = if (value == 0) stringResource(R.string.settings_quiet_threshold_value_off)
                  else stringResource(R.string.settings_quiet_threshold_value, value)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = { expanded = true },
                    role = Role.Button,
                    onClickLabel = title,
                )
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .semantics { contentDescription = "$title, $current" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { n ->
                val label = if (n == 0) stringResource(R.string.settings_quiet_threshold_value_off)
                            else stringResource(R.string.settings_quiet_threshold_value, n)
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        SettingsRepository.setQuietBreakThreshold(n)
                        expanded = false
                    },
                    trailingIcon = if (n == value) {
                        { RadioButton(selected = true, onClick = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun FormatPickerDialog(
    onDismiss: () -> Unit,
    onPick: (ContactPorting.Format) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_pick_format)) },
        text = {
            Column {
                ContactPorting.Format.entries.forEach { fmt ->
                    TextButton(
                        onClick = { onPick(fmt) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { role = Role.Button },
                    ) {
                        Text(fmt.displayName, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
    HorizontalDivider()
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = checked,
                onClick = { onChange(!checked) },
                role = Role.Switch,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The whole row is the toggle target; hide the inner Switch from TalkBack so the
        // row's spoken state ("on" / "off") isn't announced twice.
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.semantics { contentDescription = "" },
        )
    }
}

@Composable
private fun <T> RadioGroup(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    // Single-row dropdown picker. The whole row is the trigger; tapping it pops a
    // menu listing every option. Designed for accessibility — the row exposes the
    // current selection in its content description so screen readers announce both
    // the setting name and its current value in one swipe.
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selected }?.second ?: ""
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = { expanded = true },
                    role = Role.Button,
                    onClickLabel = title,
                )
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .semantics { contentDescription = "$title, $currentLabel" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    currentLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                    trailingIcon = if (value == selected) {
                        { RadioButton(selected = true, onClick = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
                role = Role.Button,
                onClickLabel = title,
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> stringResource(R.string.settings_theme_system)
    ThemeMode.Light -> stringResource(R.string.settings_theme_light)
    ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
}

@Composable
private fun textScaleLabel(scale: TextScale): String = when (scale) {
    TextScale.Small -> stringResource(R.string.settings_text_small)
    TextScale.Default -> stringResource(R.string.settings_text_default)
    TextScale.Large -> stringResource(R.string.settings_text_large)
    TextScale.ExtraLarge -> stringResource(R.string.settings_text_xlarge)
}
