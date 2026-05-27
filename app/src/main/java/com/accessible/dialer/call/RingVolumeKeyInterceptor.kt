package com.accessible.dialer.call

import android.content.Context
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * Media-session-based hardware-volume-key interceptor used while a call is RINGING.
 *
 * Why this exists: the default-dialer activity sets [android.app.Activity.volumeControlStream]
 * but on many OEM builds the heads-up incoming-call notification (or the lock screen)
 * owns the window focus, so volume-rocker presses never reach
 * [InCallActivity.dispatchKeyEvent] and the user cannot answer / silence the ringer.
 *
 * Trick: a [MediaSession] published with a remote [VolumeProvider] AND an actively-
 * playing [PlaybackState] is treated by the framework as the addressed media session,
 * so volume-rocker presses are routed straight to [VolumeProvider.onAdjustVolume]
 * regardless of which window has focus. The dummy playback state is required — without
 * it the system does not consider the session a candidate for volume routing.
 */
class RingVolumeKeyInterceptor(
    private val onVolumeUp: () -> Unit,
    private val onVolumeDown: () -> Unit,
) {
    private var session: MediaSession? = null

    fun start(context: Context) {
        if (session != null) return
        val s = MediaSession(context, "AccessibleDialerRingingKeys")
        s.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        // Tiny dummy metadata + a PLAYING playback state. The framework only routes
        // volume keys to sessions that look like they are currently producing audio,
        // so without this the VolumeProvider is silently ignored.
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Incoming call")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 1L)
                .build()
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(
                    PlaybackState.STATE_PLAYING,
                    0L,
                    1.0f,
                    SystemClock.elapsedRealtime(),
                )
                .build()
        )

        val provider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                when {
                    direction > 0 -> onVolumeUp()
                    direction < 0 -> onVolumeDown()
                }
                // Re-anchor so the framework keeps sending us adjustments instead of
                // saturating at min/max after a couple of presses.
                currentVolume = 50
            }
        }
        s.setPlaybackToRemote(provider)
        s.isActive = true
        session = s
    }

    fun stop() {
        runCatching {
            session?.isActive = false
            session?.release()
        }
        session = null
    }
}
