package com.accessible.dialer.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Lightweight shake-gesture detector built on the accelerometer.
 *
 * Approach: each sensor sample yields a vector whose magnitude is `sqrt(x^2+y^2+z^2)`.
 * Subtracting `SensorManager.STANDARD_GRAVITY` gives the *delta* from a stationary
 * phone laid flat. We accumulate samples whose absolute delta exceeds [shakeGForce]
 * G's; once we see [shakesNeeded] such samples inside a short window the gesture is
 * recognized and [onShake] fires. A debounce of [cooldownMs] ms blocks immediate
 * re-triggers so a single shake doesn't fire twice.
 *
 * Defaults are tuned to require a deliberate wrist-flick (~2.5 G peak, 3 samples in
 * 600ms), high enough to ignore walking / car bumps but low enough that elderly /
 * low-mobility users can reliably trigger it.
 *
 * Not thread-safe; instantiate once per usage site (Activity, Service) and call
 * [start] / [stop] from the corresponding lifecycle method.
 */
class ShakeDetector(
    private val onShake: () -> Unit,
    private val shakeGForce: Float = 2.5f,
    private val shakesNeeded: Int = 3,
    private val windowMs: Long = 600L,
    private val cooldownMs: Long = 1500L,
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0L
    private var hitTimestamps: ArrayDeque<Long> = ArrayDeque()

    fun start(context: Context): Boolean {
        if (sensorManager != null) return true // already started
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        sensorManager = sm
        accelerometer = acc
        return sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        accelerometer = null
        hitTimestamps.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.STANDARD_GRAVITY
        if (gForce < shakeGForce) return

        val now = System.currentTimeMillis()
        if (now - lastShakeTime < cooldownMs) return

        // Sliding window of recent over-threshold samples. We trim entries older than
        // windowMs so a slow series of jolts never accumulates into a false positive.
        hitTimestamps.addLast(now)
        while (hitTimestamps.isNotEmpty() && now - hitTimestamps.first() > windowMs) {
            hitTimestamps.removeFirst()
        }
        if (hitTimestamps.size >= shakesNeeded) {
            lastShakeTime = now
            hitTimestamps.clear()
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}
