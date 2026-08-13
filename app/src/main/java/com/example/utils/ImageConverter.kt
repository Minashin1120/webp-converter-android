package com.example.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.model.ConversionConfig
import com.example.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ImageConverter {
    private const val TAG = "ImageConverter"

    suspend fun inspectImage(context: Context, uri: Uri): ImageItem = withContext(Dispatchers.IO) {
        var displayName = "image_${System.currentTimeMillis()}"
        var sizeBytes = 0L
        var mimeType = context.contentResolver.getType(uri) ?: "image/*"

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) displayName = name
                    }
                    if (sizeIndex != -1) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query content resolver for uri: $uri", e)
        }

        if (sizeBytes <= 0) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    sizeBytes = stream.available().toLong()
                }
            } catch (_: Exception) {}
        }

        // Extract dimensions
        var width = 0
        var height = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(stream, null, options)
                width = options.outWidth
                height = options.outHeight
                if (options.outMimeType != null) {
                    mimeType = options.outMimeType
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode image bounds for $uri", e)
        }

        val exifSummary = ExifHelper.extractSummary(context, uri)

        ImageItem(
            uri = uri,
            displayName = displayName,
            originalSizeBytes = sizeBytes,
            mimeType = mimeType,
            width = width,
            height = height,
            exifSummary = exifSummary
        )
    }

    suspend fun convertToWebP(
        context: Context,
        item: ImageItem,
        config: ConversionConfig,
        onProgress: (Float) -> Unit = {}
    ): Result<Pair<File, Long>> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            onProgress(0.15f)

            val rotationDegrees = getOrientationDegrees(context, item.uri)
            val bitmap = decodeBitmap(context, item.uri, config.scalePercent, rotationDegrees)
                ?: return@withContext Result.failure(Exception("Failed to decode image bitmap"))

            onProgress(0.55f)

            // Setup destination file in app cache
            val outputDir = File(context.cacheDir, "converted_webp").apply { mkdirs() }
            val baseName = item.displayName.substringBeforeLast(".")
            val destFileName = "${baseName}_${System.currentTimeMillis()}.webp"
            val destFile = File(outputDir, destFileName)

            val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (config.isLossless) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    Bitmap.CompressFormat.WEBP_LOSSY
                }
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            val quality = if (config.isLossless) 100 else config.qualityPercent.coerceIn(1, 100)

            FileOutputStream(destFile).use { outStream ->
                val compressed = bitmap.compress(compressFormat, quality, outStream)
                if (!compressed) {
                    bitmap.recycle()
                    return@withContext Result.failure(Exception("WebP compression failed"))
                }
            }
            bitmap.recycle()

            onProgress(0.85f)

            if (config.preserveExif) {
                ExifHelper.copyExif(context, item.uri, destFile, normalizedOrientation = true)
            }

            onProgress(1.0f)
            val duration = System.currentTimeMillis() - startTime
            Result.success(Pair(destFile, duration))
        } catch (e: Exception) {
            Log.e(TAG, "Conversion error for ${item.displayName}", e)
            Result.failure(e)
        }
    }

    private fun decodeBitmap(context: Context, uri: Uri, scalePercent: Int, rotationDegrees: Float): Bitmap? {
        var inputStream: InputStream? = null
        return try {
            // First decode bounds for memory safety
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            inputStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream, null, boundsOptions)
            inputStream?.close()

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight

            if (origWidth <= 0 || origHeight <= 0) return null

            // Subsampling if image is massive (e.g. > 48MP)
            var sampleSize = 1
            val maxDimension = 8192
            while ((origWidth / sampleSize) > maxDimension || (origHeight / sampleSize) > maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            inputStream = context.contentResolver.openInputStream(uri)
            val rawBitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions) ?: return null
            inputStream?.close()

            // Handle rotation or scaling if necessary
            val needRotation = rotationDegrees != 0f
            val needScale = scalePercent in 1..99

            if (!needRotation && !needScale) {
                return rawBitmap
            }

            val matrix = Matrix()
            if (needRotation) {
                matrix.postRotate(rotationDegrees)
            }
            if (needScale) {
                val factor = scalePercent / 100f
                matrix.postScale(factor, factor)
            }

            val transformed = Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                matrix,
                true
            )

            if (transformed != rawBitmap) {
                rawBitmap.recycle()
            }
            transformed
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap from $uri", e)
            null
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
        }
    }

    private fun getOrientationDegrees(context: Context, uri: Uri): Float {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
    }

    suspend fun saveToGallery(context: Context, webpFile: File): Uri? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, webpFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/webp")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WebPConverter")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val newUri = resolver.insert(collection, values) ?: return@withContext null

            resolver.openOutputStream(newUri)?.use { outStream ->
                webpFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(newUri, values, null, null)
            }

            newUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save WebP to gallery", e)
            null
        }
    }

    fun createShareIntent(context: Context, files: List<File>): Intent {
        val uris = files.map { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/webp"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/webp"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
