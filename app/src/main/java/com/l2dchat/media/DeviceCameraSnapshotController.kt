package com.l2dchat.media

import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.l2dchat.chat.DeviceRequest
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in, on-demand camera capture for MaiBot device requests.
 *
 * The camera is never bound unless the user enables remote snapshots. Each request produces one
 * JPEG and concurrent requests are rejected.
 */
class DeviceCameraSnapshotController(
        context: Context,
        private val lifecycleOwner: LifecycleOwner
) {
    private val appContext = context.applicationContext
    private val controller =
            LifecycleCameraController(appContext).apply {
                setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF
            }
    private val capturing = AtomicBoolean(false)
    private var boundFacing: DeviceRequest.CameraFacing? = null

    val isBound: Boolean
        get() = boundFacing != null

    fun start(facing: DeviceRequest.CameraFacing): Result<Unit> =
            runCatching {
                check(hasCamera(facing)) {
                    if (facing == DeviceRequest.CameraFacing.FRONT) {
                        "设备没有前置摄像头"
                    } else {
                        "设备没有后置摄像头"
                    }
                }
                if (boundFacing == facing) return@runCatching
                controller.unbind()
                controller.cameraSelector =
                        if (facing == DeviceRequest.CameraFacing.FRONT) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                controller.bindToLifecycle(lifecycleOwner)
                boundFacing = facing
            }.onFailure {
                controller.unbind()
                boundFacing = null
            }

    fun capture(
            request: DeviceRequest,
            onCaptured: (File) -> Unit,
            onError: (String) -> Unit
    ) {
        if (!capturing.compareAndSet(false, true)) {
            onError("摄像头正在处理上一条请求")
            return
        }
        start(request.camera).onFailure {
            capturing.set(false)
            onError(it.message ?: "无法启动摄像头")
            return
        }

        val directory = File(appContext.cacheDir, "device-media").apply { mkdirs() }
        val output =
                File(
                        directory,
                        "remote_camera_${request.requestId.safeFilePart()}_${System.currentTimeMillis()}.jpg"
                )
        val options = ImageCapture.OutputFileOptions.Builder(output).build()
        controller.takePicture(
                options,
                ContextCompat.getMainExecutor(appContext),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        capturing.set(false)
                        onCaptured(output)
                    }

                    override fun onError(error: ImageCaptureException) {
                        capturing.set(false)
                        output.delete()
                        onError(error.message ?: "拍照失败")
                    }
                }
        )
    }

    fun stop() {
        controller.unbind()
        boundFacing = null
        capturing.set(false)
    }

    private fun hasCamera(facing: DeviceRequest.CameraFacing): Boolean =
            appContext.packageManager.hasSystemFeature(
                    if (facing == DeviceRequest.CameraFacing.FRONT) {
                        PackageManager.FEATURE_CAMERA_FRONT
                    } else {
                        PackageManager.FEATURE_CAMERA
                    }
            )

    private fun String.safeFilePart(): String =
            replace(Regex("[^a-zA-Z0-9_-]"), "_").take(48).ifBlank { "request" }
}
