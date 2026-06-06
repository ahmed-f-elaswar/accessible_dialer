package com.accessible.dialer.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import com.accessible.dialer.settings.SettingsRepository

/**
 * Plays the incoming-call ringtone and vibration. Because the manifest declares
 * `android.telecom.IN_CALL_SERVICE_RINGING = true`, the platform stops playing the
 * system ringer and expects us to play it. Without this helper, incoming calls are
 * silent on devices that honor that flag (most modern Androids).
 *
 * The helper resolves the contact's CUSTOM_RINGTONE first (so per-contact ringtones
 * set from Contact Details actually play) and falls back to the user's default
 * ringtone. Vibration mirrors the system AudioManager.ringerMode so silent / vibrate
 * profiles are honored.
 */
class Ringer(private val context: Context) {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var vibrating = false
    // Active only in call-waiting mode (a second incoming call arrives while
    // the user is already on a call). We play short periodic SUP_CALL_WAITING
    // tones on the in-call audio stream instead of the full ringtone so the
    // existing call audio stays intelligible.
    private var waitingTone: ToneGenerator? = null
    private val waitingHandler = Handler(Looper.getMainLooper())
    private var waitingRunnable: Runnable? = null

    /**
     * Start ringing for an incoming call.
     *
     * @param callerNumber phone number of the incoming caller (for per-contact ringtone lookup).
     * @param accountHandle PhoneAccountHandle of the incoming call (for per-SIM ringtone lookup).
     * @param callWaiting true when the user is already on another call. In that
     *  case we skip the full ringtone + vibration and instead play a periodic
     *  short "beep beep" (the standard call-waiting alert tone) on the voice-
     *  call stream so the ongoing call audio is not drowned out.
     */
    fun start(
        callerNumber: String?,
        accountHandle: PhoneAccountHandle? = null,
        callWaiting: Boolean = false,
    ) {
        // Already playing — nothing to do. The system can re-deliver STATE_RINGING when
        // the call's details change, and we don't want to restart the tone every time.
        if (ringtone?.isPlaying == true || vibrating || waitingTone != null) return

        if (callWaiting) {
            startCallWaitingAlert()
            return
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            val uri = resolveRingtoneUri(callerNumber, accountHandle)
            val rt = RingtoneManager.getRingtone(context, uri)
            if (rt != null) {
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                runCatching { rt.play() }
                ringtone = rt
            }
        }

        if (mode != AudioManager.RINGER_MODE_SILENT) {
            startVibration()
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        if (vibrating) {
            runCatching { vibrator?.cancel() }
            vibrating = false
        }
        stopCallWaitingAlert()
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (v == null || !v.hasVibrator()) return
        // 1s on, 1s off; loop until cancel().
        val pattern = longArrayOf(0L, 1000L, 1000L)
        val effect = VibrationEffect.createWaveform(pattern, 0)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
        runCatching { v.vibrate(effect, attrs) }
        vibrator = v
        vibrating = true
    }

    /**
     * Call-waiting alert: play the standard "beep beep" SUP_CALL_WAITING tone on
     * the voice-call stream every few seconds until [stop] is called. Mirrors
     * what stock dialers do for a second incoming call so the active call
     * audio stays intelligible.
     */
    private fun startCallWaitingAlert() {
        // 60% of max volume — clearly audible over speech but well below the
        // full ringer level.
        val tg = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60) }
            .getOrNull() ?: return
        waitingTone = tg
        val runnable = object : Runnable {
            override fun run() {
                // TONE_SUP_CALL_WAITING is itself a short two-pulse "beep beep"
                // (~300ms total). Re-trigger every 4s for the duration of the
                // ringing state.
                runCatching { tg.startTone(ToneGenerator.TONE_SUP_CALL_WAITING) }
                waitingHandler.postDelayed(this, 4000L)
            }
        }
        waitingRunnable = runnable
        // Fire the first beep immediately so the user is alerted right away.
        waitingHandler.post(runnable)
    }

    private fun stopCallWaitingAlert() {
        waitingRunnable?.let { waitingHandler.removeCallbacks(it) }
        waitingRunnable = null
        runCatching { waitingTone?.stopTone() }
        runCatching { waitingTone?.release() }
        waitingTone = null
    }

    /**
     * Looks up the contact whose phone number matches [number] and returns their
     * `CUSTOM_RINGTONE` URI if set; otherwise falls back to the user's per-SIM
     * ringtone override (keyed by [accountHandle]) and finally the system default.
     *
     * Priority: contact CUSTOM_RINGTONE → per-SIM override → system default.
     * Per-contact wins because the user explicitly chose a ringtone for *that*
     * person; the SIM-level override is the next best signal of intent.
     */
    private fun resolveRingtoneUri(number: String?, accountHandle: PhoneAccountHandle?): Uri {
        if (!number.isNullOrBlank()) {
            val custom = runCatching {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.CUSTOM_RINGTONE,
                    ),
                    "${ContactsContract.Contacts.HAS_PHONE_NUMBER}=1",
                    null, null,
                )?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    val rtIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.CUSTOM_RINGTONE)
                    var match: String? = null
                    while (c.moveToNext() && match == null) {
                        val ringtoneUri = c.getString(rtIdx) ?: continue
                        // Look up phones for this contact and compare.
                        val contactId = c.getLong(idIdx)
                        if (contactHasNumber(contactId, number)) match = ringtoneUri
                    }
                    match
                }
            }.getOrNull()
            if (!custom.isNullOrBlank()) return Uri.parse(custom)
        }
        // Per-SIM override: the user assigned a ringtone specifically to this calling
        // account in Settings → Calling → Ringtone per SIM. Empty / unknown id falls
        // through to the system default below.
        val simId = accountHandle?.id
        if (!simId.isNullOrBlank()) {
            val simUri = SettingsRepository.simRingtones.value[simId]
            if (!simUri.isNullOrBlank()) {
                runCatching { return Uri.parse(simUri) }
            }
        }
        return RingtoneManager.getActualDefaultRingtoneUri(
            context,
            RingtoneManager.TYPE_RINGTONE,
        ) ?: Uri.parse("content://settings/system/ringtone")
    }

    private fun contactHasNumber(contactId: Long, number: String): Boolean {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
                arrayOf(contactId.toString()),
                null,
            )?.use { c ->
                val nIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    @Suppress("DEPRECATION")
                    if (PhoneNumberUtils.compare(number, c.getString(nIdx).orEmpty())) return@use true
                }
                false
            } ?: false
        }.getOrDefault(false)
    }
}
