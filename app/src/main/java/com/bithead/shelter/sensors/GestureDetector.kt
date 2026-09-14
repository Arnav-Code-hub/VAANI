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
    private var lastTriggerAt = 0L
    private var started = false

    init {
        check(JERK_THRESHOLD in 20f..24f)
    }

    fun start() {
        if (started || accelerometer == null) return
        started = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
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
        val magnitude = sqrt(x * x + y * y + z * z)
        val now = SystemClock.elapsedRealtime()
        if (magnitude >= JERK_THRESHOLD && now - lastTriggerAt >= COOLDOWN_MS) {
            lastTriggerAt = now
            onJerkDetected()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val JERK_THRESHOLD = 22f
        const val COOLDOWN_MS = 4_000L
    }
}
