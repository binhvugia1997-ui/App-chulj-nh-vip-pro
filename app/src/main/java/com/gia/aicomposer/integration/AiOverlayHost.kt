package com.gia.aicomposer.integration

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView

/**
 * Overlay phải nằm cùng một khung với PreviewView để toạ độ 0..1 khớp đúng vùng ảnh.
 *
 * Nếu layout của bạn đã có sẵn một FrameLayout bọc PreviewView (khuyến nghị), cứ truyền nó
 * vào [AiCameraAssistant]. Nếu chưa, hàm này sẽ tự bọc PreviewView bằng một FrameLayout
 * giữ nguyên LayoutParams (kể cả ConstraintLayout constraints) rồi trả khung đó về.
 */
object AiOverlayHost {

    private const val WRAPPER_TAG = "aicomposer_overlay_host"

    fun ensureContainer(previewView: PreviewView): FrameLayout {
        val currentParent = previewView.parent as? ViewGroup
        if (currentParent != null && currentParent.tag == WRAPPER_TAG && currentParent is FrameLayout) {
            return currentParent
        }

        val parent = currentParent
            ?: throw IllegalStateException(
                "PreviewView phải nằm trong layout trước khi tạo AiCameraAssistant",
            )

        val params = previewView.layoutParams
        val index = parent.indexOfChild(previewView)

        parent.removeView(previewView)

        val container = FrameLayout(previewView.context)
        container.tag = WRAPPER_TAG
        container.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        parent.addView(container, index, params)
        return container
    }
}
