package com.gia.aicomposer.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Một chỗ duy nhất quyết định ảnh chụp được lưu ở đâu, để cả ảnh của MainActivity (chụp tay,
 * chế độ 1:1) và ảnh của pipeline AI ([com.gia.aicomposer.integration.AiCameraAssistant]) đều
 * nằm trong [GalleryActivity] đọc được.
 *
 * - Android 10 (API 29) trở lên: ghi thẳng vào MediaStore, album `Pictures/AICameraComposer`,
 *   dùng IS_PENDING để ảnh chưa ghi xong không hiện nửa vời trong thư viện.
 * - Android 8-9 (API 26-28): MediaStore chưa có RELATIVE_PATH/IS_PENDING, ghi ra thư mục ảnh
 *   riêng của app; [GalleryActivity] quét thêm thư mục này nên ảnh vẫn xem được.
 */
object PhotoStore {

    private const val ALBUM = "AICameraComposer"
    private const val TAG = "PhotoStore"
    private const val NAME_PREFIX = "AIC_"
    private const val NAME_PATTERN = "yyyyMMdd_HHmmss_SSS"

    /** Đích ghi do [prepare] tạo ra; [options] đưa thẳng cho `ImageCapture.takePicture`. */
    class Target internal constructor(
        val options: ImageCapture.OutputFileOptions,
        internal val file: File?,
    )

    /**
     * Chuẩn bị đích ghi cho một lần chụp. Trả null nếu không tạo được thư mục dự phòng.
     * Không ném exception: lỗi MediaStore (đầy bộ nhớ, provider chết...) được log rồi
     * chuyển sang ghi file.
     */
    fun prepare(context: Context): Target? {
        // Tên file theo đúng quy ước cũ `AIC_*.jpg` (GalleryActivity lọc theo tiền tố này).
        val name = "$NAME_PREFIX${SimpleDateFormat(NAME_PATTERN, Locale.US).format(Date())}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$ALBUM")
                    // Ảnh chỉ hiện trong thư viện sau khi publish() xoá cờ này.
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val options = ImageCapture.OutputFileOptions.Builder(
                    context.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                ).build()
                return Target(options, null)
            } catch (t: Throwable) {
                Log.w(TAG, "MediaStore unavailable, fallback to app dir", t)
            }
        }
        val dir = appPictureDir(context) ?: return null
        val file = File(dir, name)
        return Target(ImageCapture.OutputFileOptions.Builder(file).build(), file)
    }

    /**
     * Gọi trong `onImageSaved`: mở khoá ảnh MediaStore và trả về Uri để mở/xem ngay.
     * Uri này dùng được cho Intent ACTION_VIEW, không giống `Uri.fromFile` (FileUriExposedException).
     */
    fun publish(
        context: Context,
        target: Target,
        results: ImageCapture.OutputFileResults,
    ): Uri {
        val savedUri = results.savedUri
        if (savedUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            try {
                context.contentResolver.update(savedUri, values, null, null)
            } catch (t: Throwable) {
                Log.w(TAG, "clear IS_PENDING failed for $savedUri", t)
            }
        }
        return savedUri ?: Uri.fromFile(target.file)
    }

    /** Gọi trong `onError`: bỏ file dở (ảnh MediaStore dở do CameraX tự dọn). */
    fun discard(target: Target) {
        target.file?.let { runCatching { it.delete() } }
    }

    /** Thư mục ảnh riêng của app (API 26-28 hoặc khi MediaStore lỗi). */
    private fun appPictureDir(context: Context): File? {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
            ?: return null
        val dir = File(base, ALBUM)
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir
    }

    /** Ảnh đã lưu ở thư mục riêng của app, mới nhất trước (dùng cho GalleryActivity). */
    fun listAppPictures(context: Context): List<Uri> {
        val dir = appPictureDir(context) ?: return emptyList()
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith(NAME_PREFIX) }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { Uri.fromFile(it) }
    }
}
