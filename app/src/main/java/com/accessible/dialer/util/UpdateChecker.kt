package com.accessible.dialer.util

import com.accessible.dialer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads a static `latest.json` manifest from the repo's `main` branch and
 * compares its version against the running build. We use a hand-maintained
 * JSON file instead of the GitHub Releases API because:
 *
 *  * the raw URL has no rate limit (unauthenticated REST is 60/hour/IP),
 *  * we control the exact APK link, so the release workflow can publish to
 *    any tag name without us having to parse asset arrays, and
 *  * it lets the maintainer roll back an update by editing one file.
 *
 * Lives in [util] so it can be called from any composable / activity without
 * dragging in extra dependencies; uses [HttpURLConnection] + the built-in
 * [JSONObject] parser to keep the AAB free of an HTTP library.
 */
object UpdateChecker {

    /**
     * Public raw URL of the version manifest on the default branch. Hitting
     * `raw.githubusercontent.com` skips the API quota and serves the file as
     * static bytes from GitHub's CDN.
     */
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/ahmed-f-elaswar/accessible_dialer/main/latest.json"

    /**
     * Parsed manifest entry as surfaced in the update dialog. [tag] is the
     * release tag (typically `vX.Y.Z`); [version] is the bare semver string
     * compared against [BuildConfig.VERSION_NAME]. [apkUrl] is the direct
     * download URL handed to [Updater] — the manifest is authoritative so the
     * URL can point at any host the maintainer chooses.
     */
    data class LatestRelease(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String,
        val htmlUrl: String,
    )

    /** Outcome of [check] — distinguishes the three states the UI cares about. */
    sealed class Result {
        /** Manifest advertises a higher version than the running build. */
        data class UpdateAvailable(val release: LatestRelease) : Result()
        /** Manifest version matches or is older than the running build. */
        data object UpToDate : Result()
        /** Network failure, JSON parse error, or missing required field. */
        data class Error(val message: String) : Result()
    }

    /**
     * Runs the network call on [Dispatchers.IO]. Caller is expected to drive
     * a loading spinner around it. The 10-second connect / read timeout means
     * a dropped network bails out fast enough that the user can retry without
     * the dialog feeling stuck.
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(MANIFEST_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "accessible-dialer/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@runCatching Result.Error("HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val version = json.optString("versionName").trim().ifBlank {
                    return@runCatching Result.Error("Missing versionName")
                }
                val tag = json.optString("tag").ifBlank { "v$version" }
                val notes = json.optString("notes").trim()
                val apkUrl = json.optString("apkUrl").trim()
                val htmlUrl = json.optString("htmlUrl").trim()
                if (apkUrl.isBlank()) {
                    return@runCatching Result.Error("Missing apkUrl")
                }
                val release = LatestRelease(tag, version, notes, apkUrl, htmlUrl)
                if (isNewer(version, BuildConfig.VERSION_NAME)) {
                    Result.UpdateAvailable(release)
                } else {
                    Result.UpToDate
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { Result.Error(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Compare two dotted semver-ish strings ("1.2.3", "1.10.0", …) component
     * by component. Non-numeric tails (`-rc1`, `-debug`) are ignored — we only
     * use this to decide "is the manifest version higher than what's installed",
     * which is unambiguous for the simple versioning scheme the app ships.
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').mapNotNull { it.trim().toIntOrNull() }
        val l = local.split('.', '-').mapNotNull { it.trim().toIntOrNull() }
        val len = maxOf(r.size, l.size)
        for (i in 0 until len) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
