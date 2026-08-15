package com.example

import android.content.Intent
import android.net.Uri
import com.example.utils.SharedImageImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedImageImporterTest {

    @Test
    fun `ignores launcher intents`() {
        val intent = Intent(Intent.ACTION_MAIN)
        assertFalse(SharedImageImporter.isShareIntent(intent))
        assertTrue(SharedImageImporter.extractImageUris(intent).isEmpty())
    }

    @Test
    fun `extracts a single shared image uri`() {
        val uri = Uri.parse("content://media/external/images/media/12")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        assertTrue(SharedImageImporter.isShareIntent(intent))
        assertEquals(listOf(uri), SharedImageImporter.extractImageUris(intent))
    }

    @Test
    fun `extracts multiple shared image uris without duplicates`() {
        val first = Uri.parse("content://media/external/images/media/1")
        val second = Uri.parse("content://media/external/images/media/2")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
        }

        assertEquals(listOf(first, second), SharedImageImporter.extractImageUris(intent))
    }
}
