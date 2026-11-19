package com.gia.aicomposer.integration

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.gia.aicomposer.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Sheet xem lại ảnh vừa chụp: ảnh thu nhỏ + nút “Xem ảnh” / “Chụp lại”.
 *
 * Cùng kiểu Material với [AiSettingsDialog] và [OnboardingDialog]. Hộp thoại chỉ **đọc** ảnh đã
 * lưu: không thêm/bớt/đổi chỗ nào trong luồng lưu (MediaStore `Pictures/AICameraComposer`), nên
 * ảnh vẫn nằm nguyên trong Thư viện dù người dùng chọn Xem ảnh hay Chụp lại.
 */
object CaptureReviewDialog {

    /**
     * @param uri ảnh vừa lưu xong (MediaStore), dùng cho thumbnail và [Intent.ACTION_VIEW].
     * @param onRetake gọi khi người dùng bấm “Chụp lại”; host tự quyết định chụp lại thế nào.
     */
    fun show(activity: ComponentActivity, uri: Uri, onRetake: () -> Unit) {
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_capture_review, null, false)
        content.findViewById<ImageView>(R.id.review_thumbnail).setImageURI(uri)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.review_title)
            .setView(content)
            .setNegativeButton(R.string.review_retake) { _, _ -> onRetake() }
            .setPositiveButton(R.string.review_open) { _, _ -> openPhoto(activity, uri) }
            .show()
    }

    private fun openPhoto(activity: ComponentActivity, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.review_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }
}
