package com.l2dchat

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.l2dchat.chat.service.ChatServiceClient
import com.l2dchat.live2d.ImprovedLive2DRenderer
import com.l2dchat.live2d.Live2DModelManager
import com.l2dchat.preferences.ChatPreferenceKeys
import com.l2dchat.ui.screens.ChatWithModelScreen
import com.l2dchat.ui.screens.ModelSelectionDialog
import com.l2dchat.ui.theme.L2DChatTheme
import com.l2dchat.update.AppUpdateManager
import com.l2dchat.update.AvailableAppUpdate
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        enterImmersiveMode()
        ImprovedLive2DRenderer.ensureFrameworkInitialized()
        val debugOpenVideoCall =
                BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_OPEN_VIDEO_CALL, false)
        setContent { L2DChatTheme { Live2DChatApp(debugOpenVideoCall = debugOpenVideoCall) } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        ImprovedLive2DRenderer.safeShutdownFramework()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_DEBUG_OPEN_VIDEO_CALL = "debug_open_video_call"
    }
}

@Composable
fun Live2DChatApp(debugOpenVideoCall: Boolean = false) {
    var currentScreen by remember { mutableStateOf(ChatAppScreen.ModelChat) }
    var selectedModel by remember { mutableStateOf<Live2DModelManager.ModelInfo?>(null) }
    var showModelSelection by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val chatManager = remember { ChatServiceClient(context.applicationContext) }
    val updateManager = remember(context) { AppUpdateManager(context.applicationContext) }
    val updateScope = rememberCoroutineScope()
    var availableUpdate by remember { mutableStateOf<AvailableAppUpdate?>(null) }
    var downloadedUpdate by remember { mutableStateOf<File?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableIntStateOf(0) }
    DisposableEffect(chatManager) {
        chatManager.bindService()
        onDispose { chatManager.release() }
    }
    var modelKey by remember { mutableStateOf(0) }
    val prefs =
            remember(context) {
                context.getSharedPreferences(
                        ChatPreferenceKeys.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE
                )
            }
    val persistModelSelection =
            remember(prefs) {
                { model: Live2DModelManager.ModelInfo? ->
                    prefs.edit()
                            .also { editor ->
                                if (model != null) {
                                    editor.putString(
                                            ChatPreferenceKeys.SELECTED_MODEL_FOLDER,
                                            model.folderPath
                                    )
                                } else {
                                    editor.remove(ChatPreferenceKeys.SELECTED_MODEL_FOLDER)
                                }
                            }
                            .apply()
                }
            }

    val checkForUpdates: (Boolean) -> Unit = { initiatedByUser ->
        if (!isCheckingUpdate) {
            updateScope.launch {
                isCheckingUpdate = true
                val manifestUrl =
                        AppUpdateManager.resolveManifestUrl(
                                prefs.getString("update_manifest_url", null),
                                prefs.getString("last_url", null)
                        )
                if (manifestUrl == null) {
                    if (initiatedByUser) {
                        Toast.makeText(context, "请先配置服务器地址", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runCatching {
                                val authToken = prefs.getString("auth_token", null)
                                val appUpdate =
                                        updateManager.checkForUpdate(manifestUrl, authToken)
                                val installedResources =
                                        updateManager.syncResourcePacks(manifestUrl, authToken)
                                appUpdate to installedResources
                            }
                            .onSuccess { (update, installedResources) ->
                                availableUpdate = update
                                downloadedUpdate = null
                                if (installedResources.isNotEmpty()) {
                                    Toast.makeText(
                                                    context,
                                                    "Live2D 资源已热更新，正在重新加载",
                                                    Toast.LENGTH_SHORT
                                            )
                                            .show()
                                    (context as? Activity)?.recreate()
                                    return@onSuccess
                                }
                                if (initiatedByUser && update == null) {
                                    Toast.makeText(context, "已经是最新版本", Toast.LENGTH_SHORT)
                                            .show()
                                }
                            }
                            .onFailure { error ->
                                if (initiatedByUser) {
                                    Toast.makeText(
                                                    context,
                                                    "检查更新失败：${error.message}",
                                                    Toast.LENGTH_LONG
                                            )
                                            .show()
                                }
                            }
                }
                isCheckingUpdate = false
            }
        }
    }

    // 自动连接逻辑：读取偏好并在首次组合时尝试连接
    LaunchedEffect(Unit) {
        chatManager.ensureBound()
        val lastUrl = prefs.getString("last_url", null)
        val nickname = prefs.getString("nickname", null)
        val authToken = prefs.getString("auth_token", null)
        val platform = prefs.getString("platform", null)
        val recvId = prefs.getString("receiver_user_id", null)
        val recvNick = prefs.getString("receiver_user_nickname", null)
        if (!recvId.isNullOrBlank() || !recvNick.isNullOrBlank()) {
            chatManager.setReceiverInfo(recvId, recvNick)
        }
        if (!nickname.isNullOrBlank()) {
            chatManager.setUserProfile(nickname)
        }
        if (!lastUrl.isNullOrBlank()) {
            chatManager.connect(lastUrl, platform, authToken)
        }
        if (selectedModel == null) {
            val savedModelFolder = prefs.getString(ChatPreferenceKeys.SELECTED_MODEL_FOLDER, null)
            if (!savedModelFolder.isNullOrBlank()) {
                val models = Live2DModelManager.scanModels(context)
                val matched = models.firstOrNull { it.folderPath == savedModelFolder }
                if (matched != null) {
                    selectedModel = matched
                    modelKey++
                }
            }
        }
        delay(5_000)
        checkForUpdates(false)
    }
    when (currentScreen) {
        ChatAppScreen.ModelChat ->
                ChatWithModelScreen(
                        selectedModel = selectedModel,
                        chatManager = chatManager,
                        modelKey = modelKey,
                        onModelSelectionRequest = { showModelSelection = true },
                        onModelChanged = { newModel ->
                            selectedModel = newModel
                            persistModelSelection(newModel)
                            modelKey++
                        },
                        onCheckForUpdates = { checkForUpdates(true) },
                        debugOpenVideoCall = debugOpenVideoCall
                )
    }
    if (showModelSelection) {
        ModelSelectionDialog(
                currentModel = selectedModel,
                onModelSelected = { m ->
                    selectedModel = m
                    persistModelSelection(m)
                    modelKey++
                    showModelSelection = false
                },
                onDismiss = { showModelSelection = false }
        )
    }
    availableUpdate?.let { update ->
        AlertDialog(
                onDismissRequest = {
                    if (!update.mandatory && !isDownloadingUpdate) availableUpdate = null
                },
                title = { Text("发现 Maimchat ${update.versionName}") },
                text = {
                    Column {
                        Text(update.notes?.takeIf { it.isNotBlank() } ?: "有新的应用版本可安装。")
                        if (isDownloadingUpdate) {
                            Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                            LinearProgressIndicator(progress = { updateProgress / 100f })
                            Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                            Text("正在下载并校验：$updateProgress%")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                            enabled = !isDownloadingUpdate,
                            onClick = {
                                val readyApk = downloadedUpdate
                                if (readyApk != null) {
                                    val opened = updateManager.requestInstall(readyApk)
                                    if (opened) availableUpdate = null
                                    else
                                            Toast.makeText(
                                                            context,
                                                            "请允许安装未知应用，返回后再次点击安装",
                                                            Toast.LENGTH_LONG
                                                    )
                                                    .show()
                                } else {
                                    updateScope.launch {
                                        isDownloadingUpdate = true
                                        updateProgress = 0
                                        runCatching {
                                                    updateManager.download(
                                                            update,
                                                            prefs.getString("auth_token", null)
                                                    ) { progress -> updateProgress = progress }
                                                }
                                                .onSuccess { apk ->
                                                    downloadedUpdate = apk
                                                    val opened = updateManager.requestInstall(apk)
                                                    if (opened) availableUpdate = null
                                                }
                                                .onFailure { error ->
                                                    Toast.makeText(
                                                                    context,
                                                                    "更新失败：${error.message}",
                                                                    Toast.LENGTH_LONG
                                                            )
                                                            .show()
                                                }
                                        isDownloadingUpdate = false
                                    }
                                }
                            }
                    ) { Text(if (downloadedUpdate == null) "下载并安装" else "安装") }
                },
                dismissButton = {
                    if (!update.mandatory) {
                        TextButton(
                                enabled = !isDownloadingUpdate,
                                onClick = { availableUpdate = null }
                        ) { Text("稍后") }
                    }
                }
        )
    }
}

enum class ChatAppScreen {
    ModelChat
}

@Preview
@Composable
fun PreviewApp() {
    L2DChatTheme { Live2DChatApp() }
}
