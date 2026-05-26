package com.accessible.dialer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The set of runtime permissions the dialer needs to function fully. Note that being the
 * default phone app grants several of these implicitly, but we still request explicitly so
 * the app works in a graceful, partially-functional state before the user opts in.
 */
object DialerPermissions {
    val all: Array<String> = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context): Boolean = all.all { granted(context, it) }
}
