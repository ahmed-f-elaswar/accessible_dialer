package com.accessible.dialer.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight app settings store backed by SharedPreferences. Each setting is exposed as
 * a [StateFlow] so the UI can collect and recompose on change without observing the raw
 * SharedPreferences listener machinery.
 *
 * SharedPreferences was preferred over DataStore here to avoid pulling in another
 * coroutines library just for a handful of scalar prefs.
 */
object SettingsRepository {
    private const val FILE = "accessible_dialer_settings"

    private const val KEY_THEME = "theme_mode"
    private const val KEY_TEXT_SCALE = "text_scale"
    private const val KEY_SORT_ORDER = "contacts_sort_order"
    private const val KEY_SHOW_NO_PHONE = "contacts_show_no_phone"
    private const val KEY_HAPTIC = "dialpad_haptic"
    private const val KEY_VERBOSE_DIGITS = "dialpad_verbose_digits"
    private const val KEY_PHONE_ACCOUNT = "default_phone_account_id"
    private const val KEY_PHONE_ACCOUNT_PKG = "default_phone_account_pkg"
    private const val KEY_PHONE_ACCOUNT_CLS = "default_phone_account_cls"
    private const val KEY_CONTACT_ACCOUNT_FILTER = "contact_account_filter"
    private const val KEY_BLOCK_MODE = "block_mode"
    private const val KEY_LAST_TAB = "last_tab"
    private const val KEY_QUIET_ENABLED = "quiet_enabled"
    private const val KEY_QUIET_START = "quiet_start_min"
    private const val KEY_QUIET_END = "quiet_end_min"
    private const val KEY_QUIET_BREAK_THRESHOLD = "quiet_break_threshold"
    private const val KEY_WELCOME_SHOWN = "welcome_shown"
    // Map of PhoneAccountHandle.id -> ringtone content URI (as String). Stored as a
    // String set of "id|uri" entries so we can fit multiple SIMs in one pref without
    // pulling in JSON. Keys with empty URIs are treated as "use system default".
    private const val KEY_SIM_RINGTONES = "sim_ringtones"
    // When true, the CallScreeningService applies [BlockMode] to every incoming call
    // whose number does not match a stored contact (treated as "unknown").
    private const val KEY_BLOCK_UNKNOWN = "block_unknown"
    // Map of dialpad digit (1-9) -> phone number. StringSet of "digit|number" entries.
    // A missing digit means "no speed dial bound"; long-press on that digit falls back
    // to normal long-press behavior (or no-op for digits without a default).
    private const val KEY_SPEED_DIAL = "speed_dial_map"
    // Whether shaking the phone while in the foreground places a call to the
    // configured speed-dial contact ([KEY_SHAKE_CALL_NUMBER]).
    private const val KEY_SHAKE_CALL_ENABLED = "shake_to_call_enabled"
    private const val KEY_SHAKE_CALL_NUMBER = "shake_to_call_number"
    // Whether shaking the phone while a call is ringing answers the call.
    private const val KEY_SHAKE_ANSWER_ENABLED = "shake_to_answer_enabled"
    // Whether shaking the phone while a call is connected ends the call.
    private const val KEY_SHAKE_END_ENABLED = "shake_to_end_enabled"
    // Auto-enable the speakerphone when the proximity sensor reports the phone is
    // far from the user's face (e.g. the user moved it away from their ear); auto-
    // disable when the phone returns close.
    private const val KEY_PROXIMITY_SPEAKER_ENABLED = "proximity_speaker_enabled"
    enum class ThemeMode { System, Light, Dark }
    enum class TextScale(val factor: Float) {
        Small(0.9f), Default(1.0f), Large(1.2f), ExtraLarge(1.45f);
    }
    enum class SortOrder { FirstName, LastName }

    /**
     * How blocked calls are handled on the caller's side.
     *  - [Reject]: call ends immediately; caller hears a short tone / busy and is
     *    typically routed to voicemail by the carrier.
     *  - [SilentRing]: the call rings normally on the caller's end (so they don't
     *    realize they're blocked) and your phone stays silent. After their ring
     *    timeout it goes to voicemail.
     */
    enum class BlockMode { Reject, SilentRing }

    data class PhoneAccountRef(
        val componentPackage: String,
        val componentClass: String,
        val id: String,
    )

    private lateinit var prefs: SharedPreferences

    private val _theme = MutableStateFlow(ThemeMode.System)
    val theme: StateFlow<ThemeMode> get() = _theme.asStateFlow()

    private val _textScale = MutableStateFlow(TextScale.Default)
    val textScale: StateFlow<TextScale> get() = _textScale.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.FirstName)
    val sortOrder: StateFlow<SortOrder> get() = _sortOrder.asStateFlow()

    private val _showNoPhone = MutableStateFlow(false)
    val showNoPhone: StateFlow<Boolean> get() = _showNoPhone.asStateFlow()

    private val _haptic = MutableStateFlow(true)
    val haptic: StateFlow<Boolean> get() = _haptic.asStateFlow()

    private val _verboseDigits = MutableStateFlow(false)
    val verboseDigits: StateFlow<Boolean> get() = _verboseDigits.asStateFlow()

    private val _phoneAccount = MutableStateFlow<PhoneAccountRef?>(null)
    val phoneAccount: StateFlow<PhoneAccountRef?> get() = _phoneAccount.asStateFlow()

    // Set of "<account_type>|<account_name>" keys. Empty = show every account (no filter).
    // Null account_type/name (local contacts) is encoded as the literal string "null|null".
    private val _accountFilter = MutableStateFlow<Set<String>>(emptySet())
    val accountFilter: StateFlow<Set<String>> get() = _accountFilter.asStateFlow()

    private val _blockMode = MutableStateFlow(BlockMode.Reject)
    val blockMode: StateFlow<BlockMode> get() = _blockMode.asStateFlow()

    // Name of the last-opened bottom-nav tab (e.g. "Dialpad", "Contacts"). Plain string
    // so the UI layer can map it to its private Tab enum without leaking the enum here.
    private val _lastTab = MutableStateFlow<String?>(null)
    val lastTab: StateFlow<String?> get() = _lastTab.asStateFlow()

    // --- Quiet hours -------------------------------------------------------
    // When enabled and the wall clock is inside [_quietStartMinute, _quietEndMinute),
    // the CallScreeningService silently rejects every incoming call. Users can still
    // be reached in emergencies because we count repeated calls from the *same* number
    // within a short window and let them through once that count crosses the threshold.
    private val _quietEnabled = MutableStateFlow(false)
    val quietEnabled: StateFlow<Boolean> get() = _quietEnabled.asStateFlow()

    // Minutes since midnight (0..1439). If start == end the window is treated as
    // "always on" (i.e. silent always); we don't expose that in the UI though.
    private val _quietStart = MutableStateFlow(22 * 60) // 22:00
    val quietStart: StateFlow<Int> get() = _quietStart.asStateFlow()

    private val _quietEnd = MutableStateFlow(7 * 60) // 07:00
    val quietEnd: StateFlow<Int> get() = _quietEnd.asStateFlow()

    /** Number of repeated calls from the same caller (within 15 min) that breaks quiet hours. 0 disables the bypass. */
    private val _quietBreakThreshold = MutableStateFlow(3)
    val quietBreakThreshold: StateFlow<Int> get() = _quietBreakThreshold.asStateFlow()

    /** True once the first-launch welcome / default-role dialog has been dismissed. */
    private val _welcomeShown = MutableStateFlow(false)
    val welcomeShown: StateFlow<Boolean> get() = _welcomeShown.asStateFlow()

    // PhoneAccountHandle.id -> ringtone content URI string. Empty map means every SIM
    // falls back to either the contact's CUSTOM_RINGTONE or the global system default.
    private val _simRingtones = MutableStateFlow<Map<String, String>>(emptyMap())
    val simRingtones: StateFlow<Map<String, String>> get() = _simRingtones.asStateFlow()

    private val _blockUnknown = MutableStateFlow(false)
    val blockUnknown: StateFlow<Boolean> get() = _blockUnknown.asStateFlow()

    // Digit (1..9) -> phone number bound to a long-press on that dialpad key. Digits
    // not present in the map have no speed-dial action (long-press is a no-op or
    // falls back to the default for that key, e.g. '+' for 0).
    private val _speedDial = MutableStateFlow<Map<Int, String>>(emptyMap())
    val speedDial: StateFlow<Map<Int, String>> get() = _speedDial.asStateFlow()

    private val _shakeToCallEnabled = MutableStateFlow(false)
    val shakeToCallEnabled: StateFlow<Boolean> get() = _shakeToCallEnabled.asStateFlow()

    /** Phone number called when shake-to-call fires. Empty = no destination set. */
    private val _shakeToCallNumber = MutableStateFlow("")
    val shakeToCallNumber: StateFlow<String> get() = _shakeToCallNumber.asStateFlow()

    private val _shakeToAnswerEnabled = MutableStateFlow(false)
    val shakeToAnswerEnabled: StateFlow<Boolean> get() = _shakeToAnswerEnabled.asStateFlow()

    private val _shakeToEndEnabled = MutableStateFlow(false)
    val shakeToEndEnabled: StateFlow<Boolean> get() = _shakeToEndEnabled.asStateFlow()

    private val _proximitySpeakerEnabled = MutableStateFlow(false)
    val proximitySpeakerEnabled: StateFlow<Boolean> get() = _proximitySpeakerEnabled.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _theme.value = ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.System.name) ?: ThemeMode.System.name)
        _textScale.value = TextScale.valueOf(prefs.getString(KEY_TEXT_SCALE, TextScale.Default.name) ?: TextScale.Default.name)
        _sortOrder.value = SortOrder.valueOf(prefs.getString(KEY_SORT_ORDER, SortOrder.FirstName.name) ?: SortOrder.FirstName.name)
        _showNoPhone.value = prefs.getBoolean(KEY_SHOW_NO_PHONE, false)
        _haptic.value = prefs.getBoolean(KEY_HAPTIC, true)
        _verboseDigits.value = prefs.getBoolean(KEY_VERBOSE_DIGITS, false)
        val pkg = prefs.getString(KEY_PHONE_ACCOUNT_PKG, null)
        val cls = prefs.getString(KEY_PHONE_ACCOUNT_CLS, null)
        val id = prefs.getString(KEY_PHONE_ACCOUNT, null)
        _phoneAccount.value = if (pkg != null && cls != null && id != null) PhoneAccountRef(pkg, cls, id) else null
        _accountFilter.value = prefs.getStringSet(KEY_CONTACT_ACCOUNT_FILTER, emptySet())?.toSet() ?: emptySet()
        _blockMode.value = BlockMode.valueOf(prefs.getString(KEY_BLOCK_MODE, BlockMode.Reject.name) ?: BlockMode.Reject.name)
        _lastTab.value = prefs.getString(KEY_LAST_TAB, null)
        _quietEnabled.value = prefs.getBoolean(KEY_QUIET_ENABLED, false)
        _quietStart.value = prefs.getInt(KEY_QUIET_START, 22 * 60).coerceIn(0, 1439)
        _quietEnd.value = prefs.getInt(KEY_QUIET_END, 7 * 60).coerceIn(0, 1439)
        _quietBreakThreshold.value = prefs.getInt(KEY_QUIET_BREAK_THRESHOLD, 3).coerceAtLeast(0)
        _welcomeShown.value = prefs.getBoolean(KEY_WELCOME_SHOWN, false)
        _simRingtones.value = (prefs.getStringSet(KEY_SIM_RINGTONES, emptySet()) ?: emptySet())
            .mapNotNull { entry ->
                // Stored as "<id>|<uri>". id can be empty (e.g. fallback SIM with no
                // PhoneAccountHandle.id), but uri must be present and non-blank.
                val sep = entry.indexOf('|')
                if (sep < 0) return@mapNotNull null
                val id = entry.substring(0, sep)
                val uri = entry.substring(sep + 1)
                if (uri.isBlank()) null else id to uri
            }
            .toMap()
        _blockUnknown.value = prefs.getBoolean(KEY_BLOCK_UNKNOWN, false)
        _speedDial.value = (prefs.getStringSet(KEY_SPEED_DIAL, emptySet()) ?: emptySet())
            .mapNotNull { entry ->
                // "<digit>|<number>". Digit must parse to 1..9; number must be non-blank.
                val sep = entry.indexOf('|')
                if (sep < 0) return@mapNotNull null
                val d = entry.substring(0, sep).toIntOrNull() ?: return@mapNotNull null
                if (d !in 1..9) return@mapNotNull null
                val n = entry.substring(sep + 1)
                if (n.isBlank()) null else d to n
            }
            .toMap()
        _shakeToCallEnabled.value = prefs.getBoolean(KEY_SHAKE_CALL_ENABLED, false)
        _shakeToCallNumber.value = prefs.getString(KEY_SHAKE_CALL_NUMBER, "") ?: ""
        _shakeToAnswerEnabled.value = prefs.getBoolean(KEY_SHAKE_ANSWER_ENABLED, false)
        _shakeToEndEnabled.value = prefs.getBoolean(KEY_SHAKE_END_ENABLED, false)
        _proximitySpeakerEnabled.value = prefs.getBoolean(KEY_PROXIMITY_SPEAKER_ENABLED, false)
    }
    fun setTheme(mode: ThemeMode) { _theme.value = mode; prefs.edit { putString(KEY_THEME, mode.name) } }
    fun setTextScale(s: TextScale) { _textScale.value = s; prefs.edit { putString(KEY_TEXT_SCALE, s.name) } }
    fun setSortOrder(o: SortOrder) { _sortOrder.value = o; prefs.edit { putString(KEY_SORT_ORDER, o.name) } }
    fun setShowNoPhone(v: Boolean) { _showNoPhone.value = v; prefs.edit { putBoolean(KEY_SHOW_NO_PHONE, v) } }
    fun setHaptic(v: Boolean) { _haptic.value = v; prefs.edit { putBoolean(KEY_HAPTIC, v) } }
    fun setVerboseDigits(v: Boolean) { _verboseDigits.value = v; prefs.edit { putBoolean(KEY_VERBOSE_DIGITS, v) } }
    fun setPhoneAccount(ref: PhoneAccountRef?) {
        _phoneAccount.value = ref
        prefs.edit {
            if (ref == null) {
                remove(KEY_PHONE_ACCOUNT); remove(KEY_PHONE_ACCOUNT_PKG); remove(KEY_PHONE_ACCOUNT_CLS)
            } else {
                putString(KEY_PHONE_ACCOUNT, ref.id)
                putString(KEY_PHONE_ACCOUNT_PKG, ref.componentPackage)
                putString(KEY_PHONE_ACCOUNT_CLS, ref.componentClass)
            }
        }
    }

    fun setAccountFilter(keys: Set<String>) {
        _accountFilter.value = keys
        prefs.edit { putStringSet(KEY_CONTACT_ACCOUNT_FILTER, keys) }
    }

    fun setBlockMode(mode: BlockMode) {
        _blockMode.value = mode
        prefs.edit { putString(KEY_BLOCK_MODE, mode.name) }
    }

    fun setLastTab(name: String) {
        _lastTab.value = name
        prefs.edit { putString(KEY_LAST_TAB, name) }
    }

    fun setQuietEnabled(v: Boolean) { _quietEnabled.value = v; prefs.edit { putBoolean(KEY_QUIET_ENABLED, v) } }
    fun setQuietStart(min: Int) {
        val v = min.coerceIn(0, 1439); _quietStart.value = v; prefs.edit { putInt(KEY_QUIET_START, v) }
    }
    fun setQuietEnd(min: Int) {
        val v = min.coerceIn(0, 1439); _quietEnd.value = v; prefs.edit { putInt(KEY_QUIET_END, v) }
    }
    fun setQuietBreakThreshold(n: Int) {
        val v = n.coerceAtLeast(0); _quietBreakThreshold.value = v; prefs.edit { putInt(KEY_QUIET_BREAK_THRESHOLD, v) }
    }

    fun setWelcomeShown(v: Boolean) { _welcomeShown.value = v; prefs.edit { putBoolean(KEY_WELCOME_SHOWN, v) } }

    /**
     * Persist a ringtone URI for the SIM identified by [phoneAccountId]. Pass a blank
     * [uri] (or use [clearSimRingtone]) to remove the override and fall back to the
     * per-contact / system default.
     */
    fun setSimRingtone(phoneAccountId: String, uri: String) {
        val current = _simRingtones.value.toMutableMap()
        if (uri.isBlank()) current.remove(phoneAccountId) else current[phoneAccountId] = uri
        _simRingtones.value = current.toMap()
        prefs.edit {
            putStringSet(
                KEY_SIM_RINGTONES,
                current.entries.map { "${it.key}|${it.value}" }.toSet(),
            )
        }
    }

    fun clearSimRingtone(phoneAccountId: String) = setSimRingtone(phoneAccountId, "")

    fun setBlockUnknown(v: Boolean) {
        _blockUnknown.value = v
        prefs.edit { putBoolean(KEY_BLOCK_UNKNOWN, v) }
    }

    /**
     * Bind [number] to long-press on dialpad digit [digit] (1..9). Passing a blank
     * [number] removes the binding (equivalent to [clearSpeedDial]).
     */
    fun setSpeedDial(digit: Int, number: String) {
        if (digit !in 1..9) return
        val current = _speedDial.value.toMutableMap()
        if (number.isBlank()) current.remove(digit) else current[digit] = number
        _speedDial.value = current.toMap()
        prefs.edit {
            putStringSet(
                KEY_SPEED_DIAL,
                current.entries.map { "${it.key}|${it.value}" }.toSet(),
            )
        }
    }

    fun clearSpeedDial(digit: Int) = setSpeedDial(digit, "")

    fun setShakeToCallEnabled(v: Boolean) {
        _shakeToCallEnabled.value = v
        prefs.edit { putBoolean(KEY_SHAKE_CALL_ENABLED, v) }
    }

    fun setShakeToCallNumber(number: String) {
        _shakeToCallNumber.value = number
        prefs.edit { putString(KEY_SHAKE_CALL_NUMBER, number) }
    }

    fun setShakeToAnswerEnabled(v: Boolean) {
        _shakeToAnswerEnabled.value = v
        prefs.edit { putBoolean(KEY_SHAKE_ANSWER_ENABLED, v) }
    }

    fun setShakeToEndEnabled(v: Boolean) {
        _shakeToEndEnabled.value = v
        prefs.edit { putBoolean(KEY_SHAKE_END_ENABLED, v) }
    }

    fun setProximitySpeakerEnabled(v: Boolean) {
        _proximitySpeakerEnabled.value = v
        prefs.edit { putBoolean(KEY_PROXIMITY_SPEAKER_ENABLED, v) }
    }
}
