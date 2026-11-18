package com.gia.aicomposer.util

import android.content.Intent
import android.net.Uri

/**
 * Intent chia sẻ một ảnh trong Thư viện.
 *
 * Ảnh có thể là `content://media/...` (MediaStore) hoặc `file://...` (thư mục riêng của app trên
 * API 26-28). `FLAG_GRANT_READ_URI_PERMISSION` cho app nhận quyền đọc đúng Uri đó; với `file://`
 * cờ này không có tác dụng nhưng cũng không gây lỗi.
 */
object PhotoShare {

    fun intent(uri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
