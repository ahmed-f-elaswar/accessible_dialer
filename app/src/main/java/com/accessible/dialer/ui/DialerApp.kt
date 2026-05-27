package com.accessible.dialer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.accessible.dialer.ui.settings.SettingsScreen

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
    permissionsGranted: Boolean,
    isDefaultDialer: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestDefaultDialer: () -> Unit,
    onPlaceCall: (String) -> Unit,
) {
    // Initial tab priority:
    //   1. explicit intent override (startOnContacts) — viewing contacts from outside,
    //   2. tel: dial intent — always land on Dialpad with the number prefilled,
    //   3. last tab the user was on (persisted across cold-starts),
    //   4. Dialpad as a safe default for a dialer.
    var currentTab by rememberSaveable {
        val initial = when {
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
    var dialpadNumber by rememberSaveable { mutableStateOf(initialNumber.orEmpty()) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    // Contact details is rendered AS a full screen — above the Scaffold's top bar and
    // outside its bottom NavigationBar. Lifting the state here (rather than scoping it
    // inside ContactsScreen) is what gives us the whole viewport for the details view.
    var detailsContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    // null = closed; 0 = new; >0 = edit existing aggregated contact.
    var editorContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Full-screen duplicate-detection wizard opened from Settings → Tools.
    var showDuplicates by rememberSaveable { mutableStateOf(false) }
    var showNameFix by rememberSaveable { mutableStateOf(false) }
    // Spelling-variant normalizer (Mohamed / Mohammad / Mahamed → one canonical).
    var showNameNormalize by rememberSaveable { mutableStateOf(false) }
    var showBlocked by rememberSaveable { mutableStateOf(false) }
    // Bumped on save to force ContactsScreen to reload.
    var contactsReloadKey by rememberSaveable { mutableStateOf(0) }

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
        androidx.activity.compose.BackHandler { editorContactId = null }
        com.accessible.dialer.ui.contacts.ContactEditorScreen(
            contactId = id,
            onBack = { editorContactId = null },
            onSaved = { savedId ->
                editorContactId = null
                contactsReloadKey += 1
                if (id > 0L) {
                    // Came from details — stay on details so the user sees updates.
                    detailsContactId = savedId
                }
            },
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
                            Icon(Icons.Filled.ArrowBack, contentDescription = backLabel)
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
                        onOpenDuplicates = { showDuplicates = true },
                        onOpenNameFix = { showNameFix = true },
                        onOpenNameNormalize = { showNameNormalize = true },
                        onOpenBlocked = { showBlocked = true },
                    )
                } else when (currentTab) {
                    Tab.Dialpad -> DialpadScreen(
                        number = dialpadNumber,
                        onNumberChange = { dialpadNumber = it },
                        onCall = { onPlaceCall(dialpadNumber) },
                        permissionsGranted = permissionsGranted,
                    )
                    Tab.Recents -> RecentsScreen(
                        permissionsGranted = permissionsGranted,
                        onCallNumber = onPlaceCall,
                        onShowInDialpad = goDialWith,
                        onOpenContactDetails = { detailsContactId = it },
                    )
                    Tab.Contacts -> ContactsScreen(
                        permissionsGranted = permissionsGranted,
                        onCallNumber = onPlaceCall,
                        onShowInDialpad = goDialWith,
                        onOpenDetails = { detailsContactId = it },
                        onNewContact = { editorContactId = 0L },
                        onEditContact = { editorContactId = it },
                        reloadKey = contactsReloadKey,
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
