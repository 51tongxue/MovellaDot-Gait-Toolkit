package com.buct.xsens.dot

import android.app.Application
import android.util.Log
import com.xsens.dot.android.sdk.DotSdk

/**
 * 按官方示例初始化 SDK（Movella DOT SDK v2025.1.1）。
 * setReconnectEnabled(true) 必须开启：同步过程中设备会断开并自动重连。
 */
class XsensDotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DotSdk.setDebugEnabled(true)
        DotSdk.setReconnectEnabled(true)
        Log.i(TAG, "DotSdk initialized (Movella DOT SDK v2025.1.1)")
    }

    companion object {
        private const val TAG = "XsensDotApp"
    }
}
