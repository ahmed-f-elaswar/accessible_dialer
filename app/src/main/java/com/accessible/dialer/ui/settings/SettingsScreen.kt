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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.accessible.dialer.util.ContactPorting
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onOpenStorage: () -> Unit = {},
    onOpenNameFix: () -> Unit = {},
    onOpenNameNormalize: () -> Unit = {},
    onOpenBlocked: () -> Unit = {},
    onOpenUserGuide: () -> Unit = {},
) {
    val context = LocalContext.current

    val theme by SettingsRepository.theme.collectAsStateWithLifecycle()
    val textScale by SettingsRepository.textScale.collectAsStateWithLifecycle()
    val sortOrder by SettingsRepository.sortOrder.collectAsStateWithLifecycle()
    val showNoPhone by SettingsRepository.showNoPhone.collectAsStateWithLifecycle()
    val haptic by SettingsRepository.haptic.collectAsStateWithLifecycle()
    val verbose by SettingsRepository.verboseDigits.collectAsStateWithLifecycle()
    val savedAccount by SettingsRepository.phoneAccount.collectAsStateWithLifecycle()
    val quietEnabled by SettingsRepository.quietEnabled.collectAsStateWithLifecycle()
    val quietStart by SettingsRepository.quietStart.collectAsStateWithLifecycle()
    val quietEnd by SettingsRepository.quietEnd.collectAsStateWithLifecycle()
    val quietThreshold by SettingsRepository.quietBreakThreshold.collectAsStateWithLifecycle()
    val blockUnknown by SettingsRepository.blockUnknown.collectAsStateWithLifecycle()
    val blockMode by SettingsRepository.blockMode.collectAsStateWithLifecycle()
    val simRingtones by SettingsRepository.simRingtones.collectAsStateWithLifecycle()

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

    // Ringtone picker. We remember which SIM (PhoneAccountHandle id) is asking so the
    // result can be persisted under the right key. The system intent returns the URI
    // in EXTRA_RINGTONE_PICKED_URI; null means the user picked "Silent".
    var pendingRingtoneSimId by remember { mutableStateOf<String?>(null) }
    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val simId = pendingRingtoneSimId
        pendingRingtoneSimId = null
        if (simId != null && result.resultCode == android.app.Activity.RESULT_OK) {
            val picked: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                result.data?.getParcelableExtra(
                    android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    android.net.Uri::class.java,
                )
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            SettingsRepository.setSimRingtone(simId, picked?.toString().orEmpty())
        }
    }

    // Speed-dial state.
    val speedDial by SettingsRepository.speedDial.collectAsStateWithLifecycle()
    // Whether the per-digit list overlay is showing.
    var showSpeedDialPanel by remember { mutableStateOf(false) }
    // Digit awaiting contact selection (1..9); non-null \u2192 contact picker open.
    var speedDialPicking by remember { mutableStateOf<Int?>(null) }

    // Shake-gesture state.
    val shakeCallEnabled by SettingsRepository.shakeToCallEnabled.collectAsStateWithLifecycle()
    val shakeCallNumber by SettingsRepository.shakeToCallNumber.collectAsStateWithLifecycle()
    val shakeAnswer by SettingsRepository.shakeToAnswerEnabled.collectAsStateWithLifecycle()
    // True while the shake-to-call contact picker is open.
    var showShakeContactPicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsSection(stringResource(R.string.settings_section_display)) {
                RadioGroup(
                    title = stringResource(R.string.settings_theme),
                    options = ThemeMode.entries.map { it to themeLabel(it) },
                    selected = theme,
                    onSelect = { SettingsRepository.setTheme(it) },
                )
                RowDivider()
                RadioGroup(
                    title = stringResource(R.string.settings_text_size),
                    options = TextScale.entries.map { it to textScaleLabel(it) },
                    selected = textScale,
                    onSelect = { SettingsRepository.setTextScale(it) },
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_contacts)) {
                RadioGroup(
                    title = stringResource(R.string.settings_sort_order),
                    options = listOf(
                        SortOrder.FirstName to stringResource(R.string.settings_sort_first),
                        SortOrder.LastName to stringResource(R.string.settings_sort_last),
                    ),
                    selected = sortOrder,
                    onSelect = { SettingsRepository.setSortOrder(it) },
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_show_no_phone),
                    checked = showNoPhone,
                    onChange = { SettingsRepository.setShowNoPhone(it) },
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_calling)) {
                if (accounts.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_account_none),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
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
                    // Per-SIM ringtone rows: one NavRow per calling account. Tapping
                    // opens the system ringtone picker pre-selecting the currently-
                    // stored override (or the system default if none). We only show
                    // this group when at least one callable account exists.
                    if (accounts.isNotEmpty()) {
                        RowDivider()
                        SimRingtonesGroup(
                            accounts = accounts,
                            simRingtones = simRingtones,
                            onPick = { acc ->
                                pendingRingtoneSimId = acc.handle.id
                                ringtoneLauncher.launch(
                                    ringtonePickerIntent(
                                        title = context.getString(R.string.settings_sim_ringtone_chooser_title),
                                        existing = simRingtones[acc.handle.id],
                                    )
                                )
                            },
                            onClear = { acc ->
                                SettingsRepository.clearSimRingtone(acc.handle.id)
                            },
                        )
                    }
                    RowDivider()
                    // Speed-dial entrypoint: opens an overlay sub-screen with one row
                    // per digit (1-9). Listed in Calling because the long-press fires
                    // a call \u2014 sits alongside default-account / ringtones.
                    NavRow(
                        title = stringResource(R.string.settings_speed_dial),
                        subtitle = stringResource(R.string.settings_speed_dial_sub),
                        onClick = { showSpeedDialPanel = true },
                    )
                }
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_accessibility)) {
                SwitchRow(
                    title = stringResource(R.string.settings_haptic),
                    subtitle = stringResource(R.string.settings_haptic_sub),
                    checked = haptic,
                    onChange = { SettingsRepository.setHaptic(it) },
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_verbose_digits),
                    subtitle = stringResource(R.string.settings_verbose_digits_sub),
                    checked = verbose,
                    onChange = { SettingsRepository.setVerboseDigits(it) },
                )
            }
        }

        item {
            // Shake gestures. Two independent toggles plus a contact picker that's
            // only relevant when shake-to-call is enabled (still always visible so
            // users can pre-configure it).
            SettingsSection(stringResource(R.string.settings_section_shake)) {
                SwitchRow(
                    title = stringResource(R.string.settings_shake_to_call),
                    subtitle = stringResource(R.string.settings_shake_to_call_sub),
                    checked = shakeCallEnabled,
                    onChange = { SettingsRepository.setShakeToCallEnabled(it) },
                )
                RowDivider()
                // Resolves the stored number to a display name on demand so the row
                // shows e.g. "Mom" instead of a raw E.164 string.
                val shakeContactLabel = remember(shakeCallNumber) {
                    mutableStateOf(shakeCallNumber.ifBlank {
                        context.getString(R.string.settings_shake_to_call_none)
                    })
                }
                androidx.compose.runtime.LaunchedEffect(shakeCallNumber) {
                    if (shakeCallNumber.isNotBlank()) {
                        val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.accessible.dialer.util.RowActions.lookupContactName(
                                context, shakeCallNumber
                            )
                        }
                        shakeContactLabel.value = name?.let { "$it \u2013 $shakeCallNumber" }
                            ?: shakeCallNumber
                    }
                }
                NavRow(
                    title = stringResource(R.string.settings_shake_to_call_pick),
                    subtitle = shakeContactLabel.value,
                    onClick = { showShakeContactPicker = true },
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_shake_to_answer),
                    subtitle = stringResource(R.string.settings_shake_to_answer_sub),
                    checked = shakeAnswer,
                    onChange = { SettingsRepository.setShakeToAnswerEnabled(it) },
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_tools)) {
                NavRow(
                    title = stringResource(R.string.settings_find_duplicates),
                    subtitle = stringResource(R.string.settings_find_duplicates_sub),
                    onClick = onOpenDuplicates,
                )
                RowDivider()
                NavRow(
                    // Storage locations: enumerate accounts that own contacts and
                    // allow bulk delete / move between accounts.
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
                    title = stringResource(R.string.settings_blocked_numbers),
                    subtitle = stringResource(R.string.settings_blocked_numbers_sub),
                    onClick = onOpenBlocked,
                )
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_block_unknown),
                    subtitle = stringResource(R.string.settings_block_unknown_sub),
                    checked = blockUnknown,
                    onChange = { SettingsRepository.setBlockUnknown(it) },
                )
                RowDivider()
                RadioGroup(
                    title = stringResource(R.string.settings_block_mode_label),
                    options = listOf(
                        SettingsRepository.BlockMode.Reject
                            to stringResource(R.string.settings_block_mode_reject),
                        SettingsRepository.BlockMode.SilentRing
                            to stringResource(R.string.settings_block_mode_silent),
                    ),
                    selected = blockMode,
                    onSelect = { SettingsRepository.setBlockMode(it) },
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_quiet)) {
                SwitchRow(
                    title = stringResource(R.string.settings_quiet_enable),
                    subtitle = stringResource(R.string.settings_quiet_enable_sub),
                    checked = quietEnabled,
                    onChange = { SettingsRepository.setQuietEnabled(it) },
                )
                RowDivider()
                TimeRow(
                    title = stringResource(R.string.settings_quiet_start),
                    minute = quietStart,
                    onPick = { SettingsRepository.setQuietStart(it) },
                    context = context,
                )
                RowDivider()
                TimeRow(
                    title = stringResource(R.string.settings_quiet_end),
                    minute = quietEnd,
                    onPick = { SettingsRepository.setQuietEnd(it) },
                    context = context,
                )
                RowDivider()
                ThresholdRow(
                    title = stringResource(R.string.settings_quiet_threshold),
                    subtitle = stringResource(R.string.settings_quiet_threshold_sub),
                    value = quietThreshold,
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_porting)) {
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

        item {
            SettingsSection(stringResource(R.string.settings_section_help)) {
                NavRow(
                    title = stringResource(R.string.settings_user_guide),
                    subtitle = stringResource(R.string.settings_user_guide_sub),
                    onClick = onOpenUserGuide,
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_about)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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

    // Speed-dial overlay. Listing 1-9 as plain rows lets TalkBack announce them one
    // at a time; tapping a row opens the contact picker (or clears the binding via
    // a trailing icon when one exists).
    if (showSpeedDialPanel) {
        SpeedDialPanelDialog(
            speedDial = speedDial,
            onDismiss = { showSpeedDialPanel = false },
            onPick = { digit -> speedDialPicking = digit },
            onClear = { digit -> SettingsRepository.clearSpeedDial(digit) },
        )
    }
    speedDialPicking?.let { digit ->
        com.accessible.dialer.ui.contacts.ContactPickerDialog(
            title = stringResource(R.string.settings_speed_dial_picker_title, digit),
            onDismiss = { speedDialPicking = null },
            onPick = { contact ->
                // ContactPickerDialog already filters phoneless contacts so the
                // displayed number is non-blank.
                val number = contact.number
                if (number.isNotBlank()) SettingsRepository.setSpeedDial(digit, number)
                speedDialPicking = null
            },
            viewModelKey = "speed_dial_picker_$digit",
        )
    }
    if (showShakeContactPicker) {
        com.accessible.dialer.ui.contacts.ContactPickerDialog(
            title = stringResource(R.string.settings_shake_to_call_picker_title),
            onDismiss = { showShakeContactPicker = false },
            onPick = { contact ->
                val number = contact.number
                if (number.isNotBlank()) SettingsRepository.setShakeToCallNumber(number)
                showShakeContactPicker = false
            },
            viewModelKey = "shake_call_picker",
        )
    }
}

// Modal dialog rendering one row per digit 1-9 with the currently-bound contact name
// (or "None"). Tapping a row triggers [onPick]; the trailing X clears via [onClear].
// We resolve display names lazily per row to avoid blocking the UI thread.
@Composable
private fun SpeedDialPanelDialog(
    speedDial: Map<Int, String>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onClear: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_speed_dial)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                for (digit in 1..9) {
                    val bound = speedDial[digit].orEmpty()
                    SpeedDialDigitRow(
                        digit = digit,
                        boundNumber = bound,
                        onClick = { onPick(digit) },
                        onClear = { onClear(digit) },
                    )
                    if (digit < 9) RowDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SpeedDialDigitRow(
    digit: Int,
    boundNumber: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    // Resolve the display name async; falls back to the raw number while loading.
    val label = remember(boundNumber) {
        mutableStateOf(
            if (boundNumber.isBlank()) context.getString(R.string.settings_speed_dial_slot_empty) else boundNumber
        )
    }
    androidx.compose.runtime.LaunchedEffect(boundNumber) {
        if (boundNumber.isNotBlank()) {
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.accessible.dialer.util.RowActions.lookupContactName(context, boundNumber)
            }
            label.value = name?.let { "$it \u2013 $boundNumber" } ?: boundNumber
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.settings_speed_dial_picker_title, digit),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = digit.toString(),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(end = 16.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_speed_dial_slot_label, digit),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                label.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (boundNumber.isNotBlank()) {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.settings_speed_dial_clear))
            }
        }
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
            .padding(horizontal = 16.dp, vertical = 14.dp)
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
                .padding(horizontal = 16.dp, vertical = 14.dp)
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
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                .padding(horizontal = 16.dp, vertical = 14.dp)
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * Renders one [NavRow] per calling account so the user can pick a ringtone for
 * each SIM. Trailing chevron is the standard "opens picker" affordance. When an
 * override is already set, a second tap-action ("Use system default") clears it.
 */
@Composable
private fun SimRingtonesGroup(
    accounts: List<PhoneAccounts.Account>,
    simRingtones: Map<String, String>,
    onPick: (PhoneAccounts.Account) -> Unit,
    onClear: (PhoneAccounts.Account) -> Unit,
) {
    val context = LocalContext.current
    accounts.forEachIndexed { index, acc ->
        if (index > 0) RowDivider()
        val title = stringResource(R.string.settings_sim_ringtone_for, acc.label)
        val storedUri = simRingtones[acc.handle.id]
        val subtitle = remember(storedUri) {
            if (storedUri.isNullOrBlank()) {
                context.getString(R.string.settings_sim_ringtone_default)
            } else {
                runCatching {
                    android.media.RingtoneManager
                        .getRingtone(context, android.net.Uri.parse(storedUri))
                        ?.getTitle(context)
                }.getOrNull()
                    ?: context.getString(R.string.settings_sim_ringtone_default)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = { onPick(acc) },
                    role = Role.Button,
                    onClickLabel = title,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!storedUri.isNullOrBlank()) {
                val clearLabel = stringResource(R.string.settings_sim_ringtone_clear)
                TextButton(onClick = { onClear(acc) }) { Text(clearLabel) }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Builds the system ringtone picker intent. Pre-selects [existing] when non-null
 * so the dialog opens highlighting the user's current choice; otherwise no item
 * is pre-selected. We intentionally do NOT include `EXTRA_RINGTONE_INCLUDE_DRM`
 * (deprecated/unused on modern Android) or `EXTRA_RINGTONE_SHOW_SILENT` — picking
 * "Silent" returns a null URI which our launcher persists as an empty string,
 * which our resolver treats as "fall back to system default" (i.e. silent here
 * would be lost). We could expose that later as an explicit option.
 */
private fun ringtonePickerIntent(title: String, existing: String?): android.content.Intent {
    return android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, title)
        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        if (!existing.isNullOrBlank()) {
            putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                android.net.Uri.parse(existing),
            )
        }
    }
}
