package com.l2dchat.media

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the persistent CameraX session used by the in-app video-call UI.
 *
 * The preview stays local. A JPEG is produced only when the current voice turn finishes, so video
 * is never uploaded continuously.
 */
class DeviceVideoCallCameraController(
        context: Context,
        private val lifecycleOwner: LifecycleOwner
) {
    private val appContext = context.applicationContext
    val previewController: LifecycleCameraController =
            LifecycleCameraController(appContext).apply {
                setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF
            }

    private val capturing = AtomicBoolean(false)
    private var boundFrontCamera: Boolean? = null

    val isBound: Boolean
        get() = boundFrontCamera != null

    fun start(useFrontCamera: Boolean): Result<Unit> =
            runCatching {
                Log.d(TAG, "start(front=$useFrontCamera, currentlyBound=$boundFrontCamera)")
                check(hasCamera(useFrontCamera)) {
                    if (useFrontCamera) "设备没有前置摄像头" else "设备没有后置摄像头"
                }
                if (boundFrontCamera == useFrontCamera) return@runCatching
                previewController.unbind()
                previewController.cameraSelector =
                        if (useFrontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                previewController.bindToLifecycle(lifecycleOwner)
                boundFrontCamera = useFrontCamera
            }.onFailure {
                previewController.unbind()
                boundFrontCamera = null
            }

    fun capture(onCaptured: (File) -> Unit, onError: (String) -> Unit) {
        if (!isBound) {
            onError("通话摄像头尚未就绪")
            return
        }
        if (!capturing.compareAndSet(false, true)) {
            onError("正在获取上一帧画面")
            return
        }

        val directory = File(appContext.cacheDir, "device-media").apply { mkdirs() }
        val output = File(directory, "call_frame_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(output).build()
        previewController.takePicture(
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
                        onError(error.message ?: "无法获取通话画面")
                    }
                }
        )
    }

    fun stop() {
        Log.d(TAG, "stop(bound=$boundFrontCamera)")
        previewController.unbind()
        boundFrontCamera = null
        capturing.set(false)
    }

    private fun hasCamera(front: Boolean): Boolean =
            appContext.packageManager.hasSystemFeature(
                    if (front) {
                        PackageManager.FEATURE_CAMERA_FRONT
                    } else {
                        PackageManager.FEATURE_CAMERA
                    }
            )

    private companion object {
        const val TAG = "MaimchatCallCamera"
    }
}
