package com.example.xsensedot;

import static com.example.xsensedot.DotCommunicationBase.Platform.UNITY;
import static com.xsens.dot.android.sdk.models.DotDevice.CONN_STATE_CONNECTED;
import static com.xsens.dot.android.sdk.models.DotDevice.CONN_STATE_DISCONNECTED;
import static com.xsens.dot.android.sdk.models.DotDevice.CONN_STATE_RECONNECTING;

import com.unity3d.player.UnityPlayer;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.xsens.dot.android.sdk.DotSdk;
import com.xsens.dot.android.sdk.events.DotData;
import com.xsens.dot.android.sdk.interfaces.DotDeviceCallback;
import com.xsens.dot.android.sdk.interfaces.DotScannerCallback;
import com.xsens.dot.android.sdk.models.DotDevice;
import com.xsens.dot.android.sdk.models.FilterProfileInfo;
import com.xsens.dot.android.sdk.utils.DotScanner;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

public class DotCommunicationBase implements DotDeviceCallback, DotScannerCallback {

    public enum Platform{
        ANDROID,
        UNITY
    }
    private DotScanner _mXsScanner;
    private DotDevice _xsDevice;
    private BluetoothDevice _scannedDevice;
    private final Platform _platform;

    private final String _iMUserial = "D4:22:CD:00:81:23";
    // 1번 IMU: "D4:22:CD:00:80:E9"
    // 2번 IMU: "D4:22:CD:00:80:DE"
    // 3번 IMU: "D4:22:CD:00:81:23"
    private final int _outputRate = 60;
    private final Context _context;

    DotData dotData;


    public DotCommunicationBase(Context context, Platform platform) {
        _context = context;
        _platform = platform;
        initXsSdk();
        initXsScanner();
        _mXsScanner.startScan();
    }

    public void disconnectIMU() {
        Log.d("debug", "DotCommunicationBase DisconnectIMU");
        _mXsScanner.stopScan();
        _xsDevice.stopMeasuring();
        _xsDevice.disconnect();
    }

    private void connectIMU() {
        Log.d("debug", "DotCommunicationBase ConnectIMU");
        _xsDevice = new DotDevice(_context, _scannedDevice, this);
        _xsDevice.setOutputRate(_outputRate);
        _xsDevice.connect();
    }

    private void initXsSdk() {
        DotSdk.setDebugEnabled(true);
        DotSdk.setReconnectEnabled(true);
        Log.d("debug", "DotCommunicationBase initXsSdk");
    }

    private void initXsScanner() {
        _mXsScanner = new DotScanner(_context, this);
        _mXsScanner.setScanMode(ScanSettings.SCAN_MODE_BALANCED);
        Log.d("debug", "DotCommunicationBase initXsScanner");
    }

    @Override
    public void onDotScanned(BluetoothDevice bluetoothDevice, int rssi) {
        if (bluetoothDevice.getAddress().equals(_iMUserial)) {
            Log.d("debug", "DotCommunicationBase onDotScanned - " + bluetoothDevice.getAddress());
            _scannedDevice = bluetoothDevice;
            connectIMU();
        }
    }

    @Override
    public void onDotConnectionChanged(String address, int state) {
        Log.d("debug", "DotCommunicationBase onDotConnectionChanged state - " + state);
        if(address.equals(_iMUserial) && (state == CONN_STATE_CONNECTED || state == CONN_STATE_RECONNECTING))
        {
            _mXsScanner.stopScan();
        }
    }

    @Override
    public void onDotServicesDiscovered(String address, int state) {
        Log.d("debug", "DotCommunicationBase onDotServicesDiscovered");
    }

    @Override
    public void onDotInitDone(String s) {
        Log.d("debug", "DotCommunicationBase onDotInitDone");
        _xsDevice.startMeasuring();
    }

    @Override
    public void onDotDataChanged(String address, DotData receivedDotData) {
        Log.d("debug", "DotCommunicationBase onDotDataChanged");

        dotData = receivedDotData;

        String accString = "acc: " + Arrays.stream(dotData.getAcc())
                .mapToObj(acc -> String.format("%.3f", acc)).collect(Collectors.joining(" "));
        String gyrString = "gyr: " + Arrays.stream(dotData.getGyr())
                .mapToObj(gyr -> String.format("%.3f", gyr)).collect(Collectors.joining(" "));
        String eulerString = "euler: " + Arrays.stream(dotData.getEuler())
                .mapToObj(euler -> String.format("%.3f", euler)).collect(Collectors.joining(" "));

        Log.d("SensorData", accString);
        Log.d("SensorData", gyrString);
        Log.d("SensorData", eulerString);

        // Unity로 데이터 전송 (Unity 플레이어가 설정된 경우)
        if(_platform==UNITY)
        {
            sendToUnity(serializeDotData(dotData));
        }
    }

    private String serializeDotData(DotData dotData) {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("acc", dotData.getAcc());
            jsonObject.put("gyr", dotData.getGyr());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return jsonObject.toString();
    }

    private void sendToUnity(String jsonData) {
        // Unity로 데이터 전송
        UnityPlayer.UnitySendMessage("RingIMUreceiver", "ReceiveRingIMU", jsonData);
    }

    @Override
    public void onDotFirmwareVersionRead(String s, String s1) {}

    @Override
    public void onDotTagChanged(String s, String s1) {}

    @Override
    public void onDotBatteryChanged(String s, int i, int i1) {}

    @Override
    public void onDotButtonClicked(String s, long l) {}

    @Override
    public void onDotPowerSavingTriggered(String s) {}

    @Override
    public void onReadRemoteRssi(String s, int i) {}

    @Override
    public void onDotOutputRateUpdate(String s, int i) {}

    @Override
    public void onDotFilterProfileUpdate(String s, int i) {}

    @Override
    public void onDotGetFilterProfileInfo(String s, ArrayList<FilterProfileInfo> arrayList) {}

    @Override
    public void onSyncStatusUpdate(String s, boolean b) {}
}
