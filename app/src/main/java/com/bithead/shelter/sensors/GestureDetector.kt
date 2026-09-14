package com.bithead.shelter.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

class GestureDetector(
    context: Context,
    private val onJerkDetected: () -> Unit
) : SensorEventListener {
    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val triggerGate = JerkTriggerGate()
    private var started = false

    val isAvailable: Boolean get() = accelerometer != null

    fun start(): Boolean {
        if (started) return true
        if (accelerometer == null) return false
        triggerGate.reset()
        started = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        return started
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        started = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitudeG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (triggerGate.onSample(magnitudeG, SystemClock.elapsedRealtime())) {
            onJerkDetected()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

}

internal class JerkTriggerGate(
    private val thresholdG: Float = 1.8f,
    private val cooldownMs: Long = 2_500L
) {
    private var aboveThreshold = false
    private var lastTriggerAt: Long? = null

    fun onSample(magnitudeG: Float, nowMs: Long): Boolean {
        val crossedThreshold = magnitudeG >= thresholdG && !aboveThreshold
        aboveThreshold = magnitudeG >= thresholdG
        if (!crossedThreshold) return false

        val last = lastTriggerAt
        if (last != null && nowMs - last < cooldownMs) return false
        lastTriggerAt = nowMs
        return true
    }

    fun reset() {
        aboveThreshold = false
        lastTriggerAt = null
    }
}
