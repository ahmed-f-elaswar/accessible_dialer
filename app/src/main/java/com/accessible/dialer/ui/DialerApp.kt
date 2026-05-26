package com.accessible.dialer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R
import com.accessible.dialer.ui.contacts.ContactsScreen
import com.accessible.dialer.ui.dialpad.DialpadScreen
import com.accessible.dialer.ui.favorites.FavoritesScreen
import com.accessible.dialer.ui.recents.RecentsScreen

private enum class Tab(val labelRes: Int) {
    Dialpad(R.string.tab_dialpad),
    Recents(R.string.tab_recents),
    Contacts(R.string.tab_contacts),
    Favorites(R.string.tab_favorites),
}

/**
 * Root composable. Owns:
 *  - Bottom navigation between the four primary destinations.
 *  - A pre-flight banner that asks the user to grant permissions OR set the app as the
 *    system default phone app — without this banner the app is just an "almost dialer".
 *  - A shared "current dialpad number" that survives switching tabs, so a user can pick a
 *    contact -> jump to dialpad with the number prefilled (set by [DialerApp.onPickNumber]).
 */
@Composable
fun DialerApp(
    initialNumber: String?,
    permissionsGranted: Boolean,
    isDefaultDialer: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestDefaultDialer: () -> Unit,
    onPlaceCall: (String) -> Unit,
) {
    var currentTab by rememberSaveable { mutableStateOf(Tab.Dialpad) }
    var dialpadNumber by rememberSaveable { mutableStateOf(initialNumber.orEmpty()) }

    val goDialWith: (String) -> Unit = { number ->
        dialpadNumber = number
        currentTab = Tab.Dialpad
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
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
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            SetupBanner(
                permissionsGranted = permissionsGranted,
                isDefaultDialer = isDefaultDialer,
                onRequestPermissions = onRequestPermissions,
                onRequestDefaultDialer = onRequestDefaultDialer,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                when (currentTab) {
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
                    )
                    Tab.Contacts -> ContactsScreen(
                        permissionsGranted = permissionsGranted,
                        onCallNumber = onPlaceCall,
                        onShowInDialpad = goDialWith,
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
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when {
                !permissionsGranted -> {
                    Text(
                        text = stringResource(R.string.perm_required_message),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onRequestPermissions, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.perm_grant))
                    }
                }
                !isDefaultDialer -> {
                    Text(
                        text = stringResource(R.string.default_dialer_banner),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onRequestDefaultDialer, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.default_dialer_action))
                    }
                }
            }
        }
    }
}
