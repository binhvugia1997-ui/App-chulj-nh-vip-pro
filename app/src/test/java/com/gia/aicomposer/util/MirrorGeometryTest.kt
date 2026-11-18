package com.gia.aicomposer.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Camera trước: preview bị lật ngang, toạ độ tracker phải lật theo trước khi vẽ. */
class MirrorGeometryTest {

    @Test
    fun mirrorXFlipsAroundCenter() {
        assertEquals(0.2f, PreviewGeometry.mirrorX(0.8f), 1e-4f)
        assertEquals(0.5f, PreviewGeometry.mirrorX(0.5f), 1e-4f)
    }

    @Test
    fun mirroredPointRoundTrips() {
        val p = PreviewGeometry.mirrorX(PreviewGeometry.mirrorX(0.37f))
        assertEquals(0.37f, p, 1e-4f)
    }
}
