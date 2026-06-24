package com.accessible.dialer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.ui.DialerApp
import com.accessible.dialer.ui.theme.AccessibleDialerTheme
import com.accessible.dialer.util.DefaultDialer
import com.accessible.dialer.util.DialerPermissions
import com.accessible.dialer.util.PhoneAccounts
import com.accessible.dialer.util.ShakeDetector

private const val NO_NUMBER_TOAST_KEY = "no_number_toast"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)        // If launched with tel: URI, prefill it in the dialpad.
        val initialNumber: String? = when (intent?.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW, Intent.ACTION_CALL ->
                intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
            else -> null
        }
        // CATEGORY_APP_CONTACTS is set by the launcher when the user taps the "Contacts"
        // entry registered in the manifest. In that case, jump straight to the Contacts
        // tab instead of opening on the dialpad.
        val startOnContacts: Boolean =
            intent?.categories?.contains(Intent.CATEGORY_APP_CONTACTS) == true
        // Set by [MissedCallNotifier] on the tap PendingIntent so the dialer lands on
        // the Recents tab — same UX as stock phone apps where tapping a missed-call
        // notification shows the call history.
        val startOnRecents: Boolean =
            intent?.getBooleanExtra(
                com.accessible.dialer.call.MissedCallNotifier.EXTRA_OPEN_RECENTS,
                false,
            ) == true
        // Clear the missed-call notification that brought us here so the user doesn't
        // have to swipe it away by hand after opening the dialer.
        intent?.getStringExtra(
            com.accessible.dialer.call.MissedCallNotifier.EXTRA_DISMISS_MISSED_NUMBER,
        )?.let { key ->
            com.accessible.dialer.call.MissedCallNotifier.cancelForKey(this, key)
        }
        // ACTION_SEND from the system Share sheet: scan EXTRA_TEXT for a phone
        // number and route it into the same Call / Add to contact / Cancel
        // prompt the clipboard detector uses. If no number is found, surface a
        // short toast — we can't filter the Share menu by content at the
        // manifest level, so we have to handle the "no number" case at runtime.
        val sharedNumber: String? = extractSharedNumber(intent)
        if (intent?.action == Intent.ACTION_SEND && sharedNumber == null) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.share_no_number_found),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        // When another app hands us a tel: number we behave like a system dialer and place
        // the call immediately instead of waiting for the user to confirm on the keypad.
        val autoCall: Boolean = !initialNumber.isNullOrBlank()
        setContent {
            AppRoot(
                initialNumber = initialNumber,
                startOnContacts = startOnContacts,
                startOnRecents = startOnRecents,
                autoCall = autoCall,
                sharedNumber = sharedNumber,
            )
        }
    }

    /**
     * Activity is `launchMode="singleTask"`, so when the user taps a missed-call
     * notification while the dialer is already running the system delivers the
     * notification's Intent here rather than spinning up a fresh [onCreate]. Honour the
     * dismiss extra so the notification clears, and forward tel: URIs straight to
     * [placeCall] so the "Call back" notification action behaves the same whether the
     * app was cold or warm.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(
            com.accessible.dialer.call.MissedCallNotifier.EXTRA_DISMISS_MISSED_NUMBER,
        )?.let { key ->
            com.accessible.dialer.call.MissedCallNotifier.cancelForKey(this, key)
        }
        val number = when (intent.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW, Intent.ACTION_CALL ->
                intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
            else -> null
        }
        if (!number.isNullOrBlank()) {
            placeCall(this, number)
        }
        // Share-sheet ACTION_SEND can also arrive while the activity is already
        // running (singleTask). Route the extracted number through the existing
        // shared-flow channel so DialerApp's prompt fires the same way as on cold start.
        if (intent.action == Intent.ACTION_SEND) {
            val sn = extractSharedNumber(intent)
            if (sn != null) {
                SharedNumberBus.emit(sn)
            } else {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.share_no_number_found),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

/**
 * Pulls a phone-number-shaped substring out of the share-sheet payload, mirroring
 * the heuristic the clipboard-to-call prompt uses (5–20 digits, allow common
 * separators / RTL marks). Returns the first match in left-to-right order.
 */
private fun extractSharedNumber(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_SEND) return null
    if (intent.type != "text/plain") return null
    val payload = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    if (payload.isBlank()) return null
    // Match runs of digits + standard separators, anchored on a digit or '+'.
    // Drop trailing punctuation, then enforce 5–20 digits like the clipboard
    // detector. The bounded character class keeps regex catastrophic backtracking
    // off the table.
    val regex = Regex("[+\\d][\\d\\s().\\-#*]{3,30}")
    for (m in regex.findAll(payload)) {
        val candidate = m.value.trim().trimEnd('.', ',', ';', ':')
        val digits = candidate.count { it.isDigit() }
        if (digits in 5..20) return candidate
    }
    return null
}

/**
 * Tiny one-shot channel that lets [MainActivity.onNewIntent] hand a fresh
 * share-sheet number to the already-composed DialerApp tree. A StateFlow with
 * value=null means "nothing pending"; setting a non-null value fires a Compose
 * collection, which the consumer must clear via [consume] after using it.
 */
object SharedNumberBus {
    private val _flow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val flow: kotlinx.coroutines.flow.StateFlow<String?> = _flow
    fun emit(number: String) { _flow.value = number }
    fun consume() { _flow.value = null }
}

@Composable
private fun AppRoot(initialNumber: String?, startOnContacts: Boolean, startOnRecents: Boolean, autoCall: Boolean, sharedNumber: String?) {
    val context = LocalContext.current
    val themeMode by SettingsRepository.theme.collectAsStateWithLifecycle()
    val textScale by SettingsRepository.textScale.collectAsStateWithLifecycle()

    AccessibleDialerTheme(themeMode = themeMode, textScale = textScale) {
        AppContent(
            initialNumber = initialNumber,
            startOnContacts = startOnContacts,
            startOnRecents = startOnRecents,
            autoCall = autoCall,
            sharedNumber = sharedNumber,
        )
    }
}

@Composable
private fun AppContent(initialNumber: String?, startOnContacts: Boolean, startOnRecents: Boolean, autoCall: Boolean, sharedNumber: String?) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(DialerPermissions.allGranted(context)) }
    var isDefault by remember { mutableStateOf(DefaultDialer.isDefault(context)) }

    // Re-check the default-dialer role whenever the Activity comes back to the
    // foreground. Without this, the welcome gate would keep showing after the user
    // grants the role from the system Default Apps page (which doesn't fire our
    // ActivityResult callback).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefault = DefaultDialer.isDefault(context)
                permissionsGranted = DialerPermissions.allGranted(context)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Shake-to-call: when the user shakes the device while this Activity is
    // foregrounded AND the feature is enabled AND a destination number is set, we
    // place a call to that number. Bound to ON_RESUME / ON_PAUSE so the
    // accelerometer is not kept hot when the app is in the background. The shake
    // is ignored when the on-screen welcome gate is still up (permissions not
    // granted or app is not the default dialer yet) to avoid surprise dials.
    val shakeToCallEnabled by SettingsRepository.shakeToCallEnabled.collectAsStateWithLifecycle()
    val shakeToCallNumber by SettingsRepository.shakeToCallNumber.collectAsStateWithLifecycle()
    DisposableEffect(lifecycle, shakeToCallEnabled, shakeToCallNumber, permissionsGranted) {
        if (!shakeToCallEnabled || shakeToCallNumber.isBlank() || !permissionsGranted) {
            return@DisposableEffect onDispose { }
        }
        val detector = ShakeDetector(onShake = {
            placeCall(context, shakeToCallNumber)
        })
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> detector.start(context)
                Lifecycle.Event.ON_PAUSE -> detector.stop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        // Activity is already resumed here (compose effects run after ON_RESUME), so
        // kick the detector now — the observer only catches *subsequent* transitions.
        detector.start(context)
        onDispose {
            lifecycle.removeObserver(obs)
            detector.stop()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it } && DialerPermissions.allGranted(context)
    }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isDefault = DefaultDialer.isDefault(context)
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) permLauncher.launch(DialerPermissions.all)
    }

    // Full-screen welcome gate. Renders BEFORE the dialer UI so nothing else of the app
    // is visible while it's up — the user asked for this to be a true blocking onboarding
    // screen, not a small dialog beside the dial pad. Once the user becomes the default
    // phone app *or* explicitly taps "Continue without setting", we let the rest of the
    // app render. The "continue" dismissal is per-session only — next cold start, the
    // gate appears again until the role is granted.
    var dismissedThisSession by remember { mutableStateOf(false) }
    if (!isDefault && !dismissedThisSession) {
        WelcomeGate(
            onSetPhone = {
                DefaultDialer.requestRoleIntent(context)?.let { roleLauncher.launch(it) }
            },
            onContinue = { dismissedThisSession = true },
        )
        return
    }

    // Fire-and-forget: if we were handed a number from outside, place the call as soon as
    // we have the required runtime permission. We guard with `remember` so config changes
    // (rotation, theme switch) don't re-trigger the call.
    var didAutoCall by remember { mutableStateOf(false) }
    LaunchedEffect(permissionsGranted, autoCall, initialNumber) {
        if (autoCall && !didAutoCall && permissionsGranted && !initialNumber.isNullOrBlank()) {
            didAutoCall = true
            placeCall(context, initialNumber)
        }
    }

    // Collect share-sheet numbers that arrived while we were already in the
    // foreground (onNewIntent emits via [SharedNumberBus]). Merge with the
    // cold-start sharedNumber prop so DialerApp sees a single source of truth.
    val busShared by SharedNumberBus.flow.collectAsStateWithLifecycle()
    val effectiveSharedNumber = busShared ?: sharedNumber

    DialerApp(
        initialNumber = initialNumber,
        startOnContacts = startOnContacts,
        startOnRecents = startOnRecents,
        permissionsGranted = permissionsGranted,
        isDefaultDialer = isDefault,
        onRequestPermissions = { permLauncher.launch(DialerPermissions.all) },
        onRequestDefaultDialer = {
            DefaultDialer.requestRoleIntent(context)?.let { roleLauncher.launch(it) }
        },
        onPlaceCall = { number ->
            placeCall(context, number)
        },
        sharedNumber = effectiveSharedNumber,
        onSharedNumberConsumed = { SharedNumberBus.consume() },
    )
}

/**
 * Full-screen onboarding take-over shown until the user grants us the default-phone
 * role. Covers the entire viewport (top to bottom, edge to edge) so there is no way to
 * accidentally interact with the dial pad before making the choice — the user
 * specifically asked for the prompt to *not* sit beside the app controls.
 */
@Composable
private fun WelcomeGate(
    onSetPhone: () -> Unit,
    onContinue: () -> Unit,
) {
    Scaffold { inner ->
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(R.string.welcome_message),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = onSetPhone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.welcome_set_phone))
                }
                TextButton(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.welcome_close))
                }
            }
        }
    }
}

private fun placeCall(context: android.content.Context, number: String) {
    if (number.isBlank()) return
    // CALL_PHONE permission must be granted; UI gates this, but defensively check.
    if (!DialerPermissions.granted(context, Manifest.permission.CALL_PHONE)) return
    val uri = Uri.fromParts("tel", number, null)
    val telecom = context.getSystemService(android.content.Context.TELECOM_SERVICE) as? TelecomManager

    // If the user picked a default calling account, route the call through it so dual-SIM
    // devices stop popping the chooser every time. Skipped pre-M because the API is gated.
    val extras: Bundle? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PhoneAccounts.resolveSaved(context)?.let { handle ->
            Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) }
        }
    } else null

    runCatching {
        telecom?.placeCall(uri, extras)
            ?: run {
                val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
    }
}
