package com.buct.xsens.dot.data

import android.bluetooth.BluetoothDevice

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
) {
    val displayName: String get() = name.ifEmpty { "Xsens DOT" }
    val realMac: String get() = address.replace(":", "").replace("-", "").uppercase()
    val sideLabel: String? get() = LongJumpDeviceRoles.assignmentLabel(realMac)
}
