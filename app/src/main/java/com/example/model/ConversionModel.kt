package com.example.model

import android.net.Uri
import java.io.File
import java.util.UUID

sealed interface ConversionStatus {
    data object Idle : ConversionStatus
    data class Converting(val progress: Float = 0f) : ConversionStatus
    data object Success : ConversionStatus
    data class Error(val message: String) : ConversionStatus
}

data class ExifSummary(
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val dateTime: String? = null,
    val iso: String? = null,
    val exposureTime: String? = null,
    val fNumber: String? = null,
    val focalLength: String? = null,
    val orientation: Int = 0,
    val orientationDesc: String? = null,
    val hasGps: Boolean = false,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val software: String? = null,
    val rawAttributes: Map<String, String> = emptyMap()
)

data class ImageItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val displayName: String,
    val originalSizeBytes: Long,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val exifSummary: ExifSummary? = null,
    val status: ConversionStatus = ConversionStatus.Idle,
    val convertedFile: File? = null,
    val convertedSizeBytes: Long? = null,
    val convertedQualityUsed: Int? = null,
    val convertedLosslessUsed: Boolean? = null,
    val convertedExifPreserved: Boolean? = null,
    val conversionDurationMs: Long? = null,
    val isSavedToGallery: Boolean = false
) {
    val savedPercentage: Int
        get() {
            if (convertedSizeBytes == null || originalSizeBytes <= 0) return 0
            val diff = originalSizeBytes - convertedSizeBytes
            val percent = (diff.toDouble() / originalSizeBytes.toDouble() * 100).toInt()
            return percent
        }

    val formattedOriginalSize: String
        get() = formatBytes(originalSizeBytes)

    val formattedConvertedSize: String
        get() = convertedSizeBytes?.let { formatBytes(it) } ?: "-"

    val formatBadge: String
        get() = when {
            mimeType.contains("png", ignoreCase = true) || displayName.endsWith(".png", ignoreCase = true) -> "PNG"
            mimeType.contains("jpeg", ignoreCase = true) || displayName.endsWith(".jpg", ignoreCase = true) || displayName.endsWith(".jpeg", ignoreCase = true) -> "JPEG"
            mimeType.contains("webp", ignoreCase = true) || displayName.endsWith(".webp", ignoreCase = true) -> "WEBP"
            mimeType.contains("heic", ignoreCase = true) || displayName.endsWith(".heic", ignoreCase = true) -> "HEIC"
            mimeType.contains("gif", ignoreCase = true) || displayName.endsWith(".gif", ignoreCase = true) -> "GIF"
            mimeType.contains("bmp", ignoreCase = true) || displayName.endsWith(".bmp", ignoreCase = true) -> "BMP"
            else -> "IMAGE"
        }
}

data class ConversionConfig(
    val isLossless: Boolean = false, // 圧縮率0% / ロスレス無劣化
    val qualityPercent: Int = 80,    // 1..100
    val preserveExif: Boolean = true,
    val scalePercent: Int = 100
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.size - 1) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${bytes} B"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
