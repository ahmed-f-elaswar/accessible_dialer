package com.accessible.dialer.ui.contacts

import android.content.Context
import android.provider.ContactsContract
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.accessible.dialer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only inventory of every account that owns contacts on the device, with a
 * raw-contact count per account. Reads `RawContacts` and groups by
 * (ACCOUNT_TYPE, ACCOUNT_NAME). DELETED rows are excluded.
 *
 * Rows with a null account_type are reported as "Local / Phone only" because that
 * matches what every major Android contacts app shows the user.
 */
internal data class StorageAccount(
    val accountType: String?,
    val accountName: String?,
    val count: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageLocationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var accounts by remember { mutableStateOf<List<StorageAccount>>(emptyList()) }

    LaunchedEffect(Unit) {
        accounts = withContext(Dispatchers.IO) { loadAccounts(context) }
        loading = false
    }

    val backLabel = stringResource(R.string.action_back)
    val title = stringResource(R.string.storage_title)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = backLabel)
                    }
                },
            )
        },
    ) { inner ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (accounts.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(inner).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.storage_none),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }
        val totalLabel = stringResource(R.string.storage_total, accounts.sumOf { it.count })
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(
                        stringResource(R.string.storage_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(totalLabel, style = MaterialTheme.typography.titleSmall)
                }
                HorizontalDivider()
            }
            items(accounts, key = { "${it.accountType}|${it.accountName}" }) { acc ->
                AccountStorageRow(acc)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AccountStorageRow(acc: StorageAccount) {
    val label = friendlyAccountLabel(acc.accountType)
    val name = acc.accountName ?: stringResource(R.string.storage_account_local)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            acc.count.toString(),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun friendlyAccountLabel(type: String?): String = when (type) {
    null -> stringResource(R.string.storage_account_local)
    "com.google" -> "Google"
    "com.osp.app.signin", "com.samsung.android.exchange" -> "Samsung"
    "com.huawei.account" -> "Huawei"
    "com.hihonor.id" -> "Honor"
    "com.xiaomi" -> "Mi Account"
    "com.whatsapp" -> "WhatsApp"
    "org.telegram.messenger" -> "Telegram"
    "vnd.sec.contact.sim", "com.android.contacts.sim" -> stringResource(R.string.storage_account_sim)
    else -> type
}

private fun loadAccounts(context: Context): List<StorageAccount> {
    val uri = ContactsContract.RawContacts.CONTENT_URI
    val proj = arrayOf(
        ContactsContract.RawContacts.ACCOUNT_TYPE,
        ContactsContract.RawContacts.ACCOUNT_NAME,
    )
    val selection = "${ContactsContract.RawContacts.DELETED} = 0"
    val map = LinkedHashMap<Pair<String?, String?>, Int>()
    context.contentResolver.query(uri, proj, selection, null, null)?.use { c ->
        val typeIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
        val nameIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
        while (c.moveToNext()) {
            val key = c.getString(typeIdx) to c.getString(nameIdx)
            map[key] = (map[key] ?: 0) + 1
        }
    }
    return map.entries
        .map { (k, v) -> StorageAccount(k.first, k.second, v) }
        .sortedByDescending { it.count }
}
