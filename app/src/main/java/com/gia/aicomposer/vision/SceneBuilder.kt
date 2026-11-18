package com.gia.aicomposer.vision

import android.graphics.RectF
import com.gia.aicomposer.model.DepthBand
import com.gia.aicomposer.model.SceneObject
import com.gia.aicomposer.model.SceneState
import com.gia.aicomposer.pose.PoseLibrary
import com.google.mediapipe.tasks.components.containers.Detection

/**
 * Chuyển kết quả EfficientDet (nhãn COCO + score + bounding box) thành SceneState:
 * nhãn, độ tin cậy, vùng sâu NEAR/MID/FAR và các vùng trống.
 */
internal object SceneBuilder {

    private const val MIN_CONFIDENCE = 0.35f
    private const val NEAR_AREA_RATIO = 0.16f
    private const val NEAR_BOTTOM_RATIO = 0.86f
    private const val FAR_AREA_RATIO = 0.05f

    fun build(detections: List<Detection>, frameWidth: Int, frameHeight: Int): SceneState {
        if (frameWidth <= 0 || frameHeight <= 0) return SceneState.empty()

        val objects = mutableListOf<SceneObject>()
        for (detection in detections) {
            val category = detection.categories().firstOrNull() ?: continue
            val label = category.categoryName()
            val score = category.score()
            if (label.isBlank() || score < MIN_CONFIDENCE) continue

            val raw = RectF(detection.boundingBox())
            val box = RectF(
                (raw.left / frameWidth).coerceIn(0f, 1f),
                (raw.top / frameHeight).coerceIn(0f, 1f),
                (raw.right / frameWidth).coerceIn(0f, 1f),
                (raw.bottom / frameHeight).coerceIn(0f, 1f),
            )
            if (box.width() <= 0f || box.height() <= 0f) continue

            val area = box.width() * box.height()
            val depth = when {
                area >= NEAR_AREA_RATIO || box.bottom >= NEAR_BOTTOM_RATIO -> DepthBand.NEAR
                area <= FAR_AREA_RATIO -> DepthBand.FAR
                else -> DepthBand.MID
            }

            objects += SceneObject(
                label = label,
                confidence = score,
                box = box,
                depth = depth,
                affordances = PoseLibrary.affordancesForLabel(label),
            )
        }

        val nearClutter = objects
            .filter { it.depth == DepthBand.NEAR }
            .sumOf { (it.box.width() * it.box.height()).toDouble() }
            .toFloat()
            .coerceIn(0f, 1f)

        return SceneState(
            objects = objects,
            freeRegion = FreeRegionFinder.find(objects),
            nearClutterRatio = nearClutter,
        )
    }
}
