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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.ui.DialerApp
import com.accessible.dialer.ui.theme.AccessibleDialerTheme
import com.accessible.dialer.util.DefaultDialer
import com.accessible.dialer.util.DialerPermissions
import com.accessible.dialer.util.PhoneAccounts

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
    val themeMode by SettingsRepository.theme.collectAsState()
    val textScale by SettingsRepository.textScale.collectAsState()

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
