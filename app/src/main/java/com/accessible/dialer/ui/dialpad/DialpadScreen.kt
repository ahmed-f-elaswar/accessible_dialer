package com.accessible.dialer.ui.dialpad

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import com.accessible.dialer.R
import com.accessible.dialer.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Big-key dialpad with:
 *  - 56sp number display so the entered digits remain readable for low-vision users.
 *  - Each key has a descriptive [contentDescription] (e.g. "2 A B C") for TalkBack.
 *  - DTMF tones via [ToneGenerator] and a short haptic via [Vibrator] for tactile feedback.
 *  - Long-press 0 inserts "+" (standard international prefix convention).
 *  - Long-press backspace clears the whole number.
 */
@Composable
fun DialpadScreen(
    number: String,
    onNumberChange: (String) -> Unit,
    onCall: () -> Unit,
    permissionsGranted: Boolean,
    /**
     * Place a call to an arbitrary number, bypassing the on-screen digit display.
     * Used by the speed-dial long-press flow so a long-press on "3" can dial the
     * bound contact directly instead of first pushing "3" into the number field.
     */
    onCallNumber: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val tone = remember { ToneGenerator(AudioManager.STREAM_DTMF, 70) }
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    DisposableEffect(Unit) { onDispose { tone.release() } }

    val hapticEnabled by SettingsRepository.haptic.collectAsStateWithLifecycle()
    val verboseDigits by SettingsRepository.verboseDigits.collectAsStateWithLifecycle()
    val speedDial by SettingsRepository.speedDial.collectAsStateWithLifecycle()
    val rootContext = LocalContext.current

    fun feedback(toneId: Int) {
        runCatching { tone.startTone(toneId, 120) }
        if (hapticEnabled) {
            runCatching {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    fun append(ch: Char, toneId: Int) {
        feedback(toneId)
        onNumberChange(number + ch)
    }

    /**
     * Long-press handler for digit keys 1-9: if the user has bound a contact to
     * [digit] in Settings → Speed dial, place that call instantly and announce it
     * for TalkBack users. Returns true if a speed dial fired, false otherwise so
     * callers can decide whether to surface a long-click hint to the user.
     */
    fun handleDigitLongPress(digit: Int): Boolean {
        val bound = speedDial[digit]?.takeIf { it.isNotBlank() } ?: return false
        feedback(ToneGenerator.TONE_PROP_ACK)
        rootView.announceForAccessibility(
            rootContext.getString(R.string.speed_dial_calling, bound)
        )
        onCallNumber(bound)
        return true
    }

    @OptIn(ExperimentalComposeUiApi::class)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // Cancel any pending lift-to-type click whenever a hover event lands on
            // anywhere within the dialpad area that *isn't* a key. AndroidView keys
            // consume their own hover events, so the only events that reach this
            // outer filter are those from the empty Compose space between/around keys
            // (number display, padding, spacers). Sliding off a key into one of those
            // regions therefore cancels the scheduled click, while truly lifting off
            // the screen leaves the scheduler alone — its 80ms timer then fires the
            // last-touched key, matching standard soft-keyboard behavior.
            .pointerInteropFilter { ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER,
                    MotionEvent.ACTION_HOVER_MOVE,
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE -> DialpadHoverScheduler.cancel()
                }
                false
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Number display area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.ifEmpty { "" },
                fontSize = 48.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    contentDescription = when {
                        number.isEmpty() -> ""
                        // Verbose mode reads each digit individually with a leading label.
                        // The default still reads the number digit-by-digit (separated by
                        // spaces) so TalkBack pronounces "5 5 5" instead of "five hundred
                        // fifty-five", but skips the "Number:" prefix for brevity.
                        verboseDigits -> "Number: ${number.toCharArray().joinToString(" ")}"
                        else -> number.toCharArray().joinToString(" ")
                    }
                }
            )
        }

        // Live contact-suggestion list. Looks up contacts whose name or number matches
        // whatever the user has typed so far, so they can tap a result to call without
        // entering the full number. We use Phone.CONTENT_FILTER_URI, which the system
        // contacts provider already exposes for this exact dialer-style lookup
        // (matches digits against E.164-normalised numbers, and on most OEMs also
        // performs T9 matching against name letters).
        DialpadSuggestions(query = number, onPick = { picked -> onNumberChange(picked) })

        // 4 rows of keys. Long-pressing a digit 1-9 fires its speed-dial binding
        // (if any) via [handleDigitLongPress]; otherwise the long-press is a no-op.
        // Long-press 0 still inserts "+" for international prefixes.
        DialRow {
            DigitKey("1", stringResource(R.string.key_1_desc), 1, Modifier.weight(1f),
                onClick = { append('1', ToneGenerator.TONE_DTMF_1) },
                onLongClick = { handleDigitLongPress(1) })
            DigitKey("2", stringResource(R.string.key_2_desc), 2, Modifier.weight(1f),
                onClick = { append('2', ToneGenerator.TONE_DTMF_2) },
                onLongClick = { handleDigitLongPress(2) })
            DigitKey("3", stringResource(R.string.key_3_desc), 3, Modifier.weight(1f),
                onClick = { append('3', ToneGenerator.TONE_DTMF_3) },
                onLongClick = { handleDigitLongPress(3) })
        }
        DialRow {
            DigitKey("4", stringResource(R.string.key_4_desc), 4, Modifier.weight(1f),
                onClick = { append('4', ToneGenerator.TONE_DTMF_4) },
                onLongClick = { handleDigitLongPress(4) })
            DigitKey("5", stringResource(R.string.key_5_desc), 5, Modifier.weight(1f),
                onClick = { append('5', ToneGenerator.TONE_DTMF_5) },
                onLongClick = { handleDigitLongPress(5) })
            DigitKey("6", stringResource(R.string.key_6_desc), 6, Modifier.weight(1f),
                onClick = { append('6', ToneGenerator.TONE_DTMF_6) },
                onLongClick = { handleDigitLongPress(6) })
        }
        DialRow {
            DigitKey("7", stringResource(R.string.key_7_desc), 7, Modifier.weight(1f),
                onClick = { append('7', ToneGenerator.TONE_DTMF_7) },
                onLongClick = { handleDigitLongPress(7) })
            DigitKey("8", stringResource(R.string.key_8_desc), 8, Modifier.weight(1f),
                onClick = { append('8', ToneGenerator.TONE_DTMF_8) },
                onLongClick = { handleDigitLongPress(8) })
            DigitKey("9", stringResource(R.string.key_9_desc), 9, Modifier.weight(1f),
                onClick = { append('9', ToneGenerator.TONE_DTMF_9) },
                onLongClick = { handleDigitLongPress(9) })
        }
        DialRow {
            Key("*", stringResource(R.string.key_star_desc), Modifier.weight(1f)) { append('*', ToneGenerator.TONE_DTMF_S) }
            Key(
                label = "0",
                description = stringResource(R.string.key_0_desc),
                modifier = Modifier.weight(1f),
                onClick = { append('0', ToneGenerator.TONE_DTMF_0) },
                onLongClick = {
                    feedback(ToneGenerator.TONE_DTMF_0)
                    onNumberChange(number + '+')
                },
                longClickLabel = stringResource(R.string.key_0_plus_action),
            )
            Key("#", stringResource(R.string.key_pound_desc), Modifier.weight(1f)) { append('#', ToneGenerator.TONE_DTMF_P) }
        }

        Spacer(Modifier.height(12.dp))

        // Action row: backspace + call + spacer for symmetry.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left placeholder so the call button stays centered.
            Spacer(Modifier.size(72.dp))
            CallButton(enabled = number.isNotBlank() && permissionsGranted, onClick = onCall)
            BackspaceButton(
                enabled = number.isNotEmpty(),
                onClick = {
                    if (number.isNotEmpty()) {
                        // Use a distinct non-DTMF tone (PROP_BEEP2 is a short two-tone
                        // "deny/back" sound on the system tone generator) so deleting
                        // never sounds like typing. The deleted digit is also announced
                        // via TalkBack so screen-reader users hear *what* was removed,
                        // not just a generic beep.
                        val removed = number.last()
                        feedback(ToneGenerator.TONE_PROP_BEEP2)
                        onNumberChange(number.dropLast(1))
                        rootView.announceForAccessibility(
                            context.getString(R.string.dialpad_digit_deleted, removed.toString())
                        )
                    }
                },
                onLongClick = {
                    if (number.isNotEmpty()) {
                        feedback(ToneGenerator.TONE_PROP_BEEP2)
                        onNumberChange("")
                        rootView.announceForAccessibility(
                            context.getString(R.string.dialpad_cleared)
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DialRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/**
 * Wrapper around [Key] for digits 1-9 that augments the content description with the
 * bound speed-dial contact name (if any) so TalkBack announces e.g. "3, long-press for
 * John Doe", and surfaces a localized long-click label in the TalkBack local context
 * menu. Falls back to the plain digit [description] when no binding is configured.
 */
@Composable
private fun DigitKey(
    label: String,
    description: String,
    digit: Int,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Boolean,
) {
    val speedDial by SettingsRepository.speedDial.collectAsStateWithLifecycle()
    val bound = speedDial[digit]?.takeIf { it.isNotBlank() }
    val context = LocalContext.current
    val effectiveDescription = if (bound != null) {
        // Resolve a friendly contact name (off-thread) so screen-reader users
        // hear who the long-press will call. Falls back to the raw number while
        // the lookup is still in flight or if no contact match exists.
        val contactName by produceState<String?>(initialValue = null, bound) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    com.accessible.dialer.util.RowActions.lookupContactName(context, bound)
                }.getOrNull()
            }
        }
        val target = contactName?.takeIf { it.isNotBlank() } ?: bound
        "$description, " + context.getString(R.string.speed_dial_calling, target)
    } else description
    val longLabel = bound?.let { context.getString(R.string.speed_dial_calling, it) }
    Key(
        label = label,
        description = effectiveDescription,
        modifier = modifier,
        onClick = onClick,
        onLongClick = { onLongClick() },
        longClickLabel = longLabel,
    )
}

/**
 * A dialpad digit. Hosted as a native Android View so we can advertise the
 * `android.inputmethodservice.Keyboard$Key` class name on its accessibility node —
 * TalkBack recognizes that classname and switches the key to "touch to explore, lift
 * to type" mode. The user can drag a finger across the dialpad and the digit they
 * release on is what gets entered, exactly like the system soft-keyboard. No
 * double-tap required.
 */
@Composable
private fun Key(
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    onClick: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val rippleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f).toArgb()
    val onClickState = rememberUpdatedState(onClick)
    val onLongClickState = rememberUpdatedState(onLongClick)
    val longClickLabelState = rememberUpdatedState(longClickLabel)

    AndroidView(
        modifier = modifier.aspectRatio(1.4f),
        factory = { ctx ->
            DialpadKeyView(ctx).apply {
                isClickable = true
                isFocusable = true
                val shape = GradientDrawable().apply {
                    this.shape = GradientDrawable.OVAL
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(rippleColor),
                    shape,
                    null,
                )
                val tv = TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(
                    tv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                setOnClickListener { onClickState.value() }
                setOnLongClickListener {
                    val cb = onLongClickState.value ?: return@setOnLongClickListener false
                    cb(); true
                }
                ViewCompat.setAccessibilityDelegate(
                    this,
                    object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfoCompat,
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            // Tells TalkBack to treat this node as a keyboard key, which
                            // enables touch-to-explore + lift-to-activate.
                            info.className = "android.inputmethodservice.Keyboard\$Key"
                            // Relabel the long-press action so TalkBack reads a meaningful
                            // hint (e.g. "Insert plus") in the local context menu.
                            longClickLabelState.value?.let { lbl ->
                                info.addAction(
                                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                                        lbl,
                                    ),
                                )
                            }
                        }
                    },
                )
            }
        },
        update = { container ->
            (container.getChildAt(0) as TextView).apply {
                text = label
                setTextColor(onSurfaceColor)
            }
            container.contentDescription = description
            // Refresh the oval fill (theme color may change).
            val ripple = container.background as RippleDrawable
            (ripple.getDrawable(0) as GradientDrawable).setColor(surfaceColor)
        },
    )
}

@Composable
private fun CallButton(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.dialpad_call)
    val background =
        if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface
    val tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface
    // Single clickable focusable: previously a Box (with the contentDescription) wrapped an
    // IconButton (with the onClick), so TalkBack saw two competing semantic nodes — the
    // outer node carried the label but no click, and the inner button had a click but no
    // label. Collapsing them fixes "announces 'Call' but double-tap does nothing".
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(
                enabled = enabled,
                onClick = onClick,
                onClickLabel = label,
                role = Role.Button,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Call,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
private fun BackspaceButton(enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val label = stringResource(R.string.dialpad_backspace)
    val clearAllLabel = stringResource(R.string.dialpad_clear_all)
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.Transparent)
            .semantics {
                contentDescription = label
                // Expose "clear all" (long-press) as a discoverable TalkBack action.
                customActions = listOf(
                    CustomAccessibilityAction(clearAllLabel) {
                        onLongClick(); true
                    },
                )
            }
            .combinedTapModifier(onClick = { if (enabled) onClick() }, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            modifier = Modifier.size(32.dp),
        )
    }
}

/**
 * Hosts arbitrary Compose [content] inside a native [FrameLayout] whose accessibility
 * node advertises the `android.inputmethodservice.Keyboard$Key` class name. TalkBack
 * treats that class as a keyboard key — touch-to-explore announces the key and lift
 * activates it, bypassing the usual double-tap requirement.
 */
@Composable
private fun KeyboardKeyShell(
    modifier: Modifier,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val cdState = rememberUpdatedState(contentDescription)
    val onClickState = rememberUpdatedState(onClick)
    val onLongClickState = rememberUpdatedState(onLongClick)
    val longClickLabelState = rememberUpdatedState(longClickLabel)
    val enabledState = rememberUpdatedState(enabled)
    val contentState = rememberUpdatedState(content)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FrameLayout(ctx).apply {
                isClickable = true
                isFocusable = true
                val composeView = ComposeView(ctx).apply {
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    setContent { contentState.value() }
                }
                addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                setOnClickListener {
                    if (enabledState.value) onClickState.value()
                }
                setOnLongClickListener {
                    val cb = onLongClickState.value ?: return@setOnLongClickListener false
                    cb(); true
                }
                ViewCompat.setAccessibilityDelegate(
                    this,
                    object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfoCompat,
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.inputmethodservice.Keyboard\$Key"
                            longClickLabelState.value?.let { lbl ->
                                info.addAction(
                                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                                        lbl,
                                    ),
                                )
                            }
                        }
                    },
                )
            }
        },
        update = { container ->
            container.contentDescription = cdState.value
            container.isEnabled = enabledState.value
        },
    )
}

// Local helper because foundation's combinedClickable lives in an experimental package.
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedTapModifier(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/**
 * A dialpad key container. When TalkBack's touch-exploration mode is active, the key
 * fires only when the finger lifts off the screen — not when it slides into another
 * key — matching the tap behavior sighted users get.
 *
 * Hover events are how the framework delivers explore-by-touch input: ACTION_HOVER_ENTER
 * when the finger lands on a key, ACTION_HOVER_EXIT when it leaves. On exit we schedule
 * the click on a short delay; if the finger moves to a neighboring key the next
 * HOVER_ENTER cancels the pending click. If no key takes over (because the finger left
 * the screen), the scheduled click fires.
 */
private class DialpadKeyView(context: Context) : FrameLayout(context) {
    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (accessibilityManager.isTouchExplorationEnabled && isClickable) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    // Finger arrived here — cancel any click pending from the previous
                    // key (the finger is still on the screen, just on a new key).
                    DialpadHoverScheduler.cancel()
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
                    return true
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    // Schedule a lift-click. If a neighboring key receives HOVER_ENTER
                    // before the delay expires, the click is cancelled.
                    DialpadHoverScheduler.scheduleLiftClick(this)
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    return true
                }
            }
        }
        return super.onHoverEvent(event)
    }
}

/**
 * Shared across every [DialpadKeyView]: holds at most one pending lift-click. Sliding
 * to a sibling cancels the pending click via [cancel]; only the final HOVER_EXIT with
 * no follow-up HOVER_ENTER actually fires.
 */
private object DialpadHoverScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    fun scheduleLiftClick(view: View) {
        cancel()
        val runnable = Runnable {
            view.performClick()
            pending = null
        }
        pending = runnable
        // 80 ms is comfortably longer than the gap between HOVER_EXIT on one key and
        // HOVER_ENTER on the next during a drag (typically <16 ms), but still fast
        // enough that lift-to-type feels instant.
        handler.postDelayed(runnable, 80L)
    }
}

/** A single contact match returned by [Phone.CONTENT_FILTER_URI]. */
private data class DialpadMatch(val name: String, val number: String)

/**
 * Horizontally scrollable strip of contact matches that appears between the entered-
 * number display and the dial keys. Empty / hidden when the user has typed fewer than
 * two characters (avoids spamming the provider on every single keystroke) or when no
 * contact matches. Tapping a chip drops the matched number into the dialpad so the
 * user can immediately press Call \u2014 it does *not* auto-dial, both to stay consistent
 * with the rest of the dialpad's "type then call" model and to keep the action
 * undoable.
 */
@Composable
private fun DialpadSuggestions(query: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    // Resolve matches off-thread; produceState recomputes when [query] changes. Capped
    // at 8 results to keep the query cheap and the row legible.
    val matches by produceState(initialValue = emptyList<DialpadMatch>(), query) {
        val q = query.trim()
        value = if (q.length < 2) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val uri = android.net.Uri.withAppendedPath(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                    android.net.Uri.encode(q),
                )
                val out = mutableListOf<DialpadMatch>()
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext() && out.size < 8) {
                        val name = c.getString(0)?.takeIf { it.isNotBlank() }
                        val number = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        out += DialpadMatch(name = name ?: number, number = number)
                    }
                }
                out
            }.getOrDefault(emptyList())
        }
    }
    if (matches.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 72.dp)
            .padding(vertical = 4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(matches, key = { it.name + "\u0000" + it.number }) { m ->
            val pickLabel = stringResource(R.string.dialpad_suggestion_call, m.name, m.number)
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(
                        onClick = { onPick(m.number) },
                        onClickLabel = pickLabel,
                        role = Role.Button,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = pickLabel
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = m.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = m.number,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
