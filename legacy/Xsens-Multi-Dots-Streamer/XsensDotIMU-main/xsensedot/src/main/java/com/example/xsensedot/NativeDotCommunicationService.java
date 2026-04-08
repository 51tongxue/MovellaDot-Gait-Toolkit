package com.example.xsensedot;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class NativeDotCommunicationService extends Service {
    private DotCommunicationBase dotCommunicationBase;

    public void onCreate() {
        super.onCreate();
        dotCommunicationBase = new DotCommunicationBase(this, DotCommunicationBase.Platform.ANDROID);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d("debug", "NativeDotCommunicationService onDestroy");
        super.onDestroy();
        dotCommunicationBase.disconnectIMU();
        dotCommunicationBase = null;
    }
    
}
