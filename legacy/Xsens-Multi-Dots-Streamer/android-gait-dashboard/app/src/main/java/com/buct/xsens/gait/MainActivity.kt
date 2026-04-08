package com.buct.xsens.gait

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.buct.xsens.gait.ui.screens.GaitDashboardScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.BLACK

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                GaitDashboardScreen()
            }
        }

        // Android 11+ 需要引导用户在设置页面授予「所有文件访问权限」
        // 才能通过 File API 直接读取其他 App 的 Android/data 目录
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要文件访问权限")
                .setMessage(
                    "步态分析仪表盘需要「所有文件访问权限」，才能直接读取 Xsens DOT 录制的离线/在线文件。\n\n" +
                    "请在弹出的设置页面中找到本应用并开启该权限，然后返回即可。"
                )
                .setPositiveButton("前往设置") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
                .setNegativeButton("暂时跳过", null)
                .setCancelable(false)
                .show()
        }
    }
}
