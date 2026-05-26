package com.accessible.dialer.util

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager

/**
 * Helpers for becoming (and detecting whether we already are) the system Default Phone App.
 *
 * On API 29+ this is handled by [RoleManager] via [RoleManager.ROLE_DIALER]; on older
 * versions we'd fall back to the legacy ACTION_CHANGE_DEFAULT_DIALER intent. Our minSdk
 * is 29 so RoleManager is the only path needed.
 */
object DefaultDialer {

    fun isDefault(context: Context): Boolean {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        return telecom.defaultDialerPackage == context.packageName
    }

    /** Build an intent the caller can launch (e.g. via ActivityResult) to request the role. */
    fun requestRoleIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) return null
        if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    }
}
