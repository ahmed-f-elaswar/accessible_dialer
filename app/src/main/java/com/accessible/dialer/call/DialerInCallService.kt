package com.accessible.dialer.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import com.accessible.dialer.blocking.BlockedNumbersRepository
import com.accessible.dialer.settings.SettingsRepository
import com.accessible.dialer.util.RowActions
import com.accessible.dialer.util.ShakeDetector

/**
 * The system binds to this service whenever there is an active phone call AND this app is
 * the user-selected default dialer. We forward call lifecycle into [OngoingCallHolder] and
 * launch the full-screen [InCallActivity] so the user can interact with the call.
 */
class DialerInCallService : InCallService() {

    private val ringer by lazy { Ringer(applicationContext) }
    // Per-call callback that drives the ringer. Held so we can unregister on remove.
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    // BroadcastReceiver that handles the action buttons (Answer / Hang up / Mute /
    // Speaker) on the ongoing-call notification posted by [InCallNotifier].
    // Registered dynamically (not in the manifest) so it is implicitly
    // not-exported on every Android version, and so we never leak across
    // service teardowns.
    private val actionReceiver = InCallActionReceiver()
    private var actionReceiverRegistered = false
    // The most recently-added call. We keep a reference (not just state) because the
    // ringer needs the call's PhoneAccountHandle to resolve a per-SIM ringtone
    // override, and the holder only exposes a phone number.
    private var currentCall: Call? = null
    // Accelerometer listener active only while a call is RINGING and the
    // shake-to-answer setting is enabled. We register / unregister tightly around
    // the RINGING state so the sensor isn't hot for active or held calls.
    private var shakeDetector: ShakeDetector? = null
    // Separate accelerometer listener active only while a call is CONNECTED
    // (ACTIVE / HOLDING) and the shake-to-end setting is enabled.
    private var shakeEndDetector: ShakeDetector? = null
    // Proximity-sensor controller that auto-toggles the speakerphone when the
    // phone moves away from / back to the ear during a connected call.
    private var proximityController: ProximitySpeakerController? = null
    // MediaSession-based volume-rocker interceptor that lets the user answer with
    // Volume Up and silence the ringer with Volume Down even when the heads-up
    // notification (not InCallActivity) owns the window focus.
    private var volumeInterceptor: RingVolumeKeyInterceptor? = null
    // Broadcast receiver listening for AudioManager STREAM_RING volume changes.
    // On many devices, [onSilenceRinger] handles Volume Down but Volume Up just
    // raises the ringer volume — we use the broadcast direction to detect that
    // press and answer the call.
    private var volumeReceiver: BroadcastReceiver? = null
    // Cached STREAM_RING volume captured the moment the call entered RINGING. Used
    // by [onSilenceRinger] to disambiguate Volume Up from Volume Down on OEM ROMs
    // that route BOTH volume keys through the ringer-silence callback: after a
    // short delay we re-read the stream and compare against this baseline.
    private var ringStartVolume: Int = -1
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSilenceRunnable: Runnable? = null

    // Per-call state used by [onCallRemoved] to decide whether to post a missed-call
    // notification. We can't read [Call.Details] reliably after the system tears the
    // call down, so we snapshot what we need (direction, number, whether the call was
    // ever answered) on each state change and stash it here keyed by the Call instance.
    private data class CallTrace(
        val isIncoming: Boolean,
        val number: String,
        var everActive: Boolean = false,
    )
    private val traces = mutableMapOf<Call, CallTrace>()

    override fun onCreate() {
        super.onCreate()
        // Register the notification-action receiver for the lifetime of this
        // service. Use the explicit not-exported flag on Android 13+ (API 33)
        // so the receiver is reachable only via our own PendingIntents.
        if (!actionReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(InCallActionReceiver.ACTION_ANSWER)
                addAction(InCallActionReceiver.ACTION_HANGUP)
                addAction(InCallActionReceiver.ACTION_TOGGLE_MUTE)
                addAction(InCallActionReceiver.ACTION_TOGGLE_SPEAKER)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(actionReceiver, filter)
            }
            actionReceiverRegistered = true
        }
    }

    override fun onDestroy() {
        if (actionReceiverRegistered) {
            runCatching { unregisterReceiver(actionReceiver) }
            actionReceiverRegistered = false
        }
        InCallNotifier.cancel(applicationContext)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        // "Silent ring" block mode: the call passed our CallScreeningService with
        // setSilenceCall(true), which means the system ringer/notification is already
        // suppressed. We must ALSO suppress our own in-app ringer + InCallActivity,
        // otherwise the user would still see/hear the call locally — defeating the
        // whole point of the mode. Don't even attach to OngoingCallHolder; let the
        // call linger in the background until the caller hangs up or hits voicemail.
        //
        // CRITICAL: only apply this to INCOMING calls. Outgoing calls (placed by the
        // user themselves) must always show the in-call UI even if the dialed number
        // happens to be on the user's block list — the user is deliberately reaching
        // out to that number, so suppressing the screen would just look like the
        // dialer is broken.
        val direction = runCatching { call.details?.callDirection }.getOrDefault(Call.Details.DIRECTION_UNKNOWN)
        val number = runCatching { call.details?.handle?.schemeSpecificPart.orEmpty() }
            .getOrDefault("")
        val isBlockedSilent = direction == Call.Details.DIRECTION_INCOMING &&
            SettingsRepository.blockMode.value == SettingsRepository.BlockMode.SilentRing &&
            (
                (number.isNotBlank() && BlockedNumbersRepository.isBlocked(this, number)) ||
                    // "Block unknown callers" also routes through SilentRing: when the
                    // incoming number is missing or doesn't resolve to a saved contact,
                    // suppress the in-app ringer and InCallActivity just like a manual
                    // block. Without this, an unknown caller would still light up the
                    // screen even though the user asked us to silence them.
                    (
                        SettingsRepository.blockUnknown.value &&
                            (number.isBlank() || RowActions.lookupContactId(this, number) == null)
                    )
            )
        if (isBlockedSilent) return

        OngoingCallHolder.bindService(this)
        OngoingCallHolder.attach(call)
        currentCall = call

        // Snapshot the bits we need for missed-call detection once, while the
        // platform still considers this a live call. Storing the direction and number
        // here (vs reading them off [Call.Details] in onCallRemoved) shields us from
        // races where the system has already nulled the details out by the time the
        // remove callback fires.
        traces[call] = CallTrace(
            isIncoming = direction == Call.Details.DIRECTION_INCOMING,
            number = number,
        )

        // Drive the ringer from this service (not the holder) — we need a Context, and
        // the holder is process-wide and shouldn't hold one. We add a *second* callback
        // alongside the holder's so both stay independent.
        val cb = object : Call.Callback() {
            override fun onStateChanged(c: Call, newState: Int) {
                // Remember whether the call ever transitioned through ACTIVE: that's
                // the only reliable signal that the user actually picked up. We can't
                // trust DisconnectCause alone — some OEM ROMs report MISSED even for
                // calls the user answered and the remote side hung up almost
                // immediately.
                if (newState == Call.STATE_ACTIVE) {
                    traces[c]?.everActive = true
                }
                syncRinger(newState)
            }
        }
        call.registerCallback(cb)
        callbacks[call] = cb
        syncRinger(call.state)

        val intent = Intent(this, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        ringer.stop()
        stopShakeListener()
        stopShakeEndListener()
        stopProximityController()
        stopVolumeInterceptor()
        if (currentCall === call) currentCall = null
        // Missed-call notification: post when an incoming call ended without the user
        // ever answering it AND the platform's DisconnectCause confirms it was a
        // genuine miss (not a manual REJECTED or a CANCELLED). Standard phone apps do
        // exactly this and surface it as a tappable notification with Call back /
        // Message actions.
        val trace = traces.remove(call)
        if (trace != null && trace.isIncoming && !trace.everActive) {
            val cause = runCatching { call.details?.disconnectCause?.code }
                .getOrNull() ?: DisconnectCause.UNKNOWN
            if (cause == DisconnectCause.MISSED || cause == DisconnectCause.UNKNOWN) {
                val name = runCatching {
                    if (trace.number.isBlank()) null
                    else RowActions.lookupContactName(applicationContext, trace.number)
                }.getOrNull()
                runCatching {
                    MissedCallNotifier.notifyMissedCall(
                        applicationContext,
                        trace.number,
                        name,
                    )
                }
            }
        }
        OngoingCallHolder.detach(call)
        OngoingCallHolder.bindService(null)
        // If that was the last live call, drop the ongoing-call notification
        // (otherwise refresh it so it reflects whichever call is now current).
        if (calls.isEmpty()) {
            InCallNotifier.cancel(applicationContext)
        } else {
            InCallNotifier.refresh(
                applicationContext,
                OngoingCallHolder.state.value,
                OngoingCallHolder.audio.value,
            )
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        // Mirror the real audio route + mute flag into the holder so the UI reflects the
        // system state and toggles are correctly applied through setAudioRoute/setMuted.
        OngoingCallHolder.updateAudioState(audioState)
        // Refresh the notification so the Mute / Speaker action labels reflect
        // the new state (e.g. "Mute" becomes "Unmute" once the user mutes from
        // the in-call screen, and vice versa).
        InCallNotifier.refresh(
            applicationContext,
            OngoingCallHolder.state.value,
            OngoingCallHolder.audio.value,
        )
    }

    /**
     * Platform callback invoked when the user presses Volume Down (or otherwise
     * triggers a ringer silence) while a call is RINGING. Because the manifest
     * declares `IN_CALL_SERVICE_RINGING = true`, the OS suppresses its own ringer
     * and expects us to play it — which also means the system can no longer
     * silence the ring itself; it just forwards the request here.
     *
     * On some OEM ROMs this callback also fires for Volume **Up** presses (the
     * platform maps both volume keys to ringer-silence). We disambiguate by
     * scheduling a short delayed read of the STREAM_RING volume and comparing it
     * against the baseline captured when RINGING started: if the volume went up,
     * the press was Volume Up and we answer the call; otherwise we silence the
     * ringer.
     */
    override fun onSilenceRinger() {
        super.onSilenceRinger()
        // Cancel any previous pending decision so back-to-back key presses always
        // act on the latest stream volume sample.
        pendingSilenceRunnable?.let { mainHandler.removeCallbacks(it) }
        val baseline = ringStartVolume
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val runnable = Runnable {
            val curr = am?.getStreamVolume(AudioManager.STREAM_RING) ?: -1
            // Only treat as Volume Up if the baseline is known and the stream
            // actually increased. Defensive against unknown baselines (e.g.
            // stream was already at max) — fall through to silence in that case.
            if (baseline >= 0 && curr > baseline) {
                runCatching { OngoingCallHolder.answer() }
            } else {
                ringer.stop()
            }
            pendingSilenceRunnable = null
        }
        pendingSilenceRunnable = runnable
        mainHandler.postDelayed(runnable, 180L)
    }

    private fun syncRinger(state: Int) {
        if (state == Call.STATE_RINGING) {
            // Capture baseline ring stream volume so [onSilenceRinger] can later
            // decide whether the volume rocker was pressed up (answer) or down
            // (silence) by comparing the current stream value to this snapshot.
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ringStartVolume = am?.getStreamVolume(AudioManager.STREAM_RING) ?: -1
            val number = OngoingCallHolder.currentNumber()
            val handle = runCatching { currentCall?.details?.accountHandle }.getOrNull()
            // Call-waiting: another call is already in progress (ACTIVE/HOLDING/
            // DIALING/CONNECTING). Play a gentle periodic "beep beep" instead of
            // the full ringtone + vibration so we don't drown out the live call
            // audio.
            val callWaiting = runCatching {
                calls.any { other ->
                    other !== currentCall && when (other.state) {
                        Call.STATE_ACTIVE,
                        Call.STATE_HOLDING,
                        Call.STATE_DIALING,
                        Call.STATE_CONNECTING -> true
                        else -> false
                    }
                }
            }.getOrDefault(false)
            ringer.start(number, handle, callWaiting = callWaiting)
            startShakeListenerIfEnabled()
            startVolumeInterceptor()
            // Active-call helpers must not run while still ringing.
            stopShakeEndListener()
            stopProximityController()
        } else {
            // Cancel any pending silence-vs-answer decision queued by onSilenceRinger.
            pendingSilenceRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingSilenceRunnable = null
            ringStartVolume = -1
            ringer.stop()
            stopShakeListener()
            stopVolumeInterceptor()
            if (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING) {
                startShakeEndListenerIfEnabled()
                startProximityControllerIfEnabled()
            } else {
                stopShakeEndListener()
                stopProximityController()
            }
        }
        // Push the latest state / audio snapshot to the notification so the
        // shade controls update at the same time as the in-call screen.
        InCallNotifier.refresh(
            applicationContext,
            OngoingCallHolder.state.value,
            OngoingCallHolder.audio.value,
        )
    }

    /**
     * If [SettingsRepository.shakeToAnswerEnabled] is on, start watching the
     * accelerometer for a shake gesture and answer the call when one is detected.
     * Re-entrant: a second call while a detector is already running is a no-op.
     */
    private fun startShakeListenerIfEnabled() {
        if (shakeDetector != null) return
        if (!SettingsRepository.shakeToAnswerEnabled.value) return
        val detector = ShakeDetector(onShake = {
            // Answer through the holder so the audio-only / video-call branching
            // logic lives in one place. Wrapped in runCatching because the call may
            // have already been torn down by the time the gesture is recognized.
            runCatching { OngoingCallHolder.answer() }
        })
        if (detector.start(applicationContext)) {
            shakeDetector = detector
        }
    }

    private fun stopShakeListener() {
        shakeDetector?.stop()
        shakeDetector = null
    }

    /**
     * Start the active-call shake-to-hangup detector if the user opted in. Idempotent.
     */
    private fun startShakeEndListenerIfEnabled() {
        if (shakeEndDetector != null) return
        if (!SettingsRepository.shakeToEndEnabled.value) return
        val detector = ShakeDetector(onShake = {
            runCatching { OngoingCallHolder.hangup() }
        })
        if (detector.start(applicationContext)) {
            shakeEndDetector = detector
        }
    }

    private fun stopShakeEndListener() {
        shakeEndDetector?.stop()
        shakeEndDetector = null
    }

    /**
     * Start the proximity-driven speakerphone auto-toggle if the user opted in.
     * Idempotent. The controller flips the audio route through [OngoingCallHolder].
     */
    private fun startProximityControllerIfEnabled() {
        if (proximityController != null) return
        if (!SettingsRepository.proximitySpeakerEnabled.value) return
        val controller = ProximitySpeakerController(onSpeakerChange = { speakerOn ->
            runCatching { OngoingCallHolder.setSpeaker(speakerOn) }
        })
        if (controller.start(applicationContext)) {
            proximityController = controller
        }
    }

    private fun stopProximityController() {
        proximityController?.stop()
        proximityController = null
    }

    /**
     * Start the MediaSession-based volume-key interceptor for the duration of the
     * RINGING state. Volume Up answers the call; Volume Down stops our ringer.
     */
    private fun startVolumeInterceptor() {
        if (volumeInterceptor != null) return
        val interceptor = RingVolumeKeyInterceptor(
            onVolumeUp = { runCatching { OngoingCallHolder.answer() } },
            onVolumeDown = {
                // Stop our own ringer directly; we also rely on the platform
                // calling onSilenceRinger when this is invoked.
                ringer.stop()
            },
        )
        interceptor.start(applicationContext)
        volumeInterceptor = interceptor
        startVolumeReceiver()
    }

    private fun stopVolumeInterceptor() {
        volumeInterceptor?.stop()
        volumeInterceptor = null
        stopVolumeReceiver()
    }

    /**
     * Best-effort fallback for "Volume Up answers the call": some OEM platforms
     * intercept the rocker before the [RingVolumeKeyInterceptor] media session
     * sees it and just change the stream volume. We listen for that volume change
     * broadcast and treat a volume increase on STREAM_RING as an answer request
     * while we are still in RINGING. Volume Down is intentionally NOT handled
     * here — the platform delivers it via [onSilenceRinger], and reacting to
     * spurious `curr < prev` events here was causing Volume Up to be misread as
     * a mute on some devices that fire a transient downward broadcast on the
     * same press.
     *
     * We MUST filter by [EXTRA_VOLUME_STREAM_TYPE] == STREAM_RING: without that
     * filter, any unrelated volume change (notifications, media, system, an
     * "increasing ringtone" OEM feature ramping our own ringer up, etc.) was
     * triggering false auto-answers on stationary devices.
     */
    private fun startVolumeReceiver() {
        if (volumeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != VOLUME_CHANGED_ACTION) return
                // Only react to the RING stream; ignore media/notification/etc.
                val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                if (stream != AudioManager.STREAM_RING) return
                val curr = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                val prev = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)
                if (curr < 0 || prev < 0) return
                // Re-confirm we are still actually ringing — defends against a
                // late broadcast arriving after the call moved to ACTIVE/DISCONNECTED.
                val call = currentCall ?: return
                if (call.details?.state != Call.STATE_RINGING) return
                if (curr > prev) runCatching { OngoingCallHolder.answer() }
            }
        }
        registerReceiver(receiver, IntentFilter(VOLUME_CHANGED_ACTION))
        volumeReceiver = receiver
    }

    private fun stopVolumeReceiver() {
        volumeReceiver?.let { runCatching { unregisterReceiver(it) } }
        volumeReceiver = null
    }

    private companion object {
        // android.media.AudioManager.VOLUME_CHANGED_ACTION is a hidden constant,
        // but the string itself is stable AOSP API and safe to reference.
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
