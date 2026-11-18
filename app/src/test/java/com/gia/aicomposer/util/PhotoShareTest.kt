package com.gia.aicomposer.util

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chia sẻ ảnh phải kèm quyền đọc; ảnh trong thư mục riêng của app phải nhận ra được để xoá. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoShareTest {

    @Test
    fun shareIntentCarriesReadGrant() {
        val intent = PhotoShare.intent(Uri.parse("content://media/external/images/media/42"))
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals(
            "42",
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.lastPathSegment,
        )
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun appDirUrisAreRecognised() {
        assertTrue(
            PhotoStore.isAppPicture(
                Uri.parse(
                    "file:///data/user/0/com.gia.aicomposer/files/Pictures/" +
                        "AICameraComposer/AIC_1.jpg",
                ),
            ),
        )
        assertFalse(PhotoStore.isAppPicture(Uri.parse("content://media/external/images/media/42")))
    }
}
