package com.gia.aicomposer.integration

import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.gia.aicomposer.R
import com.gia.aicomposer.settings.AppSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToLong

/**
 * Hộp thoại cài đặt kiểu Material (layout `dialog_ai_settings.xml`):
 *  - bật/tắt gợi ý dáng,
 *  - bật/tắt tự chụp khi căn chuẩn,
 *  - độ trễ chụp 0–3s (mặc định 1s).
 *
 * Giá trị mặc định giữ nguyên trong [AppSettings]: gợi ý dáng ON, tự chụp OFF, trễ 1.00s.
 * [autoCapture] cho phép màn hình chính truyền trạng thái tự chụp đang dùng trên màn hình
 * (màn chụp bật tự động mặc định), để hai chỗ không hiển thị lệch nhau.
 */
object AiSettingsDialog {

    private const val DELAY_STEP_SECONDS = 0.25f
    private val MAX_DELAY_SECONDS = AppSettings.MAX_CAPTURE_DELAY_MS / 1000f

    fun show(
        activity: ComponentActivity,
        settings: AppSettings,
        autoCapture: Boolean = settings.autoCaptureEnabled,
        onApplied: (autoCapture: Boolean) -> Unit,
    ) {
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_ai_settings, null, false)

        val switchPose = content.findViewById<MaterialSwitch>(R.id.switch_pose)
        val switchAuto = content.findViewById<MaterialSwitch>(R.id.switch_auto)
        val delayValue = content.findViewById<TextView>(R.id.delay_value)
        val slider = content.findViewById<Slider>(R.id.delay_slider)

        switchPose.isChecked = settings.poseSuggestionsEnabled
        switchAuto.isChecked = autoCapture

        slider.valueFrom = 0f
        slider.valueTo = MAX_DELAY_SECONDS
        slider.stepSize = DELAY_STEP_SECONDS
        // Giá trị lưu trong SharedPreferences có thể lệch bước 0.25s -> bắt về đúng bước.
        val steps = (settings.captureDelayMs / 1000f / DELAY_STEP_SECONDS).roundToLong()
        slider.value = (steps * DELAY_STEP_SECONDS).coerceIn(0f, MAX_DELAY_SECONDS)
        delayValue.text = activity.getString(R.string.settings_delay_value, slider.value)
        slider.addOnChangeListener { _, value, _ ->
            delayValue.text = activity.getString(R.string.settings_delay_value, value)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_title)
            .setView(content)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_close) { _, _ ->
                settings.poseSuggestionsEnabled = switchPose.isChecked
                settings.autoCaptureEnabled = switchAuto.isChecked
                settings.captureDelayMs = (slider.value * 1000f).roundToLong()
                onApplied(switchAuto.isChecked)
            }
            .show()
    }
}
