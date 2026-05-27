package com.accessible.dialer.ui.favorites

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accessible.dialer.R
import com.accessible.dialer.ui.contacts.ContactRow
import com.accessible.dialer.ui.contacts.ContactsViewModel
import com.accessible.dialer.util.DialerPermissions

@Composable
fun FavoritesScreen(
    permissionsGranted: Boolean,
    onCallNumber: (String) -> Unit,
    onShowInDialpad: (String) -> Unit,
    vm: ContactsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val contacts by vm.displayed.collectAsStateWithLifecycle()

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && DialerPermissions.granted(context, Manifest.permission.READ_CONTACTS)) {
            vm.load(context)
        }
    }

    val favorites = contacts.filter { it.starred }

    if (favorites.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.favorites_empty), style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(favorites, key = { it.id }) { contact ->
            val displayName = contact.name.ifBlank { contact.number }
            ContactRow(
                contact = contact,
                onTap = { onCallNumber(contact.number) },
                tapLabel = stringResource(R.string.contacts_call, displayName),
                onCall = { onCallNumber(contact.number) },
                onShowInDialpad = { onShowInDialpad(contact.number) },
                onContactsChanged = { vm.load(context) },
            )
            HorizontalDivider()
        }
    }
}
