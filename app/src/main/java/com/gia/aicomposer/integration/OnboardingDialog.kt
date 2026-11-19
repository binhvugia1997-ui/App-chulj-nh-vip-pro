package com.gia.aicomposer.integration

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.gia.aicomposer.R
import com.gia.aicomposer.settings.AppSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Onboarding lần đầu mở app: 3 màn ngắn (gợi ý dáng → căn khung → tự chụp), có nút “Bỏ qua”.
 *
 * Dùng chung [MaterialAlertDialogBuilder] với [AiSettingsDialog] và [CaptureReviewDialog] nên nền,
 * bo góc và chữ do theme Material 3 vẽ, không tự dựng `Dialog` + card riêng.
 * Cờ [AppSettings.onboardingSeen] được bật khi đóng nên chỉ hiện đúng một lần.
 */
object OnboardingDialog {

    private data class Page(
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val body: Int,
    )

    private val PAGES = listOf(
        Page(R.drawable.ic_pose, R.string.onboarding_title_1, R.string.onboarding_body_1),
        Page(R.drawable.ic_aspect_ratio, R.string.onboarding_title_2, R.string.onboarding_body_2),
        Page(R.drawable.ic_auto_awesome, R.string.onboarding_title_3, R.string.onboarding_body_3),
    )

    /** Hiện onboarding nếu đây là lần mở app đầu tiên. */
    fun showIfFirstRun(activity: ComponentActivity, settings: AppSettings) {
        if (settings.onboardingSeen) return
        show(activity, settings)
    }

    fun show(activity: ComponentActivity, settings: AppSettings) {
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_onboarding, null, false)

        val icon = content.findViewById<ImageView>(R.id.onboarding_icon)
        val step = content.findViewById<TextView>(R.id.onboarding_step)
        val title = content.findViewById<TextView>(R.id.onboarding_title)
        val body = content.findViewById<TextView>(R.id.onboarding_body)
        val dots = listOf(
            content.findViewById<View>(R.id.onboarding_dot_1),
            content.findViewById<View>(R.id.onboarding_dot_2),
            content.findViewById<View>(R.id.onboarding_dot_3),
        )
        val skip = content.findViewById<Button>(R.id.onboarding_skip)
        val next = content.findViewById<Button>(R.id.onboarding_next)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(content)
            .setCancelable(false)
            .create()

        var index = 0

        fun render() {
            val page = PAGES[index]
            icon.setImageResource(page.icon)
            icon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.accent),
            )
            step.text = activity.getString(R.string.onboarding_step, index + 1, PAGES.size)
            title.setText(page.title)
            body.setText(page.body)
            next.setText(
                if (index == PAGES.lastIndex) R.string.onboarding_start else R.string.onboarding_next,
            )

            val activeColor = ContextCompat.getColor(activity, R.color.accent)
            val inactiveColor = ContextCompat.getColor(activity, R.color.dot_inactive)
            dots.forEachIndexed { dotIndex, dot ->
                val active = dotIndex == index
                dot.backgroundTintList = ColorStateList.valueOf(
                    if (active) activeColor else inactiveColor,
                )
                dot.layoutParams = dot.layoutParams.also { params ->
                    params.width = dp(activity, if (active) 18 else 8)
                    params.height = dp(activity, 8)
                }
                dot.requestLayout()
            }
        }

        fun close() {
            settings.onboardingSeen = true
            dialog.dismiss()
        }

        skip.setOnClickListener { close() }
        next.setOnClickListener {
            if (index == PAGES.lastIndex) {
                close()
            } else {
                index += 1
                render()
            }
        }

        dialog.setOnDismissListener { settings.onboardingSeen = true }
        render()
        dialog.show()
    }

    private fun dp(activity: ComponentActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
