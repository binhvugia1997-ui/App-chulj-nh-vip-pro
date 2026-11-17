package com.gia.aicomposer.integration

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.gia.aicomposer.R
import com.gia.aicomposer.settings.AppSettings

/**
 * Hộp thoại cài đặt dựng bằng code (không cần layout, không cần theme Material):
 *  - bật/tắt gợi ý dáng,
 *  - bật/tắt tự chụp khi căn chuẩn,
 *  - độ trễ chụp 0–3s (mặc định 1s).
 */
object AiSettingsDialog {

    private const val DELAY_STEP_MS = 250

    fun show(activity: ComponentActivity, settings: AppSettings, onApplied: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val switchPose = SwitchCompat(activity).apply {
            text = activity.getString(R.string.settings_pose_suggestions)
            isChecked = settings.poseSuggestionsEnabled
        }
        val switchAuto = SwitchCompat(activity).apply {
            text = activity.getString(R.string.settings_auto_capture)
            isChecked = settings.autoCaptureEnabled
        }

        val delayLabel = TextView(activity).apply {
            text = activity.getString(R.string.settings_capture_delay)
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        val delayValue = TextView(activity).apply {
            text = activity.getString(R.string.settings_delay_value, settings.captureDelayMs / 1000f)
        }
        val steps = (AppSettings.MAX_CAPTURE_DELAY_MS - AppSettings.MIN_CAPTURE_DELAY_MS) / DELAY_STEP_MS
        val slider = SeekBar(activity).apply {
            max = steps.toInt()
            progress = (settings.captureDelayMs / DELAY_STEP_MS).toInt()
                .coerceIn(0, steps.toInt())
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress * DELAY_STEP_MS / 1000f
                delayValue.text = activity.getString(R.string.settings_delay_value, seconds)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        content.addView(switchPose)
        content.addView(switchAuto)
        content.addView(delayLabel)
        content.addView(slider)
        content.addView(delayValue)

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_title)
            .setView(content)
            .setPositiveButton(R.string.settings_close) { _, _ ->
                settings.poseSuggestionsEnabled = switchPose.isChecked
                settings.autoCaptureEnabled = switchAuto.isChecked
                settings.captureDelayMs = (slider.progress * DELAY_STEP_MS).toLong()
                onApplied()
            }
            .show()
    }
}
