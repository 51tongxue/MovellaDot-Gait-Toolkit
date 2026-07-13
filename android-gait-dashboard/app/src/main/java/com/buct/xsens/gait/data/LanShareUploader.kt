package com.buct.xsens.gait.data

import android.content.Context
import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties

data class LanShareConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val shareName: String = "",
    val remoteDir: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val uploadSourceCsv: Boolean = true,
)

class LanShareUploader(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getConfigJson(): String =
        readConfig().let { config ->
            JSONObject()
                .put("ok", true)
                .put("config", configToJson(config, includePassword = true))
                .put("target_url", safeBaseUrl(config))
                .toString()
        }

    fun saveConfigJson(content: String): String {
        return try {
            val root = JSONObject(content)
            val config = LanShareConfig(
                enabled = root.optBoolean("enabled", false),
                host = root.optString("host").trim(),
                shareName = root.optString("share_name").trim().trim('/'),
                remoteDir = normalizeRemoteDir(root.optString("remote_dir").trim()),
                username = root.optString("username").trim(),
                password = root.optString("password"),
                domain = root.optString("domain").trim(),
                uploadSourceCsv = root.optBoolean("upload_source_csv", true),
            )
            prefs.edit()
                .putBoolean(KEY_ENABLED, config.enabled)
                .putString(KEY_HOST, config.host)
                .putString(KEY_SHARE_NAME, config.shareName)
                .putString(KEY_REMOTE_DIR, config.remoteDir)
                .putString(KEY_USERNAME, config.username)
                .putString(KEY_PASSWORD, config.password)
                .putString(KEY_DOMAIN, config.domain)
                .putBoolean(KEY_UPLOAD_SOURCE_CSV, config.uploadSourceCsv)
                .apply()
            Log.i(TAG, "Saved LAN upload config target=${safeBaseUrl(config)} enabled=${config.enabled}")
            JSONObject()
                .put("ok", true)
                .put("config", configToJson(config, includePassword = true))
                .put("target_url", safeBaseUrl(config))
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save LAN upload config", e)
            errorJson(e.message ?: "保存局域网上传配置失败")
        }
    }

    fun testConnection(): String {
        return try {
            val config = readConfig()
            validateConfig(config)
            val ctx = buildContext(config)
            val dir = ensureBaseDir(config, ctx)
            val probeName = ".imu_upload_probe_${System.currentTimeMillis()}.tmp"
            val probe = SmbFile(dir, probeName)
            val payload = "imu upload probe\n".toByteArray(Charsets.UTF_8)
            probe.outputStream.use { it.write(payload) }
            verifyRemoteFile(probe, payload.size.toLong())
            try {
                probe.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Probe file uploaded but could not be deleted: ${probe.canonicalPath}", e)
                return errorJson("连接可写入，但测试文件删除失败：${probe.canonicalPath}")
            }
            Log.i(TAG, "LAN upload test ok target=${dir.canonicalPath}")
            JSONObject()
                .put("ok", true)
                .put("remote_dir", dir.canonicalPath)
                .put("target_url", safeBaseUrl(config))
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "LAN upload test failed", e)
            errorJson(e.message ?: "局域网共享文件夹连接失败")
        }
    }

    fun uploadAnalysisFiles(manifestPath: String, sourceFilePath: String): String =
        uploadAnalysisFiles(manifestPath, listOf(sourceFilePath))

    fun uploadAnalysisFiles(manifestPath: String, sourceFilePaths: List<String>): String {
        return try {
            val config = readConfig()
            if (!config.enabled) {
                return JSONObject()
                    .put("ok", true)
                    .put("skipped", true)
                    .put("message", "未启用局域网上传")
                    .toString()
            }
            validateConfig(config)
            val files = buildList {
                add(File(manifestPath))
                if (config.uploadSourceCsv) {
                    sourceFilePaths
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { add(File(it)) }
                }
            }
                .filter { it.exists() && it.isFile }
                .distinctBy { it.absolutePath }

            if (files.isEmpty()) return errorJson("没有可上传的分析文件")

            val ctx = buildContext(config)
            val base = ensureBaseDir(config, ctx)
            Log.i(TAG, "Uploading ${files.size} analysis file(s) to ${base.canonicalPath}")

            val uploaded = JSONArray()
            files.forEach { localFile ->
                val remote = SmbFile(base, localFile.name)
                localFile.inputStream().use { input ->
                    remote.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                val localBytes = localFile.length()
                verifyRemoteFile(remote, localBytes)
                Log.i(TAG, "Uploaded and verified ${localFile.absolutePath} -> ${remote.canonicalPath} bytes=$localBytes")
                uploaded.put(
                    JSONObject()
                        .put("name", localFile.name)
                        .put("local_path", localFile.absolutePath)
                        .put("remote_path", remote.canonicalPath)
                        .put("bytes", localBytes)
                        .put("verified", true)
                )
            }

            JSONObject()
                .put("ok", true)
                .put("skipped", false)
                .put("remote_dir", base.canonicalPath)
                .put("target_url", safeBaseUrl(config))
                .put("files", uploaded)
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "LAN upload failed", e)
            errorJson(e.message ?: "上传局域网共享文件夹失败")
        }
    }

    private fun readConfig(): LanShareConfig {
        val storedRemoteDir = prefs.getString(KEY_REMOTE_DIR, "") ?: ""
        val remoteDir = normalizeRemoteDir(storedRemoteDir)
        if (storedRemoteDir != remoteDir) {
            prefs.edit().putString(KEY_REMOTE_DIR, remoteDir).apply()
            Log.i(TAG, "Normalized LAN upload remote_dir from '$storedRemoteDir' to '$remoteDir'")
        }
        return LanShareConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            host = prefs.getString(KEY_HOST, "") ?: "",
            shareName = prefs.getString(KEY_SHARE_NAME, "") ?: "",
            remoteDir = remoteDir,
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            domain = prefs.getString(KEY_DOMAIN, "") ?: "",
            uploadSourceCsv = prefs.getBoolean(KEY_UPLOAD_SOURCE_CSV, true),
        )
    }

    private fun validateConfig(config: LanShareConfig) {
        require(config.host.isNotBlank()) { "请填写共享设备 IP 或主机名" }
        require(config.shareName.isNotBlank()) { "请填写共享文件夹名称" }
    }

    private fun buildContext(config: LanShareConfig): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.enableSMB2", "true")
            setProperty("jcifs.smb.client.disableSMB1", "false")
            setProperty("jcifs.smb.client.responseTimeout", "10000")
            setProperty("jcifs.smb.client.soTimeout", "10000")
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
        }
        val base = BaseContext(PropertyConfiguration(props))
        if (config.username.isBlank()) return base
        val auth = NtlmPasswordAuthenticator(config.domain, config.username, config.password)
        return base.withCredentials(auth)
    }

    private fun baseUrl(config: LanShareConfig): String {
        val host = config.host.removePrefix("smb://").trim('/').substringBefore('/')
        val share = config.shareName.trim('/')
        val dir = normalizeRemoteDir(config.remoteDir)
        return buildString {
            append("smb://")
            append(host)
            append("/")
            append(share)
            append("/")
            if (dir.isNotBlank()) {
                append(dir)
                append("/")
            }
        }
    }

    private fun safeBaseUrl(config: LanShareConfig): String =
        baseUrl(config)

    private fun ensureBaseDir(config: LanShareConfig, ctx: CIFSContext): SmbFile {
        val dir = SmbFile(baseUrl(config), ctx)
        if (!dir.exists()) dir.mkdirs()
        require(dir.exists()) { "目标路径创建失败：${dir.canonicalPath}" }
        require(dir.isDirectory) { "目标路径不是文件夹：${dir.canonicalPath}" }
        return dir
    }

    private fun verifyRemoteFile(remote: SmbFile, expectedBytes: Long) {
        require(remote.exists()) { "上传后未找到远端文件：${remote.canonicalPath}" }
        val remoteBytes = remote.length()
        require(remoteBytes == expectedBytes) {
            "上传校验失败：${remote.canonicalPath}，本地 ${expectedBytes}B，远端 ${remoteBytes}B"
        }
    }

    private fun normalizeRemoteDir(raw: String): String =
        raw.replace('\\', '/')
            .trim()
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { segment ->
                if (segment.equals("Destop", ignoreCase = true)) "Desktop" else segment
            }

    private fun configToJson(config: LanShareConfig, includePassword: Boolean): JSONObject =
        JSONObject()
            .put("enabled", config.enabled)
            .put("host", config.host)
            .put("share_name", config.shareName)
            .put("remote_dir", config.remoteDir)
            .put("username", config.username)
            .put("password", if (includePassword) config.password else "")
            .put("domain", config.domain)
            .put("upload_source_csv", config.uploadSourceCsv)

    private fun errorJson(message: String): String =
        JSONObject()
            .put("ok", false)
            .put("error", message)
            .toString()

    private companion object {
        private const val TAG = "LAN_UPLOAD"
        private const val PREFS_NAME = "lan_share_upload"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOST = "host"
        private const val KEY_SHARE_NAME = "share_name"
        private const val KEY_REMOTE_DIR = "remote_dir"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_UPLOAD_SOURCE_CSV = "upload_source_csv"
    }
}
