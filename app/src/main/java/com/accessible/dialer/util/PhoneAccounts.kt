package com.accessible.dialer.util

import android.content.Context
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.accessible.dialer.settings.SettingsRepository

/**
 * Discovery + resolution helpers for the user-pickable calling account (SIM 1 / SIM 2 /
 * SIP). We don't reimplement the call account selector — we just enumerate accounts and
 * remember the user's choice so we can pass it through `TelecomManager.placeCall`.
 */
object PhoneAccounts {
    data class Account(val handle: PhoneAccountHandle, val label: String)

    /**
     * Returns the list of accounts the user can place calls with. Requires the READ_PHONE_STATE
     * permission at runtime for full visibility, but degrades gracefully when missing.
     */
    fun callable(context: Context): List<Account> {
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return emptyList()
        val raw: List<PhoneAccountHandle> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) tm.callCapablePhoneAccounts else emptyList()
        }.getOrDefault(emptyList())
        return raw.mapNotNull { handle ->
            val pa: PhoneAccount? = runCatching { tm.getPhoneAccount(handle) }.getOrNull()
            val label = pa?.label?.toString()?.takeIf { it.isNotBlank() }
                ?: handle.id
            Account(handle, label)
        }
    }

    /** Resolves the user-chosen account back to a [PhoneAccountHandle], or null. */
    fun resolveSaved(context: Context): PhoneAccountHandle? {
        val ref = SettingsRepository.phoneAccount.value ?: return null
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
        val accounts: List<PhoneAccountHandle> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) tm.callCapablePhoneAccounts else emptyList()
        }.getOrDefault(emptyList())
        return accounts.firstOrNull {
            it.componentName.packageName == ref.componentPackage &&
                it.componentName.className == ref.componentClass &&
                it.id == ref.id
        }
    }

    /**
     * Returns a human-readable label for a [PhoneAccountHandle] (e.g. "SIM 1",
     * carrier name, or the handle id as a fallback). Returns null if no label
     * can be resolved or [handle] is null.
     */
    fun labelFor(context: Context, handle: PhoneAccountHandle?): String? {
        if (handle == null) return null
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
        val pa = runCatching { tm.getPhoneAccount(handle) }.getOrNull()
        return pa?.label?.toString()?.takeIf { it.isNotBlank() }
            ?: handle.id.takeIf { it.isNotBlank() }
    }
}
