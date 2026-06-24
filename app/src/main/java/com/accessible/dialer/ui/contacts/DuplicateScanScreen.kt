package com.accessible.dialer.ui.contacts

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Duplicate-detection wizard. Scans all contacts, groups candidates that share at
 * least one of: identical name (case-insensitive), identical email, or identical
 * phone (last 7 digits). For each group the user can merge with a single tap.
 *
 * Merge is implemented via `ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER`
 * on every pair of raw_contact_ids in the group. The system aggregates them into one
 * displayed contact while keeping the underlying raw rows intact (so the merge is
 * reversible via AggregationExceptions).
 */
internal data class DuplicateGroup(
    val key: String,
    val reason: String,
    val contactIds: List<Long>,
    val displayNames: List<String>,
    /**
     * Per-contact account-key set, e.g. `42L -> {"com.google|me@gmail.com"}` so the
     * UI can render where each side of the pair is stored. Multi-account aggregated
     * contacts get >1 key. Missing or empty falls back to the synthetic local key.
     */
    val accountKeysByContactId: Map<Long, Set<String>> = emptyMap(),
    // When set, "merging" means deleting these Data rows (keeping one phone row per
    // normalized number on a single contact) rather than aggregating multiple
    // contacts together. Used for the intra-contact "same number listed twice" case.
    val rowIdsToDelete: List<Long> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DuplicateScanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var groups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }
    // When true the next scan also runs fuzzy name matching (transposed tokens,
    // diacritic-insensitive equality, edit-distance â‰¤ 2) on top of the strict
    // exact-match scan. Default off so the cheap exact-match scan still loads
    // instantly; the user opts into the heavier O(NÂ²) sweep via the "Smart scan"
    // toolbar button.
    var smartMode by remember { mutableStateOf(false) }
    var confirmGroup by remember { mutableStateOf<DuplicateGroup?>(null) }
    var confirmMergeAll by remember { mutableStateOf(false) }
    var mergingAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadKey) {
        loading = true
        val raw = withContext(Dispatchers.IO) { scanDuplicates(context, smart = smartMode) }
        val dismissed = DuplicateDismissals.load(context)
        groups = raw.filterNot { it.key in dismissed }
        loading = false
    }

    val backLabel = stringResource(R.string.action_back)
    val title = stringResource(R.string.duplicates_title)

    confirmGroup?.let { g ->
        AlertDialog(
            onDismissRequest = { confirmGroup = null },
            title = { Text(stringResource(R.string.duplicates_merge_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.duplicates_merge_confirm_message,
                        g.displayNames.joinToString(", "),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = if (g.rowIdsToDelete.isNotEmpty()) {
                        deleteDataRows(context, g.rowIdsToDelete)
                    } else {
                        mergeContacts(context, g.contactIds)
                    }
                    confirmGroup = null
                    if (ok) {
                        Toast.makeText(context, R.string.duplicates_merged, Toast.LENGTH_SHORT).show()
                        reloadKey += 1
                    } else {
                        Toast.makeText(context, R.string.duplicates_merge_failed, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.duplicates_merge)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmGroup = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmMergeAll) {
        AlertDialog(
            onDismissRequest = { confirmMergeAll = false },
            title = { Text(stringResource(R.string.duplicates_merge_all_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.duplicates_merge_all_confirm_message,
                        groups.size,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmMergeAll = false
                    val toMerge = groups
                    mergingAll = true
                    scope.launch {
                        val total = toMerge.size
                        val success = withContext(Dispatchers.IO) {
                            var count = 0
                            for (g in toMerge) {
                                val ok = if (g.rowIdsToDelete.isNotEmpty()) {
                                    deleteDataRows(context, g.rowIdsToDelete)
                                } else {
                                    mergeContacts(context, g.contactIds)
                                }
                                if (ok) count++
                            }
                            count
                        }
                        mergingAll = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.duplicates_merge_all_result, success, total),
                            Toast.LENGTH_SHORT,
                        ).show()
                        reloadKey += 1
                    }
                }) { Text(stringResource(R.string.duplicates_merge_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmMergeAll = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        smartMode = true
                        reloadKey += 1
                    }) {
                        Text(stringResource(R.string.duplicates_smart_scan))
                    }
                    TextButton(onClick = {
                        smartMode = false
                        reloadKey += 1
                    }) {
                        Text(stringResource(R.string.duplicates_rescan))
                    }
                    TextButton(onClick = {
                        DuplicateDismissals.clear(context)
                        Toast.makeText(
                            context,
                            R.string.duplicates_restored,
                            Toast.LENGTH_SHORT,
                        ).show()
                        reloadKey += 1
                    }) {
                        Text(stringResource(R.string.duplicates_restore_dismissed))
                    }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when {
                loading || mergingAll -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.duplicates_scanning))
                    }
                }
                groups.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            stringResource(R.string.duplicates_none_found),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.duplicates_none_found_sub),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item(key = "__merge_all__") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${groups.size}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Button(onClick = { confirmMergeAll = true }) {
                                    Icon(Icons.Filled.MergeType, contentDescription = null)
                                    Spacer(Modifier.size(6.dp))
                                    Text(stringResource(R.string.duplicates_merge_all))
                                }
                            }
                            HorizontalDivider()
                        }
                        items(groups, key = { it.key }) { g ->
                            DuplicateGroupRow(
                                group = g,
                                onMerge = { confirmGroup = g },
                                onDismiss = {
                                    DuplicateDismissals.dismiss(context, g.key)
                                    groups = groups.filterNot { it.key == g.key }
                                    Toast.makeText(
                                        context,
                                        R.string.duplicates_dismissed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupRow(
    group: DuplicateGroup,
    onMerge: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // One row per contact in the group so the user can see "Jane (Google) +
        // Jane (SIM 1)" at a glance and decide whether merging is sensible.
        group.contactIds.forEachIndexed { i, id ->
            val name = group.displayNames.getOrNull(i) ?: "(unnamed)"
            val keys = group.accountKeysByContactId[id].orEmpty()
            val storage = if (keys.isEmpty()) {
                friendlyAccountLabel(context, com.accessible.dialer.util.ContactAccounts.LOCAL_KEY)
            } else {
                keys.joinToString(", ") { friendlyAccountLabel(context, it) }
            }
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.duplicates_stored_in, storage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = group.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onMerge) {
                Icon(Icons.Filled.MergeType, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.duplicates_merge))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.duplicates_not_duplicate))
            }
        }
    }
}

/* ---------------- Scanning ---------------- */

private data class ScanRow(
    val contactId: Long,
    val displayName: String,
    val phoneLast7: Set<String>,
    val emails: Set<String>,
    // Every phone row this contact has, keyed by Data._ID, with the original raw
    // string. Used to find duplicate numbers within a single contact (e.g. "555 1234"
    // and "+1-555-1234" stored twice).
    val phoneRows: List<Pair<Long, String>>,
)

private fun scanDuplicates(context: Context, smart: Boolean = false): List<DuplicateGroup> {
    val cr = context.contentResolver
    val rowsById = mutableMapOf<Long, ScanRow>()

    cr.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data._ID,
        ),
        "${ContactsContract.Data.MIMETYPE} IN (?,?)",
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        ),
        null,
    )?.use { c ->
        while (c.moveToNext()) {
            val contactId = c.getLong(0)
            val mime = c.getString(1)
            val data = c.getString(2).orEmpty()
            val name = c.getString(3).orEmpty()
            val rowId = c.getLong(4)
            val existing = rowsById[contactId]
            val phones = existing?.phoneLast7?.toMutableSet() ?: mutableSetOf()
            val emails = existing?.emails?.toMutableSet() ?: mutableSetOf()
            val phoneRows = existing?.phoneRows?.toMutableList() ?: mutableListOf()
            when (mime) {
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                    val digits = data.filter { it.isDigit() }
                    if (digits.length >= 7) phones += digits.takeLast(7)
                    if (data.isNotBlank()) phoneRows += rowId to data
                }
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                    if (data.isNotBlank()) emails += data.trim().lowercase()
                }
            }
            rowsById[contactId] = ScanRow(contactId, name, phones, emails, phoneRows)
        }
    }

    // Also include name-only contacts (no phone/email rows yet).
    cr.query(
        ContactsContract.Contacts.CONTENT_URI,
        arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ),
        null, null, null,
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val name = c.getString(1).orEmpty()
            if (rowsById[id] == null && name.isNotBlank()) {
                rowsById[id] = ScanRow(id, name, emptySet(), emptySet(), emptyList())
            }
        }
    }

    val rows = rowsById.values.toList()

    // One pass over RawContacts to gather every account each aggregated contact
    // is backed by. Cheaper than per-group queries, and we need it for the
    // "stored in" line under each contact name in the merge UI.
    val accountKeysByContactId = mutableMapOf<Long, MutableSet<String>>()
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
                val type = c.getString(tIdx)
                val name = c.getString(nIdx)
                val key = if (type == null && name == null) {
                    com.accessible.dialer.util.ContactAccounts.LOCAL_KEY
                } else {
                    "${type ?: "null"}|${name ?: "null"}"
                }
                accountKeysByContactId.getOrPut(c.getLong(idIdx)) { mutableSetOf() }.add(key)
            }
        }
    }

    val phoneIdx = mutableMapOf<String, MutableSet<Long>>()
    val emailIdx = mutableMapOf<String, MutableSet<Long>>()
    val nameIdx = mutableMapOf<String, MutableSet<Long>>()
    rows.forEach { r ->
        r.phoneLast7.forEach { phoneIdx.getOrPut(it) { mutableSetOf() }.add(r.contactId) }
        r.emails.forEach { emailIdx.getOrPut(it) { mutableSetOf() }.add(r.contactId) }
        val norm = r.displayName.trim().lowercase()
        if (norm.isNotBlank()) {
            nameIdx.getOrPut(norm) { mutableSetOf() }.add(r.contactId)
        }
    }

    val groups = mutableListOf<DuplicateGroup>()
    val seenSignatures = mutableSetOf<String>()

    fun addGroup(ids: Set<Long>, reasonKey: String, reason: String) {
        if (ids.size < 2) return
        val sortedIds = ids.sorted()
        val signature = reasonKey + ":" + sortedIds.joinToString(",")
        if (!seenSignatures.add(signature)) return
        val accountsMap = sortedIds.associateWith {
            accountKeysByContactId[it]?.toSet().orEmpty()
        }
        // Skip groups whose contacts live in disjoint accounts. The user keeps
        // separate copies in (say) Google vs. SIM on purpose \u2014 surfacing them as
        // a "duplicate" would push them into merging accounts they want kept
        // apart. We only flag a group when every contact in it shares at least
        // one account with every other contact; contacts with no account at all
        // (the synthetic local bucket) are treated as a wildcard so legitimate
        // intra-account dupes that include a phone-only entry still surface.
        val accountSets = sortedIds.map { accountsMap[it].orEmpty() }
        val withRealAccounts = accountSets.filter { it.isNotEmpty() }
        if (withRealAccounts.size >= 2) {
            val shared = withRealAccounts.reduce { acc, next -> acc.intersect(next) }
            if (shared.isEmpty()) return
        }
        val names = sortedIds.map { id ->
            rowsById[id]?.displayName?.ifBlank { "(unnamed)" } ?: "(unnamed)"
        }
        groups += DuplicateGroup(signature, reason, sortedIds, names, accountsMap)
    }

    phoneIdx.forEach { (digits, ids) ->
        addGroup(ids, "phone:$digits", "Same phone ending in $digits")
    }
    emailIdx.forEach { (email, ids) ->
        addGroup(ids, "email:$email", "Same email $email")
    }
    nameIdx.forEach { (name, ids) ->
        addGroup(ids, "name:$name", "Same name \"$name\"")
    }

    // Intra-contact duplicates: same contact holds multiple phone rows that
    // normalize to the same digit sequence (ignoring spaces, dashes, parentheses,
    // "+" prefix, and country code via last-7 matching). Group keeps the first row
    // and proposes deleting the rest.
    rows.forEach { r ->
        if (r.phoneRows.size < 2) return@forEach
        val byNorm = LinkedHashMap<String, MutableList<Pair<Long, String>>>()
        r.phoneRows.forEach { (rowId, raw) ->
            val digits = raw.filter { it.isDigit() }
            if (digits.isEmpty()) return@forEach
            val key = if (digits.length >= 7) digits.takeLast(7) else digits
            byNorm.getOrPut(key) { mutableListOf() }.add(rowId to raw)
        }
        byNorm.forEach { (norm, list) ->
            if (list.size < 2) return@forEach
            // Keep the first row, propose deleting the rest.
            val keepers = list.first()
            val toDelete = list.drop(1).map { it.first }
            val variants = list.joinToString(", ") { "\"${it.second}\"" }
            val signature = "intra-phone:${r.contactId}:$norm"
            if (!seenSignatures.add(signature)) return@forEach
            val name = r.displayName.ifBlank { "(unnamed)" }
            groups += DuplicateGroup(
                key = signature,
                reason = "$name has the same number listed more than once ($variants). Keep \"${keepers.second}\" and remove the rest.",
                contactIds = listOf(r.contactId),
                displayNames = listOf(name),
                accountKeysByContactId = mapOf(
                    r.contactId to accountKeysByContactId[r.contactId]?.toSet().orEmpty()
                ),
                rowIdsToDelete = toDelete,
            )
        }
    }
    // Fuzzy name matching (opt-in via the "Smart scan" toolbar button). Catches
    // common ways the same person ends up stored twice with slightly different
    // names: diacritics, reordered tokens (Smith John vs John Smith), token-
    // subset matches (John A Smith vs John Smith), and single-character typos
    // via Damerau-Levenshtein distance up to 2 on the diacritic-stripped form.
    if (smart) {
        data class FuzzyRow(val id: Long, val tokens: List<String>, val flat: String)
        val fuzzy = rows.mapNotNull { r ->
            val tokens = normalizeForFuzzy(r.displayName)
            if (tokens.isEmpty()) null
            else FuzzyRow(r.contactId, tokens.sorted(), tokens.joinToString(""))
        }
        val byTokens = mutableMapOf<List<String>, MutableSet<Long>>()
        fuzzy.forEach { byTokens.getOrPut(it.tokens) { mutableSetOf() }.add(it.id) }
        byTokens.forEach { (toks, ids) ->
            addGroup(
                ids,
                "fuzzy-tokens:" + toks.joinToString("|"),
                "Likely the same name: \"" + toks.joinToString(" ") + "\"",
            )
        }
        val byFirst = fuzzy.groupBy { it.flat.firstOrNull() ?: ' ' }
        byFirst.values.forEach { bucket ->
            for (i in bucket.indices) {
                val a = bucket[i]
                for (j in (i + 1) until bucket.size) {
                    val b = bucket[j]
                    val matched = when {
                        a.tokens == b.tokens -> false
                        a.tokens.size >= 2 && b.tokens.size >= 2 &&
                            (a.tokens.toSet().containsAll(b.tokens) ||
                                b.tokens.toSet().containsAll(a.tokens)) -> true
                        kotlin.math.abs(a.flat.length - b.flat.length) <= 2 &&
                            a.flat.length >= 4 &&
                            damerauLevenshtein(a.flat, b.flat, maxDistance = 2) <= 2 -> true
                        else -> false
                    }
                    if (matched) {
                        val names = listOf(
                            rowsById[a.id]?.displayName.orEmpty(),
                            rowsById[b.id]?.displayName.orEmpty(),
                        ).filter { it.isNotBlank() }
                        addGroup(
                            setOf(a.id, b.id),
                            "fuzzy-pair:" + a.id + "-" + b.id,
                            "Similar names: " + names.joinToString(" / "),
                        )
                    }
                }
            }
        }
    }

    return groups.sortedByDescending { it.contactIds.size }
}

private fun normalizeForFuzzy(name: String): List<String> {
    val nfd = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
    val stripped = buildString {
        for (ch in nfd) {
            if (Character.getType(ch).toByte() != Character.NON_SPACING_MARK) append(ch)
        }
    }.lowercase()
    return stripped.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
}

private fun damerauLevenshtein(a: String, b: String, maxDistance: Int): Int {
    val la = a.length
    val lb = b.length
    if (kotlin.math.abs(la - lb) > maxDistance) return maxDistance + 1
    var prevPrev = IntArray(lb + 1)
    var prev = IntArray(lb + 1) { it }
    var curr = IntArray(lb + 1)
    for (i in 1..la) {
        curr[0] = i
        var rowMin = curr[0]
        for (j in 1..lb) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(
                curr[j - 1] + 1,
                prev[j] + 1,
                prev[j - 1] + cost,
            )
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                v = minOf(v, prevPrev[j - 2] + 1)
            }
            curr[j] = v
            if (v < rowMin) rowMin = v
        }
        if (rowMin > maxDistance) return maxDistance + 1
        val tmp = prevPrev
        prevPrev = prev
        prev = curr
        curr = tmp
    }
    return prev[lb]
}
/* ---------------- Intra-contact dedupe ---------------- */

private fun deleteDataRows(context: Context, rowIds: List<Long>): Boolean {
    if (rowIds.isEmpty()) return false
    val cr = context.contentResolver
    val ops = arrayListOf<ContentProviderOperation>()
    rowIds.forEach { id ->
        ops += ContentProviderOperation.newDelete(
            ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)
        ).build()
    }
    return runCatching {
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    }.getOrDefault(false)
}

/* ---------------- Merging ---------------- */

internal fun mergeContacts(context: Context, contactIds: List<Long>): Boolean {
    if (contactIds.size < 2) return false
    val cr = context.contentResolver
    // Resolve one raw contact id per aggregated contact id. We need at least one raw
    // contact from each contact so AggregationExceptions can pair them up.
    val rawIds = mutableListOf<Long>()
    contactIds.forEach { cid ->
        cr.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=?",
            arrayOf(cid.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) rawIds += c.getLong(0) }
    }
    if (rawIds.size < 2) return false

    val ops = arrayListOf<ContentProviderOperation>()
    // Insert KEEP_TOGETHER for every unique pair.
    for (i in rawIds.indices) {
        for (j in i + 1 until rawIds.size) {
            ops += ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                .withValue(
                    ContactsContract.AggregationExceptions.TYPE,
                    ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER,
                )
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, rawIds[i])
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rawIds[j])
                .build()
        }
    }
    return runCatching {
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    }.getOrDefault(false)
}
