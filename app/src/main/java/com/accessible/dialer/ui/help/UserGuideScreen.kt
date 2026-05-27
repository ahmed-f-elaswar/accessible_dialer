package com.accessible.dialer.ui.help

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R

/**
 * Full-screen user guide opened from Settings → Help.
 *
 * Content is a flat list of titled cards covering every major feature so a
 * first-time user can answer "what is this screen for?" without leaving the
 * app. Each section's title and body live in strings.xml so all five locales
 * stay in sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserGuideScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val backLabel = stringResource(R.string.action_back)

    val sections = listOf(
        R.string.user_guide_section_getting_started to R.string.user_guide_body_getting_started,
        R.string.user_guide_section_dialpad to R.string.user_guide_body_dialpad,
        R.string.user_guide_section_favorites to R.string.user_guide_body_favorites,
        R.string.user_guide_section_contacts to R.string.user_guide_body_contacts,
        R.string.user_guide_section_recents to R.string.user_guide_body_recents,
        R.string.user_guide_section_in_call to R.string.user_guide_body_in_call,
        R.string.user_guide_section_blocking to R.string.user_guide_body_blocking,
        R.string.user_guide_section_duplicates to R.string.user_guide_body_duplicates,
        R.string.user_guide_section_storage to R.string.user_guide_body_storage,
        R.string.user_guide_section_name_tools to R.string.user_guide_body_name_tools,
        R.string.user_guide_section_import_export to R.string.user_guide_body_import_export,
        R.string.user_guide_section_accessibility to R.string.user_guide_body_accessibility,
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.user_guide_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backLabel,
                        )
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.user_guide_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(sections) { (titleRes, bodyRes) ->
                GuideCard(
                    title = stringResource(titleRes),
                    body = stringResource(bodyRes),
                )
            }
        }
    }
}

@Composable
private fun GuideCard(title: String, body: String) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
