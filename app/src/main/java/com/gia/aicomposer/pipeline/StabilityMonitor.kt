package com.gia.aicomposer.pipeline

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

data class MotionSnapshot(
    val accelStd: Float = 0f,
    val gyroStd: Float = 0f,
    val isStill: Boolean = false,
    val isStable: Boolean = false,
    val isShaking: Boolean = false,
    val stableForMs: Long = 0L,
    val shakeToken: Long = 0L,
    val sampleCount: Int = 0,
    val hasSensors: Boolean = false,
)

/**
 * Ổn định máy: đọc gia tốc + con quay ~60Hz, cửa sổ 0.8s, tính độ lệch chuẩn của
 * độ lớn vector. Yên khi std(gia tốc) < 0.12 và std(con quay) < 0.08.
 *
 * Cần 10 lần đánh giá liên tiếp (mỗi lần cách nhau 50ms ≈ 600ms) mới tính là yên,
 * 5 lần liên tiếp mới tính là rung. Rung mạnh (gia tốc > 1.5 hoặc con quay > 2.0)
 * thì tăng shakeToken để pipeline reset toàn bộ trạng thái khóa.
 *
 * Ngưỡng sẵn có của app (con quay 0.08 / 600ms) được giữ nguyên: GYRO_STILL_STD = 0.08
 * và 10 lần × 50ms ≈ 600ms chính là LEGACY_STABILITY_HOLD_MS.
 */
class StabilityMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val hasMotionSensors: Boolean = accelSensor != null || gyroSensor != null

    /** Sensor mất sau một thời gian (một số máy tắt gyro khi tiết kiệm pin). */
    var onSnapshot: ((MotionSnapshot) -> Unit)? = null

    private val accelWindow = ArrayDeque<Pair<Long, Float>>()
    private val gyroWindow = ArrayDeque<Pair<Long, Float>>()
    private var lastEvaluationMs = 0L
    private var stillStreak = 0
    private var shakeStreak = 0
    private var shakeToken = 0L
    private var running = false

    fun start() {
        if (running) return
        running = true
        accelSensor?.let { sensorManager.registerListener(this, it, SAMPLE_INTERVAL_US) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SAMPLE_INTERVAL_US) }
        publish(MotionSnapshot(hasSensors = hasMotionSensors, shakeToken = shakeToken))
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        accelWindow.clear()
        gyroWindow.clear()
        stillStreak = 0
        shakeStreak = 0
        lastEvaluationMs = 0L
        publish(MotionSnapshot(hasSensors = hasMotionSensors, shakeToken = shakeToken))
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val values = event.values
                val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
                accelWindow.addLast(now to magnitude)
                prune(accelWindow, now)
                maybeEvaluate(now, magnitude)
            }

            Sensor.TYPE_GYROSCOPE -> {
                val values = event.values
                val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
                gyroWindow.addLast(now to magnitude)
                prune(gyroWindow, now)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun prune(window: ArrayDeque<Pair<Long, Float>>, now: Long) {
        while (window.isNotEmpty() && now - window.first().first > WINDOW_MS) {
            window.removeFirst()
        }
    }

    private fun maybeEvaluate(now: Long, instantAccel: Float) {
        if (now - lastEvaluationMs < EVALUATION_INTERVAL_MS) return
        lastEvaluationMs = now
        if (accelWindow.size < MIN_WINDOW_SAMPLES) return

        val accelStd = stdDev(accelWindow)
        val gyroStd = if (gyroWindow.size >= MIN_WINDOW_SAMPLES) stdDev(gyroWindow) else 0f
        val instantDeviation = abs(instantAccel - SensorManager.GRAVITY_EARTH)
        val strongShake = accelStd > STRONG_SHAKE_ACCEL ||
            gyroStd > STRONG_SHAKE_GYRO ||
            instantDeviation > STRONG_SHAKE_ACCEL

        if (strongShake) {
            shakeToken++
            stillStreak = 0
            shakeStreak = REQUIRED_SHAKE_EVALUATIONS
            accelWindow.clear()
            gyroWindow.clear()
            publish(
                MotionSnapshot(
                    accelStd = accelStd,
                    gyroStd = gyroStd,
                    isStill = false,
                    isStable = false,
                    isShaking = true,
                    stableForMs = 0L,
                    shakeToken = shakeToken,
                    sampleCount = accelWindow.size,
                    hasSensors = hasMotionSensors,
                ),
            )
            return
        }

        val still = accelStd < ACCEL_STILL_STD && gyroStd < GYRO_STILL_STD
        if (still) {
            stillStreak++
            shakeStreak = 0
        } else {
            stillStreak = 0
            shakeStreak = if (accelStd > ACCEL_STILL_STD * 1.5f || gyroStd > GYRO_STILL_STD * 1.5f) {
                shakeStreak + 1
            } else {
                (shakeStreak - 1).coerceAtLeast(0)
            }
        }

        val isStable = stillStreak >= REQUIRED_STILL_EVALUATIONS
        publish(
            MotionSnapshot(
                accelStd = accelStd,
                gyroStd = gyroStd,
                isStill = still,
                isStable = isStable,
                isShaking = shakeStreak >= REQUIRED_SHAKE_EVALUATIONS,
                stableForMs = stillStreak * EVALUATION_INTERVAL_MS,
                shakeToken = shakeToken,
                sampleCount = accelWindow.size,
                hasSensors = hasMotionSensors,
            ),
        )
    }

    private fun publish(snapshot: MotionSnapshot) {
        onSnapshot?.invoke(snapshot)
    }

    private fun stdDev(window: ArrayDeque<Pair<Long, Float>>): Float {
        if (window.isEmpty()) return 0f
        var sum = 0.0
        var sumSq = 0.0
        for ((_, value) in window) {
            sum += value
            sumSq += value.toDouble() * value
        }
        val count = window.size
        val mean = sum / count
        val variance = (sumSq / count) - mean * mean
        return if (variance <= 0.0) 0f else sqrt(variance).toFloat()
    }

    companion object {
        /** Cửa sổ tính độ lệch chuẩn. */
        const val WINDOW_MS = 800L
        /** ~60Hz. */
        const val SAMPLE_INTERVAL_US = 16_666
        /** Nhịp đánh giá 50ms -> 10 lần ≈ 600ms. */
        const val EVALUATION_INTERVAL_MS = 50L

        const val ACCEL_STILL_STD = 0.12f
        const val GYRO_STILL_STD = 0.08f
        const val REQUIRED_STILL_EVALUATIONS = 10
        const val REQUIRED_SHAKE_EVALUATIONS = 5
        const val STRONG_SHAKE_ACCEL = 1.5f
        const val STRONG_SHAKE_GYRO = 2.0f
        const val MIN_WINDOW_SAMPLES = 10

        /** Ngưỡng sẵn có của app, giữ lại để đối chiếu: con quay 0.08 trong 600ms. */
        const val LEGACY_STABILITY_GYRO_THRESHOLD = 0.08f
        const val LEGACY_STABILITY_HOLD_MS = 600L
    }
}
