package com.buct.xsens.gait

import android.app.Application
import android.util.Log
import com.xsens.dot.android.sdk.DotSdk

class GaitDashboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DotSdk.setDebugEnabled(true)
        DotSdk.setReconnectEnabled(true)
        DotSdk.setOtaNotificationEnabled(true)
        Log.i(TAG, "Unified app initialized with Movella DOT SDK")
    }

    companion object {
        private const val TAG = "GaitDashboardApp"
    }
}
