package com.gia.aicomposer.settings

import android.content.Context

/**
 * Cài đặt người dùng cho pipeline gợi ý dáng + tự chụp.
 * Lưu bằng SharedPreferences để giữ nguyên giữa các lần mở app.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Bật/tắt gợi ý dáng và overlay hình que. */
    var poseSuggestionsEnabled: Boolean
        get() = prefs.getBoolean(KEY_POSE_SUGGESTIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_POSE_SUGGESTIONS, value).apply()

    /** Bật/tắt tự căn khung + tự chụp. */
    var autoCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPTURE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPTURE, value).apply()

    /** Độ trễ sau khi khóa khung (mặc định 1 giây). */
    var captureDelayMs: Long
        get() = prefs.getLong(KEY_CAPTURE_DELAY, DEFAULT_CAPTURE_DELAY_MS)
            .coerceIn(MIN_CAPTURE_DELAY_MS, MAX_CAPTURE_DELAY_MS)
        set(value) = prefs.edit()
            .putLong(KEY_CAPTURE_DELAY, value.coerceIn(MIN_CAPTURE_DELAY_MS, MAX_CAPTURE_DELAY_MS))
            .apply()

    /** Đã xem onboarding lần đầu mở app chưa (mặc định false). */
    var onboardingSeen: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_SEEN, value).apply()

    companion object {
        const val DEFAULT_CAPTURE_DELAY_MS = 1_000L
        const val MIN_CAPTURE_DELAY_MS = 0L
        const val MAX_CAPTURE_DELAY_MS = 3_000L

        private const val PREFS_NAME = "aicomposer_settings"
        private const val KEY_POSE_SUGGESTIONS = "pose_suggestions_enabled"
        private const val KEY_AUTO_CAPTURE = "auto_capture_enabled"
        private const val KEY_CAPTURE_DELAY = "capture_delay_ms"
        private const val KEY_ONBOARDING_SEEN = "onboarding_seen"
    }
}
