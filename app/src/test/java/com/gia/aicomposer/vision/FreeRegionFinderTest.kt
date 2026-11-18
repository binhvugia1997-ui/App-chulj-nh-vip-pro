package com.gia.aicomposer.vision

import android.graphics.RectF
import com.gia.aicomposer.model.DepthBand
import com.gia.aicomposer.model.SceneObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lưới 8×12: ô bị coi là bận khi `confidence × weight ≥ 0.4` (NEAR 1.0, MID 0.6, FAR bỏ qua).
 * Vùng trống trả về là hình chữ nhật trống **lớn nhất**, chuẩn hoá 0..1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeRegionFinderTest {

    private fun obj(
        box: RectF,
        depth: DepthBand = DepthBand.NEAR,
        confidence: Float = 0.9f,
    ) = SceneObject("obj", confidence, box, depth, emptySet())

    private fun assertRect(left: Float, top: Float, right: Float, bottom: Float, actual: RectF) {
        assertEquals("left", left, actual.left, 1e-4f)
        assertEquals("top", top, actual.top, 1e-4f)
        assertEquals("right", right, actual.right, 1e-4f)
        assertEquals("bottom", bottom, actual.bottom, 1e-4f)
    }

    @Test
    fun emptySceneUsesWholeFrame() {
        assertRect(0f, 0f, 1f, 1f, FreeRegionFinder.find(emptyList()))
    }

    @Test
    fun objectOverLeftHalfLeavesRightHalf() {
        // Tâm ô cột 0..3 nằm trong [0, 0.5) → cột 4..7 trống.
        assertRect(0.5f, 0f, 1f, 1f, FreeRegionFinder.find(listOf(obj(RectF(0f, 0f, 0.5f, 1f)))))
    }

    @Test
    fun objectOverTopHalfLeavesBottomHalf() {
        // Tâm ô hàng 0..5 nằm trong [0, 0.5) → hàng 6..11 trống.
        assertRect(0f, 0.5f, 1f, 1f, FreeRegionFinder.find(listOf(obj(RectF(0f, 0f, 1f, 0.5f)))))
    }

    @Test
    fun narrowGapBetweenTwoObjectsIsReturned() {
        val region = FreeRegionFinder.find(
            listOf(
                obj(RectF(0f, 0f, 0.375f, 1f)),
                obj(RectF(0.625f, 0f, 1f, 1f)),
            ),
        )
        assertRect(0.375f, 0f, 0.625f, 1f, region)
    }

    @Test
    fun largestRectangleWinsOverTallerButNarrowerOne() {
        // Góc dưới-trái bị che: dải ngang 8×9 ô (72) lớn hơn cột dọc 4×12 ô (48).
        val region = FreeRegionFinder.find(listOf(obj(RectF(0f, 0.75f, 0.5f, 1f))))
        assertRect(0f, 0f, 1f, 0.75f, region)
    }

    @Test
    fun farObjectsAreIgnored() {
        assertRect(0f, 0f, 1f, 1f, FreeRegionFinder.find(listOf(obj(RectF(0f, 0f, 1f, 1f), DepthBand.FAR))))
    }

    @Test
    fun lowConfidenceObjectsAreIgnored() {
        // NEAR 0.39 < 0.4 → bỏ qua, cả khung còn trống.
        assertRect(0f, 0f, 1f, 1f, FreeRegionFinder.find(listOf(obj(RectF(0f, 0f, 1f, 1f), confidence = 0.39f))))
        // NEAR đúng 0.4 được tính → che kín khung, rơi về fallback cả khung.
        assertRect(0f, 0f, 1f, 1f, FreeRegionFinder.find(listOf(obj(RectF(0f, 0f, 1f, 1f), confidence = 0.4f))))
    }

    @Test
    fun midDepthNeedsHigherConfidence() {
        // MID: 0.6 × 0.6 = 0.36 < 0.4 → bỏ qua.
        assertRect(0f, 0f, 1f, 1f, FreeRegionFinder.find(listOf(obj(RectF(0f, 0f, 1f, 1f), DepthBand.MID, 0.6f))))
        // MID: 0.7 × 0.6 = 0.42 ≥ 0.4 → che kín khung.
        assertRect(0f, 0f, 1f, 1f, FreeRegionFinder.find(listOf(obj(RectF(0f, 0f, 1f, 1f), DepthBand.MID, 0.7f))))
    }
}
