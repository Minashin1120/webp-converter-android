package com.example.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.model.ExifSummary
import java.io.File
import java.io.InputStream

object ExifHelper {
    private const val TAG = "ExifHelper"

    private val EXIF_TAGS_TO_COPY = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_CUSTOM_RENDERED,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION
    )

    fun extractSummary(context: Context, uri: Uri): ExifSummary? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val rawMap = mutableMapOf<String, String>()

                for (tag in EXIF_TAGS_TO_COPY) {
                    val value = exif.getAttribute(tag)
                    if (!value.isNullOrBlank()) {
                        rawMap[tag] = value
                    }
                }

                if (rawMap.isEmpty()) {
                    return null
                }

                val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                val expTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                val fNum = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)

                val latLong = exif.latLong
                val hasGps = latLong != null

                val orientationDesc = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> "90° 回転"
                    ExifInterface.ORIENTATION_ROTATE_180 -> "180° 回転"
                    ExifInterface.ORIENTATION_ROTATE_270 -> "270° 回転"
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "水平反転"
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> "垂直反転"
                    else -> "標準"
                }

                ExifSummary(
                    cameraMake = make,
                    cameraModel = model,
                    dateTime = dateTime,
                    iso = iso,
                    exposureTime = expTime?.let { formatExposureTime(it) },
                    fNumber = fNum?.let { "f/$it" },
                    focalLength = focal?.let { "${it}mm" },
                    orientation = orientation,
                    orientationDesc = orientationDesc,
                    hasGps = hasGps,
                    gpsLatitude = latLong?.get(0),
                    gpsLongitude = latLong?.get(1),
                    software = software,
                    rawAttributes = rawMap
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF from $uri", e)
            null
        }
    }

    private fun formatExposureTime(value: String): String {
        return try {
            val d = value.toDouble()
            if (d < 1.0 && d > 0.0) {
                "1/${(1.0 / d).toInt()}s"
            } else {
                "${value}s"
            }
        } catch (e: Exception) {
            "${value}s"
        }
    }

    fun copyExif(context: Context, sourceUri: Uri, destinationFile: File, normalizedOrientation: Boolean = true): Boolean {
        return try {
            var sourceExif: ExifInterface? = null
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                sourceExif = ExifInterface(stream)
            }

            val src = sourceExif ?: return false
            val dest = ExifInterface(destinationFile.absolutePath)

            for (tag in EXIF_TAGS_TO_COPY) {
                // If we already rotated the bitmap in memory during decode, reset orientation tag to normal
                if (tag == ExifInterface.TAG_ORIENTATION && normalizedOrientation) {
                    dest.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    continue
                }
                val value = src.getAttribute(tag)
                if (value != null) {
                    dest.setAttribute(tag, value)
                }
            }

            dest.saveAttributes()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy EXIF to destination file: ${destinationFile.name}", e)
            false
        }
    }
}
