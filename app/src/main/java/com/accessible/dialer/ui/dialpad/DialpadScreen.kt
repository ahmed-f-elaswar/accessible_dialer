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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
import com.accessible.dialer.R

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
) {
    val context = LocalContext.current
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

    fun feedback(toneId: Int) {
        runCatching { tone.startTone(toneId, 120) }
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun append(ch: Char, toneId: Int) {
        feedback(toneId)
        onNumberChange(number + ch)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    contentDescription = if (number.isEmpty()) "" else "Number: ${number.toCharArray().joinToString(" ")}"
                }
            )
        }

        // 4 rows of keys
        DialRow {
            Key("1", stringResource(R.string.key_1_desc), Modifier.weight(1f)) { append('1', ToneGenerator.TONE_DTMF_1) }
            Key("2", stringResource(R.string.key_2_desc), Modifier.weight(1f)) { append('2', ToneGenerator.TONE_DTMF_2) }
            Key("3", stringResource(R.string.key_3_desc), Modifier.weight(1f)) { append('3', ToneGenerator.TONE_DTMF_3) }
        }
        DialRow {
            Key("4", stringResource(R.string.key_4_desc), Modifier.weight(1f)) { append('4', ToneGenerator.TONE_DTMF_4) }
            Key("5", stringResource(R.string.key_5_desc), Modifier.weight(1f)) { append('5', ToneGenerator.TONE_DTMF_5) }
            Key("6", stringResource(R.string.key_6_desc), Modifier.weight(1f)) { append('6', ToneGenerator.TONE_DTMF_6) }
        }
        DialRow {
            Key("7", stringResource(R.string.key_7_desc), Modifier.weight(1f)) { append('7', ToneGenerator.TONE_DTMF_7) }
            Key("8", stringResource(R.string.key_8_desc), Modifier.weight(1f)) { append('8', ToneGenerator.TONE_DTMF_8) }
            Key("9", stringResource(R.string.key_9_desc), Modifier.weight(1f)) { append('9', ToneGenerator.TONE_DTMF_9) }
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
                onClick = { if (number.isNotEmpty()) onNumberChange(number.dropLast(1)) },
                onLongClick = { onNumberChange("") },
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
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.surface
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(88.dp)) {
            Icon(
                imageVector = Icons.Filled.Call,
                contentDescription = null,
                tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(36.dp),
            )
        }
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
            imageVector = Icons.Filled.Backspace,
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
