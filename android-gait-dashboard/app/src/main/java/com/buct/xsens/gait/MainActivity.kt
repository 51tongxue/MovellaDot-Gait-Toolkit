package com.buct.xsens.gait

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buct.xsens.dot.R
import com.buct.xsens.dot.data.CaptureAthleteOption
import com.buct.xsens.dot.ui.screens.MainScreen
import com.buct.xsens.dot.ui.theme.Accent
import com.buct.xsens.dot.ui.theme.Bg
import com.buct.xsens.dot.ui.theme.Border
import com.buct.xsens.dot.ui.theme.Card
import com.buct.xsens.dot.ui.theme.Green
import com.buct.xsens.dot.ui.theme.Muted
import com.buct.xsens.dot.ui.theme.Surface as AppSurfaceColor
import com.buct.xsens.dot.ui.theme.XsensDotTheme
import com.buct.xsens.dot.viewmodel.CollectionViewModel
import com.buct.xsens.gait.data.GaitDataRepository
import com.buct.xsens.gait.data.AthleteEntity
import com.buct.xsens.gait.ui.screens.NativeAnalysisScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        requestRuntimePermissions()
        requestStorageManagerPermission()

        setContent {
            XsensDotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Bg
                ) {
                    UnifiedWorkbenchScreen()
                }
            }
        }
    }

    private fun requestStorageManagerPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要文件访问权限")
                .setMessage(
                    "步态分析系统需要「所有文件访问权限」，以便保存采集文件并直接读取 offline_export / data_logging。\n\n请在设置页面中开启该权限后返回。"
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

    private fun requestRuntimePermissions() {
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

private enum class WorkspaceTab {
    Capture,
    Analysis,
}

@Composable
private fun UnifiedWorkbenchScreen() {
    var selectedTab by rememberSaveable { mutableStateOf(WorkspaceTab.Capture) }
    val captureViewModel: CollectionViewModel = viewModel()
    val repository = remember { GaitDataRepository(captureViewModel.getApplication()) }
    val scope = rememberCoroutineScope()
    val connectedDevices by captureViewModel.connectedDevices.collectAsState()
    val deviceRoleConfig by captureViewModel.deviceRoleConfig.collectAsState()
    val configuredDeviceCount = deviceRoleConfig.participants.size * 2
    val connectedConfiguredCount = remember(connectedDevices, deviceRoleConfig) {
        val connected = connectedDevices
            .map { it.replace(":", "").uppercase() }
            .toSet()
        deviceRoleConfig.targetDeviceIds.count {
            it.replace(":", "").uppercase() in connected
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == WorkspaceTab.Capture) {
            val athletes = withContext(Dispatchers.IO) {
                repository.listAthletes().map(AthleteEntity::toCaptureAthleteOption)
            }
            captureViewModel.setAvailableAthletes(athletes)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Bg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            WorkbenchTopBar(
                connectedCount = connectedConfiguredCount,
                targetCount = configuredDeviceCount,
                showConnectionStatus = selectedTab == WorkspaceTab.Capture,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                when (selectedTab) {
                    WorkspaceTab.Capture -> {
                        MainScreen(
                            viewModel = captureViewModel,
                            showBrandHeader = false,
                            onSaveAthlete = { athlete, targetSlotId ->
                                scope.launch {
                                    val athletes = withContext(Dispatchers.IO) {
                                        repository.saveAthlete(athlete.toAthleteEntity())
                                        repository.listAthletes()
                                            .map(AthleteEntity::toCaptureAthleteOption)
                                    }
                                    captureViewModel.setAvailableAthletes(athletes)
                                    targetSlotId?.let { slotId ->
                                        captureViewModel.selectParticipantAthlete(
                                            slotId = slotId,
                                            athleteId = athlete.athleteId,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 12.dp)
                        )
                    }

                    WorkspaceTab.Analysis -> {
                        NativeAnalysisScreen(modifier = Modifier.fillMaxSize())
                    }
                }
            }
            NavigationBar(
                containerColor = AppSurfaceColor,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                NavigationBarItem(
                    selected = selectedTab == WorkspaceTab.Capture,
                    onClick = { selectedTab = WorkspaceTab.Capture },
                    icon = { Icon(Icons.Outlined.GraphicEq, contentDescription = null) },
                    label = { Text(text = "采集") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Green,
                        selectedTextColor = Green,
                        indicatorColor = Accent.copy(alpha = 0.16f),
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == WorkspaceTab.Analysis,
                    onClick = { selectedTab = WorkspaceTab.Analysis },
                    icon = { Icon(Icons.Outlined.Analytics, contentDescription = null) },
                    label = { Text(text = "分析") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Green,
                        selectedTextColor = Green,
                        indicatorColor = Accent.copy(alpha = 0.16f),
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted
                    )
                )
            }
        }
    }
}

private fun AthleteEntity.toCaptureAthleteOption(): CaptureAthleteOption {
    val extraObject = runCatching { JSONObject(extra) }.getOrElse { JSONObject() }
    return CaptureAthleteOption(
        athleteId = athleteId,
        athleteName = name,
        athleteCode = athleteCode,
        gender = gender,
        birthDate = birthDate,
        heightCm = heightCm,
        weightKg = weightKg,
        groupName = groupName,
        dominantLeg = extraObject.optString("dominant_leg", "left"),
        extraJson = extra,
    )
}

private fun CaptureAthleteOption.toAthleteEntity(): AthleteEntity {
    val extraObject = runCatching { JSONObject(extraJson) }.getOrElse { JSONObject() }
    extraObject.put("dominant_leg", dominantLeg)
    return AthleteEntity(
        athleteId = athleteId.ifBlank { "ath-local-${System.currentTimeMillis()}" },
        athleteCode = athleteCode.ifBlank { athleteId.ifBlank { "LOCAL" } },
        name = athleteName.trim(),
        gender = gender.trim(),
        birthDate = birthDate.trim(),
        heightCm = heightCm,
        weightKg = weightKg,
        groupName = groupName.trim(),
        extra = extraObject.toString(),
    )
}

@Composable
private fun WorkbenchTopBar(
    connectedCount: Int,
    targetCount: Int,
    showConnectionStatus: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(AppSurfaceColor)
            .border(1.dp, Border)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.buct_logo),
                    contentDescription = "北京体育大学",
                    modifier = Modifier.height(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(id = R.string.app_org_name),
                    style = MaterialTheme.typography.labelLarge,
                    color = Muted
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(id = R.string.app_product_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (showConnectionStatus) {
            val fullyConnected = targetCount > 0 && connectedCount == targetCount
            Row(
                modifier = Modifier
                    .background(AppSurfaceColor, RoundedCornerShape(999.dp))
                    .border(1.dp, Border, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            if (fullyConnected) Green else Muted,
                            RoundedCornerShape(999.dp),
                        ),
                )
                Text(
                    text = "$connectedCount / $targetCount 已连接",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (fullyConnected) "状态正常" else "等待设备",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (fullyConnected) Green else Muted,
                )
            }
        }
    }
}
