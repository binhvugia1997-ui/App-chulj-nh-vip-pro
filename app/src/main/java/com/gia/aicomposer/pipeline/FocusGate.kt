package com.gia.aicomposer.pipeline

/**
 * Cổng chờ lấy nét/phơi sáng hội tụ trước khi cho pipeline tự chụp.
 *
 * Camera2 báo trạng thái AF/AE theo từng frame ([android.hardware.camera2.TotalCaptureResult]);
 * một frame "đã nét" chưa đủ vì ngay sau đó máy có thể chỉnh tiếp. Cổng này chỉ mở khi cả AF và AE
 * cùng converged **liên tục** trong [settleWindowMs] (mặc định 400 ms), đủ dài để tránh chụp đúng
 * lúc ống kính còn đang chạy.
 *
 * Không thread-safe: gọi từ main thread (assistant chuyển kết quả camera về main thread).
 */
class FocusGate(private val settleWindowMs: Long = DEFAULT_SETTLE_WINDOW_MS) {

    /** Thời điểm bắt đầu chuỗi converged hiện tại; null khi AF hoặc AE vừa lệch. */
    private var convergedSinceMs: Long? = null

    /** Thời điểm của lần cập nhật gần nhất, dùng để tính cửa sổ mà không cần đồng hồ riêng. */
    private var lastMs = 0L

    /** Gọi mỗi khi có một `TotalCaptureResult` mới. */
    fun onCaptureResult(afConverged: Boolean, aeConverged: Boolean, nowMs: Long) {
        lastMs = nowMs
        if (!afConverged || !aeConverged) {
            convergedSinceMs = null
            return
        }
        if (convergedSinceMs == null) convergedSinceMs = nowMs
    }

    /** True khi AF/AE đã converged liên tục đủ [settleWindowMs] tính tới lần cập nhật cuối. */
    fun isReady(): Boolean {
        val since = convergedSinceMs ?: return false
        return lastMs - since >= settleWindowMs
    }

    /** Quên trạng thái cũ (gọi khi người dùng chạm lấy nét hoặc bắt đầu một lần căn mới). */
    fun reset() {
        convergedSinceMs = null
        lastMs = 0L
    }

    companion object {
        const val DEFAULT_SETTLE_WINDOW_MS = 400L
    }
}
