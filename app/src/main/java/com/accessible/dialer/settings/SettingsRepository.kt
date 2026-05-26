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
}
