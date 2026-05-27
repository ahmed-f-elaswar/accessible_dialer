package com.accessible.dialer.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accessible.dialer.R
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.util.PhoneAccounts

/**
 * Standalone screen housing the per-SIM ringtone overrides. Previously these rows
 * lived inline inside the Calling section of [SettingsScreen]; promoting them to
 * a dedicated screen keeps the main settings list short on multi-SIM devices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RingtonesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val simRingtones by SettingsRepository.simRingtones.collectAsStateWithLifecycle()
    val accounts = remember { PhoneAccounts.callable(context) }

    // Same pattern as SettingsScreen: remember which SIM is asking so the picker
    // result lands under the right key. Null result means the user backed out.
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

    val backLabel = stringResource(R.string.action_back)
    val title = stringResource(R.string.settings_ringtones)

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            if (accounts.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.settings_account_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
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
        }
    }
}
