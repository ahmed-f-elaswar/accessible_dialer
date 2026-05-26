package com.accessible.dialer.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.media.AudioManager
import android.telecom.Call
import com.accessible.dialer.R

/**
 * UI for an active call. Two key accessibility considerations:
 *  - Large, unambiguous primary controls (answer / hangup) sized at minimum 96dp circular.
 *  - Status text is wrapped in a live region so TalkBack announces transitions
 *    (ringing -> active -> ended) automatically.
 */
@Composable
fun InCallScreen(onClose: () -> Unit, onAddCall: () -> Unit = {}) {
    val state by OngoingCallHolder.state.collectAsState()
    val context = LocalContext.current
    val audio = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }

    // Close the activity when the call goes away.
    LaunchedEffect(state) {
        if (state is CallState.None) onClose()
    }

    val active = state as? CallState.Active
    val statusText = when (active?.telecomState) {
        Call.STATE_RINGING -> stringResource(R.string.call_incoming)
        Call.STATE_DIALING, Call.STATE_CONNECTING -> stringResource(R.string.call_outgoing)
        Call.STATE_ACTIVE -> stringResource(R.string.call_active)
        Call.STATE_HOLDING -> stringResource(R.string.call_hold)
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> stringResource(R.string.call_ended)
        else -> ""
    }
    val number = active?.number?.takeIf { it.isNotBlank() } ?: stringResource(R.string.call_unknown)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = number,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            // Mid-call controls visible only when call is connected.
            if (active?.telecomState == Call.STATE_ACTIVE || active?.telecomState == Call.STATE_HOLDING) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ToggleControl(
                        on = muted,
                        onLabel = stringResource(R.string.call_unmute),
                        offLabel = stringResource(R.string.call_mute),
                        iconOn = Icons.Filled.MicOff,
                        iconOff = Icons.Filled.Mic,
                        onToggle = {
                            muted = !muted
                            audio.isMicrophoneMute = muted
                        },
                    )
                    ToggleControl(
                        on = speaker,
                        onLabel = stringResource(R.string.call_speaker),
                        offLabel = stringResource(R.string.call_speaker),
                        iconOn = Icons.Filled.VolumeUp,
                        iconOff = Icons.Filled.VolumeUp,
                        onToggle = {
                            speaker = !speaker
                            audio.isSpeakerphoneOn = speaker
                        },
                    )
                    val held = active.telecomState == Call.STATE_HOLDING
                    ToggleControl(
                        on = held,
                        onLabel = stringResource(R.string.call_resume),
                        offLabel = stringResource(R.string.call_hold),
                        iconOn = Icons.Filled.PlayArrow,
                        iconOff = Icons.Filled.Pause,
                        onToggle = {
                            if (held) OngoingCallHolder.resume() else OngoingCallHolder.hold()
                        },
                    )
                    ActionControl(
                        label = stringResource(R.string.call_add),
                        icon = Icons.Filled.PersonAdd,
                        onClick = {
                            // Park the current call before opening the dialpad for the next one.
                            if (!held) OngoingCallHolder.hold()
                            onAddCall()
                        },
                    )
                }
            } else {
                Spacer(Modifier.height(0.dp))
            }

            // Primary action row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (active?.telecomState) {
                    Call.STATE_RINGING -> {
                        BigCircleButton(
                            color = MaterialTheme.colorScheme.error,
                            contentDescription = stringResource(R.string.call_decline),
                            icon = Icons.Filled.CallEnd,
                            onClick = { OngoingCallHolder.reject() },
                        )
                        BigCircleButton(
                            color = MaterialTheme.colorScheme.secondary,
                            contentDescription = stringResource(R.string.call_answer),
                            icon = Icons.Filled.Call,
                            onClick = { OngoingCallHolder.answer() },
                        )
                    }
                    else -> {
                        BigCircleButton(
                            color = MaterialTheme.colorScheme.error,
                            contentDescription = stringResource(R.string.call_hangup),
                            icon = Icons.Filled.CallEnd,
                            onClick = { OngoingCallHolder.hangup() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BigCircleButton(
    color: Color,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    // Single clickable surface with the label on the icon. Avoids a Box-wrapping-IconButton
    // sandwich that produces two focusable nodes (one unlabeled) for TalkBack.
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(color),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun ToggleControl(
    on: Boolean,
    onLabel: String,
    offLabel: String,
    iconOn: androidx.compose.ui.graphics.vector.ImageVector,
    iconOff: androidx.compose.ui.graphics.vector.ImageVector,
    onToggle: () -> Unit,
) {
    val label = if (on) onLabel else offLabel
    // Single TalkBack focusable: the IconButton announces `label` once. The visible Text
    // below is decorative (sighted users only) and is removed from the a11y tree, so the
    // reader does not say the label twice.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    if (on) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface
                )
                .semantics {
                    contentDescription = label
                    role = Role.Button
                },
        ) {
            Icon(
                imageVector = if (on) iconOn else iconOff,
                contentDescription = null,
                tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * Non-toggling mid-call action (e.g. "Add call"). Same visual & accessibility shape as
 * [ToggleControl] so the row stays consistent: one focusable, labeled once.
 */
@Composable
private fun ActionControl(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
