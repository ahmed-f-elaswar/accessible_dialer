package com.accessible.dialer.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import com.accessible.dialer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.os.SystemClock

/**
 * UI for an active call. Two key accessibility considerations:
 *  - Large, unambiguous primary controls (answer / hangup) sized at minimum 96dp circular.
 *  - Status text is wrapped in a live region so TalkBack announces transitions
 *    (ringing -> active -> ended) automatically.
 */
@Composable
fun InCallScreen(onClose: () -> Unit, onAddCall: () -> Unit = {}) {
    val state by OngoingCallHolder.state.collectAsStateWithLifecycle()
    val audioState by OngoingCallHolder.audio.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val muted = audioState.muted
    val speaker = audioState.speaker

    // Close the activity when the call goes away.
    LaunchedEffect(state) {
        if (state is CallState.None) onClose()
    }

    val active = state as? CallState.Active
    // Whether the in-call DTMF keypad overlay is showing.
    var showKeypad by remember { mutableStateOf(false) }
    // Whether the "reply with message" picker is showing (only meaningful on RINGING).
    var showReplyPicker by remember { mutableStateOf(false) }
    // Localized strings for the live-region announcement that fires after a swipe
    // gesture toggles speaker or mute. We resolve them outside the pointer-input
    // lambda because stringResource is only callable from a @Composable scope.
    val speakerOnLabel = stringResource(R.string.call_speaker_on)
    val speakerOffLabel = stringResource(R.string.call_speaker_off)
    val muteOnLabel = stringResource(R.string.call_mute_on)
    val muteOffLabel = stringResource(R.string.call_mute_off)
    // Speak swipe-toggle feedback to TalkBack directly via the platform view, so no
    // on-screen element is needed.
    val rootView = LocalView.current
    fun announce(text: String) {
        rootView.announceForAccessibility(text)
    }
    // Swipe up = toggle speakerphone, swipe down = toggle mute. Each swipe past the
    // threshold flips the state once and resets the accumulator, so a single long drag
    // can flip a control multiple times ("repeating switches") and discrete swipes each
    // produce one toggle. Only enabled while the call is connected, since those are
    // the only states where mute/speaker have any effect.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 64.dp.toPx() }
    var dragAccum by remember { mutableStateOf(0f) }
    val gestureEnabled = active?.telecomState == Call.STATE_ACTIVE ||
        active?.telecomState == Call.STATE_HOLDING
    val swipeModifier = if (gestureEnabled) {
        Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { dragAccum = 0f },
                onDragEnd = { dragAccum = 0f },
                onDragCancel = { dragAccum = 0f },
            ) { _, dy ->
                dragAccum += dy
                if (dragAccum <= -swipeThresholdPx) {
                    // Read the *current* mirrored state from the holder so repeated swipes
                    // within one drag toggle from the latest known route, not from a stale
                    // captured boolean.
                    val newOn = !OngoingCallHolder.audio.value.speaker
                    OngoingCallHolder.setSpeaker(newOn)
                    announce(if (newOn) speakerOnLabel else speakerOffLabel)
                    dragAccum = 0f
                } else if (dragAccum >= swipeThresholdPx) {
                    val newMuted = !OngoingCallHolder.audio.value.muted
                    OngoingCallHolder.setMuted(newMuted)
                    announce(if (newMuted) muteOnLabel else muteOffLabel)
                    dragAccum = 0f
                }
            }
        }
    } else Modifier
    val statusText = ""
    val number = active?.number?.takeIf { it.isNotBlank() } ?: stringResource(R.string.call_unknown)
    // Resolve a contact display name for the active number (PhoneLookup is the cheap
    // E.164-tolerant index). Falls back to the bare number when nothing matches or the
    // number is unknown. Looked up off-thread so the screen can render immediately.
    val rawNumber = active?.number
    val contactName by produceState<String?>(initialValue = null, rawNumber) {
        val n = rawNumber
        value = if (n.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(n),
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
                }
            }.getOrNull()
        }
    }
    // Tick once per second so the elapsed duration updates while the call is active.
    // Only ticks while we have a connect time and the call isn't terminating.
    val connectTime = active?.connectTimeMillis
    val isLive = active?.telecomState == Call.STATE_ACTIVE ||
        active?.telecomState == Call.STATE_HOLDING
    val elapsedMs by produceState(initialValue = 0L, connectTime, isLive) {
        if (connectTime == null) {
            value = 0L
            return@produceState
        }
        while (true) {
            value = System.currentTimeMillis() - connectTime
            if (!isLive) break
            delay(1000)
        }
    }
    val durationText = if (connectTime != null && elapsedMs > 0) {
        stringResource(R.string.call_duration, formatDuration(elapsedMs))
    } else ""

    Surface(modifier = Modifier.fillMaxSize().then(swipeModifier), color = MaterialTheme.colorScheme.background) {
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
                // App name and call-state status ("Active call", etc.) intentionally
                // omitted -- the contact name, number, and live duration are sufficient
                // and reduce visual + screen-reader noise.
                contactName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = number,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                active?.accountLabel?.takeIf { it.isNotBlank() }?.let { acct ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.call_via_account, acct),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (durationText.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = durationText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
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
                            val next = !muted
                            OngoingCallHolder.setMuted(next)
                            announce(if (next) muteOnLabel else muteOffLabel)
                        },
                    )
                    ToggleControl(
                        on = speaker,
                        onLabel = stringResource(R.string.call_speaker),
                        offLabel = stringResource(R.string.call_speaker),
                        iconOn = Icons.Filled.VolumeUp,
                        iconOff = Icons.Filled.VolumeUp,
                        onToggle = {
                            val next = !speaker
                            OngoingCallHolder.setSpeaker(next)
                            announce(if (next) speakerOnLabel else speakerOffLabel)
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
                    ActionControl(
                        label = stringResource(R.string.call_keypad),
                        icon = Icons.Filled.Dialpad,
                        onClick = { showKeypad = true },
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
                            color = MaterialTheme.colorScheme.tertiary,
                            contentDescription = stringResource(R.string.call_reply_message),
                            icon = Icons.AutoMirrored.Filled.Message,
                            onClick = { showReplyPicker = true },
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

    if (showKeypad) {
        InCallKeypad(onClose = { showKeypad = false })
    }

    if (showReplyPicker) {
        ReplyWithMessageDialog(
            onDismiss = { showReplyPicker = false },
            onSend = { msg ->
                showReplyPicker = false
                OngoingCallHolder.rejectWithMessage(msg)
            },
        )
    }
}

// Formats elapsed milliseconds as "M:SS" (or "H:MM:SS" past one hour). Used for the
// in-call duration display.
private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val s = total % 60
    val m = (total / 60) % 60
    val h = total / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun BigCircleButton(
    color: Color,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    // Single clickable surface: a Box with `clickable(role = Role.Button)` produces one
    // TalkBack focusable that announces the label and is activatable with double-tap.
    // Earlier we used an IconButton inside a styled Box, which on some TalkBack builds
    // produced an unlabeled focusable for the IconButton and a non-actionable label for
    // the Box — i.e. the user could hear "Answer" but the button wouldn't activate.
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(
                onClick = onClick,
                onClickLabel = contentDescription,
                role = Role.Button,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
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

/**
 * Full-screen DTMF keypad shown over the in-call screen. Each digit sends a DTMF tone
 * to the carrier via [OngoingCallHolder.playDtmf]; the tone is stopped on release so
 * the IVR sees a tap rather than a held key.
 */
@Composable
private fun InCallKeypad(onClose: () -> Unit) {
    val closeLabel = stringResource(R.string.call_keypad_close)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.call_keypad),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.semantics {
                    contentDescription = closeLabel
                    role = Role.Button
                }) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
            Spacer(Modifier.height(8.dp))
            val rows = listOf(
                listOf('1', '2', '3'),
                listOf('4', '5', '6'),
                listOf('7', '8', '9'),
                listOf('*', '0', '#'),
            )
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    row.forEach { digit ->
                        DtmfKey(
                            digit = digit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DtmfKey(digit: Char, modifier: Modifier = Modifier) {
    val label = digit.toString()
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                role = Role.Button,
                onClick = {
                    // Most carriers don't care about the gap between play and stop; we
                    // fire them back-to-back since a Compose click is already a tap.
                    OngoingCallHolder.playDtmf(digit)
                    OngoingCallHolder.stopDtmf()
                },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * "Reply with message" picker shown when the user taps the message button on a ringing
 * call. Picks one of four preset SMS-style strings and hands it to
 * [OngoingCallHolder.rejectWithMessage], which rejects the call with that text.
 */
@Composable
private fun ReplyWithMessageDialog(
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    val presets = listOf(
        stringResource(R.string.call_reply_preset_1),
        stringResource(R.string.call_reply_preset_2),
        stringResource(R.string.call_reply_preset_3),
        stringResource(R.string.call_reply_preset_4),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.call_reply_title)) },
        text = {
            Column {
                presets.forEach { preset ->
                    TextButton(
                        onClick = { onSend(preset) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(preset)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.call_reply_cancel))
            }
        },
    )
}
