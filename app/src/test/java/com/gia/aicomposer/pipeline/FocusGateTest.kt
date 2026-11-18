package com.gia.aicomposer.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FocusGate: chỉ cho pipeline tự chụp khi AF và AE đã giữ ổn định đủ lâu
 * (mặc định 400 ms) kể từ lần gần nhất cả hai cùng converged.
 */
class FocusGateTest {

    @Test
    fun notReadyBeforeAnyResult() {
        assertFalse(FocusGate().isReady())
    }

    @Test
    fun becomesReadyOnlyAfterWindowOfConvergedResults() {
        val gate = FocusGate(settleWindowMs = 400)
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 0)
        assertFalse(gate.isReady())
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 399)
        assertFalse(gate.isReady())
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 400)
        assertTrue(gate.isReady())
    }

    @Test
    fun divergenceResetsTheWindow() {
        val gate = FocusGate(settleWindowMs = 400)
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 0)
        gate.onCaptureResult(afConverged = false, aeConverged = true, nowMs = 200)
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 300)
        assertFalse(gate.isReady())
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 700)
        assertTrue(gate.isReady())
    }

    @Test
    fun resetMakesItUnreadyAgain() {
        val gate = FocusGate(settleWindowMs = 400)
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 0)
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 400)
        assertTrue(gate.isReady())
        gate.reset()
        assertFalse(gate.isReady())
    }

    @Test
    fun exposureDivergenceAlsoResetsTheWindow() {
        val gate = FocusGate(settleWindowMs = 400)
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 0)
        gate.onCaptureResult(afConverged = true, aeConverged = false, nowMs = 250)
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 500)
        assertFalse(gate.isReady())
        gate.onCaptureResult(afConverged = true, aeConverged = true, nowMs = 900)
        assertTrue(gate.isReady())
    }
}
