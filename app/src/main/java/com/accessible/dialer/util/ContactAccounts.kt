package com.accessible.dialer.util

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract

/**
 * Enumeration and bulk-mutation helpers for contact *storage* accounts (Google,
 * Samsung, SIM, "local / phone-only", etc.). These are distinct from
 * [PhoneAccounts], which deals with calling/SIM accounts at the Telecom layer.
 *
 * Account identity is encoded throughout the app as `"<ACCOUNT_TYPE>|<ACCOUNT_NAME>"`,
 * with the literal string `"null"` for either component when it is null on disk
 * (matches what [com.accessible.dialer.ui.contacts.ContactsViewModel] writes into
 * [com.accessible.dialer.ui.contacts.Contact.accountKeys]). The reserved key
 * [LOCAL_KEY] (`"null|null"`) represents Android's unsynced "phone-only" bucket.
 *
 * We deliberately do **not** require the `GET_ACCOUNTS` permission — most Android
 * versions still ask the user for it and refusing it would break the screen. The
 * helpers fall back to enumerating only accounts that already own contacts (or
 * groups), which is enough for the in-app filter / move workflows.
 */
object ContactAccounts {

    /** Synthetic key for contacts that have no [ContactsContract.RawContacts.ACCOUNT_TYPE]. */
    const val LOCAL_KEY = "null|null"

    /** Components of a parsed account key. `type == null` <=> local/phone-only. */
    data class Parsed(val type: String?, val name: String?) {
        /** Encoded form ready for prefs / set-comparison. */
        fun toKey(): String = "${type ?: "null"}|${name ?: "null"}"
    }

    /** One row in the account picker / storage settings list. */
    data class Entry(val key: String, val label: String, val count: Int)

    fun parse(key: String): Parsed {
        val parts = key.split("|", limit = 2)
        val t = parts.getOrNull(0)?.takeIf { it != "null" }
        val n = parts.getOrNull(1)?.takeIf { it != "null" }
        return Parsed(t, n)
    }

    /**
     * Returns every account on the device that *could* hold a contact:
     *   1. Every account currently referenced by a `RawContacts` row.
     *   2. Every account that owns a contact `Group` (catches empty cloud accounts
     *      that were freshly added before the user saved anything to them).
     *   3. The system [AccountManager] account list, when readable without
     *      `GET_ACCOUNTS` (Android caches per-package visibility and a same-package
     *      account is always visible — works for accounts the user has logged into
     *      from this app, which is none for us, but the call is harmless otherwise).
     *   4. The synthetic local/phone-only entry, always present.
     *
     * Counts are exact for accounts seen via (1); zero for accounts that come only
     * from (2)/(3) since they hold no aggregated contacts yet. The list is sorted
     * by descending count and then by label so the "where the user is most likely
     * looking" account floats to the top.
     */
    fun list(context: Context): List<Entry> {
        val cr = context.contentResolver
        // Per-account contact counts. We count distinct CONTACT_IDs per account so
        // an aggregated contact spread across two raw rows in the same account is
        // counted once. Reading RawContacts directly avoids needing a JOIN.
        val countsByKey = linkedMapOf<String, MutableSet<Long>>()
        runCatching {
            cr.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts.CONTACT_ID,
                    ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ContactsContract.RawContacts.ACCOUNT_NAME,
                ),
                "${ContactsContract.RawContacts.DELETED}=0",
                null,
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
                val tIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
                val nIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
                while (c.moveToNext()) {
                    val key = "${c.getString(tIdx) ?: "null"}|${c.getString(nIdx) ?: "null"}"
                    countsByKey.getOrPut(key) { mutableSetOf() }.add(c.getLong(idIdx))
                }
            }
        }
        // Accounts visible through Groups but with no current contacts.
        runCatching {
            cr.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(
                    ContactsContract.Groups.ACCOUNT_TYPE,
                    ContactsContract.Groups.ACCOUNT_NAME,
                ),
                null, null, null,
            )?.use { c ->
                val tIdx = c.getColumnIndexOrThrow(ContactsContract.Groups.ACCOUNT_TYPE)
                val nIdx = c.getColumnIndexOrThrow(ContactsContract.Groups.ACCOUNT_NAME)
                while (c.moveToNext()) {
                    val key = "${c.getString(tIdx) ?: "null"}|${c.getString(nIdx) ?: "null"}"
                    countsByKey.getOrPut(key) { mutableSetOf() }
                }
            }
        }
        // AccountManager — best-effort. On API 26+ getAccounts() returns only
        // accounts visible to this package; we don't gate on permission since the
        // call itself throws SecurityException when not allowed and we just swallow.
        runCatching {
            val am = AccountManager.get(context)
            am.accounts.forEach { acc ->
                val key = "${acc.type}|${acc.name}"
                countsByKey.getOrPut(key) { mutableSetOf() }
            }
        }
        // Always include the synthetic local bucket even if zero contacts live in
        // it — it's the default destination when creating a new contact and the
        // user must be able to filter / move into it.
        countsByKey.getOrPut(LOCAL_KEY) { mutableSetOf() }

        return countsByKey.entries
            .map { (k, set) -> Entry(k, friendlyLabel(context, k), set.size) }
            // Sort: nonzero counts first (descending), then alphabetical by label.
            .sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.label.lowercase() })
    }

    /**
     * Same vocabulary as [com.accessible.dialer.ui.contacts.friendlyAccountLabel]
     * but local to this util so callers outside the contacts package don't drag in
     * a UI dependency. Kept in sync with that function by tests / by code review.
     *
     * String-only fallback used when no [Context] is available. Unknown account
     * types fall through to the raw type string (e.g. "com.android.exchange") —
     * prefer the [friendlyLabel] overload that takes a [Context] so we can ask
     * [AccountManager] for the authenticator's human-readable label.
     */
    fun friendlyLabel(key: String): String = staticLabel(key)

    /**
     * Resolve a friendly label for an account key, falling back to the
     * authenticator's own human-readable label (the same string Android shows in
     * Settings → Accounts) for types we don't recognise statically. This avoids
     * leaking raw package-name strings like "com.android.exchange" or
     * "com.sec.android.app.contacts.sim" into the storage picker UI.
     *
     * AuthenticatorDescription lookups are cached per type to keep the per-row
     * cost negligible — list() / editor compose every recomposition for a
     * many-account device would otherwise hammer PackageManager.
     */
    fun friendlyLabel(context: Context, key: String): String {
        val parts = key.split("|", limit = 2)
        val type = parts.getOrNull(0)?.takeIf { it != "null" }
        val name = parts.getOrNull(1)?.takeIf { it != "null" }
        val typeLabel = friendlyTypeLabel(context, type)
        // SIM accounts already encode the slot in the type label ("SIM 1" /
        // "SIM 2"); appending the raw account name ("sim1", "sim2") would just
        // produce noise like "SIM 1 — sim1". Skip the suffix in that case.
        if (typeLabel.startsWith("SIM")) return typeLabel
        return if (name != null) "$typeLabel — $name" else typeLabel
    }

    private fun staticLabel(key: String): String {
        val parts = key.split("|", limit = 2)
        val type = parts.getOrNull(0)?.takeIf { it != "null" }
        val name = parts.getOrNull(1)?.takeIf { it != "null" }
        val typeLabel = staticTypeLabel(type) ?: (type ?: "Local / Phone only")
        return if (name != null) "$typeLabel — $name" else typeLabel
    }

    private fun staticTypeLabel(type: String?): String? = when (type) {
        null -> "Local / Phone only"
        "com.google" -> "Google"
        "com.osp.app.signin", "com.samsung.android.exchange" -> "Samsung"
        "com.huawei.account" -> "Huawei"
        "com.hihonor.id" -> "Honor"
        "com.xiaomi" -> "Mi Account"
        "com.whatsapp" -> "WhatsApp"
        "org.telegram.messenger" -> "Telegram"
        // SIM-card storage providers vary by OEM. Match the common ones and
        // collapse them all to "SIM 1" / "SIM 2" / "SIM card" so the picker
        // shows the slot rather than the vendor's package id.
        "vnd.sec.contact.sim", "com.android.contacts.sim",
        "com.sec.android.app.contacts.sim",
        -> "SIM card"
        "com.android.contacts.sim.sim1",
        "com.android.huawei.sim", "com.android.hihonor.sim",
        -> "SIM 1"
        "com.android.contacts.sim.sim2",
        "com.android.huawei.secondsim", "com.android.hihonor.secondsim",
        -> "SIM 2"
        else -> null
    }

    // Cache the per-type label resolution so list() / picker rows don't hit
    // PackageManager once per row. Cleared lazily — entries are immutable once
    // an authenticator is installed, and a brand-new authenticator type just
    // means we miss it for one app session, which is acceptable.
    private val typeLabelCache = HashMap<String, String>()

    private fun friendlyTypeLabel(context: Context, type: String?): String {
        if (type == null) return "Local / Phone only"
        staticTypeLabel(type)?.let { return it }
        synchronized(typeLabelCache) {
            typeLabelCache[type]?.let { return it }
        }
        // Step 1: ask AccountManager for the authenticator whose `type` matches.
        // This works for any installed authenticator we can see (manifest
        // <queries> grants visibility through Android 11+ package filtering).
        val viaAuthenticator = runCatching {
            val am = AccountManager.get(context)
            val descriptor = am.authenticatorTypes.firstOrNull { it.type == type }
                ?: return@runCatching null
            if (descriptor.labelId == 0 || descriptor.packageName.isNullOrBlank()) {
                return@runCatching null
            }
            val pm = context.packageManager
            val res = pm.getResourcesForApplication(descriptor.packageName)
            res.getString(descriptor.labelId)?.takeIf { it.isNotBlank() }
        }.getOrNull()
        // Step 2: many account types ARE the owning package name (e.g.
        // "com.google.android.apps.tachyon"). Fall back to the app's launcher
        // label so the picker shows "Meet" instead of the raw package id.
        val viaApplicationInfo = viaAuthenticator ?: runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(type, 0)
            pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
        }.getOrNull()
        val label = viaApplicationInfo ?: type
        synchronized(typeLabelCache) { typeLabelCache[type] = label }
        return label
    }

    /**
     * Returns the aggregated contact ids currently stored in [accountKey]. Used by
     * the storage-locations screen to populate the per-account list.
     */
    fun contactIdsIn(context: Context, accountKey: String): List<Long> {
        val parsed = parse(accountKey)
        val ids = LinkedHashSet<Long>()
        val selection: String
        val args: Array<String>
        when {
            parsed.type == null && parsed.name == null -> {
                selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL " +
                    "AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IS NULL " +
                    "AND ${ContactsContract.RawContacts.DELETED}=0"
                args = emptyArray()
            }
            parsed.type == null -> {
                selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL " +
                    "AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? " +
                    "AND ${ContactsContract.RawContacts.DELETED}=0"
                args = arrayOf(parsed.name!!)
            }
            parsed.name == null -> {
                selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? " +
                    "AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IS NULL " +
                    "AND ${ContactsContract.RawContacts.DELETED}=0"
                args = arrayOf(parsed.type!!)
            }
            else -> {
                selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? " +
                    "AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? " +
                    "AND ${ContactsContract.RawContacts.DELETED}=0"
                args = arrayOf(parsed.type!!, parsed.name!!)
            }
        }
        runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                selection, args, null,
            )?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
        }
        return ids.toList()
    }

    /**
     * Copies every raw row of [contactId] (Name, Phone, Email, Address, Website,
     * Event, Nickname, Organization, Note, GroupMembership) from its current
     * account into a new RawContact owned by [destKey], then deletes the source
     * raw rows. Android's aggregation engine will then re-aggregate the new raw
     * contact under either the same or a new Contact id depending on its rules.
     *
     * The function does NOT attempt to migrate sync-extension columns (DATA_SYNC1
     * etc.) — those belong to the source sync adapter and aren't ours to carry.
     *
     * Returns true on success, false on any failure (operation runs in a
     * single applyBatch transaction so partial moves do not leak).
     */
    fun moveContact(context: Context, contactId: Long, destKey: String): Boolean {
        val cr = context.contentResolver
        val dest = parse(destKey)
        // Source raw rows + per-raw data rows.
        val rawIds = mutableListOf<Long>()
        runCatching {
            cr.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=? AND " +
                    "${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(contactId.toString()),
                null,
            )?.use { c -> while (c.moveToNext()) rawIds.add(c.getLong(0)) }
        }
        if (rawIds.isEmpty()) return false

        val ops = arrayListOf<ContentProviderOperation>()
        // Insert one fresh raw contact in the destination account. Index 0 in the
        // batch so subsequent Data inserts can backreference it.
        ops += ContentProviderOperation
            .newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, dest.type)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, dest.name)
            .build()

        // Pull every data row for every source raw contact and re-insert it
        // pointing at the new raw via back-reference.
        val dataProjection = arrayOf(
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1, ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3, ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7, ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9, ContactsContract.Data.DATA10,
            ContactsContract.Data.DATA11, ContactsContract.Data.DATA12,
            ContactsContract.Data.DATA13, ContactsContract.Data.DATA14,
            ContactsContract.Data.DATA15,
        )
        runCatching {
            // Use bound parameters for the IN clause even though every value
            // is a Long (so injection isn't possible); keeps the call shape
            // consistent and avoids future regressions if someone refactors
            // to accept untrusted ids.
            val placeholders = rawIds.joinToString(",") { "?" }
            cr.query(
                ContactsContract.Data.CONTENT_URI,
                dataProjection,
                "${ContactsContract.Data.RAW_CONTACT_ID} IN ($placeholders)",
                rawIds.map { it.toString() }.toTypedArray(),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(0) ?: continue
                    // GroupMembership references rows in the source account's
                    // Groups table; carrying it over would dangle. Skip — the user
                    // can re-add the contact to a destination-account group later.
                    if (mime == ContactsContract.CommonDataKinds.GroupMembership
                            .CONTENT_ITEM_TYPE) continue
                    val b = ContentProviderOperation
                        .newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, mime)
                    for (i in 1..15) {
                        if (!c.isNull(i)) {
                            b.withValue("data$i", c.getString(i))
                        }
                    }
                    ops += b.build()
                }
            }
        }
        // Delete the source raw contacts last so the destination is fully built
        // before aggregation re-runs.
        rawIds.forEach { rid ->
            ops += ContentProviderOperation
                .newDelete(ContentUris.withAppendedId(
                    ContactsContract.RawContacts.CONTENT_URI, rid))
                .build()
        }
        return runCatching {
            cr.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        }.getOrDefault(false)
    }

    /**
     * Deletes the aggregated contact and every raw row underneath it. Returns
     * true on success.
     */
    fun deleteContact(context: Context, contactId: Long): Boolean = runCatching {
        context.contentResolver.delete(
            ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI, contactId),
            null, null,
        ) > 0
    }.getOrDefault(false)
}
