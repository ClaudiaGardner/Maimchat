package com.l2dchat.ui.components

import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import java.io.File

@Composable
fun CameraCaptureDialog(
        onCaptured: (File) -> Unit,
        onError: (String) -> Unit,
        onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val hasFrontCamera =
            remember(context) {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
            }
    val hasBackCamera =
            remember(context) {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
            }
    var useFrontCamera by remember { mutableStateOf(hasFrontCamera) }
    var isCapturing by remember { mutableStateOf(false) }
    var bindingError by remember { mutableStateOf<String?>(null) }
    val controller =
            remember(context) {
                LifecycleCameraController(context).apply {
                    setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                    imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF
                }
            }

    DisposableEffect(controller, lifecycleOwner, useFrontCamera) {
        controller.unbind()
        bindingError = null
        controller.cameraSelector =
                if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching { controller.bindToLifecycle(lifecycleOwner) }
                .onFailure { bindingError = it.message ?: "无法启动摄像头" }
        onDispose { controller.unbind() }
    }

    Dialog(
            onDismissRequest = { if (!isCapturing) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
                modifier = Modifier.fillMaxWidth(0.94f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 8.dp
        ) {
            Column {
                Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("拍照发送给 MaiBot", style = MaterialTheme.typography.titleLarge)
                    IconButton(enabled = !isCapturing, onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭相机")
                    }
                }
                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .height(
                                                if (configuration.screenWidthDp >
                                                                configuration.screenHeightDp
                                                ) {
                                                    280.dp
                                                } else {
                                                    440.dp
                                                }
                                        ),
                        contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                            factory = { viewContext ->
                                PreviewView(viewContext).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    this.controller = controller
                                }
                            },
                            modifier = Modifier.matchParentSize()
                    )
                    bindingError?.let {
                        Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(24.dp)
                        )
                    }
                }
                Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                            enabled = !isCapturing && hasFrontCamera && hasBackCamera,
                            onClick = {
                                useFrontCamera = !useFrontCamera
                            }
                    ) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "切换前后摄像头")
                    }
                    Button(
                            enabled = !isCapturing && bindingError == null,
                            onClick = {
                                isCapturing = true
                                val directory =
                                        File(context.cacheDir, "device-media").apply { mkdirs() }
                                val output =
                                        File(
                                                directory,
                                                "camera_${System.currentTimeMillis()}.jpg"
                                        )
                                val options = ImageCapture.OutputFileOptions.Builder(output).build()
                                controller.takePicture(
                                        options,
                                        ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(
                                                    result: ImageCapture.OutputFileResults
                                            ) {
                                                isCapturing = false
                                                onCaptured(output)
                                            }

                                            override fun onError(error: ImageCaptureException) {
                                                isCapturing = false
                                                output.delete()
                                                onError(error.message ?: "拍照失败")
                                            }
                                        }
                                )
                            }
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Text(if (isCapturing) "处理中…" else "拍照并发送")
                    }
                }
            }
        }
    }
}
