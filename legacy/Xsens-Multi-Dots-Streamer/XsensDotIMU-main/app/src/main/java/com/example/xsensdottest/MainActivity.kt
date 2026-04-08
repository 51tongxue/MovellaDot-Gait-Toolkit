package com.example.xsensdottest;

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.xsensdottest.ui.theme.XsensDotTestTheme
import com.example.xsensedot.NativeDotCommunicationService


class MainActivity : ComponentActivity() {
    // 요청할 권한 리스트
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>


    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("debug", "MainActivity onCreate")
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 요청 권한 처리
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.values.all { it }
            if (!granted) {
                // 권한이 부여되지 않은 경우 처리
                Log.e("Permission", "Required permissions not granted.")
            }
        }

        // 권한 체크
        if (!arePermissionsGranted()) {
            // 권한이 부여되지 않은 경우 권한 요청
            requestPermissions()
        }

        setContent {
            XsensDotTestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConnectionScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun arePermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
    }
}

@Composable
fun ConnectionScreen(modifier: Modifier = Modifier) {
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    var sensorStatus by remember { mutableStateOf("Not Connected") }

    val context = LocalContext.current
    var intent by remember { mutableStateOf<Intent?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Connection Status: $connectionStatus")
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Sensor Status: $sensorStatus")
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = {
                connectionStatus = "Connected"
                sensorStatus = "Sensor Active"
                if(intent==null)
                {
                    intent = Intent(context, NativeDotCommunicationService::class.java)
                    context.startService(intent)
                }
            }) {
                Text("Connect")
            }
            Button(onClick = {
                connectionStatus = "Disconnected"
                sensorStatus = "Sensor Inactive"
                if(intent!=null)
                {
                    context.stopService(intent)
                    intent = null
                }
            }) {
                Text("Disconnect")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ConnectionScreenPreview() {
    XsensDotTestTheme {
        ConnectionScreen()
    }
}
