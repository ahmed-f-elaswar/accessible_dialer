package com.accessible.dialer.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.accessible.dialer.R

/**
 * Downloads an APK from a public URL via [DownloadManager] and hands the result
 * to the system installer. Used by the in-app updater so the user never has to
 * leave the dialer to install a new release.
 *
 * Why DownloadManager instead of [java.net.HttpURLConnection] + a foreground
 * service: the system service runs out-of-process, survives Activity
 * recreation, shows a system notification + progress bar the user can cancel,
 * handles redirects (the GitHub asset URL 302-redirects to S3), and exposes a
 * content:// URI we can install from directly without needing a FileProvider
 * for our private downloads dir. All of that for ~20 lines of code.
 *
 * The install handoff requires the user to grant the app the
 * "Install unknown apps" permission once. [tryInstall] catches the
 * SecurityException that the system throws when the permission is missing and
 * opens [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES] for the user instead of
 * silently failing.
 */
object Updater {

    /**
     * Kick off the APK download. Registers a one-shot [BroadcastReceiver] that
     * fires when the download finishes; on success it triggers the install
     * prompt, on failure it surfaces a toast.
     *
     * Returns the DownloadManager id so the caller can plumb it through to UI
     * if it ever wants to (the current flow doesn't — the toast + system
     * progress notification are enough).
     */
    fun startDownloadAndInstall(context: Context, apkUrl: String, version: String): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(context.getString(R.string.update_download_title, version))
            .setDescription(context.getString(R.string.update_download_subtitle))
            .setMimeType("application/vnd.android.package-archive")
            // Save into the *public* Downloads dir so the file is still
            // discoverable after install if the user wants to keep it. We
            // pick a versioned filename so repeated checks don't overwrite an
            // older APK the user might still want.
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "accessible-dialer-$version.apk",
            )
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val id = dm.enqueue(request)
        Toast.makeText(context, R.string.update_download_started, Toast.LENGTH_SHORT).show()

        // One-shot receiver — unregisters itself once the matching id reports.
        // Registered against the application context so an activity teardown
        // mid-download doesn't drop the install handoff.
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val finished = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (finished != id) return
                runCatching { app.unregisterReceiver(this) }
                handleDownloadComplete(app, dm, id)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            app,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        return id
    }

    private fun handleDownloadComplete(context: Context, dm: DownloadManager, id: Long) {
        val query = DownloadManager.Query().setFilterById(id)
        dm.query(query)?.use { c ->
            if (!c.moveToFirst()) return
            val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = if (statusIdx >= 0) c.getInt(statusIdx) else DownloadManager.STATUS_FAILED
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return
            }
        }
        val uri = dm.getUriForDownloadedFile(id) ?: run {
            Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            return
        }
        tryInstall(context, uri)
    }

    private fun tryInstall(context: Context, apkUri: Uri) {
        // Android 8+ gates side-loading behind a per-app toggle. If we don't
        // have it yet, send the user to the system page that lets them grant
        // it — the alternative is letting the install intent throw with no UI
        // feedback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                context, R.string.update_install_permission_needed, Toast.LENGTH_LONG,
            ).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
            return
        }
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(installIntent) }.onFailure {
            Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
        }
    }
}
