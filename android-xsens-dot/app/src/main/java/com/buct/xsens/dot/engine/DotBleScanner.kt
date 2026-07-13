package com.buct.xsens.dot.engine

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 自定义 BLE 扫描器：无过滤扫描，按设备名称筛选 Movella/Xsens DOT。
 * SDK 的 XsensDotScanner 可能只匹配旧名称 "Xsens DOT"，新固件广播 "Movella DOT" 导致扫描不到。
 * 扫描超时后自动停止，避免一直显示「扫描中」。
 */
class DotBleScanner(
    private val context: Context,
    private val callback: (BluetoothDevice, Int, String) -> Unit,
    private val onScanFinished: () -> Unit = {}
) {
    companion object {
        private const val SCAN_TIMEOUT_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val leScanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    private var timeoutRunnable: Runnable? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasConnectPermission()) return
            val device = result.device
            val name = try {
                device.name ?: result.scanRecord?.deviceName ?: ""
            } catch (_: SecurityException) {
                return
            }
            if (isDotDevice(name)) {
                mainHandler.post { callback(device, result.rssi, name) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post { /* 可扩展：通知扫描失败 */ }
        }
    }

    private fun isDotDevice(name: String): Boolean {
        if (name.isBlank()) return false
        return name.contains("Movella DOT", ignoreCase = true) ||
               name.contains("Xsens DOT", ignoreCase = true)
    }

    fun startScan(): Boolean {
        if (!hasScanPermission() || !hasConnectPermission()) return false
        val scanner = leScanner ?: return false
        val bluetoothAdapter = adapter ?: return false
        val enabled = try {
            bluetoothAdapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
        if (!enabled) return false
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (_: SecurityException) {
            return false
        }
        timeoutRunnable = Runnable {
            stopScannerSafely(scanner)
            mainHandler.post { onScanFinished() }
        }
        mainHandler.postDelayed(timeoutRunnable!!, SCAN_TIMEOUT_MS)
        return true
    }

    fun stopScan() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        leScanner?.let(::stopScannerSafely)
    }

    private fun stopScannerSafely(scanner: BluetoothLeScanner) {
        if (!hasScanPermission()) return
        try {
            scanner.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission may be revoked while scanning; the system will stop delivery.
        }
    }

    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
}
