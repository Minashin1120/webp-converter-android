package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Imports images sent to the app via ACTION_SEND / ACTION_SEND_MULTIPLE.
 * Shared content URIs are typically temporary, so they are copied into app cache.
 */
object SharedImageImporter {
    private const val TAG = "SharedImageImporter"
    private const val IMPORT_DIR = "shared_imports"

    fun isShareIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }

    fun extractImageUris(intent: Intent?): List<Uri> {
        if (intent == null || !isShareIntent(intent)) return emptyList()

        val collected = linkedSetOf<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { collected.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.forEach { collected.add(it) }
            }
        }

        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { collected.add(it) }
            }
        }

        if (collected.isEmpty()) {
            intent.data?.let { collected.add(it) }
        }

        return collected.filter { uri ->
            uri != Uri.EMPTY && (uri.scheme == "content" || uri.scheme == "file")
        }
    }

    suspend fun importUris(context: Context, uris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext emptyList()

        val importDir = File(context.cacheDir, IMPORT_DIR).apply { mkdirs() }
        uris.mapNotNull { source -> copyToCache(context, source, importDir) }
    }

    private fun copyToCache(context: Context, source: Uri, importDir: File): Uri? {
        return try {
            tryTakePersistableReadPermission(context, source)

            val displayName = queryDisplayName(context, source) ?: "shared_${System.currentTimeMillis()}"
            val destFile = File(importDir, "${System.currentTimeMillis()}_${sanitizeFileName(displayName)}")

            context.contentResolver.openInputStream(source)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            if (destFile.length() <= 0L) {
                destFile.delete()
                return null
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
        } catch (error: Exception) {
            Log.w(TAG, "Failed to import shared image: $source", error)
            null
        }
    }

    private fun tryTakePersistableReadPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Share grants are usually temporary; the cached copy is the durable source.
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
                ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return sanitized.ifBlank { "shared_image" }
    }
}
