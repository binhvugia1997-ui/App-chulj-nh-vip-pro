package com.gia.aicomposer.model

import android.graphics.RectF
import com.gia.aicomposer.pose.Affordance

/** Độ sâu tương đối của vật thể trong khung (chỉ cần NEAR để tính "nền trước lộn xộn"). */
enum class DepthBand { NEAR, MID, FAR }

data class SceneObject(
    val label: String,
    val confidence: Float,
    /** Hộp chuẩn hoá 0..1 theo khung hình đã xoay đúng chiều. */
    val box: RectF,
    val depth: DepthBand,
    val affordances: Set<Affordance>,
)

/**
 * Kết quả nhận diện cảnh của một khung: vật thể + độ tin cậy + vùng sâu + vùng trống lớn nhất.
 * [freeRegion] luôn có giá trị (mặc định cả khung) nên không cần null-check.
 * SceneState.empty() là fallback offline: không có model vẫn còn vùng trống để chọn dáng.
 */
data class SceneState(
    val objects: List<SceneObject> = emptyList(),
    /** Hình chữ nhật trống lớn nhất, chuẩn hoá 0..1. */
    val freeRegion: RectF = RectF(0f, 0f, 1f, 1f),
    val nearClutterRatio: Float = 0f,
) {
    val tallestOpenRegion: Float get() = freeRegion.height()

    val hasOpenGround: Boolean
        get() = freeRegion.height() >= 0.45f && freeRegion.width() >= 0.20f

    val affordances: Set<Affordance>
        get() {
            val result = mutableSetOf<Affordance>()
            objects.forEach { result.addAll(it.affordances) }
            if (hasOpenGround) result.add(Affordance.OPEN_GROUND)
            return result
        }

    val isFrontBusy: Boolean get() = nearClutterRatio > BUSY_CLUTTER_RATIO

    val summary: String
        get() = objects.take(3).joinToString(separator = ", ") {
            "${it.label} ${(it.confidence * 100f).toInt()}%"
        }

    companion object {
        /** Vật gần che quá 35% khung thì coi là nền trước lộn xộn. */
        const val BUSY_CLUTTER_RATIO = 0.35f

        fun empty(): SceneState = SceneState(
            freeRegion = RectF(0.10f, 0f, 0.90f, 1f),
        )
    }
}
