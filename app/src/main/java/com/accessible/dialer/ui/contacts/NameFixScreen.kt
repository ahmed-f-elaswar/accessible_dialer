package com.accessible.dialer.ui.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * Unified "fix contact names" wizard. Walks the user through every contact whose
 * structured name has any detected quality issue (junk characters, wrong casing,
 * missing split, particle/title misplacement, organization-looking names, embedded
 * email/parenthetical metadata, repeated words…). For each contact we precompute
 * a list of "variants" — alternative interpretations — and the user flips through
 * them, edits any field, then Applies or Skips.
 */

/* ---------------- Public data types ---------------- */

internal data class NameFixVariant(
    val labelResId: Int,
    val prefix: String = "",
    val given: String = "",
    val middle: String = "",
    val family: String = "",
    val suffix: String = "",
    /** Variant that just rewrites display_name without touching structured parts. */
    val asRawDisplay: Boolean = false,
    val displayName: String = "",
    /** Variant that writes an Organization row and clears the structured name. */
    val asCompany: Boolean = false,
    val companyName: String = "",
)

internal data class NameFixIssue(
    val contactId: Long,
    val originalDisplay: String,
    val reasons: List<Int>,
    val variants: List<NameFixVariant>,
)

/* ---------------- Lexicons ---------------- */

private val TITLE_PREFIXES = setOf(
    "mr", "mr.", "mrs", "mrs.", "ms", "ms.", "miss", "mx", "mx.",
    "dr", "dr.", "prof", "prof.", "professor", "sir", "madam", "madame",
    "rev", "rev.", "fr", "fr.", "father", "sister", "sr.", "br", "br.",
    "capt", "capt.", "lt", "lt.", "sgt", "sgt.", "col", "col.", "gen", "gen.",
    "hon", "hon.", "eng", "eng.", "engineer", "doctor",
    // Arabic kunya-style honorifics. Treat "Abu Bakr" / "Abo Ali" as title+name
    // so the wizard offers a prefix split rather than gluing them together.
    "abu", "abo", "abou",
)

private val NAME_SUFFIXES = setOf(
    "jr", "jr.", "sr", "sr.", "ii", "iii", "iv", "v",
    "phd", "ph.d.", "md", "m.d.", "esq", "esq.", "dds", "rn",
)

private val GLUE_TO_NEXT = setOf(
    // Arabic / Islamic compound-name prefixes.
    "abd", "abdul", "abdel", "abdu",
    "ibn", "bin", "ben",
    "al", "el", "ul", "ud",
    "hajj", "hajji", "haji",
    "sheikh", "shaikh", "sayyid", "sayed", "sidi",
    // Latin / European surname particles.
    "de", "del", "della", "di", "da", "do", "dos", "das",
    "la", "le", "lo", "li",
    "van", "von", "der", "den", "ter", "ten",
    "mc", "mac",
    "st", "st.", "saint",
)

/** Substrings that suggest a contact is an organization, not a person. */
private val ORG_KEYWORDS = setOf(
    "center", "centre", "company", "co.", "corp", "corporation", "inc", "inc.",
    "llc", "ltd", "ltd.", "group", "bank", "hospital", "clinic", "pharmacy",
    "cafe", "restaurant", "hotel", "store", "shop", "school", "university",
    "college", "institute", "academy", "service", "services", "agency", "office",
    "department", "ministry", "embassy", "consulate", "computer", "computers",
    "test", "tests", "exam", "support", "helpdesk", "hotline", "delivery",
)

/* ---------------- Pure helpers ---------------- */

internal fun gluedTokens(parts: List<String>): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < parts.size) {
        val cur = parts[i]
        if (i + 1 < parts.size && GLUE_TO_NEXT.contains(cur.lowercase().trimEnd('.'))) {
            out += cur + " " + parts[i + 1]
            i += 2
        } else {
            out += cur
            i += 1
        }
    }
    return out
}

internal data class NormalizedName(
    val prefix: String,
    val suffix: String,
    val coreWords: List<String>,
)

internal fun normalizeName(parts: List<String>): NormalizedName {
    val mutable = parts.toMutableList()
    val prefixes = mutableListOf<String>()
    while (mutable.isNotEmpty() && TITLE_PREFIXES.contains(mutable.first().lowercase().trimEnd('.'))) {
        prefixes += mutable.removeAt(0)
        if (prefixes.size >= 2) break
    }
    val suffixes = mutableListOf<String>()
    while (mutable.isNotEmpty() && NAME_SUFFIXES.contains(mutable.last().lowercase().trimEnd('.'))) {
        suffixes += mutable.removeAt(mutable.size - 1)
        if (suffixes.size >= 2) break
    }
    suffixes.reverse()
    val glued = gluedTokens(mutable)
    return NormalizedName(prefixes.joinToString(" "), suffixes.joinToString(" "), glued)
}

/**
 * Strip characters that don't belong in a person/business name. We keep:
 *   - letters (any script)
 *   - whitespace
 *   - common name punctuation:  -  '  .  ,  ( )  /  &
 * Everything else (digits, emoji, pictographs, decorative symbols, control chars, …)
 * is dropped. Digits are deliberately removed too because they almost never belong in
 * a real name and they show up as noise from imports / messaging apps. Multiple
 * spaces are collapsed, an unspaced `.` between letters becomes a space, and
 * underscores become spaces.
 */
internal fun cleanName(input: String): String {
    val allowedPunct = setOf('-', '\'', '.', ',', '(', ')', '/', '&', '\u2019')
    val pre = input
        .replace('_', ' ')
        .replace(Regex("(?<=\\p{L})\\.(?=\\p{L})"), " ")
    val sb = StringBuilder(pre.length)
    var i = 0
    while (i < pre.length) {
        val cp = pre.codePointAt(i)
        val charCount = Character.charCount(cp)
        val keep = when {
            Character.isLetter(cp) -> true
            Character.isWhitespace(cp) -> true
            cp < 128 && allowedPunct.contains(cp.toChar()) -> true
            cp == 0x2019 -> true
            else -> false
        }
        if (keep) sb.appendCodePoint(cp)
        i += charCount
    }
    return sb.toString().replace(Regex("\\s+"), " ").trim()
}

/** Strip parenthetical / dash-suffix metadata: "Amira (AUCTV)" → "Amira", "X - email" → "X". */
internal fun stripMetadata(input: String): String {
    var s = input
    s = s.replace(Regex("\\s*\\([^)]*\\)"), "")
    s = s.replace(Regex("\\s+-\\s+.*$"), "")
    s = s.replace(Regex("\\s*@\\S+"), "")
    return s.replace(Regex("\\s+"), " ").trim()
}

/** Title-case a string, lowercasing the rest of each word. Keeps non-letter runs as-is. */
internal fun toTitleCase(s: String): String {
    if (s.isBlank()) return s
    return s.split(Regex("\\s+")).joinToString(" ") { word ->
        if (word.isEmpty()) word
        else {
            val first = word[0]
            val rest = word.substring(1)
            (if (first.isLetter()) first.uppercaseChar().toString() else first.toString()) + rest.lowercase()
        }
    }
}

/**
 * True if every letter in the word is upper-case AND there are >= 3 letters.
 * Threshold is 3 so legitimate short names ("Al", "Bo", "Mc", "Lu") that title-case
 * to two upper letters don't trip the badcase rule and re-flag a contact we already
 * fixed.
 */
private fun isAllCaps(word: String): Boolean {
    val letters = word.filter { it.isLetter() }
    if (letters.length < 3) return false
    return letters.all { it.isUpperCase() }
}

/** True if every letter in the word is lower-case (and there are >= 3 letters). */
private fun isAllLower(word: String): Boolean {
    val letters = word.filter { it.isLetter() }
    if (letters.length < 3) return false
    return letters.all { it.isLowerCase() }
}

private fun fieldHasBadWhitespace(s: String): Boolean =
    s.isNotEmpty() && (s != s.trim() || s.contains("  "))

private fun looksLikeOrg(text: String): Boolean {
    val lower = text.lowercase()
    return ORG_KEYWORDS.any { kw ->
        lower.contains(Regex("(^|\\s)" + Regex.escape(kw) + "(\\s|$|[,.])"))
    }
}

private fun hasDuplicateWord(words: List<String>): Boolean {
    if (words.size < 2) return false
    val seen = HashSet<String>()
    return words.any { !seen.add(it.lowercase().trimEnd('.', ',')) }
}

private fun hasMetadataMarkers(text: String): Boolean =
    text.contains('(') || text.contains('@') || Regex("\\s-\\s").containsMatchIn(text)

/* ---------------- Variant generator ---------------- */

/**
 * Build the list of possible interpretations for a contact. Always includes a
 * "treat as company" variant; for a name that just needs character cleanup we
 * also include a "rewrite display only" variant.
 */
internal fun buildVariants(
    rawDisplay: String,
    cleanedTitleCasedDisplay: String,
    prefix: String,
    suffix: String,
    coreWords: List<String>,
    needsCleanupOnly: Boolean,
): List<NameFixVariant> {
    val variants = mutableListOf<NameFixVariant>()

    // If cleaning the display would actually change something (junk/whitespace/case/
    // metadata/digits/etc.), offer a "just rewrite display name" variant as a safe
    // fallback. Previously this was gated on cleanup-only mode, which meant a
    // contact with both junk AND a split problem had no quick-clean option even
    // though the user might want that. Always surfacing it gives the user an out.
    if (cleanedTitleCasedDisplay.isNotBlank() &&
        cleanedTitleCasedDisplay != rawDisplay.trim().replace(Regex("\\s+"), " ")
    ) {
        variants += NameFixVariant(
            labelResId = R.string.name_split_variant_cleaned_display,
            asRawDisplay = true,
            displayName = cleanedTitleCasedDisplay,
        )
    }

    val w = coreWords
    when (w.size) {
        0 -> { /* nothing structured to propose */ }
        1 -> {
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_given_only,
                prefix = prefix, given = w[0], suffix = suffix,
            )
        }
        2 -> {
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_first_last,
                prefix = prefix, given = w[0], family = w[1], suffix = suffix,
            )
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_first_middle,
                prefix = prefix, given = w[0], middle = w[1], suffix = suffix,
            )
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_last_first,
                prefix = prefix, given = w[1], family = w[0], suffix = suffix,
            )
        }
        3 -> {
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_first_middle_last,
                prefix = prefix, given = w[0], middle = w[1], family = w[2], suffix = suffix,
            )
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_first_familyjoin,
                prefix = prefix, given = w[0], family = "${w[1]} ${w[2]}", suffix = suffix,
            )
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_first_middlejoin,
                prefix = prefix, given = w[0], middle = "${w[1]} ${w[2]}", suffix = suffix,
            )
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_first_last_2of3,
                prefix = prefix, given = "${w[0]} ${w[1]}", family = w[2], suffix = suffix,
            )
        }
        else -> {
            val n = w.size
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_first_middlejoin_last,
                prefix = prefix,
                given = w[0],
                middle = w.subList(1, n - 1).joinToString(" "),
                family = w[n - 1],
                suffix = suffix,
            )
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_first_familyjoin,
                prefix = prefix,
                given = w[0],
                family = w.subList(1, n).joinToString(" "),
                suffix = suffix,
            )
            variants += NameFixVariant(
                labelResId = R.string.name_split_variant_first_middlejoin,
                prefix = prefix,
                given = w[0],
                middle = w.subList(1, n).joinToString(" "),
                suffix = suffix,
            )
        }
    }

    // Variants that treat the *last* core word as the person's company while the
    // remaining words form their name (e.g. "Ahmed Mohammed Microsoft" →
    // given=Ahmed, family=Mohammed, company=Microsoft).
    if (w.size >= 2) {
        val last = w.last()
        val rest = w.dropLast(1)
        val companyTitled = toTitleCase(last)
        val nameVariant = when (rest.size) {
            1 -> NameFixVariant(
                labelResId = R.string.name_split_variant_person_with_company,
                prefix = prefix, given = rest[0], suffix = suffix,
                companyName = companyTitled,
            )
            2 -> NameFixVariant(
                labelResId = R.string.name_split_variant_person_with_company,
                prefix = prefix, given = rest[0], family = rest[1], suffix = suffix,
                companyName = companyTitled,
            )
            else -> NameFixVariant(
                labelResId = R.string.name_split_variant_person_with_company,
                prefix = prefix,
                given = rest[0],
                middle = rest.subList(1, rest.size - 1).joinToString(" "),
                family = rest.last(),
                suffix = suffix,
                companyName = companyTitled,
            )
        }
        variants += nameVariant
    }

    // Always allow re-interpreting the whole thing as a company.
    variants += NameFixVariant(
        labelResId = R.string.name_split_variant_company,
        asCompany = true,
        companyName = cleanedTitleCasedDisplay.ifBlank { rawDisplay },
    )
    return variants
}

/* ---------------- UI ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NameFixScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Start idle — scanning is expensive on big address books and the user just navigated
    // here, so we wait for an explicit Rescan tap before touching the provider.
    var loading by remember { mutableStateOf(false) }
    var issues by remember { mutableStateOf<List<NameFixIssue>>(emptyList()) }
    var hasScanned by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val runScan: () -> Unit = {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) { scanContactNames(context) }
            issues = result
            currentIndex = 0
            hasScanned = true
            loading = false
        }
        Unit
    }

    val backLabel = stringResource(R.string.action_back)
    val title = stringResource(R.string.name_fix_title)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = backLabel)
                    }
                },
                actions = {
                    TextButton(onClick = runScan) {
                        Text(stringResource(R.string.duplicates_rescan))
                    }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when {
                loading -> {
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
                !hasScanned -> EmptyState(
                    title = stringResource(R.string.name_fix_idle_title),
                    sub = stringResource(R.string.name_fix_idle_sub),
                )
                issues.isEmpty() -> EmptyState(
                    title = stringResource(R.string.name_fix_none),
                    sub = stringResource(R.string.name_fix_none_sub),
                )
                currentIndex >= issues.size -> EmptyState(
                    title = stringResource(R.string.name_split_done_title),
                    sub = stringResource(R.string.name_split_done_sub),
                )
                else -> {
                    val issue = issues[currentIndex]
                    IssuePage(
                        issue = issue,
                        position = currentIndex,
                        total = issues.size,
                        onApply = { variant ->
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    applyVariant(context, issue.contactId, variant)
                                }
                                if (ok) {
                                    Toast.makeText(context, R.string.name_split_applied, Toast.LENGTH_SHORT).show()
                                    currentIndex += 1
                                } else {
                                    Toast.makeText(context, R.string.name_split_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        // Skip moves the current case to the END of the queue so the user
                        // can come back to it later without losing track of it. We keep
                        // currentIndex unchanged because removing at currentIndex shifts
                        // the next case into that slot.
                        onSkip = {
                            val updated = issues.toMutableList()
                            val moved = updated.removeAt(currentIndex)
                            updated.add(moved)
                            issues = updated
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, sub: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            sub,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IssuePage(
    issue: NameFixIssue,
    position: Int,
    total: Int,
    onApply: (NameFixVariant) -> Unit,
    onSkip: () -> Unit,
) {
    var variantIndex by remember(issue.contactId) { mutableIntStateOf(0) }
    val variants = issue.variants
    val variant = variants[variantIndex.coerceIn(0, variants.size - 1)]

    val key = issue.contactId to variantIndex
    var prefix by remember(key) { mutableStateOf(variant.prefix) }
    var given by remember(key) { mutableStateOf(variant.given) }
    var middle by remember(key) { mutableStateOf(variant.middle) }
    var family by remember(key) { mutableStateOf(variant.family) }
    var suffix by remember(key) { mutableStateOf(variant.suffix) }
    var company by remember(key) { mutableStateOf(variant.companyName) }
    var displayOnly by remember(key) { mutableStateOf(variant.displayName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.name_split_progress, position + 1, total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.name_split_original, issue.originalDisplay),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (issue.reasons.isNotEmpty()) {
            val joined = issue.reasons
                .map { stringResource(it) }
                .joinToString(", ")
            Text(
                text = stringResource(R.string.name_fix_reasons, joined),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val prevDesc = stringResource(R.string.name_split_prev_variant)
            IconButton(
                onClick = { if (variantIndex > 0) variantIndex -= 1 },
                enabled = variantIndex > 0,
                modifier = Modifier.semantics { contentDescription = prevDesc },
            ) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = prevDesc)
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(
                        R.string.name_split_variant_progress,
                        variantIndex + 1,
                        variants.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(variant.labelResId)) },
                )
            }
            val nextDesc = stringResource(R.string.name_split_next_variant)
            IconButton(
                onClick = { if (variantIndex < variants.size - 1) variantIndex += 1 },
                enabled = variantIndex < variants.size - 1,
                modifier = Modifier.semantics { contentDescription = nextDesc },
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = nextDesc)
            }
        }
        HorizontalDivider()
        when {
            variant.asCompany -> {
                OutlinedTextField(
                    value = company,
                    onValueChange = { company = it },
                    label = { Text(stringResource(R.string.editor_field_company)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            variant.asRawDisplay -> {
                OutlinedTextField(
                    value = displayOnly,
                    onValueChange = { displayOnly = it },
                    label = { Text(stringResource(R.string.editor_field_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text(stringResource(R.string.editor_field_prefix)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = given,
                    onValueChange = { given = it },
                    label = { Text(stringResource(R.string.editor_field_given)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = middle,
                    onValueChange = { middle = it },
                    label = { Text(stringResource(R.string.editor_field_middle)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = family,
                    onValueChange = { family = it },
                    label = { Text(stringResource(R.string.editor_field_family)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = suffix,
                    onValueChange = { suffix = it },
                    label = { Text(stringResource(R.string.editor_field_suffix)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // "Person + company" variants carry a non-blank companyName; expose it
                // here so the user can confirm or edit the company alongside the name.
                if (variant.companyName.isNotBlank() || company.isNotBlank()) {
                    OutlinedTextField(
                        value = company,
                        onValueChange = { company = it },
                        label = { Text(stringResource(R.string.editor_field_company)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    val edited = when {
                        variant.asCompany -> variant.copy(companyName = company.trim())
                        variant.asRawDisplay -> variant.copy(displayName = displayOnly.trim())
                        else -> variant.copy(
                            prefix = prefix.trim(),
                            given = given.trim(),
                            middle = middle.trim(),
                            family = family.trim(),
                            suffix = suffix.trim(),
                            companyName = company.trim(),
                        )
                    }
                    onApply(edited)
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.name_split_apply))
            }
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.name_split_skip))
            }
        }
    }
}

/* ---------------- Scanning ---------------- */

private fun scanContactNames(context: Context): List<NameFixIssue> {
    val out = mutableListOf<NameFixIssue>()
    val cr = context.contentResolver
    val nameMime = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
    val projection = arrayOf(
        ContactsContract.Data.CONTACT_ID,
        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
        ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
        ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
        ContactsContract.CommonDataKinds.StructuredName.PREFIX,
        ContactsContract.CommonDataKinds.StructuredName.SUFFIX,
    )
    val seen = HashSet<Long>()
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
                if (!seen.add(cid)) continue
                val display = c.getString(1).orEmpty()
                val given = c.getString(2).orEmpty()
                val middle = c.getString(3).orEmpty()
                val family = c.getString(4).orEmpty()
                val prefix = c.getString(5).orEmpty()
                val suffix = c.getString(6).orEmpty()

                val issue = buildIssueFor(
                    cid = cid,
                    display = display,
                    given = given,
                    middle = middle,
                    family = family,
                    prefix = prefix,
                    suffix = suffix,
                )
                if (issue != null) out += issue
            }
        }
    } catch (_: SecurityException) {
        return emptyList()
    }
    return out
}

private fun buildIssueFor(
    cid: Long,
    display: String,
    given: String,
    middle: String,
    family: String,
    prefix: String,
    suffix: String,
): NameFixIssue? {
    // Raw "everything joined" text.
    val rawText = if (display.isNotBlank()) display
    else listOf(prefix, given, middle, family, suffix)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    if (rawText.isBlank()) return null

    // Stage 1: pre-clean for variant building (strip metadata, junk, normalize spaces, title-case).
    val noMeta = stripMetadata(rawText)
    val cleaned = cleanName(noMeta)
    val titled = toTitleCase(cleaned)

    // Detect each kind of problem.
    val reasons = mutableListOf<Int>()
    val structuredFields = listOf(given, middle, family, prefix, suffix)

    val hasJunk = cleaned != rawText.trim().replace(Regex("\\s+"), " ")
    if (hasJunk) reasons += R.string.name_fix_reason_junk

    val hasWhitespace = structuredFields.any { fieldHasBadWhitespace(it) } ||
        (display != display.trim() && display.isNotBlank())
    if (hasWhitespace) reasons += R.string.name_fix_reason_whitespace

    val hasMetadata = hasMetadataMarkers(rawText)
    if (hasMetadata) reasons += R.string.name_fix_reason_metadata

    // Title-case detection: any letter-only word in any field that is all-upper or all-lower.
    val badCase = structuredFields.any { f ->
        f.trim().split(Regex("\\s+")).any { w -> isAllCaps(w) || isAllLower(w) }
    } || rawText.split(Regex("\\s+")).any { w -> isAllCaps(w) || isAllLower(w) }
    if (badCase) reasons += R.string.name_fix_reason_badcase

    val isOrg = looksLikeOrg(cleaned)
    // Only treat the org keyword as a problem when the contact has no structured
    // person parts yet. Once the user has split it into given/family (or applied a
    // "person + company" variant that wrote the org keyword into the Organization
    // mimetype), we respect that decision and don't keep re-flagging the same row.
    val structuredAllBlankPre = given.isBlank() && middle.isBlank() && family.isBlank()
    if (isOrg && structuredAllBlankPre) reasons += R.string.name_fix_reason_org

    val parts = titled.split(Regex("\\s+")).filter { it.isNotBlank() }
    val structuredAllBlank = structuredAllBlankPre
    val needsSplit = structuredAllBlank && parts.size >= 2
    if (needsSplit) reasons += R.string.name_fix_reason_needs_split

    // Wrong-split: existing parts present but obviously wrong (lone particle in given,
    // title in given/family without prefix).
    val givenKey = given.lowercase().trim().trimEnd('.')
    val givenWords = given.split(Regex("\\s+")).filter { it.isNotBlank() }
    val familyWords = family.split(Regex("\\s+")).filter { it.isNotBlank() }
    val loneParticle = given.isNotBlank() && !given.trim().contains(Regex("\\s")) &&
        GLUE_TO_NEXT.contains(givenKey)
    val titleInFields = prefix.isBlank() && (
        (givenWords.isNotEmpty() && TITLE_PREFIXES.contains(givenWords.first().lowercase().trimEnd('.'))) ||
        (given.isBlank() && familyWords.isNotEmpty() && TITLE_PREFIXES.contains(familyWords.first().lowercase().trimEnd('.')))
    )
    val wrongSplit = !structuredAllBlank && (loneParticle || titleInFields)
    if (wrongSplit) reasons += R.string.name_fix_reason_wrong_split

    val duplicateWord = hasDuplicateWord(parts)
    if (duplicateWord) reasons += R.string.name_fix_reason_duplicate

    if (reasons.isEmpty()) return null

    // Build the variant list from the title-cased cleaned text.
    val norm = normalizeName(parts)
    val effectivePrefix = if (prefix.isNotBlank()) toTitleCase(cleanName(prefix)) else norm.prefix
    val effectiveSuffix = if (suffix.isNotBlank()) toTitleCase(cleanName(suffix)) else norm.suffix

    // "Cleanup-only" mode: only character / whitespace / casing fixes, no structural change.
    val needsCleanupOnly = (hasJunk || hasWhitespace || badCase || hasMetadata) &&
        !needsSplit && !wrongSplit && !isOrg && !duplicateWord

    val variants = buildVariants(
        rawDisplay = rawText,
        cleanedTitleCasedDisplay = titled,
        prefix = effectivePrefix,
        suffix = effectiveSuffix,
        coreWords = norm.coreWords,
        needsCleanupOnly = needsCleanupOnly,
    )

    // For org-looking contacts, surface the company variant first.
    val orderedVariants = if (isOrg) {
        val orgIndex = variants.indexOfFirst { it.asCompany }
        if (orgIndex > 0) {
            val mutable = variants.toMutableList()
            val org = mutable.removeAt(orgIndex)
            mutable.add(0, org)
            mutable
        } else variants
    } else variants

    return NameFixIssue(cid, rawText.trim(), reasons, orderedVariants)
}

/* ---------------- Apply ---------------- */

private fun applyVariant(
    context: Context,
    contactId: Long,
    variant: NameFixVariant,
): Boolean {
    val cr = context.contentResolver
    val nameMime = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
    val orgMime = ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
    return try {
        val rawIds = mutableListOf<Long>()
        cr.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()),
            null,
        )?.use { c -> while (c.moveToNext()) rawIds += c.getLong(0) }
        if (rawIds.isEmpty()) return false

        val ops = ArrayList<ContentProviderOperation>()
        for (rawId in rawIds) {
            when {
                variant.asCompany -> {
                    // Clear the StructuredName so the framework uses Organization.COMPANY.
                    val hasName = hasRow(cr, rawId, nameMime)
                    val nameBuilder = if (hasName) {
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                                arrayOf(rawId.toString(), nameMime),
                            )
                    } else {
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                            .withValue(ContactsContract.Data.MIMETYPE, nameMime)
                    }
                    ops += nameBuilder
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, null)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, null)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, null)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, null)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, null)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, null)
                        .build()

                    val hasOrg = hasRow(cr, rawId, orgMime)
                    val orgBuilder = if (hasOrg) {
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                                arrayOf(rawId.toString(), orgMime),
                            )
                    } else {
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                            .withValue(ContactsContract.Data.MIMETYPE, orgMime)
                    }
                    ops += orgBuilder
                        .withValue(
                            ContactsContract.CommonDataKinds.Organization.COMPANY,
                            variant.companyName.ifBlank { null },
                        )
                        .build()
                }
                variant.asRawDisplay -> {
                    val hasName = hasRow(cr, rawId, nameMime)
                    val builder = if (hasName) {
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                                arrayOf(rawId.toString(), nameMime),
                            )
                    } else {
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                            .withValue(ContactsContract.Data.MIMETYPE, nameMime)
                    }
                    // Rewrite the display name and ALSO populate basic structured parts
                    // from the cleaned text. Leaving the parts blank would make the
                    // next rescan immediately re-flag this contact under "name not
                    // split into parts".
                    val tokens = variant.displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    val derivedGiven: String?
                    val derivedMiddle: String?
                    val derivedFamily: String?
                    when (tokens.size) {
                        0 -> { derivedGiven = null; derivedMiddle = null; derivedFamily = null }
                        1 -> { derivedGiven = tokens[0]; derivedMiddle = null; derivedFamily = null }
                        2 -> { derivedGiven = tokens[0]; derivedMiddle = null; derivedFamily = tokens[1] }
                        else -> {
                            derivedGiven = tokens.first()
                            derivedMiddle = tokens.subList(1, tokens.size - 1).joinToString(" ")
                            derivedFamily = tokens.last()
                        }
                    }
                    ops += builder
                        .withValue(
                            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                            variant.displayName.ifBlank { null },
                        )
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, null)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, derivedGiven)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, derivedMiddle)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, derivedFamily)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, null)
                        .build()
                }
                else -> {
                    val hasName = hasRow(cr, rawId, nameMime)
                    val builder = if (hasName) {
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                                arrayOf(rawId.toString(), nameMime),
                            )
                    } else {
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                            .withValue(ContactsContract.Data.MIMETYPE, nameMime)
                    }
                    ops += builder
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, null)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, variant.prefix.ifBlank { null })
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, variant.given.ifBlank { null })
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, variant.middle.ifBlank { null })
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, variant.family.ifBlank { null })
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, variant.suffix.ifBlank { null })
                        .build()

                    // "Person + company" variants carry a non-blank companyName. Write
                    // it to the Organization mimetype so the company sticks alongside
                    // the structured name. Without this the chosen company would be
                    // silently dropped on apply.
                    if (variant.companyName.isNotBlank()) {
                        val hasOrg = hasRow(cr, rawId, orgMime)
                        val orgBuilder = if (hasOrg) {
                            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                                .withSelection(
                                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                                    arrayOf(rawId.toString(), orgMime),
                                )
                        } else {
                            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                                .withValue(ContactsContract.Data.MIMETYPE, orgMime)
                        }
                        ops += orgBuilder
                            .withValue(
                                ContactsContract.CommonDataKinds.Organization.COMPANY,
                                variant.companyName,
                            )
                            .build()
                    }
                }
            }
        }
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    } catch (_: Exception) {
        false
    }
}

private fun hasRow(cr: ContentResolver, rawId: Long, mime: String): Boolean {
    var found = false
    cr.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(ContactsContract.Data._ID),
        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
        arrayOf(rawId.toString(), mime),
        null,
    )?.use { c -> if (c.moveToFirst()) found = true }
    return found
}
