package com.accessible.dialer.voice

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R

/**
 * Google-style live voice-search sheet for the contacts screen. Displays:
 *  - A title ("Speak now").
 *  - The live partial transcription as it streams in from the system recognizer.
 *  - A pulsing mic circle whose scale tracks the microphone RMS, so the user gets
 *    immediate feedback that we're actually listening.
 *  - A "Cancel" button.
 *
 * The sheet:
 *  - Starts listening as soon as it appears.
 *  - Calls [onResult] with the final transcript and dismisses itself when the
 *    recognizer finishes (or when the user taps the mic to stop early).
 *  - Calls [onDismiss] for any cancellation path so the host can clear its state.
 *
 * We deliberately keep this composable focused on the *contacts* search use case —
 * the result text is just a string; the host decides whether to route it to the
 * dialpad or to the contacts search filter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSearchSheet(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val controller = remember { VoiceSearchController(context) }
    val state by controller.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { controller.start() }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    // When the recognizer hands us a final result, forward it and close.
    LaunchedEffect(state) {
        val s = state
        if (s is VoiceState.Done) {
            onResult(s.text)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            controller.cancel()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        VoiceSearchContent(
            state = state,
            onStopOrRetry = {
                when (state) {
                    is VoiceState.Listening -> controller.stop()
                    is VoiceState.Error -> controller.start()
                    else -> Unit
                }
            },
            onCancel = {
                controller.cancel()
                onDismiss()
            },
        )
    }
}

@Composable
private fun VoiceSearchContent(
    state: VoiceState,
    onStopOrRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = when (state) {
        is VoiceState.Listening -> stringResource(R.string.voice_search_listening)
        is VoiceState.Error -> when (state.kind) {
            VoiceErrorKind.NoMatch -> stringResource(R.string.voice_search_no_match)
            VoiceErrorKind.NoRecognizer -> stringResource(R.string.voice_search_no_recognizer)
            VoiceErrorKind.Permission -> stringResource(R.string.voice_search_perm_denied)
            VoiceErrorKind.Network -> stringResource(R.string.voice_search_network)
            VoiceErrorKind.Generic -> stringResource(R.string.voice_search_failed)
        }
        is VoiceState.Done -> stringResource(R.string.voice_search_done)
        VoiceState.Idle -> stringResource(R.string.voice_search_prompt)
    }
    val transcript = (state as? VoiceState.Listening)?.partial?.takeIf { it.isNotBlank() }
        ?: (state as? VoiceState.Done)?.text.orEmpty()

    // RMS-driven scale for the mic circle. Idle = 1f, loud = ~1.4f.
    val rms = (state as? VoiceState.Listening)?.rms ?: 0f
    val scale by animateFloatAsState(targetValue = 1f + rms * 0.4f, label = "mic-rms")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            // Announce title changes (listening → done → error) for TalkBack users.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(20.dp))
        // Big pulsing mic circle. We use Material's primary colour so it tracks
        // the user's theme (dynamic / light / dark).
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        if (transcript.isNotEmpty()) {
            Text(
                text = transcript,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            // Reserve space so the layout doesn't jump when the first partial arrives.
            Spacer(Modifier.height(40.dp))
        }
        Spacer(Modifier.height(24.dp))
        // Primary action depends on state: while listening, "Done" commits the
        // partial; on error, "Try again" restarts the recognizer.
        val primary = when (state) {
            is VoiceState.Listening -> stringResource(R.string.voice_search_done_btn)
            is VoiceState.Error -> stringResource(R.string.voice_search_retry)
            else -> null
        }
        if (primary != null) {
            TextButton(onClick = onStopOrRetry, modifier = Modifier.fillMaxWidth()) {
                Text(primary)
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}
