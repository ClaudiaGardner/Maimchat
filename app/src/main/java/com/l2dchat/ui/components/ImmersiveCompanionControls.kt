/*
 * Hallmark · pre-emit critique: P5 H5 E4 S5 R5 V5
 * Atmospheric-minimal companion stage · contrast pass · tokenized controls
 */
package com.l2dchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val CompanionGlass = Color(0xD9121418)
private val CompanionGlassQuiet = Color(0xA6121418)
private val CompanionInk = Color(0xFFF4F5F7)
private val CompanionInkMuted = Color(0xFFB8BDC7)
private val CompanionAccent = Color(0xFFDCE7FF)
private val CompanionAccentInk = Color(0xFF182033)
private val CompanionDanger = Color(0xFFFFB4AB)
private val CompanionRule = Color(0x33FFFFFF)
private val CompanionOnline = Color(0xFF64D990)
private val CompanionOffline = Color(0xFFFFB4AB)

enum class CompanionMode {
    CONVERSATION,
    VIDEO
}

@Composable
fun ImmersivePresenceControls(
        modelName: String,
        connectionText: String,
        connected: Boolean,
        overflowExpanded: Boolean,
        onOverflowExpandedChange: (Boolean) -> Unit,
        modifier: Modifier = Modifier,
        overflowContent: @Composable ColumnScope.() -> Unit
) {
    Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top
    ) {
        Surface(
                shape = RoundedCornerShape(20.dp),
                color = CompanionGlassQuiet,
                contentColor = CompanionInk
        ) {
            Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                        modifier =
                                Modifier.size(8.dp)
                                        .background(
                                                if (connected) CompanionOnline
                                                else CompanionOffline,
                                                CircleShape
                                        )
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                            text = modelName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                    )
                    Text(
                            text = connectionText,
                            style = MaterialTheme.typography.labelSmall,
                            color = CompanionInkMuted
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))
        Box {
            Surface(shape = CircleShape, color = CompanionGlassQuiet, contentColor = CompanionInk) {
                IconButton(
                        onClick = { onOverflowExpandedChange(true) },
                        modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多设置")
                }
            }
            DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { onOverflowExpandedChange(false) },
                    content = overflowContent
            )
        }
    }
}

@Composable
fun ImmersiveCompanionControls(
        mode: CompanionMode,
        statusText: String,
        microphoneMuted: Boolean,
        speakerEnabled: Boolean,
        onConversationMode: () -> Unit,
        onVideoMode: () -> Unit,
        onMicrophoneToggle: () -> Unit,
        onSpeakerToggle: () -> Unit,
        modifier: Modifier = Modifier
) {
    Column(
            modifier = modifier.navigationBarsPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
                shape = RoundedCornerShape(12.dp),
                color = CompanionGlassQuiet,
                contentColor = CompanionInkMuted
        ) {
            Text(
            text = statusText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
                shape = RoundedCornerShape(32.dp),
                color = CompanionGlass,
                contentColor = CompanionInk,
                shadowElevation = 8.dp
        ) {
            Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                ModeButton(
                        selected = mode == CompanionMode.CONVERSATION,
                        label = "对话",
                        icon = { Icon(Icons.Default.ChatBubble, contentDescription = null) },
                        onClick = onConversationMode
                )
                Spacer(Modifier.width(4.dp))
                ModeButton(
                        selected = mode == CompanionMode.VIDEO,
                        label = "视频",
                        icon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                        onClick = onVideoMode
                )
                Spacer(
                        Modifier.padding(horizontal = 8.dp)
                                .width(1.dp)
                                .height(32.dp)
                                .background(CompanionRule)
                )
                AudioButton(
                        selected = microphoneMuted,
                        selectedColor = CompanionDanger,
                        onClick = onMicrophoneToggle
                ) {
                    Icon(
                            if (microphoneMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (microphoneMuted) "开启麦克风" else "关闭麦克风"
                    )
                }
                AudioButton(
                        selected = !speakerEnabled,
                        selectedColor = CompanionDanger,
                        onClick = onSpeakerToggle
                ) {
                    Icon(
                            if (speakerEnabled) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = if (speakerEnabled) "关闭扬声器" else "开启扬声器"
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeButton(
        selected: Boolean,
        label: String,
        icon: @Composable () -> Unit,
        onClick: () -> Unit
) {
    Surface(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            color = if (selected) CompanionAccent else Color.Transparent,
            contentColor = if (selected) CompanionAccentInk else CompanionInkMuted
    ) {
        Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
            Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
            )
        }
    }
}

@Composable
private fun AudioButton(
        selected: Boolean,
        selectedColor: Color,
        onClick: () -> Unit,
        content: @Composable () -> Unit
) {
    Surface(
            shape = CircleShape,
            color = if (selected) selectedColor.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (selected) selectedColor else CompanionInk,
            onClick = onClick
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { content() }
    }
}
