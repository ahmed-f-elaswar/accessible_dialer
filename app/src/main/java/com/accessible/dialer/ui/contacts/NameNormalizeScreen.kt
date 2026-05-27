package com.accessible.dialer.ui.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * "Normalize spelling variants" wizard. Sister tool to [DuplicateScanScreen] and
 * [NameFixScreen]:
 *
 *  - Duplicate scan: same person stored twice → merge.
 *  - Name fix: a single contact's name is dirty (junk chars, casing, wrong split) → clean.
 *  - **This screen**: many *different* people whose first / middle / last name is
 *    spelled inconsistently across the address book (Mohamed / Mohammad / Mahamed,
 *    Catherine / Katherine, Philip / Phillip) and you want to unify the spelling
 *    so search and sort behave consistently.
 *
 * Approach:
 *  1. Read every contact's StructuredName row.
 *  2. Tokenize each slot (given / middle / family) on whitespace.
 *  3. Compute a phonetic key per token — a deliberately aggressive fold that maps
 *     common cross-spelling differences to the same key:
 *       • Unicode NFD → strip combining marks (so "José"/"Jose" collide).
 *       • ASCII letters only.
 *       • Common digraphs first: ph→f, gh→g, ck→k, kh→k, ch→k, sh→s, dh→d, th→t.
 *       • Letter equivalences: c→k, q→k, x→k, z→s, v→f, w→f, b→p, j→y, y→i.
 *       • Collapse adjacent duplicates ("Mohammed" → "Mohamed").
 *       • Drop a trailing plural "s" when length > 3.
 *       • Final key = first letter + (rest with vowels and silent 'h' removed).
 *     Examples that collide on this key:
 *       Mohamed, Mahamed, Mohammad, Muhammad, Mohammed, Mohamad → "mmd"
 *       Catherine, Katherine, Katharine, Kathryn → "ktrn"
 *       Philip, Phillip, Phillipp → "flp"
 *       Steven, Stephen → "stfn"
 *  4. Group tokens that share a (slot, phoneticKey) where the originals differ in
 *     letter case-insensitive spelling AND the group has ≥ 2 distinct contacts.
 *  5. The user types the canonical spelling for the group themselves. We replace
 *     just that one token inside each selected member's slot value — other tokens
 *     in the slot (e.g. "Ali" in "Ali Mohammad") are preserved verbatim.
 *
 * The phonetic key is local to this screen; it never gets written to the database.
 * It exists only to *propose* groupings.
 */

/* ---------------- Data ---------------- */

/** Where a single matched token lives inside a contact's structured name. */
internal data class NormalizeToken(
    val contactId: Long,
    val rawContactId: Long,
    val slot: NameSlot,
    /** The full slot value as it currently lives in the DB. */
    val slotValue: String,
    /** Index into `slotValue.split(\s+)` of the matched token. */
    val tokenIndex: Int,
    /** The matched token text as originally written. Used for the variant chips. */
    val original: String,
    /** Display name of the contact (for the member list). */
    val displayName: String,
)

internal enum class NameSlot { Given, Middle, Family }

internal data class NormalizeGroup(
    val phoneticKey: String,
    /** Distinct original spellings + how many contacts use each. */
    val variants: List<Pair<String, Int>>,
    /**
     * Every matching token across *all* structured-name slots. A single group can
     * contain tokens that live in the given name of one contact and the family
     * name of another — they're still the same spelling-variant problem.
     */
    val tokens: List<NormalizeToken>,
    /** Pre-computed default canonical: the most common spelling, ties → longest. */
    val suggestedCanonical: String,
)

/* ---------------- Screen ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NameNormalizeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var groups by remember { mutableStateOf<List<NormalizeGroup>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }
    // Single page-at-a-time index, same pattern as NameFixScreen.
    var currentIndex by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadKey) {
        loading = true
        groups = withContext(Dispatchers.IO) { scanNameVariants(context) }
        currentIndex = 0
        loading = false
    }

    val backLabel = stringResource(R.string.action_back)
    val prevLabel = stringResource(R.string.name_normalize_prev)
    val nextLabel = stringResource(R.string.name_normalize_next)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.name_normalize_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                groups.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.name_normalize_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.name_normalize_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                currentIndex >= groups.size -> {
                    // User has paged past the last group (everything skipped /
                    // applied). Show a wrap-up state with a back hint.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.name_normalize_all_done_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.name_normalize_all_done_sub),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    val g = groups[currentIndex]
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    ) {
                        // The scrolling content area holds the current group's card.
                        // Keyed by phoneticKey so per-card state (toggled chips,
                        // typed canonical, member checkboxes) is reset cleanly when
                        // the user advances to a different group.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            androidx.compose.runtime.key(g.phoneticKey) {
                                NormalizeGroupCard(
                                    group = g,
                                    onApplied = {
                                        // Remove the just-applied group from the
                                        // queue. currentIndex naturally points at
                                        // the next group (or past the end).
                                        val updated = groups.toMutableList()
                                        if (currentIndex in updated.indices) {
                                            updated.removeAt(currentIndex)
                                        }
                                        groups = updated
                                    },
                                    onSkip = {
                                        // Move to next without changing the list.
                                        if (currentIndex < groups.size) {
                                            currentIndex += 1
                                        }
                                    },
                                )
                            }
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            IconButton(
                                onClick = { if (currentIndex > 0) currentIndex -= 1 },
                                enabled = currentIndex > 0,
                                modifier = Modifier.semantics {
                                    contentDescription = prevLabel
                                },
                            ) {
                                Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                            }
                            Text(
                                stringResource(
                                    R.string.name_normalize_progress,
                                    currentIndex + 1,
                                    groups.size,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            IconButton(
                                onClick = {
                                    if (currentIndex < groups.size - 1) currentIndex += 1
                                },
                                enabled = currentIndex < groups.size - 1,
                                modifier = Modifier.semantics {
                                    contentDescription = nextLabel
                                },
                            ) {
                                Icon(Icons.Filled.ChevronRight, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NormalizeGroupCard(
    group: NormalizeGroup,
    onApplied: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var canonical by remember(group.phoneticKey) { mutableStateOf(group.suggestedCanonical) }
    // Which detected variant spellings are actually treated as the same name. The
    // phonetic key is intentionally aggressive and will occasionally bucket two
    // unrelated names together (e.g. very short tokens), so the user gets the final
    // say per-spelling. Default: every variant on. Keyed by lowercase spelling so
    // the same word with different casing collapses to one toggle.
    val variantOn = remember(group.phoneticKey) {
        mutableStateMapOf<String, Boolean>().apply {
            group.variants.forEach { (spelling, _) -> put(spelling.lowercase(), true) }
        }
    }
    // Per-contact opt-out within the *enabled* variants. Same keying scheme as
    // before — contactId/slot/tokenIndex — so re-opening the card after a partial
    // apply doesn't lose the user's previous unticks.
    val selection = remember(group.phoneticKey) {
        mutableStateMapOf<String, Boolean>().apply {
            group.tokens.forEach { put(it.selectionKey(), true) }
        }
    }
    var applying by remember { mutableStateOf(false) }

    // A token is in scope iff its spelling chip is on AND its row checkbox is on.
    fun tokenInScope(t: NormalizeToken): Boolean =
        (variantOn[t.original.lowercase()] != false) &&
            (selection[t.selectionKey()] != false)
    val visibleTokens = group.tokens.filter { variantOn[it.original.lowercase()] != false }

    val toggleLabel = stringResource(
        if (expanded) R.string.name_normalize_collapse else R.string.name_normalize_expand,
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.variants.joinToString("  •  ") { "${it.first} (${it.second})" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics { contentDescription = toggleLabel },
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            // Variant filter chips. Tap a chip to *include / exclude* that spelling
            // from the replacement; long-press equivalent is via TalkBack toggle.
            // Tap the chip's label area normally would also seed the canonical input
            // — we keep that affordance through a separate "Use" button below the
            // chip strip so the toggle gesture stays single-purpose.
            Text(
                stringResource(R.string.name_normalize_variants_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            // Vertical list of variant toggles. Each row is a single TalkBack
            // focus target (Checkbox + label inside a `toggleable` Row with
            // Role.Checkbox) so swiping moves cleanly from one variant to the
            // next — no overlapping touch slop with the chip below it.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                group.variants.forEach { (spelling, count) ->
                    val chipKey = spelling.lowercase()
                    androidx.compose.runtime.key(chipKey) {
                        val selected = variantOn[chipKey] != false
                        // One TalkBack focus target per variant: the Row owns the
                        // click + role + state announcement (mergeDescendants pulls
                        // the Checkbox and label into the same node), while the
                        // Checkbox is non-interactive so TalkBack double-tap can't
                        // mis-route to it.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) {}
                                .toggleable(
                                    value = selected,
                                    role = Role.Checkbox,
                                    onValueChange = { variantOn[chipKey] = it },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selected, onCheckedChange = null)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "$spelling ($count)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = canonical,
                onValueChange = { canonical = it },
                label = { Text(stringResource(R.string.name_normalize_canonical_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (expanded) {
                Spacer(Modifier.size(12.dp))
                HorizontalDivider()
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.name_normalize_members),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (visibleTokens.isEmpty()) {
                    Text(
                        stringResource(R.string.name_normalize_no_variants_picked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                visibleTokens.forEach { tok ->
                    val key = tok.selectionKey()
                    val checked = selection[key] != false
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { selection[key] = it },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                tok.displayName.ifBlank { tok.original },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(
                                    R.string.name_normalize_member_sub,
                                    tok.original,
                                    tok.slotValue,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.name_normalize_skip))
                }
                Spacer(Modifier.size(8.dp))
                val targetsAll = group.tokens.filter { tokenInScope(it) }
                val canApply = !applying &&
                    canonical.isNotBlank() &&
                    targetsAll.isNotEmpty()
                Button(
                    onClick = {
                        applying = true
                        val targets = targetsAll
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                applyNormalization(context, targets, canonical.trim())
                            }
                            applying = false
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (ok) R.string.name_normalize_done
                                    else R.string.name_normalize_failed
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                            if (ok) onApplied()
                        }
                    },
                    enabled = canApply,
                ) {
                    Text(stringResource(R.string.name_normalize_apply))
                }
            }
        }
    }
}

private fun NormalizeToken.selectionKey(): String =
    // Must include rawContactId, not just contactId: a single aggregated contact
    // can own multiple raw rows (SIM + Google + Exchange etc.), each with its own
    // StructuredName. Keying by contactId only made those rows share a checkbox,
    // so toggling one toggled the others. Same risk for the same token index
    // appearing in two slots of one raw — slot is already in the key.
    "$rawContactId/${slot.name}/$tokenIndex"

/* ---------------- Phonetic key ---------------- */

/**
 * Folds a single name token to a key that collapses common cross-spelling variations
 * (Mohamed/Mahamed, Catherine/Katherine, Philip/Phillip). The result is opaque — it
 * exists only so callers can ask "do these two spellings sound alike?".
 */
internal fun phoneticKey(raw: String): String {
    if (raw.isBlank()) return ""
    // Strip diacritics so "José" and "Jose" collide.
    val nfd = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    var t = nfd.lowercase().filter { it in 'a'..'z' }
    if (t.isEmpty()) return ""
    // Digraph collapses — must run before single-letter substitution so e.g. "ph"
    // becomes "f" rather than going through the p→p, h→h path.
    t = t
        .replace("ph", "f")
        .replace("gh", "g")
        .replace("ck", "k")
        .replace("kh", "k")
        .replace("ch", "k")
        .replace("sh", "s")
        .replace("dh", "d")
        .replace("th", "t")
        .replace("sch", "s")
    // Single-letter equivalence map.
    val sub = mapOf(
        'c' to 'k', 'q' to 'k', 'x' to 'k',
        'z' to 's',
        'v' to 'f', 'w' to 'f',
        'b' to 'p',
        'j' to 'y', 'y' to 'i',
    )
    t = buildString {
        for (ch in t) append(sub[ch] ?: ch)
    }
    // Collapse adjacent duplicate letters: "Mohammed"→"Mohamed", "Phillip"→"Philip".
    t = buildString {
        for (ch in t) if (isEmpty() || last() != ch) append(ch)
    }
    // Drop a trailing plural / possessive "s".
    if (t.length > 3 && t.last() == 's') t = t.dropLast(1)
    // Keep first letter (it's almost always typed correctly and is what people
    // alphabetize by) and strip vowels + silent 'h' from the rest.
    val first = t.first()
    val rest = t.drop(1).filter { it !in "aeiouh" }
    return first + rest
}

/* ---------------- Scanning ---------------- */

private fun scanNameVariants(context: Context): List<NormalizeGroup> {
    val cr = context.contentResolver
    val nameMime = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
    val projection = arrayOf(
        ContactsContract.Data.CONTACT_ID,
        ContactsContract.Data.RAW_CONTACT_ID,
        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
        ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
        ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
    )
    // Flat list of every (slot, token) → its host contact / raw / position.
    val all = mutableListOf<NormalizeToken>()
    try {
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(nameMime),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val cid = c.getLong(0)
                val rid = c.getLong(1)
                val display = c.getString(2).orEmpty()
                val given = c.getString(3).orEmpty()
                val middle = c.getString(4).orEmpty()
                val family = c.getString(5).orEmpty()
                addSlotTokens(all, cid, rid, display, given, NameSlot.Given)
                addSlotTokens(all, cid, rid, display, middle, NameSlot.Middle)
                addSlotTokens(all, cid, rid, display, family, NameSlot.Family)
            }
        }
    } catch (_: SecurityException) {
        return emptyList()
    }

    // Group by phoneticKey only — the same spelling-variant problem doesn't care
    // whether "Mohamed" lives in the given name of one contact and the family name
    // of another, it's still one normalize decision for the user.
    val buckets = all.groupBy { phoneticKey(it.original) }
        .filterKeys { it.isNotEmpty() }

    val out = mutableListOf<NormalizeGroup>()
    for ((key, tokens) in buckets) {
        // Need ≥ 2 distinct contacts AND ≥ 2 distinct case-insensitive spellings —
        // otherwise it's not a "spelling variant" problem.
        val byContact = tokens.distinctBy { it.contactId }
        if (byContact.size < 2) continue
        val spellingCounts = tokens
            .groupingBy { it.original.lowercase() }
            .eachCount()
        if (spellingCounts.size < 2) continue

        // Variants list sorted by usage desc then spelling. Use the most common
        // original casing (first occurrence) for display.
        val firstCasing = mutableMapOf<String, String>()
        tokens.forEach { firstCasing.putIfAbsent(it.original.lowercase(), it.original) }
        val variants = spellingCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key })
            .map { firstCasing[it.key]!! to it.value }

        // Suggested canonical: most common spelling, tie-break by length (longer
        // tends to be the "complete" form, e.g. "Catherine" over "Cathrin").
        val suggested = variants.maxWithOrNull(
            compareBy<Pair<String, Int>>({ it.second }, { it.first.length })
        )!!.first

        out += NormalizeGroup(
            phoneticKey = key,
            variants = variants,
            tokens = tokens.sortedBy { it.displayName.lowercase() },
            suggestedCanonical = suggested,
        )
    }
    // Show biggest impact (most affected contacts) first.
    return out.sortedByDescending { it.tokens.distinctBy { t -> t.contactId }.size }
}

private fun addSlotTokens(
    out: MutableList<NormalizeToken>,
    contactId: Long,
    rawId: Long,
    displayName: String,
    slotValue: String,
    slot: NameSlot,
) {
    if (slotValue.isBlank()) return
    val parts = slotValue.trim().split(Regex("\\s+"))
    parts.forEachIndexed { idx, token ->
        // Skip tokens that are clearly not names (initials, particles like "de" /
        // "al" / "bin" that show up everywhere and would otherwise create huge
        // false-positive groups).
        if (token.length < 2) return@forEachIndexed
        if (token.lowercase().trimEnd('.') in NORMALIZE_STOPWORDS) return@forEachIndexed
        out += NormalizeToken(
            contactId = contactId,
            rawContactId = rawId,
            slot = slot,
            slotValue = slotValue,
            tokenIndex = idx,
            original = token,
            displayName = displayName,
        )
    }
}

/** Particles/glue words we never offer to normalize on their own. */
private val NORMALIZE_STOPWORDS = setOf(
    "al", "el", "ul", "ud",
    "de", "del", "della", "di", "da", "do", "dos", "das",
    "la", "le", "lo", "li",
    "van", "von", "der", "den", "ter", "ten",
    "bin", "ibn", "ben",
    "abu", "abo", "abou",
    "abd", "abdul", "abdel", "abdu",
    "mc", "mac", "st", "saint",
)

/* ---------------- Apply ---------------- */

/**
 * For every token in [targets] (already filtered to the user's selection), rewrite
 * just that one whitespace-delimited word inside the contact's slot value to
 * [canonical], leaving sibling tokens untouched. All updates run in a single
 * applyBatch so partial failures roll back together.
 */
private fun applyNormalization(
    context: Context,
    targets: List<NormalizeToken>,
    canonical: String,
): Boolean {
    if (canonical.isBlank() || targets.isEmpty()) return false
    val cr = context.contentResolver
    val nameMime = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
    // Group by rawContactId because each StructuredName lives on a raw contact, and
    // (unlikely but possible) a single raw might host two tokens that need rewriting
    // in the same slot — we must combine them into one rewritten slot value rather
    // than issue two competing updates.
    val perRaw = targets.groupBy { it.rawContactId to it.slot }
    val ops = ArrayList<ContentProviderOperation>()
    for ((rawAndSlot, tokens) in perRaw) {
        val (rawId, s) = rawAndSlot
        // Start from the slot value as it is now and replace each target token by
        // index. Since we tokenized on \s+, recompose with single spaces — this
        // also incidentally normalizes weird inner whitespace, which is harmless.
        val base = tokens.first().slotValue
        val parts = base.trim().split(Regex("\\s+")).toMutableList()
        tokens.forEach { tok ->
            if (tok.tokenIndex in parts.indices) parts[tok.tokenIndex] = canonical
        }
        val newValue = parts.joinToString(" ")
        val column = when (s) {
            NameSlot.Given -> ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME
            NameSlot.Middle -> ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME
            NameSlot.Family -> ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME
        }
        ops += ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
            .withSelection(
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(rawId.toString(), nameMime),
            )
            // Setting DISPLAY_NAME to null forces the framework to recompute the
            // aggregated display name from the structured parts we just wrote.
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, null)
            .withValue(column, newValue.ifBlank { null })
            .build()
    }
    return try {
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    } catch (_: Exception) {
        false
    }
}
