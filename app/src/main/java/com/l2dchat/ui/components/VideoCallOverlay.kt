/*
 * Hallmark · pre-emit critique: P5 H5 E4 S5 R5 V5
 * Video presence layer · content-first picture-in-picture · controls reveal on demand
 */
package com.l2dchat.ui.components

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.l2dchat.media.DeviceVideoCallCameraController
import com.l2dchat.media.DeviceVoiceActivityRecorder
import kotlinx.coroutines.delay

private val CallPreviewBackground = Color(0xFF0D1014)
private val CallChrome = Color(0xA6121418)
private val CallInk = Color(0xFFF4F5F7)

/**
 * Local video presence over the Live2D scene.
 *
 * The avatar remains the remote party. The device preview is content, so it remains visible during
 * a call; camera chrome is revealed only with the shared immersive controls.
 */
@Composable
fun VideoCallOverlay(
        cameraController: DeviceVideoCallCameraController,
        voiceState: DeviceVoiceActivityRecorder.State,
        controlsVisible: Boolean,
        microphoneMuted: Boolean,
        botSpeaking: Boolean,
        onError: (String) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val isTablet = configuration.screenWidthDp >= 600
    var useFrontCamera by remember { mutableStateOf(true) }

    LaunchedEffect(cameraController, useFrontCamera) {
        // PreviewView needs one layout pass to provide its Surface. Binding in the same commit
        // races CameraX on some Xiaomi devices and closes the session as an invalid surface.
        delay(150)
        cameraController.start(useFrontCamera).onFailure {
            onError(it.message ?: "无法启动通话摄像头")
        }
    }
    DisposableEffect(cameraController) {
        onDispose { cameraController.stop() }
    }

    val statusText =
            when {
                botSpeaking -> "正在回应"
                microphoneMuted -> "麦克风已静音"
                voiceState == DeviceVoiceActivityRecorder.State.SPEAKING -> "正在听你说话"
                voiceState == DeviceVoiceActivityRecorder.State.PAUSED -> "收音已暂停"
                else -> "正在聆听"
            }
    val previewWidth =
            when {
                isTablet && isLandscape -> 240.dp
                isTablet -> 192.dp
                isLandscape -> 208.dp
                else -> 132.dp
            }
    val previewHeight =
            when {
                isTablet && isLandscape -> 144.dp
                isTablet -> 248.dp
                isLandscape -> 124.dp
                else -> 188.dp
            }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
                modifier =
                        Modifier.align(Alignment.TopEnd)
                                .padding(
                                        top = if (isTablet) 24.dp else 20.dp,
                                        end = if (isTablet) 24.dp else 16.dp
                                )
                                .width(previewWidth)
                                .height(previewHeight)
                                .clip(RoundedCornerShape(if (isTablet) 24.dp else 20.dp))
                                .background(CallPreviewBackground),
                contentAlignment = Alignment.Center
        ) {
            AndroidView(
                    factory = { viewContext ->
                        PreviewView(viewContext).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            controller = cameraController.previewController
                        }
                    },
                    modifier = Modifier.fillMaxSize()
            )
            AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Surface(
                        modifier = Modifier.padding(8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = CallChrome,
                        contentColor = CallInk
                ) {
                    Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
            AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                IconButton(
                        onClick = { useFrontCamera = !useFrontCamera },
                        modifier =
                                Modifier.padding(8.dp)
                                        .size(40.dp)
                                        .background(CallChrome, CircleShape)
                ) {
                    Icon(
                            Icons.Default.Cameraswitch,
                            contentDescription = "切换前后摄像头",
                            tint = CallInk
                    )
                }
            }
        }
    }
}
