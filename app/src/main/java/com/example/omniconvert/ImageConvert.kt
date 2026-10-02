package com.example.omniconvert

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

enum class TargetFormat(val extension: String, val mimeType: String, val compressFormat: Bitmap.CompressFormat) {
    JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
    PNG("png", "image/png", Bitmap.CompressFormat.PNG),
    WEBP("webp", "image/webp",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
    )
}

object ImageConverter {

    suspend fun convertAndSaveImage(
        context: Context,
        inputUri: Uri,
        targetFormat: TargetFormat,
        quality: Int = 90
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val contentResolver = context.contentResolver

            // 1. Decode and correct rotation using EXIF
            val bitmap = decodeAndCorrectOrientation(context, inputUri)
                ?: throw Exception("Failed to decode image.")

            // 2. Prepare metadata for MediaStore
            val filename = "converted_${System.currentTimeMillis()}.${targetFormat.extension}"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, targetFormat.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LocalConvert")
            }

            // 3. Insert record into MediaStore
            val outputUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Failed to create file entry in MediaStore.")

            // 4. Compress directly into the output stream
            contentResolver.openOutputStream(outputUri)?.use { outputStream: OutputStream ->
                val success = bitmap.compress(targetFormat.compressFormat, quality, outputStream)
                if (!success) throw Exception("Bitmap compression failed.")
            } ?: throw Exception("Failed to open output stream.")

            bitmap.recycle()
            outputUri
        }
    }

    suspend fun convertMultipleImages(
        context: Context,
        uris: List<Uri>,
        targetFormat: TargetFormat,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<Result<Uri>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Result<Uri>>()
        val total = uris.size

        uris.forEachIndexed { index, uri ->
            val result = convertAndSaveImage(context, uri, targetFormat)
            results.add(result)
            onProgress(index + 1, total)
        }

        results
    }

    private fun decodeAndCorrectOrientation(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        // 1. Read EXIF orientation
        val orientation = resolver.openInputStream(uri)?.use { stream: InputStream ->
            val exif = ExifInterface(stream)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        // 2. Decode raw bitmap
        val rawBitmap = resolver.openInputStream(uri)?.use { stream: InputStream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null

        // 3. Calculate rotation degrees
        val rotationAngle = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        // If no rotation needed, return original
        if (rotationAngle == 0f) return rawBitmap

        // 4. Apply transformation matrix
        val matrix = Matrix().apply { postRotate(rotationAngle) }
        val rotatedBitmap = Bitmap.createBitmap(
            rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
        )

        // Recycle the unrotated intermediate bitmap to conserve memory
        rawBitmap.recycle()
        return rotatedBitmap
    }
}