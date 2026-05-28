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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onOpenDisplay: () -> Unit = {},
    onOpenCalling: () -> Unit = {},
    onOpenAccessibility: () -> Unit = {},
    onOpenBlocking: () -> Unit = {},
    onOpenTools: () -> Unit = {},
    onOpenUserGuide: () -> Unit = {},
) {
    val context = LocalContext.current

    // All grouped settings now live on dedicated sub-screens; this screen is a
    // flat list of NavRows that route into them. Quiet hours moved into
    // BlockingScreen since they act as a time-windowed blocker. Export/import
    // moved into ToolsScreen.

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // One flat card of navigation rows \u2014 no section headers \u2014 because each
        // group now opens its own sub-screen. Keeps the top-level settings list
        // short and scannable.
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    NavRow(
                        title = stringResource(R.string.settings_section_display),
                        subtitle = stringResource(R.string.settings_display_sub),
                        onClick = onOpenDisplay,
                    )
                    RowDivider()
                    NavRow(
                        title = stringResource(R.string.settings_section_calling),
                        subtitle = stringResource(R.string.settings_calling_sub),
                        onClick = onOpenCalling,
                    )
                    RowDivider()
                    NavRow(
                        title = stringResource(R.string.settings_section_accessibility),
                        subtitle = stringResource(R.string.settings_accessibility_sub),
                        onClick = onOpenAccessibility,
                    )
                    RowDivider()
                    NavRow(
                        title = stringResource(R.string.settings_section_block),
                        subtitle = stringResource(R.string.settings_blocking_sub),
                        onClick = onOpenBlocking,
                    )
                    RowDivider()
                    NavRow(
                        title = stringResource(R.string.settings_section_tools),
                        subtitle = stringResource(R.string.settings_tools_sub),
                        onClick = onOpenTools,
                    )
                    RowDivider()
                    NavRow(
                        title = stringResource(R.string.settings_user_guide),
                        subtitle = stringResource(R.string.settings_user_guide_sub),
                        onClick = onOpenUserGuide,
                    )
                    RowDivider()
                    CheckForUpdatesRow()
                }
            }
        }

        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
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
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    // Author attribution. The name + email are merged into a single
                    // TalkBack node so the screen reader announces "Created by Ahmed
                    // Farid" together, followed by the email row as its own item.
                    Text(
                        text = stringResource(
                            R.string.settings_about_author_label,
                        ) + ": " + stringResource(R.string.settings_about_author_name),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val email = stringResource(R.string.settings_about_author_email)
                    val emailAction = stringResource(R.string.settings_about_email_action)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                onClickLabel = emailAction,
                                role = Role.Button,
                            ) {
                                // Fire a mail intent. ACTION_SENDTO with a mailto:
                                // URI is the standard way; any installed mail app
                                // will accept it. Wrapped in runCatching so the
                                // row can't crash the screen if the device has no
                                // email client.
                                runCatching {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_SENDTO,
                                        android.net.Uri.parse("mailto:" + email),
                                    )
                                    context.startActivity(intent)
                                }
                            }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// Modal dialog rendering one row per digit 1-9 with the currently-bound contact name
// (or "None"). Tapping a row triggers [onPick]; the trailing X clears via [onClear].
// We resolve display names lazily per row to avoid blocking the UI thread.
@Composable
internal fun SpeedDialPanelDialog(
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
internal fun TimeRow(title: String, minute: Int, onPick: (Int) -> Unit, context: Context) {
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
internal fun ThresholdRow(title: String, subtitle: String, value: Int) {
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
internal fun FormatPickerDialog(
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
internal fun SettingsSection(
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
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
internal fun SwitchRow(
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
internal fun <T> RadioGroup(
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
internal fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
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

/**
 * Settings → Help → Check for updates row.
 *
 * Pressed on demand by the user; never polls in the background. Drives a
 * coroutine that hits the GitHub Releases API via [com.accessible.dialer.util.UpdateChecker]
 * and shows one of three dialogs depending on the result. The download is
 * delegated to [com.accessible.dialer.util.Updater] which uses
 * [android.app.DownloadManager] under the hood so the file lands directly in
 * Downloads and the system installer takes over — no browser bounce.
 *
 * State is kept inside this composable instead of being hoisted into
 * [com.accessible.dialer.ui.DialerApp] because the dialog is self-contained
 * (no cross-screen data) and the user is on this exact row when the dialog
 * shows, so there's no win to having it survive navigation away.
 */
@Composable
private fun CheckForUpdatesRow() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember {
        mutableStateOf<com.accessible.dialer.util.UpdateChecker.Result?>(null)
    }
    val subtitle = if (checking) {
        stringResource(R.string.update_checking)
    } else {
        stringResource(R.string.settings_check_updates_sub)
    }
    NavRow(
        title = stringResource(R.string.settings_check_updates),
        subtitle = subtitle,
        onClick = {
            if (checking) return@NavRow
            checking = true
            scope.launch {
                val r = com.accessible.dialer.util.UpdateChecker.check()
                checking = false
                result = r
            }
        },
    )
    val current = result
    if (current != null) {
        when (current) {
            is com.accessible.dialer.util.UpdateChecker.Result.UpToDate -> {
                AlertDialog(
                    onDismissRequest = { result = null },
                    title = { Text(stringResource(R.string.update_up_to_date_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.update_up_to_date_message,
                                BuildConfig.VERSION_NAME,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { result = null }) {
                            Text(stringResource(R.string.update_action_ok))
                        }
                    },
                )
            }
            is com.accessible.dialer.util.UpdateChecker.Result.Error -> {
                AlertDialog(
                    onDismissRequest = { result = null },
                    title = { Text(stringResource(R.string.update_check_failed_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.update_check_failed_message,
                                current.message,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { result = null }) {
                            Text(stringResource(R.string.update_action_ok))
                        }
                    },
                )
            }
            is com.accessible.dialer.util.UpdateChecker.Result.UpdateAvailable -> {
                val rel = current.release
                AlertDialog(
                    onDismissRequest = { result = null },
                    title = { Text(stringResource(R.string.update_available_title)) },
                    text = {
                        Column {
                            Text(
                                stringResource(
                                    R.string.update_available_message,
                                    rel.tag,
                                    BuildConfig.VERSION_NAME,
                                ),
                            )
                            if (rel.notes.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    rel.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                result = null
                                com.accessible.dialer.util.Updater.startDownloadAndInstall(
                                    context, rel.apkUrl, rel.version,
                                )
                            },
                        ) { Text(stringResource(R.string.update_action_download)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { result = null }) {
                            Text(stringResource(R.string.update_action_later))
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> stringResource(R.string.settings_theme_system)
    ThemeMode.Light -> stringResource(R.string.settings_theme_light)
    ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
}

@Composable
internal fun textScaleLabel(scale: TextScale): String = when (scale) {
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
internal fun SimRingtonesGroup(
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
internal fun ringtonePickerIntent(title: String, existing: String?): android.content.Intent {
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
