package com.accessible.dialer.blocking

import android.telecom.Call
import android.telecom.CallScreeningService
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.util.RowActions

/**
 * Automatically rejects incoming calls whose number is on our block list.
 *
 * Only one [CallScreeningService] is active at a time, chosen by the system —
 * the default dialer wins on Android 10+ which is our [minSdk]. We delegate the
 * actual block-state lookup to [BlockedNumbersRepository.isBlocked], which is
 * backed by the system `BlockedNumberContract`.
 */
class BlockingCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val blocked = number.isNotBlank() &&
            BlockedNumbersRepository.isBlocked(this, number)

        // "Block unknown callers" — when enabled, every incoming call from a number
        // that doesn't resolve to a saved contact is treated like a manual block.
        // Number-blank (private / withheld) counts as unknown too, since the user
        // can't possibly have it saved. We deliberately re-use BlockMode so the
        // user only needs to choose the screening behaviour once.
        val unknownBlocked = !blocked &&
            SettingsRepository.blockUnknown.value &&
            (number.isBlank() || RowActions.lookupContactId(this, number) == null)

        // Quiet hours: when active, count repeated attempts from the same number. If the
        // caller exceeds the configured threshold within a short rolling window we let
        // them through; otherwise the call is silently rejected.
        val quietActive = !blocked && !unknownBlocked && QuietHours.isQuietNow()
        val quietRejected = quietActive && number.isNotBlank() &&
            !QuietHours.shouldBypassQuiet(number)

        val response = when {
            blocked || unknownBlocked -> {
                when (SettingsRepository.blockMode.value) {
                    SettingsRepository.BlockMode.SilentRing ->
                        // Caller hears normal ringing; my phone stays silent. After their
                        // ring timeout the carrier routes them to voicemail naturally.
                        // We still record the attempt in the system call log so the user
                        // can see who tried to reach them.
                        //
                        // We deliberately do NOT set setSkipNotification here: combined with
                        // disallowCall=false some OEMs (notably Huawei) treat that pair as
                        // "drop the call silently" — i.e. an auto-reject from the caller's
                        // point of view, which defeats the whole purpose of this mode. Just
                        // setting setSilenceCall(true) is enough: the system suppresses its
                        // own ringer/notification, the call still flows through to our
                        // InCallService, and DialerInCallService skips our in-app ringer +
                        // UI when it sees the number is blocked + mode is SilentRing.
                        CallResponse.Builder()
                            .setDisallowCall(false)
                            .setSilenceCall(true)
                            .build()
                    SettingsRepository.BlockMode.Reject ->
                        CallResponse.Builder()
                            .setDisallowCall(true)
                            .setRejectCall(true)
                            // Don't ring, don't show in any UI — but DO add to the system
                            // call log so the user can review blocked attempts later.
                            .setSkipNotification(true)
                            .build()
                }
            }
            quietRejected ->
                // Silent reject — looks to the caller like a normal decline, our phone
                // never makes a sound, but we *do* still log it so the user can see who
                // tried to reach them during quiet hours.
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipNotification(true)
                    .build()
            else -> CallResponse.Builder().build()
        }
        respondToCall(callDetails, response)
    }
}
