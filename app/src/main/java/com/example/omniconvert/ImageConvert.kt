package com.example.omniconvert

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

enum class TargetFormat(
    val extension: String, 
    val mimeType: String, 
    val compressFormat: Bitmap.CompressFormat?
) {
    JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
    PNG("png", "image/png", Bitmap.CompressFormat.PNG),
    WEBP("webp", "image/webp",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
    ),
    PDF("pdf", "application/pdf", null)
}

object ImageConverter {

    suspend fun compileImagesToPdf(
    context: Context,
    uris: List<Uri>,
    onProgress: (current: Int, total: Int) -> Unit
): Result<Uri> = withContext(Dispatchers.IO) {
    runCatching {
        if (uris.isEmpty()) throw IllegalArgumentException("No images selected.")

        val pdfDocument = PdfDocument()
        val total = uris.size

        try {
            uris.forEachIndexed { index, uri ->
                val bitmap = decodeAndCorrectOrientation(context, uri)
                    ?: throw Exception("Failed to decode image at index $index")

                // Fit bitmap proportionally to standard A4 page dimensions (595 x 842 pt)
                val targetWidth = 595
                val targetHeight = 842
                val pageInfo = PdfDocument.PageInfo.Builder(targetWidth, targetHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val scale = minOf(
                    targetWidth.toFloat() / bitmap.width.toFloat(),
                    targetHeight.toFloat() / bitmap.height.toFloat()
                )
                val scaledWidth = bitmap.width * scale
                val scaledHeight = bitmap.height * scale
                val dx = (targetWidth - scaledWidth) / 2f
                val dy = (targetHeight - scaledHeight) / 2f

                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(dx, dy)
                }

                page.canvas.drawBitmap(bitmap, matrix, null)
                pdfDocument.finishPage(page)
                bitmap.recycle()

                onProgress(index + 1, total)
            }

            val filename = "compiled_${System.currentTimeMillis()}.pdf"
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniConvert")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val outputUri = context.contentResolver.insert(collectionUri, values)
                ?: throw Exception("Failed to create PDF entry in MediaStore.")

            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                pdfDocument.writeTo(outputStream)
            } ?: throw Exception("Failed to open PDF output stream.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(outputUri, values, null, null)
            }

            outputUri
        } finally {
            pdfDocument.close()
        }
    }
}
    

    suspend fun convertAndSaveImage(
        context: Context,
        inputUri: Uri,
        targetFormat: TargetFormat,
        quality: Int = 90
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val contentResolver = context.contentResolver
            val bitmap = decodeAndCorrectOrientation(context, inputUri)
                ?: throw Exception("Failed to decode image.")

            val filename = "converted_${System.currentTimeMillis()}.${targetFormat.extension}"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, targetFormat.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OmniConvert")
            }

            val outputUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Failed to create file entry in MediaStore.")

            val compressFormat = when (targetFormat) {
                TargetFormat.PNG -> Bitmap.CompressFormat.PNG
                TargetFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }

            contentResolver.openOutputStream(outputUri)?.use { outputStream: OutputStream ->
                val success = bitmap.compress(compressFormat, quality, outputStream)
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

        val orientation = resolver.openInputStream(uri)?.use { stream: InputStream ->
            val exif = ExifInterface(stream)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val rawBitmap = resolver.openInputStream(uri)?.use { stream: InputStream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null

        val rotationAngle = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (rotationAngle == 0f) return rawBitmap

        val matrix = Matrix().apply { postRotate(rotationAngle) }
        val rotatedBitmap = Bitmap.createBitmap(
            rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
        )
        rawBitmap.recycle()
        return rotatedBitmap
    }
}