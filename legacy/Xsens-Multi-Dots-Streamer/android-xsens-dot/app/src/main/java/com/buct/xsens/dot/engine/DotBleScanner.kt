package com.buct.xsens.dot.engine

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

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
        private const val SCAN_TIMEOUT_MS = 12_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val leScanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    private var timeoutRunnable: Runnable? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: ""
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
        if (leScanner == null || adapter == null || !adapter.isEnabled) return false
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        leScanner.startScan(null, settings, scanCallback)
        timeoutRunnable = Runnable {
            leScanner?.stopScan(scanCallback)
            mainHandler.post { onScanFinished() }
        }
        mainHandler.postDelayed(timeoutRunnable!!, SCAN_TIMEOUT_MS)
        return true
    }

    fun stopScan() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        leScanner?.stopScan(scanCallback)
    }
}
