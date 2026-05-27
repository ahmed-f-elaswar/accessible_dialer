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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accessible.dialer.R
import com.accessible.dialer.settings.SettingsRepository

/**
 * Blocking sub-screen: blocked numbers, block-unknown switch, block mode, and
 * the quiet-hours settings (start / end / break-through threshold) that act as
 * a time-windowed blocker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlockingScreen(
    onBack: () -> Unit,
    onOpenBlocked: () -> Unit,
) {
    val context = LocalContext.current
    val blockUnknown by SettingsRepository.blockUnknown.collectAsStateWithLifecycle()
    val blockMode by SettingsRepository.blockMode.collectAsStateWithLifecycle()
    val quietEnabled by SettingsRepository.quietEnabled.collectAsStateWithLifecycle()
    val quietStart by SettingsRepository.quietStart.collectAsStateWithLifecycle()
    val quietEnd by SettingsRepository.quietEnd.collectAsStateWithLifecycle()
    val quietThreshold by SettingsRepository.quietBreakThreshold.collectAsStateWithLifecycle()

    val backLabel = stringResource(R.string.action_back)
    val title = stringResource(R.string.settings_section_block)

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
                SettingsSection(stringResource(R.string.settings_section_block)) {
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
        }
    }
}
