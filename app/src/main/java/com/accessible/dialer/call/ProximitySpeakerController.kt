package com.accessible.dialer.call

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Reads the proximity sensor and auto-flips the speakerphone:
 *  - phone moved away from ear (FAR) → speakerphone on
 *  - phone returned close to ear (NEAR) → speakerphone off
 *
 * The detector ignores changes that happen within [debounceMs] of the previous
 * transition so a brief flicker (e.g. the user re-adjusting their grip) does not
 * toggle the route. Re-entrant; calling [start] twice is a no-op.
 */
class ProximitySpeakerController(
    private val onSpeakerChange: (Boolean) -> Unit,
    private val debounceMs: Long = 250L,
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var lastWasNear: Boolean? = null
    private var lastTransitionAt: Long = 0L

    fun start(context: Context): Boolean {
        if (sensorManager != null) return true
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val s = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return false
        sensorManager = sm
        sensor = s
        // Reset state so we don't carry over a stale NEAR/FAR across activations.
        lastWasNear = null
        lastTransitionAt = 0L
        return sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        sensor = null
        lastWasNear = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = sensor ?: return
        if (event.sensor?.type != Sensor.TYPE_PROXIMITY) return
        // Many sensors are binary (0 = NEAR, maxRange = FAR); the canonical
        // interpretation per the Android docs is value < maxRange ⇒ NEAR.
        val isNear = event.values[0] < s.maximumRange
        if (isNear == lastWasNear) return
        val now = SystemClock.elapsedRealtime()
        if (lastWasNear != null && now - lastTransitionAt < debounceMs) return
        lastWasNear = isNear
        lastTransitionAt = now
        // NEAR → ear-piece (speaker off); FAR → speakerphone on.
        onSpeakerChange(!isNear)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}
