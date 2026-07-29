package com.l2dchat.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.l2dchat.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AppUpdateManifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String? = null,
        val mandatory: Boolean = false,
        val resources: List<ResourcePackManifest>? = null
)

data class ResourcePackManifest(
        val id: String,
        val version: Int,
        val url: String,
        val sha256: String
)

data class AvailableAppUpdate(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String?,
        val mandatory: Boolean
)

/**
 * Private-distribution OTA updater.
 *
 * The manifest is fetched over HTTP(S), the APK is streamed to app-private cache, and SHA-256 is
 * verified before Android's package installer is opened. Android still enforces the package
 * signature and version rules when installing the update.
 */
class AppUpdateManager(context: Context, private val client: OkHttpClient = OkHttpClient()) {
    private val appContext = context.applicationContext
    private val gson = Gson()

    suspend fun checkForUpdate(manifestUrl: String, authToken: String?): AvailableAppUpdate? =
            withContext(Dispatchers.IO) {
                val request =
                        Request.Builder()
                                .url(manifestUrl)
                                .apply {
                                    authToken
                                            ?.trim()
                                            ?.takeIf { it.isNotEmpty() }
                                            ?.let { header("Authorization", "Bearer $it") }
                                }
                                .build()
                client.newCall(request).execute().use { response ->
                    requireSuccessful(response, "更新清单")
                    val body = response.body?.string().orEmpty()
                    val manifest = gson.fromJson(body, AppUpdateManifest::class.java)
                    require(manifest.versionCode > 0) { "更新清单缺少有效 versionCode" }
                    require(manifest.versionName.isNotBlank()) { "更新清单缺少 versionName" }
                    require(manifest.sha256.matches(Regex("[A-Fa-f0-9]{64}"))) {
                        "更新清单中的 SHA-256 无效"
                    }
                    if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                        return@withContext null
                    }
                    val base =
                            manifestUrl.toHttpUrlOrNull()
                                    ?: error("更新清单 URL 无效: $manifestUrl")
                    val resolved =
                            base.resolve(manifest.apkUrl)
                                    ?: error("APK URL 无效: ${manifest.apkUrl}")
                    AvailableAppUpdate(
                            versionCode = manifest.versionCode,
                            versionName = manifest.versionName,
                            apkUrl = resolved.toString(),
                            sha256 = manifest.sha256.lowercase(),
                            notes = manifest.notes,
                            mandatory = manifest.mandatory
                    )
                }
            }

    suspend fun download(
            update: AvailableAppUpdate,
            authToken: String?,
            onProgress: (Int) -> Unit
    ): File =
            withContext(Dispatchers.IO) {
                val request =
                        Request.Builder()
                                .url(update.apkUrl)
                                .apply {
                                    authToken
                                            ?.trim()
                                            ?.takeIf { it.isNotEmpty() }
                                            ?.let { header("Authorization", "Bearer $it") }
                                }
                                .build()
                val targetDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val partial = File(targetDir, "maimchat-${update.versionCode}.apk.part")
                val target = File(targetDir, "maimchat-${update.versionCode}.apk")

                client.newCall(request).execute().use { response ->
                    requireSuccessful(response, "APK")
                    val body = response.body ?: error("APK 响应为空")
                    val expectedBytes = body.contentLength()
                    var downloadedBytes = 0L
                    partial.outputStream().buffered().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloadedBytes += count
                                if (expectedBytes > 0) {
                                    onProgress(
                                            ((downloadedBytes * 100L) / expectedBytes)
                                                .toInt()
                                                .coerceIn(0, 100)
                                    )
                                }
                            }
                        }
                    }
                }

                val actualSha256 = sha256(partial)
                check(actualSha256.equals(update.sha256, ignoreCase = true)) {
                    partial.delete()
                    "APK 校验失败：期望 ${update.sha256}，实际 $actualSha256"
                }
                if (target.exists()) target.delete()
                check(partial.renameTo(target)) { "无法保存已校验的 APK" }
                onProgress(100)
                target
            }

    suspend fun syncResourcePacks(manifestUrl: String, authToken: String?): List<String> =
            withContext(Dispatchers.IO) {
                val manifest = fetchManifest(manifestUrl, authToken)
                val resources = manifest.resources.orEmpty()
                if (resources.isEmpty()) return@withContext emptyList()

                val versions =
                        appContext.getSharedPreferences(
                                "maimchat_resource_updates",
                                Context.MODE_PRIVATE
                        )
                val installed = mutableListOf<String>()
                for (resource in resources) {
                    require(resource.id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
                        "资源包 ID 无效: ${resource.id}"
                    }
                    require(resource.version > 0) { "资源包版本无效: ${resource.id}" }
                    require(resource.sha256.matches(Regex("[A-Fa-f0-9]{64}"))) {
                        "资源包 SHA-256 无效: ${resource.id}"
                    }
                    val currentVersion = versions.getInt("version_${resource.id}", 0)
                    if (resource.version <= currentVersion) continue

                    val base =
                            manifestUrl.toHttpUrlOrNull()
                                    ?: error("更新清单 URL 无效: $manifestUrl")
                    val resourceUrl =
                            base.resolve(resource.url)?.toString()
                                    ?: error("资源包 URL 无效: ${resource.url}")
                    val archive =
                            downloadResourceArchive(
                                    resource.id,
                                    resource.version,
                                    resourceUrl,
                                    resource.sha256,
                                    authToken
                            )
                    installResourceArchive(resource.id, archive)
                    versions.edit().putInt("version_${resource.id}", resource.version).apply()
                    installed.add(resource.id)
                }
                installed
            }

    /**
     * Returns true when the installer was opened. False means the user must first grant this app
     * permission to install packages from this source.
     */
    fun requestInstall(apk: File): Boolean {
        check(apk.isFile) { "APK 文件不存在: ${apk.absolutePath}" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !appContext.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent =
                    Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${appContext.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(settingsIntent)
            return false
        }

        val apkUri =
                FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        apk
                )
        val installIntent =
                Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apkUri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        appContext.startActivity(installIntent)
        return true
    }

    private fun requireSuccessful(response: Response, label: String) {
        check(response.isSuccessful) {
            "$label 下载失败：HTTP ${response.code} ${response.message}"
        }
    }

    private fun fetchManifest(manifestUrl: String, authToken: String?): AppUpdateManifest {
        val request =
                Request.Builder()
                        .url(manifestUrl)
                        .apply {
                            authToken
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { header("Authorization", "Bearer $it") }
                        }
                        .build()
        return client.newCall(request).execute().use { response ->
            requireSuccessful(response, "更新清单")
            gson.fromJson(response.body?.string().orEmpty(), AppUpdateManifest::class.java)
        }
    }

    private fun downloadResourceArchive(
            id: String,
            version: Int,
            url: String,
            expectedSha256: String,
            authToken: String?
    ): File {
        val request =
                Request.Builder()
                        .url(url)
                        .apply {
                            authToken
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { header("Authorization", "Bearer $it") }
                        }
                        .build()
        val downloadDir = File(appContext.cacheDir, "resource-updates").apply { mkdirs() }
        val archive = File(downloadDir, "$id-$version.zip")
        client.newCall(request).execute().use { response ->
            requireSuccessful(response, "资源包 $id")
            val body = response.body ?: error("资源包 $id 响应为空")
            archive.outputStream().buffered().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }
        val actualSha256 = sha256(archive)
        check(actualSha256.equals(expectedSha256, ignoreCase = true)) {
            archive.delete()
            "资源包 $id 校验失败"
        }
        return archive
    }

    private fun installResourceArchive(id: String, archive: File) {
        val modelRoot = File(appContext.filesDir, "live2d/models").apply { mkdirs() }
        val staging = File(modelRoot, ".$id.staging")
        val target = File(modelRoot, id)
        val backup = File(modelRoot, ".$id.backup")
        staging.deleteRecursively()
        staging.mkdirs()

        val canonicalStaging = staging.canonicalFile
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = File(staging, entry.name).canonicalFile
                check(
                        destination.path == canonicalStaging.path ||
                                destination.path.startsWith(
                                        canonicalStaging.path + File.separator
                                )
                ) {
                    "资源包包含非法路径: ${entry.name}"
                }
                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    FileOutputStream(destination).buffered().use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
            }
        }

        val modelFiles =
                staging.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".model3.json", true) }
                        .toList()
        check(modelFiles.isNotEmpty()) { "资源包 $id 中没有 model3.json" }
        val extractedModelRoot = modelFiles.first().parentFile ?: staging

        backup.deleteRecursively()
        if (target.exists()) check(target.renameTo(backup)) { "无法备份旧资源包 $id" }
        try {
            check(extractedModelRoot.renameTo(target)) { "无法安装资源包 $id" }
            backup.deleteRecursively()
            staging.deleteRecursively()
        } catch (error: Throwable) {
            target.deleteRecursively()
            if (backup.exists()) backup.renameTo(target)
            staging.deleteRecursively()
            throw error
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun resolveManifestUrl(explicitUrl: String?, webSocketUrl: String?): String? {
            explicitUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val socket = webSocketUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val httpUrl =
                    when {
                        socket.startsWith("wss://", ignoreCase = true) ->
                                "https://${socket.substring(6)}"
                        socket.startsWith("ws://", ignoreCase = true) ->
                                "http://${socket.substring(5)}"
                        else -> return null
                    }.toHttpUrlOrNull() ?: return null
            return httpUrl.newBuilder()
                    .encodedPath("/maimchat/updates/manifest.json")
                    .query(null)
                    .fragment(null)
                    .build()
                    .toString()
        }
    }
}
