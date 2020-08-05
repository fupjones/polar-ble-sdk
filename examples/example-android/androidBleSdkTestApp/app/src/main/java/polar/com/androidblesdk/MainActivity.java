package polar.com.androidblesdk;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Button;

import org.reactivestreams.Publisher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;
import java.text.SimpleDateFormat;
//import java.text.DateFormat;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Function;
import polar.com.sdk.api.PolarBleApi;
import polar.com.sdk.api.PolarBleApiCallback;
import polar.com.sdk.api.PolarBleApiDefaultImpl;
import polar.com.sdk.api.errors.PolarInvalidArgument;
import polar.com.sdk.api.model.PolarAccelerometerData;
import polar.com.sdk.api.model.PolarDeviceInfo;
import polar.com.sdk.api.model.PolarEcgData;
import polar.com.sdk.api.model.PolarExerciseEntry;
import polar.com.sdk.api.model.PolarHrData;
import polar.com.sdk.api.model.PolarOhrPPGData;
import polar.com.sdk.api.model.PolarOhrPPIData;
import polar.com.sdk.api.model.PolarSensorSetting;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = MainActivity.class.getSimpleName();
    PolarBleApi api;
    Disposable broadcastDisposable;
    Disposable ecgDisposable;
    Disposable accDisposable;
    Disposable ppgDisposable;
    Disposable ppiDisposable;
    Disposable scanDisposable;
    String DEVICE_ID = "A0:9E:1A:71:8F:36"; // or bt address like F5:A7:B8:EF:7A:D1 // TODO replace with your device id
    Disposable autoConnectDisposable;
    PolarExerciseEntry exerciseEntry;

    String FILENAME_BASE_ACC = "EVO_ACC_DATA";
    String FILENAME_BASE_HR = "EVO_HR_DATA";
    String FILENAME_BASE_DEV = "EVO_DEV_DATA";
    String FILENAME_EXTENSION = ".csv";
    FileOutputStream accFileOutputStream = null;
    FileOutputStream hrFileOutputStream = null;
    FileOutputStream devFileOutputStream = null;

    int blePower;
    int batteryLevel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Notice PolarBleApi.ALL_FEATURES are enabled
        api = PolarBleApiDefaultImpl.defaultImplementation(this, PolarBleApi.ALL_FEATURES);
        api.setPolarFilter(false);

        final Button broadcast = this.findViewById(R.id.broadcast_button);
        final Button connect = this.findViewById(R.id.connect_button);
        final Button disconnect = this.findViewById(R.id.disconnect_button);
        final Button autoConnect = this.findViewById(R.id.auto_connect_button);
        final Button acc = this.findViewById(R.id.acc_button);
        final Button scan = this.findViewById(R.id.scan_button);
        final Button setTime = this.findViewById(R.id.set_time);

        api.setApiLogger(s -> Log.d(TAG,s));

        Log.d(TAG,"version: " + PolarBleApiDefaultImpl.versionInfo());

        // make sure we can write to files
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            // open our 2 data files
            Log.d(TAG,"isExternalStorageWritable() allows writing files");
            DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
            String today = df.format(Calendar.getInstance().getTime());
            // accelerometer data first
//            String filename = FILENAME_BASE_ACC + System.currentTimeMillis() + FILENAME_EXTENSION;
            String filename = FILENAME_BASE_ACC + "-" + today + FILENAME_EXTENSION;
            String fileHeader = "Start: " + Calendar.getInstance().getTime() + System.lineSeparator();
            File accFile = new File(getApplicationContext().getExternalFilesDir(null), filename);
            try {
                accFileOutputStream = new FileOutputStream(accFile,true);
                accFileOutputStream.write(fileHeader.getBytes());
            } catch (IOException e1) {
                Log.d(TAG, "exception opening: " + filename + ", " + e1.getMessage());
            }

            // now set up for heart rate data
            filename = FILENAME_BASE_HR + "-" + today + FILENAME_EXTENSION;
            File hrFile = new File(getApplicationContext().getExternalFilesDir(null), filename);
            try {
                hrFileOutputStream = new FileOutputStream(hrFile, true);
                hrFileOutputStream.write(fileHeader.getBytes());
            } catch (IOException e1) {
                Log.d(TAG, "exception opening: " + filename + ", " + e1.getMessage());
            }

            // now set up for device data
            filename = FILENAME_BASE_DEV + "-" + today + FILENAME_EXTENSION;
            File devFile = new File(getApplicationContext().getExternalFilesDir(null), filename);
            try {
                devFileOutputStream = new FileOutputStream(devFile, true);
                devFileOutputStream.write(fileHeader.getBytes());
            } catch (IOException e1) {
                Log.d(TAG, "exception opening: " + filename + ", " + e1.getMessage());
            }
        }

        api.setApiCallback(new PolarBleApiCallback() {
            @Override
            public void blePowerStateChanged(boolean powered) {
                Log.d(TAG,"BLE power: " + powered);
            }

            @Override
            public void deviceConnected(@NonNull PolarDeviceInfo polarDeviceInfo) {
                Log.d(TAG,"CONNECTED: " + polarDeviceInfo.deviceId);
                DEVICE_ID = polarDeviceInfo.deviceId;
            }

            @Override
            public void deviceConnecting(@NonNull PolarDeviceInfo polarDeviceInfo) {
                Log.d(TAG,"CONNECTING: " + polarDeviceInfo.deviceId);
                DEVICE_ID = polarDeviceInfo.deviceId;
            }

            @Override
            public void deviceDisconnected(@NonNull PolarDeviceInfo polarDeviceInfo) {
                Log.d(TAG,"DISCONNECTED: " + polarDeviceInfo.deviceId);
                ecgDisposable = null;
                accDisposable = null;
                ppgDisposable = null;
                ppiDisposable = null;
            }

            @Override
            public void accelerometerFeatureReady(@NonNull String identifier) {
                Log.d(TAG,"ACC READY: " + identifier);
                // acc streaming can be started now if needed
                if(accDisposable == null) {
                    accDisposable = api.requestAccSettings(DEVICE_ID).toFlowable().flatMap((Function<PolarSensorSetting, Publisher<PolarAccelerometerData>>) settings -> {
                        PolarSensorSetting sensorSetting = settings.maxSettings();
                        return api.startAccStreaming(DEVICE_ID,sensorSetting);
                    }).observeOn(AndroidSchedulers.mainThread()).subscribe(
                            polarAccelerometerData -> {
                                Log.d(TAG,"New acc data: " + polarAccelerometerData.samples.size() + " samples at time: " + polarAccelerometerData.timeStamp);
                                for( PolarAccelerometerData.PolarAccelerometerSample data : polarAccelerometerData.samples ){
                                    String dataOut = Calendar.getInstance().getTimeInMillis() + "," + polarAccelerometerData.timeStamp + "," + data.x + "," + data.y + ","+ data.z + System.lineSeparator();
                                    try {
                                        accFileOutputStream.write(dataOut.getBytes());
                                    } catch (IOException e1) {
                                        Log.d(TAG, "exception writing acc data" + ", " + e1.getMessage());
                                    }
                                }
                            },
                            throwable -> Log.e(TAG,""+throwable.getLocalizedMessage()),
                            () -> Log.d(TAG,"complete")
                    );
                } else {
                    // NOTE dispose will stop streaming if it is "running"
                    accDisposable.dispose();
                    accDisposable = null;
                }
            }

            @Override
            public void hrFeatureReady(@NonNull String identifier) {
                Log.d(TAG,"HR READY: " + identifier);
                // hr notifications are about to start
            }

            @Override
            public void disInformationReceived(@NonNull String identifier,@NonNull UUID uuid,@NonNull String value) {
                Log.d(TAG,"uuid: " + uuid + " value: " + value);

            }

            @Override
            public void batteryLevelReceived(@NonNull String identifier, int level) {
                Log.d(TAG,"BATTERY LEVEL: " + level);
                batteryLevel = level;
                String dataOut = Calendar.getInstance().getTimeInMillis() + "," + batteryLevel + System.lineSeparator();
                try {
                    devFileOutputStream.write(dataOut.getBytes());
                } catch (IOException e1) {
                    Log.d(TAG, "exception writing dev data" + ", " + e1.getMessage());
                }
            }

            @Override
            public void hrNotificationReceived(@NonNull String identifier,@NonNull PolarHrData data) {
                Log.d(TAG,"HR value: " + data.hr);
                String dataOut =  Calendar.getInstance().getTimeInMillis() + "," + data.hr + System.lineSeparator();
                try {
                    hrFileOutputStream.write(dataOut.getBytes());
                } catch (IOException e1) {
                    Log.d(TAG, "exception writing hr data" + ", " + e1.getMessage());
                }
            }

            @Override
            public void polarFtpFeatureReady(@NonNull String s) {
                Log.d(TAG,"FTP ready");
            }
        });

        connect.setOnClickListener(v -> {
            try {
                api.connectToDevice(DEVICE_ID);
            } catch (PolarInvalidArgument polarInvalidArgument) {
                polarInvalidArgument.printStackTrace();
            }
        });

        disconnect.setOnClickListener(view -> {
            try {
                api.disconnectFromDevice(DEVICE_ID);
            } catch (PolarInvalidArgument polarInvalidArgument) {
                polarInvalidArgument.printStackTrace();
            }
        });

        autoConnect.setOnClickListener(view -> {
            if(autoConnectDisposable != null) {
                autoConnectDisposable.dispose();
                autoConnectDisposable = null;
            }
            autoConnectDisposable = api.autoConnectToDevice(-50, "180D", null).subscribe(
                    () -> Log.d(TAG,"auto connect search complete"),
                    throwable -> Log.e(TAG,"" + throwable.toString())
            );
        });

        acc.setOnClickListener(v -> {
            if(accDisposable == null) {
                accDisposable = api.requestAccSettings(DEVICE_ID).toFlowable().flatMap((Function<PolarSensorSetting, Publisher<PolarAccelerometerData>>) settings -> {
                    PolarSensorSetting sensorSetting = settings.maxSettings();
                    return api.startAccStreaming(DEVICE_ID,sensorSetting);
                }).observeOn(AndroidSchedulers.mainThread()).subscribe(
                        polarAccelerometerData -> {
                            Log.d(TAG,"New acc data: " + polarAccelerometerData.samples.size() + " samples at time: " + polarAccelerometerData.timeStamp);
                            for( PolarAccelerometerData.PolarAccelerometerSample data : polarAccelerometerData.samples ){
                                String dataOut = polarAccelerometerData.timeStamp + "," + data.x + "," + data.y + ","+ data.z + System.lineSeparator();
                                try {
                                    accFileOutputStream.write(dataOut.getBytes());
                                } catch (IOException e1) {
                                    Log.d(TAG, "exception writing acc data" + ", " + e1.getMessage());
                                }
                            }
                        },
                        throwable -> Log.e(TAG,""+throwable.getLocalizedMessage()),
                        () -> Log.d(TAG,"complete")
                );
            } else {
                // NOTE dispose will stop streaming if it is "running"
                accDisposable.dispose();
                accDisposable = null;
            }
        });

        scan.setOnClickListener(view -> {
            if(scanDisposable == null) {
                scanDisposable = api.searchForDevice().observeOn(AndroidSchedulers.mainThread()).subscribe(
                        polarDeviceInfo -> Log.d(TAG, "polar device found id: " + polarDeviceInfo.deviceId + " address: " + polarDeviceInfo.address + " rssi: " + polarDeviceInfo.rssi + " name: " + polarDeviceInfo.name + " isConnectable: " + polarDeviceInfo.isConnectable),
                        throwable -> Log.d(TAG, "" + throwable.getLocalizedMessage()),
                        () -> Log.d(TAG, "complete")
                );
            }else{
                scanDisposable.dispose();
                scanDisposable = null;
            }
        });

        setTime.setOnClickListener(v -> {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(new Date());
            api.setLocalTime(DEVICE_ID,calendar).subscribe(
                    () -> Log.d(TAG,"time set to device"),
                    throwable -> Log.d(TAG,"set time failed: " + throwable.getLocalizedMessage()));
        });

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && savedInstanceState == null) {
            this.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if(requestCode == 1) {
            Log.d(TAG,"bt ready");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        api.backgroundEntered();
    }

    @Override
    public void onResume() {
        super.onResume();
        api.foregroundEntered();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        api.shutDown();
    }
}
