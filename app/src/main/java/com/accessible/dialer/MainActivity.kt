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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If launched with tel: URI, prefill it in the dialpad.
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
        // When another app hands us a tel: number we behave like a system dialer and place
        // the call immediately instead of waiting for the user to confirm on the keypad.
        val autoCall: Boolean = !initialNumber.isNullOrBlank()
        setContent {
            AppRoot(
                initialNumber = initialNumber,
                startOnContacts = startOnContacts,
                autoCall = autoCall,
            )
        }
    }
}

@Composable
private fun AppRoot(initialNumber: String?, startOnContacts: Boolean, autoCall: Boolean) {
    val context = LocalContext.current
    val themeMode by SettingsRepository.theme.collectAsStateWithLifecycle()
    val textScale by SettingsRepository.textScale.collectAsStateWithLifecycle()

    AccessibleDialerTheme(themeMode = themeMode, textScale = textScale) {
        AppContent(
            initialNumber = initialNumber,
            startOnContacts = startOnContacts,
            autoCall = autoCall,
        )
    }
}

@Composable
private fun AppContent(initialNumber: String?, startOnContacts: Boolean, autoCall: Boolean) {
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

    DialerApp(
        initialNumber = initialNumber,
        startOnContacts = startOnContacts,
        permissionsGranted = permissionsGranted,
        isDefaultDialer = isDefault,
        onRequestPermissions = { permLauncher.launch(DialerPermissions.all) },
        onRequestDefaultDialer = {
            DefaultDialer.requestRoleIntent(context)?.let { roleLauncher.launch(it) }
        },
        onPlaceCall = { number ->
            placeCall(context, number)
        },
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
