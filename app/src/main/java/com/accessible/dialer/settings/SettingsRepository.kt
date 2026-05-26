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

    enum class ThemeMode { System, Light, Dark }
    enum class TextScale(val factor: Float) {
        Small(0.9f), Default(1.0f), Large(1.2f), ExtraLarge(1.45f);
    }
    enum class SortOrder { FirstName, LastName }

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
}
