package com.accessible.dialer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R
import com.accessible.dialer.ui.contacts.ContactsScreen
import com.accessible.dialer.ui.dialpad.DialpadScreen
import com.accessible.dialer.ui.favorites.FavoritesScreen
import com.accessible.dialer.ui.recents.RecentsScreen
import com.accessible.dialer.ui.recents.RecentsViewModel
import com.accessible.dialer.ui.settings.SettingsScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Tab(val labelRes: Int) {
    Dialpad(R.string.tab_dialpad),
    Favorites(R.string.tab_favorites),
    Contacts(R.string.tab_contacts),
    Recents(R.string.tab_recents),
}

/**
 * Root composable. Owns:
 *  - Bottom navigation between the four primary destinations.
 *  - A top app bar with a kebab "more options" menu that opens Settings as a sub-screen
 *    (Settings is no longer a tab; it's a one-off overflow destination per the user's
 *    request, freeing the bottom bar for the four call-related views).
 *  - A pre-flight banner that asks the user to grant permissions OR set the app as the
 *    system default phone app — without this banner the app is just an "almost dialer".
 *  - A shared "current dialpad number" that survives switching tabs, so a user can pick a
 *    contact -> jump to dialpad with the number prefilled (set by [DialerApp.onPickNumber]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerApp(
    initialNumber: String?,
    startOnContacts: Boolean = false,
    startOnRecents: Boolean = false,
    permissionsGranted: Boolean,
    isDefaultDialer: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestDefaultDialer: () -> Unit,
    onPlaceCall: (String) -> Unit,
    /**
     * Phone number extracted from a system Share-sheet payload; surfaces the
     * same Call / Add to contact / Cancel dialog the clipboard detector uses.
     * [onSharedNumberConsumed] is invoked once we route the number into the
     * prompt so MainActivity can clear its one-shot bus.
     */
    sharedNumber: String? = null,
    onSharedNumberConsumed: () -> Unit = {},
) {
    // Initial tab priority:
    //   1. explicit intent override (startOnContacts / startOnRecents) — viewing
    //      contacts or missed-call notification deep-link from outside,
    //   2. tel: dial intent — always land on Dialpad with the number prefilled,
    //   3. last tab the user was on (persisted across cold-starts),
    //   4. Dialpad as a safe default for a dialer.
    var currentTab by rememberSaveable {
        val initial = when {
            startOnRecents -> Tab.Recents
            startOnContacts -> Tab.Contacts
            !initialNumber.isNullOrEmpty() -> Tab.Dialpad
            else -> com.accessible.dialer.settings.SettingsRepository.lastTab.value
                ?.let { name -> Tab.values().firstOrNull { it.name == name } }
                ?: Tab.Dialpad
        }
        mutableStateOf(initial)
    }
    androidx.compose.runtime.LaunchedEffect(currentTab) {
        com.accessible.dialer.settings.SettingsRepository.setLastTab(currentTab.name)
    }
    var dialpadNumber by rememberSaveable {
        // Prefer the number from an inbound tel: intent (the user is being
        // handed a number to dial from another app); otherwise restore whatever
        // the user last had typed before the process was paused / killed so the
        // keypad doesn't appear empty after coming back from a call or from
        // being swiped out of memory.
        mutableStateOf(
            initialNumber?.takeIf { it.isNotEmpty() }
                ?: com.accessible.dialer.settings.SettingsRepository.lastDialpadInput.value
        )
    }
    // Mirror every change back into SharedPreferences so the keypad survives
    // process death (the InCallActivity taking over for a call can cause the
    // OS to reclaim our process while in the background).
    androidx.compose.runtime.LaunchedEffect(dialpadNumber) {
        com.accessible.dialer.settings.SettingsRepository.setLastDialpadInput(dialpadNumber)
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    // Contact details is rendered AS a full screen — above the Scaffold's top bar and
    // outside its bottom NavigationBar. Lifting the state here (rather than scoping it
    // inside ContactsScreen) is what gives us the whole viewport for the details view.
    var detailsContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    // null = closed; 0 = new; >0 = edit existing aggregated contact.
    var editorContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    // When opening the editor as "new contact" (editorContactId == 0L) from the
    // Recents "Save as new contact" action, this holds the phone number to
    // prefill into the first phone slot. Cleared along with editorContactId.
    var editorPrefillNumber by rememberSaveable { mutableStateOf<String?>(null) }
    // Full-screen duplicate-detection wizard opened from Settings → Tools.
    var showDuplicates by rememberSaveable { mutableStateOf(false) }
    var showNameFix by rememberSaveable { mutableStateOf(false) }
    // Spelling-variant normalizer (Mohamed / Mohammad / Mahamed → one canonical).
    var showNameNormalize by rememberSaveable { mutableStateOf(false) }
    var showBlocked by rememberSaveable { mutableStateOf(false) }
    // Per-SIM ringtone overrides — pulled out of the Calling section into their
    // own sub-screen so the main settings list stays short on multi-SIM devices.
    var showRingtones by rememberSaveable { mutableStateOf(false) }
    // Top-level settings sub-screens. Each row in SettingsScreen now opens one
    // of these instead of expanding inline.
    var showDisplay by rememberSaveable { mutableStateOf(false) }
    var showCalling by rememberSaveable { mutableStateOf(false) }
    var showAccessibility by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var showBlocking by rememberSaveable { mutableStateOf(false) }
    // Settings → Tools → "Where contacts are stored": full-screen account list +
    // per-account contact picker with bulk delete / move.
    var showStorage by rememberSaveable { mutableStateOf(false) }
    // Bumped on save to force ContactsScreen to reload.
    var contactsReloadKey by rememberSaveable { mutableStateOf(0) }
    // Settings → Help → User guide: full-screen scrollable help content.
    var showUserGuide by rememberSaveable { mutableStateOf(false) }
    // Bumped after destructive call-log changes (e.g. "Clear all call history") so
    // RecentsScreen reacts even when a targeted in-memory edit isn't enough. Routine
    // additions (a placed call ending, a renamed contact) take the targeted-edit
    // paths on `recentsVm` below and DO NOT bump this key — they don't need a full
    // re-query and shouldn't make the LazyColumn flash blank.
    var recentsReloadKey by rememberSaveable { mutableStateOf(0) }
    // Activity-scoped ViewModel shared with RecentsScreen (which also calls
    // `viewModel()` and receives the same instance). Declared up here so the call-
    // end watcher below can drive incremental list edits directly instead of going
    // through a coarse reloadKey bump.
    val recentsVm: RecentsViewModel = viewModel()
    val prewarmContext = LocalContext.current
    // Watch the global call state and merge just the new call-log row(s) whenever
    // an active call tears down (transitions back to None). The system writes the
    // row asynchronously, so a tiny grace delay gives the provider time to commit
    // before we query. `mergeRecent` fetches only rows with _ID greater than what's
    // currently on screen and either replaces the matching contact's row in place
    // or prepends a new one — so the LazyColumn keeps its scroll and doesn't flash
    // through an empty state the way a full reload would.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var wasInCall = false
        com.accessible.dialer.call.OngoingCallHolder.state.collect { s ->
            val inCall = s !is com.accessible.dialer.call.CallState.None
            if (wasInCall && !inCall) {
                kotlinx.coroutines.delay(400)
                if (com.accessible.dialer.util.DialerPermissions.granted(
                        prewarmContext, android.Manifest.permission.READ_CALL_LOG
                    )
                ) {
                    recentsVm.mergeRecent(prewarmContext)
                }
            }
            wasInCall = inCall
        }
    }
    // Pre-warm the call log in the background as soon as permissions are granted,
    // so by the time the user opens the Recents tab the data is already cached in
    // the (activity-scoped) ViewModel. Without this, the call log scan + PhoneLookup
    // queries only start after the Recents tab is first composed, which gave the
    // user a perceptible blank pause on tab switch.
    androidx.compose.runtime.LaunchedEffect(permissionsGranted) {
        if (permissionsGranted &&
            com.accessible.dialer.util.DialerPermissions.granted(
                prewarmContext, android.Manifest.permission.READ_CALL_LOG
            )
        ) {
            recentsVm.ensureLoaded(prewarmContext)
        }
    }
    // Toggled by the overflow menu's "Clear all call history" item; renders a
    // destructive confirmation dialog before any rows are deleted.
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    // Two-stage "Save unknown caller" flow:
    //   1. saveContactChoiceNumber non-null → show "New contact / Add to existing" chooser
    //   2. addNumberToExistingFor non-null → show contact picker; on pick open editor
    // Each stage is a separate state var so dismissing one doesn't leave the other
    // hanging. Number is normalized only at the final call site (ContactEditor).
    var saveContactChoiceNumber by rememberSaveable { mutableStateOf<String?>(null) }
    var addNumberToExistingFor by rememberSaveable { mutableStateOf<String?>(null) }

    // Clipboard-to-action prompt: when the user copies a phone number elsewhere
    // and switches back to the dialer, offer Call / Add to contact / Cancel.
    // Lives inside DialerApp — not MainActivity — so the "Add to contact" button
    // can hand the number straight into the existing [saveContactChoiceNumber]
    // flow (Create new → ContactEditor or Add to existing → ContactPickerDialog).
    var clipboardPromptNumber by remember { mutableStateOf<String?>(null) }
    val lastPromptedClipboard = remember { mutableStateOf<String?>(null) }

    // Share-sheet entry: when MainActivity finds a phone number in the
    // ACTION_SEND payload it passes it here, and we surface it through the
    // same prompt as the clipboard detector. We deliberately don't update
    // [lastPromptedClipboard] so the next clipboard pass for the same value
    // still works normally.
    androidx.compose.runtime.LaunchedEffect(sharedNumber) {
        val s = sharedNumber
        if (!s.isNullOrBlank()) {
            clipboardPromptNumber = s
            onSharedNumberConsumed()
        }
    }
    val clipboardCtx = LocalContext.current
    val clipboardLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(clipboardLifecycle, permissionsGranted) {
        if (!permissionsGranted) return@DisposableEffect onDispose { }
        val cm = clipboardCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
            ?: return@DisposableEffect onDispose { }
        fun checkClipboard() {
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            // Skip clips that came from inside the dialer itself — RowActions.copyNumber
            // stamps an extras flag on the ClipDescription so we recognise our own clips
            // reliably (label-based matching collided with other apps that used "phone").
            if (clip.description?.extras?.getBoolean("com.accessible.dialer.copied_internally") == true) {
                return
            }
            val raw = clip.getItemAt(0).coerceToText(clipboardCtx)?.toString().orEmpty()
            val text = raw.trim()
            if (text.isEmpty() || text == lastPromptedClipboard.value) return
            // Phone-number-ish heuristic: 5–20 digits, and digits outnumber (or equal)
            // the non-digit chars — lets "+1 (234) 567-8900" pass and rejects free-form
            // text like "Order #12345". Tolerates Unicode whitespace / RTL marks /
            // narrow no-break spaces that messaging apps tend to inject.
            val digits = text.count { it.isDigit() }
            if (digits < 5 || digits > 20) return
            if (text.length - digits > digits) return
            android.util.Log.d("ClipboardCall", "Prompting for clipboard number: $text")
            lastPromptedClipboard.value = text
            clipboardPromptNumber = text
        }
        // Android 10+ blocks ClipboardManager.getPrimaryClip() unless the calling app
        // is focused; ON_RESUME fires *before* the window gains focus, so an immediate
        // read silently returns null. Defer the check by ~350 ms on the main thread —
        // long enough for focus to land, short enough to still feel instant.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val pendingCheck = Runnable { checkClipboard() }
        fun scheduleCheck() {
            handler.removeCallbacks(pendingCheck)
            handler.postDelayed(pendingCheck, 350L)
        }
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scheduleCheck()
        }
        clipboardLifecycle.addObserver(obs)
        scheduleCheck()
        onDispose {
            handler.removeCallbacks(pendingCheck)
            clipboardLifecycle.removeObserver(obs)
        }
    }

    // List scroll state hoisted up here so it survives the early-return swap-outs
    // for ContactDetails / ContactEditor / Settings sub-screens. `rememberLazyListState`
    // also uses `rememberSaveable` internally so the position survives configuration
    // changes (rotation, process death restore).
    val contactsListState = rememberLazyListState()
    val recentsListState = rememberLazyListState()
    // When the user opens contact details from a list row, we capture the id so
    // that when they back out the same row gets TalkBack / input focus again
    // (instead of focus snapping to the top of the rebuilt list).
    var contactsFocusReturnId by rememberSaveable { mutableStateOf<Long?>(null) }
    var recentsFocusReturnEntryId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Back-press inside Settings should exit Settings into the dialer's main UI,
    // NOT pop the activity (which would drop the user out to the system home
    // screen). The early-return blocks for full-screen sub-routes register their
    // own BackHandlers; Settings is rendered inline inside the Scaffold body so
    // it needs an explicit handler here.
    androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }

    val goDialWith: (String) -> Unit = { number ->
        dialpadNumber = number
        currentTab = Tab.Dialpad
        showSettings = false
    }

    val settingsLabel = stringResource(R.string.tab_settings)
    val moreLabel = stringResource(R.string.more_options)
    val backLabel = stringResource(R.string.action_back)

    // Full-screen in-app editor (replaces the system contact editor). Opened from the
    // Contacts FAB (id = 0 → new) or from the Details action row (id > 0 → edit).
    // Must be checked BEFORE details — when editing from details both ids are set,
    // and we want the editor to take over the viewport.
    if (editorContactId != null) {
        val id = editorContactId!!
        val prefill = editorPrefillNumber
        androidx.activity.compose.BackHandler {
            editorContactId = null
            editorPrefillNumber = null
        }
        com.accessible.dialer.ui.contacts.ContactEditorScreen(
            contactId = id,
            onBack = {
                editorContactId = null
                editorPrefillNumber = null
            },
            onSaved = { savedId ->
                editorContactId = null
                editorPrefillNumber = null
                contactsReloadKey += 1
                // Recents shows the contact's display name resolved from the
                // CallLog cache, which the system refreshes lazily. Re-run the
                // live PhoneLookup overlay against the existing on-screen rows
                // so the freshly-saved/renamed contact's name updates in place
                // — no full call-log re-query, no scroll reset.
                recentsVm.refreshDisplayNames(prewarmContext)
                if (id > 0L) {
                    // Came from details — stay on details so the user sees updates.
                    detailsContactId = savedId
                }
            },
            prefillNumber = prefill,
        )
        return
    }

    // Full-screen contact details takes over the whole viewport — no banner, no
    // bottom bar, no nav tabs. The user explicitly asked for this because the screen
    // is denser than the list and competing with the dialer chrome made it cramped.
    if (detailsContactId != null) {
        val id = detailsContactId!!
        androidx.activity.compose.BackHandler { detailsContactId = null }
        com.accessible.dialer.ui.contacts.ContactDetailsScreen(
            contactId = id,
            onBack = { detailsContactId = null },
            onCallNumber = onPlaceCall,
            onShowInDialpad = goDialWith,
            onEdit = { editorContactId = it },
        )
        return
    }

    if (showDuplicates) {
        androidx.activity.compose.BackHandler { showDuplicates = false }
        com.accessible.dialer.ui.contacts.DuplicateScanScreen(
            onBack = {
                showDuplicates = false
                contactsReloadKey += 1
            },
        )
        return
    }

    if (showNameFix) {
        androidx.activity.compose.BackHandler { showNameFix = false }
        com.accessible.dialer.ui.contacts.NameFixScreen(
            onBack = {
                showNameFix = false
                contactsReloadKey += 1
            },
        )
        return
    }

    if (showNameNormalize) {
        androidx.activity.compose.BackHandler { showNameNormalize = false }
        com.accessible.dialer.ui.contacts.NameNormalizeScreen(
            onBack = {
                showNameNormalize = false
                contactsReloadKey += 1
            },
        )
        return
    }

    if (showBlocked) {
        androidx.activity.compose.BackHandler { showBlocked = false }
        com.accessible.dialer.ui.blocking.BlockedNumbersScreen(
            onBack = { showBlocked = false },
        )
        return
    }

    if (showRingtones) {
        androidx.activity.compose.BackHandler { showRingtones = false }
        com.accessible.dialer.ui.settings.RingtonesScreen(
            onBack = { showRingtones = false },
        )
        return
    }

    if (showDisplay) {
        androidx.activity.compose.BackHandler { showDisplay = false }
        com.accessible.dialer.ui.settings.DisplayScreen(
            onBack = { showDisplay = false },
        )
        return
    }

    if (showCalling) {
        androidx.activity.compose.BackHandler { showCalling = false }
        com.accessible.dialer.ui.settings.CallingScreen(
            onBack = { showCalling = false },
            onOpenRingtones = { showRingtones = true },
        )
        return
    }

    if (showAccessibility) {
        androidx.activity.compose.BackHandler { showAccessibility = false }
        com.accessible.dialer.ui.settings.AccessibilityScreen(
            onBack = { showAccessibility = false },
        )
        return
    }

    if (showStorage) {
        androidx.activity.compose.BackHandler { showStorage = false }
        com.accessible.dialer.ui.storage.StorageLocationsScreen(
            onBack = {
                showStorage = false
                // Moves / deletes from this screen change which accounts own
                // which contacts — force the Contacts tab to re-query so the
                // filter dropdown and aggregated list match reality.
                contactsReloadKey += 1
            },
        )
        return
    }

    if (showTools) {
        androidx.activity.compose.BackHandler { showTools = false }
        com.accessible.dialer.ui.settings.ToolsScreen(
            onBack = { showTools = false },
            onOpenDuplicates = { showDuplicates = true },
            onOpenStorage = { showStorage = true },
            onOpenNameFix = { showNameFix = true },
            onOpenNameNormalize = { showNameNormalize = true },
        )
        return
    }

    if (showBlocking) {
        androidx.activity.compose.BackHandler { showBlocking = false }
        com.accessible.dialer.ui.settings.BlockingScreen(
            onBack = { showBlocking = false },
            onOpenBlocked = { showBlocked = true },
        )
        return
    }

    if (showUserGuide) {
        androidx.activity.compose.BackHandler { showUserGuide = false }
        com.accessible.dialer.ui.help.UserGuideScreen(
            onBack = { showUserGuide = false },
        )
        return
    }

    val clearHistoryContext = LocalContext.current
    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text(stringResource(R.string.clear_all_history_title)) },
            text = { Text(stringResource(R.string.clear_all_history_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistoryConfirm = false
                    val deleted = com.accessible.dialer.util.ContactOps
                        .eraseAllCallHistory(clearHistoryContext)
                    val msg = if (deleted >= 0) {
                        clearHistoryContext.getString(R.string.clear_all_history_done, deleted)
                    } else {
                        clearHistoryContext.getString(R.string.clear_all_history_failed)
                    }
                    android.widget.Toast.makeText(
                        clearHistoryContext, msg, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    // We just emptied the system call log; mirror that in the
                    // in-memory list without a re-query (which would just
                    // return zero rows anyway).
                    recentsVm.clearLocally()
                }) { Text(stringResource(R.string.clear_all_history_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Stage 1 of the "Save unknown caller" flow: ask whether to create a new contact
    // or append the number to an existing one. Each button sets the next state var
    // and clears this one so the dialogs daisy-chain without overlapping.
    saveContactChoiceNumber?.let { pendingNumber ->
        AlertDialog(
            onDismissRequest = { saveContactChoiceNumber = null },
            title = { Text(stringResource(R.string.save_contact_choice_title)) },
            text = { Text(stringResource(R.string.save_contact_choice_message, pendingNumber)) },
            confirmButton = {
                TextButton(onClick = {
                    saveContactChoiceNumber = null
                    editorPrefillNumber = pendingNumber
                    editorContactId = 0L
                }) { Text(stringResource(R.string.save_contact_choice_new)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    saveContactChoiceNumber = null
                    addNumberToExistingFor = pendingNumber
                }) { Text(stringResource(R.string.save_contact_choice_existing)) }
            },
        )
    }

    // Stage 2: contact picker. On pick, open the editor for that contact with the
    // number queued as a fresh phone row (ContactEditorScreen handles the append).
    addNumberToExistingFor?.let { pendingNumber ->
        com.accessible.dialer.ui.contacts.ContactPickerDialog(
            title = stringResource(R.string.save_contact_picker_title),
            onDismiss = { addNumberToExistingFor = null },
            onPick = { contact ->
                addNumberToExistingFor = null
                editorPrefillNumber = pendingNumber
                editorContactId = contact.id
            },
            viewModelKey = "save_unknown_picker",
        )
    }

    // Clipboard prompt: Call / Add to contact / Cancel. "Add to contact" hands the
    // number off to [saveContactChoiceNumber] so it routes through the same Create
    // new / Add to existing chooser used by Recents' row action.
    clipboardPromptNumber?.let { number ->
        AlertDialog(
            onDismissRequest = { clipboardPromptNumber = null },
            title = { Text(stringResource(R.string.clipboard_call_title)) },
            text = { Text(stringResource(R.string.clipboard_call_message, number)) },
            confirmButton = {
                // Two primary actions stacked horizontally. The chooser's outside-tap
                // and back-press both route through onDismissRequest, so Cancel is
                // also offered as an explicit dismissButton for discoverability.
                Row {
                    TextButton(onClick = {
                        clipboardPromptNumber = null
                        saveContactChoiceNumber = number
                    }) { Text(stringResource(R.string.action_save_as_contact)) }
                    TextButton(onClick = {
                        clipboardPromptNumber = null
                        onPlaceCall(number)
                    }) { Text(stringResource(R.string.clipboard_call_action_call)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { clipboardPromptNumber = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            if (showSettings) {
                CenterAlignedTopAppBar(
                    title = { Text(settingsLabel) },
                    navigationIcon = {
                        IconButton(
                            onClick = { showSettings = false },
                            modifier = Modifier.semantics { contentDescription = backLabel },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!showSettings) {
                NavigationBar {
                    // "More options" sits at the LEFT edge of the bottom bar (before the
                    // tabs) so it lives on the opposite side from where Android usually
                    // puts overflow menus, per the user's preference. Hand-rolled because
                    // wrapping NavigationBarItem in a Box (needed to anchor the
                    // DropdownMenu) breaks its RowScope.weight modifier. Visually it
                    // matches a NavigationBarItem: weighted column, centered icon+label.
                    //
                    // IMPORTANT: do NOT add `fillMaxHeight()` here. NavigationBar uses
                    // `defaultMinSize` for its height, so a child requesting fillMaxHeight
                    // expands the whole bar to the screen height and pushes it into the
                    // middle of the screen.
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClick = { menuExpanded = true },
                                    onClickLabel = moreLabel,
                                    role = Role.Button,
                                )
                                .padding(vertical = 12.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = moreLabel
                                },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = moreLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(settingsLabel) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Settings, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    showSettings = true
                                },
                            )
                            // "Clear all call history" only makes sense while the user is
                            // viewing the Recents tab — outside that context the action
                            // is hidden to avoid an unexpected destructive option in an
                            // unrelated screen (Dialpad, Contacts, Favorites, Settings).
                            if (!showSettings && currentTab == Tab.Recents) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_clear_all_history)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        showClearHistoryConfirm = true
                                    },
                                )
                            }
                        }
                    }
                    Tab.values().forEach { tab ->
                        val label = stringResource(tab.labelRes)
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = when (tab) {
                                        Tab.Dialpad -> Icons.Filled.Dialpad
                                        Tab.Recents -> Icons.Filled.History
                                        Tab.Contacts -> Icons.Filled.Person
                                        Tab.Favorites -> Icons.Filled.Star
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(label) },
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                }
            }
        }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            SetupBanner(
                permissionsGranted = permissionsGranted,
                isDefaultDialer = isDefaultDialer,
                onRequestPermissions = onRequestPermissions,
                onRequestDefaultDialer = onRequestDefaultDialer,
            )
            // weight(1f) — not fillMaxSize — is what guarantees the content area gets
            // *exactly* the remaining height after SetupBanner. Without it some Compose
            // layouts collapse this Box to zero height when the banner is present, which
            // showed up as "tabs at the bottom but no content above".
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (showSettings) {
                    SettingsScreen(
                        onOpenDisplay = { showDisplay = true },
                        onOpenCalling = { showCalling = true },
                        onOpenAccessibility = { showAccessibility = true },
                        onOpenBlocking = { showBlocking = true },
                        onOpenTools = { showTools = true },
                        onOpenUserGuide = { showUserGuide = true },
                    )
                } else when (currentTab) {
                    Tab.Dialpad -> DialpadScreen(
                        number = dialpadNumber,
                        onNumberChange = { dialpadNumber = it },
                        // Clear the on-screen digits as soon as a call is placed
                        // from the dialpad. Without this, returning to the app
                        // after the call still shows the previously dialed
                        // number, which the user then has to manually erase
                        // before dialing again.
                        onCall = {
                            val toDial = dialpadNumber
                            if (toDial.isNotBlank()) {
                                onPlaceCall(toDial)
                                dialpadNumber = ""
                            }
                        },
                        onCallNumber = onPlaceCall,
                        permissionsGranted = permissionsGranted,
                    )
                    Tab.Recents -> RecentsScreen(
                        permissionsGranted = permissionsGranted,
                        onCallNumber = onPlaceCall,
                        onShowInDialpad = goDialWith,
                        onOpenContactDetails = { detailsContactId = it },
                        onOpenContactDetailsForEntry = { entryId, cid ->
                            recentsFocusReturnEntryId = entryId
                            detailsContactId = cid
                        },
                        onSaveAsContact = { number ->
                            // Open the new/existing chooser instead of going straight
                            // to a new-contact editor. The user picks intent here;
                            // either branch ends up in ContactEditorScreen with
                            // [editorPrefillNumber] set.
                            saveContactChoiceNumber = number
                        },
                        reloadKey = recentsReloadKey,
                        listState = recentsListState,
                        returnFocusEntryId = recentsFocusReturnEntryId,
                        onReturnFocusConsumed = { recentsFocusReturnEntryId = null },
                    )
                    Tab.Contacts -> ContactsScreen(
                        permissionsGranted = permissionsGranted,
                        onCallNumber = onPlaceCall,
                        onShowInDialpad = goDialWith,
                        onOpenDetails = { id ->
                            contactsFocusReturnId = id
                            detailsContactId = id
                        },
                        onNewContact = { editorContactId = 0L },
                        onEditContact = { editorContactId = it },
                        reloadKey = contactsReloadKey,
                        listState = contactsListState,
                        focusTargetId = contactsFocusReturnId,
                        onFocusConsumed = { contactsFocusReturnId = null },
                    )
                    Tab.Favorites -> FavoritesScreen(
                        permissionsGranted = permissionsGranted,
                        onCallNumber = onPlaceCall,
                        onShowInDialpad = goDialWith,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupBanner(
    permissionsGranted: Boolean,
    isDefaultDialer: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestDefaultDialer: () -> Unit,
) {
    if (permissionsGranted && isDefaultDialer) return
    // The "Set as default phone app" prompt is now handled by the startup dialog in
    // MainActivity (so it isn't shown as a button inside the app). Here we only show
    // the runtime-permissions card.
    if (permissionsGranted) return
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.perm_required_message),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onRequestPermissions, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.perm_grant))
            }
        }
    }
}
