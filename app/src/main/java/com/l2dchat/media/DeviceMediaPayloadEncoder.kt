package com.l2dchat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object DeviceMediaPayloadEncoder {
    private const val MAX_SOURCE_BYTES = 32L * 1024L * 1024L
    private const val MAX_AUDIO_BYTES = 4L * 1024L * 1024L
    private const val MAX_IMAGE_EDGE = 1_600

    fun encode(context: Context, type: String, path: String): String {
        val file = validatedPrivateFile(context, path)
        return when (type) {
            "image" ->
                    Base64.encodeToString(
                            normalizeImage(file, MAX_IMAGE_EDGE, 84),
                            Base64.NO_WRAP
                    )
            "voice" -> {
                require(file.length() <= MAX_AUDIO_BYTES) { "录音超过 4 MB，请缩短录音时间" }
                Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            }
            else -> error("不支持的媒体类型：$type")
        }
    }

    fun encodeRealtimeImage(context: Context, path: String): ByteArray {
        val file = validatedPrivateFile(context, path)
        return normalizeImage(file, maxEdge = 768, jpegQuality = 72)
    }

    private fun validatedPrivateFile(context: Context, path: String): File {
        val file = File(path).canonicalFile
        val allowedRoots =
                listOf(context.cacheDir, context.filesDir).map { it.canonicalFile.path + File.separator }
        require(allowedRoots.any { file.path.startsWith(it) }) { "媒体文件不在应用私有目录" }
        require(file.isFile && file.canRead()) { "媒体文件不存在或不可读" }
        require(file.length() in 1..MAX_SOURCE_BYTES) { "媒体文件为空或超过 32 MB" }
        return file
    }

    private fun normalizeImage(
            file: File,
            maxEdge: Int,
            jpegQuality: Int
    ): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别拍摄的图片" }
        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) >
                        maxEdge * 2
        ) {
            sampleSize *= 2
        }
        var bitmap =
                requireNotNull(
                        BitmapFactory.decodeFile(
                                file.path,
                                BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        )
                ) { "图片解码失败" }

        val rotation = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        if (rotation != 0) {
            val rotated =
                    Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height,
                            Matrix().apply { postRotate(rotation.toFloat()) },
                            true
                    )
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }

        val longestEdge = max(bitmap.width, bitmap.height)
        if (longestEdge > maxEdge) {
            val ratio = maxEdge.toFloat() / longestEdge
            val scaled =
                    Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                            true
                    )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
                    "图片压缩失败"
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
