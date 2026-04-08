package com.buct.xsens.gait.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buct.xsens.gait.R
import com.buct.xsens.gait.engine.GaitAnalysisManager
import org.json.JSONArray
import java.io.File

class GaitViewModel(application: Application) : AndroidViewModel(application) {
    private val analysisManager = GaitAnalysisManager(application)

    private fun csvFilesIn(vararg dirs: File): List<String> =
        dirs.filter { it.exists() }
            .flatMap { it.listFiles { _, name -> name.lowercase().endsWith(".csv") }?.toList() ?: emptyList() }
            .map { it.absolutePath }
            .distinct()
            .sortedByDescending { it }

    private val docsXsens = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "XsensData"
    )

    /** 离线导出文件（offline_export，120Hz） */
    fun getOfflineFiles(): List<String> = csvFilesIn(File(docsXsens, "offline_export"))

    /** 在线流式采集文件（data_logging，60Hz） */
    fun getOnlineFiles(): List<String> = csvFilesIn(File(docsXsens, "data_logging"))

    /** 兼容旧接口：合并两路 */
    fun getRecordedFiles(): List<String> = (getOfflineFiles() + getOnlineFiles()).distinct().sortedByDescending { it }

    /** 检查是否有「所有文件访问权限」 */
    fun isStorageManagerGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** 诊断用：返回搜索目录的状态 */
    fun diagOfflinePaths(): String {
        val dir = File(docsXsens, "offline_export")
        return "path=${dir.absolutePath} exists=${dir.exists()} files=${dir.listFiles()?.size ?: -1}"
    }

    fun analyze(path: String, weightStr: String): String {
        return analysisManager.analyzeGait(path, weightStr)
    }

    fun analyze(path: String, weightStr: String, startTimeStr: String, endTimeStr: String): String {
        return analysisManager.analyzeGait(path, weightStr, startTimeStr, endTimeStr)
    }

    fun analyzeContent(content: String, weightStr: String): String {
        return analysisManager.analyzeGaitContent(content, weightStr)
    }

    fun analyzeContent(content: String, weightStr: String, startTimeStr: String, endTimeStr: String): String {
        return analysisManager.analyzeGaitContent(content, weightStr, startTimeStr, endTimeStr)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GaitDashboardScreen(
    viewModel: GaitViewModel = viewModel()
) {
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        filePathCallback = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallbackIn: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            filePathCallback = filePathCallbackIn
                            val ctx = webView?.context
                            // 优先用华为文件管理器（第三方 App 可直接浏览所有目录）
                            val hwPkg = listOf("com.hihonor.filemanager", "com.huawei.filemanager", "com.huawei.hidisk", "com.honor.filemanager")
                                .firstOrNull { pkg ->
                                    ctx?.packageManager?.getLaunchIntentForPackage(pkg) != null
                                }
                            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "*/*"
                                if (hwPkg != null) setPackage(hwPkg)
                            }
                            launcher.launch(intent)
                            return true
                        }
                    }

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun isStorageManagerGranted(): Boolean = viewModel.isStorageManagerGranted()

                        @JavascriptInterface
                        fun openStorageSettings() {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:${context.packageName}")
                            context.startActivity(intent)
                        }

                        @JavascriptInterface
                        fun diagOfflinePaths(): String = viewModel.diagOfflinePaths()

                        @JavascriptInterface
                        fun getFileList(): String {
                            return JSONArray(viewModel.getRecordedFiles()).toString()
                        }

                        @JavascriptInterface
                        fun getOfflineFileList(): String {
                            return JSONArray(viewModel.getOfflineFiles()).toString()
                        }

                        @JavascriptInterface
                        fun getOnlineFileList(): String {
                            return JSONArray(viewModel.getOnlineFiles()).toString()
                        }

                        @JavascriptInterface
                        fun analyzeGait(filePath: String, weightStr: String): String {
                            return viewModel.analyze(filePath, weightStr)
                        }

                        @JavascriptInterface
                        fun analyzeGait(filePath: String, weightStr: String, startTimeStr: String, endTimeStr: String): String {
                            return viewModel.analyze(filePath, weightStr, startTimeStr, endTimeStr)
                        }

                        @JavascriptInterface
                        fun analyzeGaitContent(content: String, weightStr: String): String {
                            return viewModel.analyzeContent(content, weightStr)
                        }

                        @JavascriptInterface
                        fun analyzeGaitContent(content: String, weightStr: String, startTimeStr: String, endTimeStr: String): String {
                            return viewModel.analyzeContent(content, weightStr, startTimeStr, endTimeStr)
                        }
                    }, "AndroidInterface")

                    loadUrl("file:///android_asset/gait_dashboard/index.html")
                }
            },
            update = { }
        )

        // 加载页：仅步态图标
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0D0F)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "步态分析",
                    modifier = Modifier.size(120.dp)
                )
            }
        }
    }
}
