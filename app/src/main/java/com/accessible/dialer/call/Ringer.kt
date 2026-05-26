package com.accessible.dialer.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils

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

    fun start(callerNumber: String?) {
        // Already playing — nothing to do. The system can re-deliver STATE_RINGING when
        // the call's details change, and we don't want to restart the tone every time.
        if (ringtone?.isPlaying == true || vibrating) return

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            val uri = resolveRingtoneUri(callerNumber)
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
     * Looks up the contact whose phone number matches [number] and returns their
     * `CUSTOM_RINGTONE` URI if set; otherwise the system default ringtone.
     */
    private fun resolveRingtoneUri(number: String?): Uri {
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
