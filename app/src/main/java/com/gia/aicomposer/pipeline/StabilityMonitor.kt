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
    val isStable: Boolean = false,
    val shakeToken: Long = 0L,
    val hasSensors: Boolean = false,
)

/**
 * Ổn định máy: đọc gia tốc + con quay ~60Hz, cửa sổ 0.8s, tính độ lệch chuẩn của
 * độ lớn vector. Yên khi std(gia tốc) < 0.12 và std(con quay) < 0.08.
 *
 * Cần 10 lần đánh giá liên tiếp, mỗi lần cách nhau 50ms, mới tính là yên: cửa sổ ~500ms
 * (spec gốc: "10 lần yên thì đạt"; ngưỡng gyro 0.08 lấy từ app cũ vốn giữ 600ms — muốn đúng
 * 600ms thì đặt [REQUIRED_STILL_EVALUATIONS] = 12). Rung mạnh (gia tốc > 1.5 hoặc con quay > 2.0)
 * thì tăng [MotionSnapshot.shakeToken] để pipeline reset toàn bộ trạng thái khóa.
 */
class StabilityMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val hasMotionSensors: Boolean = accelSensor != null || gyroSensor != null

    var onSnapshot: ((MotionSnapshot) -> Unit)? = null

    private val accelWindow = ArrayDeque<Pair<Long, Float>>()
    private val gyroWindow = ArrayDeque<Pair<Long, Float>>()
    private var lastEvaluationMs = 0L
    private var stillStreak = 0
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
        lastEvaluationMs = 0L
        publish(MotionSnapshot(hasSensors = hasMotionSensors, shakeToken = shakeToken))
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        val values = event.values
        val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelWindow.addLast(now to magnitude)
                prune(accelWindow, now)
                maybeEvaluate(now, magnitude)
            }

            Sensor.TYPE_GYROSCOPE -> {
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
        val strongShake = accelStd > STRONG_SHAKE_ACCEL ||
            gyroStd > STRONG_SHAKE_GYRO ||
            abs(instantAccel - SensorManager.GRAVITY_EARTH) > STRONG_SHAKE_ACCEL

        if (strongShake) {
            shakeToken++
            stillStreak = 0
            accelWindow.clear()
            gyroWindow.clear()
            publish(MotionSnapshot(isStable = false, shakeToken = shakeToken, hasSensors = hasMotionSensors))
            return
        }

        val still = accelStd < ACCEL_STILL_STD && gyroStd < GYRO_STILL_STD
        stillStreak = if (still) stillStreak + 1 else 0
        publish(
            MotionSnapshot(
                isStable = stillStreak >= REQUIRED_STILL_EVALUATIONS,
                shakeToken = shakeToken,
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
        val mean = sum / window.size
        val variance = (sumSq / window.size) - mean * mean
        return if (variance <= 0.0) 0f else sqrt(variance).toFloat()
    }

    companion object {
        /** Cửa sổ tính độ lệch chuẩn. */
        const val WINDOW_MS = 800L
        /** ~60Hz. */
        const val SAMPLE_INTERVAL_US = 16_666
        /** Nhịp đánh giá 50ms → 10 lần ≈ 500ms (12 lần mới tròn 600ms). */
        const val EVALUATION_INTERVAL_MS = 50L

        const val ACCEL_STILL_STD = 0.12f
        /** Ngưỡng con quay cũ của app: 0.08. */
        const val GYRO_STILL_STD = 0.08f
        const val REQUIRED_STILL_EVALUATIONS = 10
        const val STRONG_SHAKE_ACCEL = 1.5f
        const val STRONG_SHAKE_GYRO = 2.0f
        const val MIN_WINDOW_SAMPLES = 10
    }
}
