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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accessible.dialer.R
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.settings.SettingsRepository.SortOrder
import com.accessible.dialer.settings.SettingsRepository.TextScale
import com.accessible.dialer.settings.SettingsRepository.ThemeMode

/**
 * Standalone screen for theme / text size / contact-list presentation knobs.
 * Promoted out of [SettingsScreen] so the main list opens with navigation rows
 * instead of dense radio groups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DisplayScreen(onBack: () -> Unit) {
    val theme by SettingsRepository.theme.collectAsStateWithLifecycle()
    val textScale by SettingsRepository.textScale.collectAsStateWithLifecycle()
    val sortOrder by SettingsRepository.sortOrder.collectAsStateWithLifecycle()
    val showNoPhone by SettingsRepository.showNoPhone.collectAsStateWithLifecycle()

    val backLabel = stringResource(R.string.action_back)
    val title = stringResource(R.string.settings_section_display)

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
        }
    }
}
