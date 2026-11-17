package com.gia.aicomposer

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/**
 * Thu vien anh da chup: luoi thumbnail cac anh ten AIC_* trong MediaStore,
 * cham vao anh de xem full man hinh, cham vao anh full de dong.
 */
class GalleryActivity : AppCompatActivity() {

    private val photos = mutableListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        loadPhotos()

        val grid = findViewById<GridView>(R.id.photo_grid)
        val full = findViewById<ImageView>(R.id.full_photo)
        grid.adapter = PhotoAdapter()
        grid.setOnItemClickListener { _, _, pos, _ ->
            full.setImageURI(photos[pos])
            full.visibility = View.VISIBLE
        }
        full.setOnClickListener { full.visibility = View.GONE }
        findViewById<Button>(R.id.back_button).setOnClickListener { finish() }
    }

    private fun loadPhotos() {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        // Loc theo ten file AIC_* (anh cua app nay). Khong dung RELATIVE_PATH
        // de chay duoc ca API 26-28.
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("AIC_%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        contentResolver.query(uri, projection, selection, args, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            var count = 0
            while (cursor.moveToNext() && count < 100) {
                val id = cursor.getLong(idCol)
                photos += android.content.ContentUris.withAppendedId(uri, id)
                count++
            }
        }
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
            val thumb = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    contentResolver.loadThumbnail(photoUri, Size(256, 256), null)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
            if (thumb != null) {
                imageView.setImageBitmap(thumb)
            } else {
                imageView.setImageURI(photoUri)
            }
            return imageView
        }
    }
}
