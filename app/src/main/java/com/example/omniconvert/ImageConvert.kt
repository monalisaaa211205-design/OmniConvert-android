package com.example.omniconvert

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

            // 1. Decode incoming image stream into an in-memory Bitmap
            val bitmap = contentResolver.openInputStream(inputUri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            } ?: throw Exception("Failed to open or decode selected image.")

            // 2. Prepare metadata for MediaStore (saving to public Pictures folder)
            val filename = "converted_${System.currentTimeMillis()}.${targetFormat.extension}"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, targetFormat.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LocalConvert")
            }

            // 3. Insert record into MediaStore
            val outputUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Failed to create file entry in MediaStore.")

            // 4. Compress/transcode bitmap bytes directly into the output stream
            contentResolver.openOutputStream(outputUri)?.use { outputStream: OutputStream ->
                val success = bitmap.compress(targetFormat.compressFormat, quality, outputStream)
                if (!success) throw Exception("Bitmap compression failed.")
            } ?: throw Exception("Failed to open output stream.")

            // Free bitmap memory immediately
            bitmap.recycle()

            outputUri
        }
    }
}