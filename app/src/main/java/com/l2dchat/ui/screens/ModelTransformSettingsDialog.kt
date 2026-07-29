package com.l2dchat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.l2dchat.live2d.Live2DViewTransform
import kotlin.math.roundToInt

@Composable
fun ModelTransformSettingsDialog(
        transform: Live2DViewTransform,
        orientationLabel: String,
        onTransformChange: (Live2DViewTransform) -> Unit,
        onReset: () -> Unit,
        onApply: () -> Unit,
        onDismiss: () -> Unit
) {
    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("模型位置与大小") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${orientationLabel}设置会单独保存，旋转屏幕后可分别调整。")
                    TransformSlider(
                            label = "大小 ${(transform.scale * 100).roundToInt()}%",
                            value = transform.scale,
                            valueRange =
                                    Live2DViewTransform.MIN_SCALE..Live2DViewTransform.MAX_SCALE,
                            steps = 51,
                            onValueChange = { onTransformChange(transform.copy(scale = it)) }
                    )
                    TransformSlider(
                            label = "水平位置 ${formatOffset(transform.offsetX)}",
                            value = transform.offsetX,
                            valueRange =
                                    Live2DViewTransform.MIN_OFFSET..Live2DViewTransform.MAX_OFFSET,
                            steps = 43,
                            onValueChange = { onTransformChange(transform.copy(offsetX = it)) }
                    )
                    TransformSlider(
                            label = "垂直位置 ${formatOffset(transform.offsetY)}",
                            value = transform.offsetY,
                            valueRange =
                                    Live2DViewTransform.MIN_OFFSET..Live2DViewTransform.MAX_OFFSET,
                            steps = 43,
                            onValueChange = { onTransformChange(transform.copy(offsetY = it)) }
                    )
                    Text("也可以双击模型进入调整模式：单指拖动位置、双指捏合缩放，再双击一次完成。手势调整会自动保存。")
                }
            },
            confirmButton = { TextButton(onClick = onApply) { Text("完成") } },
            dismissButton = {
                Row {
                    TextButton(onClick = onReset) { Text("恢复默认") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
    )
}

@Composable
private fun TransformSlider(
        label: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int,
        onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label)
        Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps
        )
    }
}

private fun formatOffset(value: Float): String =
        if (value >= 0f) "+%.2f".format(value) else "%.2f".format(value)
