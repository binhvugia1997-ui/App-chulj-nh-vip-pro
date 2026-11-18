package com.gia.aicomposer.util

import android.graphics.PointF
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PreviewView dùng FIT_CENTER: toạ độ overlay 0..1 phải chiếu qua rect nội dung, nếu không
 * khung/vòng tâm lệch đúng bằng phần viền đen. Đây là đầu vào của [com.gia.aicomposer.pipeline.FrameLockTracker].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewGeometryTest {

    private fun assertRect(expected: RectF, actual: RectF) {
        assertEquals(expected.left, actual.left, 1e-3f)
        assertEquals(expected.top, actual.top, 1e-3f)
        assertEquals(expected.right, actual.right, 1e-3f)
        assertEquals(expected.bottom, actual.bottom, 1e-3f)
    }

    @Test
    fun matchingAspectFillsTheView() {
        assertRect(RectF(0f, 0f, 1080f, 1920f), PreviewGeometry.contentRect(1080, 1920, 1080f / 1920f))
    }

    @Test
    fun tallerViewGetsLetterboxTopAndBottom() {
        // 4:3 trong view 9:16 → cao 810, viền trên/dưới 555.
        assertRect(RectF(0f, 555f, 1080f, 1365f), PreviewGeometry.contentRect(1080, 1920, 4f / 3f))
    }

    @Test
    fun widerViewGetsPillarboxLeftAndRight() {
        // 4:3 trong view 16:9 → rộng 1440, viền hai bên 240.
        assertRect(RectF(240f, 0f, 1680f, 1080f), PreviewGeometry.contentRect(1920, 1080, 4f / 3f))
    }

    @Test
    fun invalidViewportGivesEmptyRect() {
        assertRect(RectF(), PreviewGeometry.contentRect(0, 1920, 4f / 3f))
        assertRect(RectF(), PreviewGeometry.contentRect(1080, 1920, 0f))
    }

    @Test
    fun normalizedCenterMapsToViewCenterInLetterbox() {
        val content = PreviewGeometry.contentRect(1080, 1920, 4f / 3f)
        val center = PreviewGeometry.toView(PointF(0.5f, 0.5f), content)
        assertEquals(540f, center.x, 1e-3f)
        assertEquals(960f, center.y, 1e-3f)
    }

    @Test
    fun viewToNormalizedRoundTrips() {
        val content = PreviewGeometry.contentRect(1080, 1920, 4f / 3f)
        val original = PointF(0.25f, 0.8f)
        val view = PreviewGeometry.toView(original, content)
        val back = PreviewGeometry.toNormalized(view.x, view.y, content)
        assertEquals(original.x, back.x, 1e-4f)
        assertEquals(original.y, back.y, 1e-4f)
    }

    @Test
    fun normalizedRectMapsIntoLetterboxedContent() {
        val content = PreviewGeometry.contentRect(1080, 1920, 4f / 3f)
        assertRect(RectF(270f, 555f, 810f, 1365f), PreviewGeometry.rectToView(RectF(0.25f, 0f, 0.75f, 1f), content))
    }

    @Test
    fun normalizedAspectMatchesFramingConstants() {
        // Khung 3:4 trong ảnh 4:3 → width/height chuẩn hoá = 0.75 / (4/3) = 0.5625.
        assertEquals(0.5625f, PreviewGeometry.normalizedAspect(4f / 3f, 3f / 4f), 1e-4f)
    }
}
