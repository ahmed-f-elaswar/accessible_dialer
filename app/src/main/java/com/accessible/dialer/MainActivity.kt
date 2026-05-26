package com.accessible.dialer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.accessible.dialer.ui.DialerApp
import com.accessible.dialer.ui.theme.AccessibleDialerTheme
import com.accessible.dialer.util.DefaultDialer
import com.accessible.dialer.util.DialerPermissions

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If launched with tel: URI, prefill it in the dialpad.
        val initialNumber: String? = when (intent?.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
            else -> null
        }
        setContent {
            AccessibleDialerTheme {
                AppRoot(initialNumber = initialNumber)
            }
        }
    }
}

@Composable
private fun AppRoot(initialNumber: String?) {
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

    DialerApp(
        initialNumber = initialNumber,
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
    val telecom = context.getSystemService(android.content.Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
    runCatching {
        // Prefer TelecomManager so we never trigger an app chooser (e.g. Zoom/Phone),
        // even when other apps also declare a tel: intent filter. Requires either
        // CALL_PHONE or MANAGE_OWN_CALLS / default-dialer role, which we hold.
        telecom?.placeCall(uri, null)
            ?: run {
                // Extremely unlikely fallback — keep behavior for very old devices.
                val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
    }
}
