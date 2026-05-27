package com.accessible.dialer.ui.settings

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
import androidx.compose.runtime.LaunchedEffect
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

/**
 * Standalone screen housing accessibility-related toggles (haptic feedback,
 * verbose digit announcements) and the shake-gesture controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccessibilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic by SettingsRepository.haptic.collectAsStateWithLifecycle()
    val verbose by SettingsRepository.verboseDigits.collectAsStateWithLifecycle()
    val shakeCallEnabled by SettingsRepository.shakeToCallEnabled.collectAsStateWithLifecycle()
    val shakeCallNumber by SettingsRepository.shakeToCallNumber.collectAsStateWithLifecycle()
    val shakeAnswer by SettingsRepository.shakeToAnswerEnabled.collectAsStateWithLifecycle()
    val shakeEnd by SettingsRepository.shakeToEndEnabled.collectAsStateWithLifecycle()
    val proximitySpeaker by SettingsRepository.proximitySpeakerEnabled.collectAsStateWithLifecycle()

    var showShakeContactPicker by remember { mutableStateOf(false) }

    val backLabel = stringResource(R.string.action_back)
    val title = stringResource(R.string.settings_section_accessibility)

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
                SettingsSection(stringResource(R.string.settings_section_shake)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_shake_to_call),
                        subtitle = stringResource(R.string.settings_shake_to_call_sub),
                        checked = shakeCallEnabled,
                        onChange = { SettingsRepository.setShakeToCallEnabled(it) },
                    )
                    RowDivider()
                    val shakeContactLabel = remember(shakeCallNumber) {
                        mutableStateOf(shakeCallNumber.ifBlank {
                            context.getString(R.string.settings_shake_to_call_none)
                        })
                    }
                    LaunchedEffect(shakeCallNumber) {
                        if (shakeCallNumber.isNotBlank()) {
                            val name = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.IO
                            ) {
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
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_shake_to_end),
                        subtitle = stringResource(R.string.settings_shake_to_end_sub),
                        checked = shakeEnd,
                        onChange = { SettingsRepository.setShakeToEndEnabled(it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_proximity_speaker),
                        subtitle = stringResource(R.string.settings_proximity_speaker_sub),
                        checked = proximitySpeaker,
                        onChange = { SettingsRepository.setProximitySpeakerEnabled(it) },
                    )
                }
            }
        }
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
