package com.example.xsensedot;

import android.util.Log;
import android.widget.Toast;

import com.unity3d.player.UnityPlayer;

public class UnityDotCommunication {
    private static UnityDotCommunication _instnace;
    private com.example.xsensedot.DotCommunicationBase dotCommunicationBase;

    public static UnityDotCommunication instnace(){
        if(_instnace == null){
            _instnace = new UnityDotCommunication();
        }
        return _instnace;
    }
    // Constructor
    public UnityDotCommunication() {
        Log.d("debug", "UnityDotCommunication");
        showToast("UnityDotCommunication");
        // Initialize DotCommunicationBase with context (유니티는 단일 액티비티 이므로 그냥 액티비티 context를 사용)
        // Connect의 경우 Base에서 초기화 + 센서 인식이 완료되면 순차적으로 자동 실행
        dotCommunicationBase = new DotCommunicationBase(UnityPlayer.currentActivity, DotCommunicationBase.Platform.UNITY);
        showToast("UnityDotCommunication2");
    }


    @Override
    protected void finalize() throws Throwable {
        try {
            disconnectIMU();
        } finally {
            super.finalize();
        }
    }

    public void disconnectIMU() {
        dotCommunicationBase.disconnectIMU();
        dotCommunicationBase = null;
    }

    // for Debug, 유니티에서만 작동하게 짜놨음
    private void showToast(String message) {
        UnityPlayer.currentActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(UnityPlayer.currentActivity, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
