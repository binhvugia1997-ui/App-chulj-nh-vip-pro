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

/** Một vùng trống trong khung, toạ độ chuẩn hoá 0..1. */
data class FreeRegion(val box: RectF) {
    val heightFraction: Float get() = box.height()
    val widthFraction: Float get() = box.width()
}

/**
 * Kết quả nhận diện cảnh của một khung: vật thể + độ tin cậy + vùng sâu + các vùng trống.
 * SceneState.empty() là fallback offline: không có model vẫn còn vùng trống toàn khung.
 */
data class SceneState(
    val objects: List<SceneObject> = emptyList(),
    val freeRegions: List<FreeRegion> = emptyList(),
    val nearClutterRatio: Float = 0f,
    val timestampMs: Long = 0L,
) {
    val tallestOpenRegion: Float
        get() = freeRegions.maxOfOrNull { it.heightFraction } ?: 0f

    val hasOpenGround: Boolean
        get() = freeRegions.any { it.heightFraction >= 0.45f && it.widthFraction >= 0.20f }

    val affordances: Set<Affordance>
        get() {
            val result = mutableSetOf<Affordance>()
            objects.forEach { result.addAll(it.affordances) }
            if (hasOpenGround) result.add(Affordance.OPEN_GROUND)
            return result
        }

    val isFrontBusy: Boolean
        get() = nearClutterRatio > 0.35f

    val summary: String
        get() = objects.take(3).joinToString(separator = ", ") {
            "${it.label} ${(it.confidence * 100f).toInt()}%"
        }

    companion object {
        fun empty(): SceneState = SceneState(
            freeRegions = listOf(FreeRegion(RectF(0.10f, 0f, 0.90f, 1f))),
        )
    }
}
