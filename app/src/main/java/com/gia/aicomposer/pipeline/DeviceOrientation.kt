package com.gia.aicomposer.pipeline

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

data class DevicePosture(
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val valid: Boolean = false,
)

/** Chênh lệch góc ngắn nhất giữa hai hướng, kết quả trong (-180, 180]. */
fun shortestAngleDelta(currentDeg: Float, baseDeg: Float): Float {
    var delta = (currentDeg - baseDeg) % 360f
    if (delta > 180f) delta -= 360f
    if (delta <= -180f) delta += 360f
    return delta
}

/**
 * Tư thế máy từ cảm biến rotation vector (fallback game rotation vector).
 * Dùng để khóa mẫu ở bước LOCKING_SAMPLE rồi tính độ trôi khung khi máy xoay.
 */
class DeviceOrientation(context: Context) : SensorEventListener {

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    val hasSensor: Boolean = rotationSensor != null

    @Volatile
    var posture: DevicePosture = DevicePosture()
        private set

    /** Tốc độ xoay gần đúng (deg/s), dùng cho làm mượt thích ứng + bù dự đoán. */
    @Volatile
    var angularVelocityDegPerSec: Float = 0f
        private set

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var previousYaw = 0f
    private var previousMs = 0L
    private var running = false

    fun start() {
        if (running) return
        running = true
        rotationSensor?.let { sensorManager.registerListener(this, it, SAMPLE_INTERVAL_US) }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        previousMs = 0L
        angularVelocityDegPerSec = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        val now = SystemClock.elapsedRealtime()

        if (previousMs != 0L) {
            val deltaSeconds = (now - previousMs).coerceAtLeast(16L) / 1000f
            val instantVelocity = abs(shortestAngleDelta(yaw, previousYaw)) / deltaSeconds
            angularVelocityDegPerSec =
                angularVelocityDegPerSec * (1f - VELOCITY_SMOOTHING) + instantVelocity * VELOCITY_SMOOTHING
        }

        previousYaw = yaw
        previousMs = now
        posture = DevicePosture(yawDeg = yaw, pitchDeg = pitch, rollDeg = roll, valid = true)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val SAMPLE_INTERVAL_US = 16_666
        private const val VELOCITY_SMOOTHING = 0.3f
    }
}
