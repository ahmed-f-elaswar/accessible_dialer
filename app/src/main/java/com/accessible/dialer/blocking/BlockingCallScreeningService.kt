package com.accessible.dialer.blocking

import android.telecom.Call
import android.telecom.CallScreeningService
import com.accessible.dialer.settings.SettingsRepository

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

        val response = if (blocked) {
            when (SettingsRepository.blockMode.value) {
                SettingsRepository.BlockMode.SilentRing ->
                    // Caller hears normal ringing; my phone stays silent. After their
                    // ring timeout the carrier routes them to voicemail naturally.
                    CallResponse.Builder()
                        .setSilenceCall(true)
                        .setSkipCallLog(true)
                        .setSkipNotification(true)
                        .build()
                SettingsRepository.BlockMode.Reject ->
                    CallResponse.Builder()
                        .setDisallowCall(true)
                        .setRejectCall(true)
                        // Don't ring, don't show in any UI, don't add to the system call log.
                        .setSkipCallLog(true)
                        .setSkipNotification(true)
                        .build()
            }
        } else {
            CallResponse.Builder().build()
        }
        respondToCall(callDetails, response)
    }
}
