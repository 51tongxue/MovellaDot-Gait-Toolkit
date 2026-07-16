package com.buct.xsens.dot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buct.xsens.dot.service.BleStreamingService
import com.buct.xsens.dot.data.CaptureAthleteOption
import com.buct.xsens.dot.ui.screens.MainScreen
import com.buct.xsens.dot.ui.theme.Bg
import com.buct.xsens.dot.ui.theme.XsensDotTheme
import com.xsens.dot.android.sdk.DotSdk

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DotSdk.setDebugEnabled(false)
        DotSdk.setReconnectEnabled(true)
        DotSdk.setOtaNotificationEnabled(true)

        window.statusBarColor = android.graphics.Color.BLACK

        requestPermissions()
        requestStorageManagerPermission()
        startForegroundService(Intent(this, BleStreamingService::class.java))
        val composeView = ComposeView(this).apply {
            setOnGenericMotionListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_EXIT -> true
                    else -> false
                }
            }
            setContent {
                XsensDotTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Bg
                    ) {
                        val collectionViewModel: com.buct.xsens.dot.viewmodel.CollectionViewModel = viewModel()
                        LaunchedEffect(Unit) {
                            collectionViewModel.setAvailableAthletes(
                                listOf(
                                    CaptureAthleteOption(
                                        athleteId = "local-athlete-1",
                                        athleteName = "运动员 1",
                                    )
                                )
                            )
                        }
                        MainScreen(
                            viewModel = collectionViewModel,
                            onSaveAthlete = { athlete, targetSlotId ->
                                val athletes = collectionViewModel.availableAthletes.value
                                    .filterNot { it.athleteId == athlete.athleteId } + athlete
                                collectionViewModel.setAvailableAthletes(athletes)
                                targetSlotId?.let { slotId ->
                                    collectionViewModel.selectParticipantAthlete(
                                        slotId = slotId,
                                        athleteId = athlete.athleteId,
                                    )
                                }
                            },
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
        setContentView(composeView)
    }

    override fun onDestroy() {
        stopService(Intent(this, BleStreamingService::class.java))
        super.onDestroy()
    }

    private fun requestStorageManagerPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要文件访问权限")
                .setMessage(
                    "Xsens DOT 需要「所有文件访问权限」，以便将录制文件保存到公共 Documents 目录，" +
                    "供步态分析仪表盘读取。\n\n请在设置页面中开启该权限后返回。"
                )
                .setPositiveButton("前往设置") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
                .setNegativeButton("暂时跳过", null)
                .show()
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest.isNotEmpty()) {
            permissionLauncher.launch(needRequest.toTypedArray())
        }
    }
}
