package com.accessible.dialer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accessible.dialer.R
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.util.PhoneAccounts

/**
 * Standalone screen for outgoing-call account selection and speed-dial bindings.
 * Ringtones live on their own sub-screen but are linked from here too so users
 * find them under "Calling".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallingScreen(
    onBack: () -> Unit,
    onOpenRingtones: () -> Unit,
) {
    val context = LocalContext.current
    val accounts = remember { PhoneAccounts.callable(context) }
    val savedAccount by SettingsRepository.phoneAccount.collectAsStateWithLifecycle()
    val speedDial by SettingsRepository.speedDial.collectAsStateWithLifecycle()

    var showSpeedDialPanel by remember { mutableStateOf(false) }
    var speedDialPicking by remember { mutableStateOf<Int?>(null) }

    val backLabel = stringResource(R.string.action_back)
    val title = stringResource(R.string.settings_section_calling)

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
                SettingsSection(stringResource(R.string.settings_section_calling)) {
                    if (accounts.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_account_none),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val systemDefault =
                            stringResource(R.string.settings_account_system_default)
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
                        RowDivider()
                        NavRow(
                            title = stringResource(R.string.settings_ringtones),
                            subtitle = stringResource(R.string.settings_ringtones_sub),
                            onClick = onOpenRingtones,
                        )
                        RowDivider()
                        NavRow(
                            title = stringResource(R.string.settings_speed_dial),
                            subtitle = stringResource(R.string.settings_speed_dial_sub),
                            onClick = { showSpeedDialPanel = true },
                        )
                    }
                }
            }
        }
    }

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
                val number = contact.number
                if (number.isNotBlank()) SettingsRepository.setSpeedDial(digit, number)
                speedDialPicking = null
            },
            viewModelKey = "speed_dial_picker_$digit",
        )
    }
}
