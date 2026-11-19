package com.gia.aicomposer

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.gia.aicomposer.util.PhotoShare
import com.gia.aicomposer.util.PhotoStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Thư viện ảnh đã chụp: lưới thumbnail các ảnh `AIC_*` (chụp tay, chế độ 1:1 và ảnh của
 * pipeline AI), chạm để xem full màn hình, chạm lần nữa để đóng.
 *
 * Nguồn ảnh gồm 2 nơi, đúng theo [PhotoStore]:
 * - MediaStore album `Pictures/AICameraComposer` (Android 10+ và ảnh chụp tay mọi phiên bản),
 * - thư mục ảnh riêng của app (Android 8-9 hoặc khi MediaStore lỗi).
 * Danh sách được nạp lại trong [onResume] nên ảnh vừa chụp hiện ngay khi quay về.
 */
class GalleryActivity : AppCompatActivity() {

    private val photos = mutableListOf<Uri>()
    private val adapter = PhotoAdapter()

    private lateinit var grid: GridView
    private lateinit var full: ImageView
    private lateinit var empty: TextView
    private lateinit var actions: View

    /** Ảnh đang xem full màn hình; null khi không xem ảnh nào. */
    private var currentPhoto: Uri? = null

    /** Android 13+ dùng READ_MEDIA_IMAGES; Android 8-12 dùng READ_EXTERNAL_STORAGE. */
    private val readPermission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { loadPhotos() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_gallery)

        val rootView = (findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        grid = findViewById(R.id.photo_grid)
        full = findViewById(R.id.full_photo)
        empty = findViewById(R.id.empty_state)
        actions = findViewById(R.id.photo_actions)

        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ -> showFull(photos[position]) }
        full.setOnClickListener { hideFull() }
        // Các nút này là ImageButton (icon tròn) nên tìm theo View, chỉ cần gắn listener.
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.share_button).setOnClickListener { shareCurrent() }
        findViewById<View>(R.id.delete_button).setOnClickListener { confirmDeleteCurrent() }

        // Từ Android 10, app đọc được ảnh do chính nó tạo trong MediaStore mà không cần quyền.
        // Android 8-9 vẫn cần READ_EXTERNAL_STORAGE để đọc thư mục ảnh của app.
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, readPermission) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(readPermission)
        }
    }

    override fun onResume() {
        super.onResume()
        loadPhotos()
    }

    private fun loadPhotos() {
        photos.clear()
        photos += queryMediaStore()
        photos += PhotoStore.listAppPictures(this)

        adapter.notifyDataSetChanged()
        empty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun queryMediaStore(): List<Uri> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        // Lọc theo tên file AIC_* (ảnh của app này). Không dùng RELATIVE_PATH để
        // chạy được cả API 26-28.
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("AIC_%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val result = mutableListOf<Uri>()
        try {
            contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && result.size < MAX_PHOTOS) {
                    val id = cursor.getLong(idColumn)
                    result += ContentUris.withAppendedId(collection, id)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to read gallery", e)
        } catch (e: Exception) {
            Log.w(TAG, "gallery query failed", e)
        }
        return result
    }

    private fun showFull(uri: Uri) {
        val bitmap = decode(uri, MAX_FULL_SIZE)
        if (bitmap == null) {
            full.setImageURI(uri) // đường lui: ảnh lạ/định dạng khác
        } else {
            full.setImageBitmap(bitmap)
        }
        currentPhoto = uri
        full.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
    }

    /** Đóng ảnh full: ẩn cả ảnh lẫn hàng nút Chia sẻ/Xoá. */
    private fun hideFull() {
        full.visibility = View.GONE
        actions.visibility = View.GONE
        currentPhoto = null
    }

    private fun shareCurrent() {
        val uri = currentPhoto ?: return
        startActivity(Intent.createChooser(PhotoShare.intent(uri), getString(R.string.gallery_share)))
    }

    private fun confirmDeleteCurrent() {
        val uri = currentPhoto ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.gallery_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.gallery_delete) { _, _ ->
                if (PhotoStore.delete(this, uri)) {
                    hideFull()
                    loadPhotos()
                } else {
                    Toast.makeText(this, R.string.gallery_delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** Decode có lấy mẫu (tránh OOM với ảnh 12 MP) và xoay theo EXIF. */
    private fun decode(uri: Uri, maxSize: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (decoded == null) null else applyExifRotation(decoded, exifRotation(uri))
        }
    } catch (e: Exception) {
        Log.w(TAG, "decode failed for $uri", e)
        null
    }

    private fun thumbnail(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return contentResolver.loadThumbnail(uri, Size(THUMB_SIZE, THUMB_SIZE), null)
            } catch (e: Exception) {
                Log.w(TAG, "thumbnail failed for $uri", e)
            }
        }
        return decode(uri, THUMB_SIZE)
    }

    private fun exifRotation(uri: Uri): Int = try {
        contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    } catch (e: Exception) {
        0
    }

    private fun applyExifRotation(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private inner class PhotoAdapter : BaseAdapter() {
        override fun getCount(): Int = photos.size
        override fun getItem(position: Int): Any = photos[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val imageView = (convertView as? ImageView)
                ?: LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_photo, parent, false) as ImageView
            val photoUri = photos[position]
            val bitmap = thumbnail(photoUri)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageURI(photoUri)
            }
            return imageView
        }
    }

    companion object {
        private const val TAG = "GalleryActivity"
        private const val MAX_PHOTOS = 100
        private const val THUMB_SIZE = 256
        private const val MAX_FULL_SIZE = 2048
    }
}
