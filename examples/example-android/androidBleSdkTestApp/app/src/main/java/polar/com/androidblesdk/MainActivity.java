package polar.com.androidblesdk;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import org.reactivestreams.Publisher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.UUID;
import java.text.SimpleDateFormat;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Function;
import polar.com.sdk.api.PolarBleApi;
import polar.com.sdk.api.PolarBleApiCallback;
import polar.com.sdk.api.PolarBleApiDefaultImpl;
import polar.com.sdk.api.errors.PolarInvalidArgument;
import polar.com.sdk.api.model.PolarAccelerometerData;
import polar.com.sdk.api.model.PolarDeviceInfo;
import polar.com.sdk.api.model.PolarHrData;
import polar.com.sdk.api.model.PolarSensorSetting;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = MainActivity.class.getSimpleName();
    PolarBleApi api;
    Disposable accDisposable;
    Disposable scanDisposable;

    static String bluetoothDeviceNames[] = {
            "Device 1","Device 2","Device 3","Device 4","Device 5","Device 6"
    };
    static String bluetoothDeviceAddresses[] = {
            "A0:9E:1A:71:88:92",
            "A0:9E:1A:71:8F:36",
            "A0:9E:1A:6E:2E:DE",
            "A0:9E:1A:71:89:BA",
            "A0:9E:1A:6B:CE:46",
            "A0:9E:1A:65:10:CF"
    };

    static HashMap<String, String> bluetoothDeviceNameToAddressMap;
    static HashMap<String, String> bluetoothDeviceAddressToNameMap;
    static {
        bluetoothDeviceNameToAddressMap = new HashMap<>();
        bluetoothDeviceAddressToNameMap = new HashMap<>();
        for (int i = 0; i < bluetoothDeviceNames.length; i++) {
            bluetoothDeviceNameToAddressMap.put(bluetoothDeviceNames[i], bluetoothDeviceAddresses[i]);
            bluetoothDeviceAddressToNameMap.put(bluetoothDeviceAddresses[i], bluetoothDeviceNames[i]);
        }
    }

    String currentBluetoothDeviceId = null;
    String targetBluetoothDeviceName = null;
    String selectedBluetoothDeviceName = null;
    String connectionState = "NOT CONNECTED";

    Disposable autoConnectDisposable;

    String FILENAME_BASE_ACC = "EVO_ACC_DATA";
    String FILENAME_BASE_HR = "EVO_HR_DATA";
    String FILENAME_BASE_LOG = "EVO_LOG";
    String FILENAME_EXTENSION = ".csv";
    FileOutputStream accFileOutputStream = null;
    FileOutputStream hrFileOutputStream = null;
    FileOutputStream logFileOutputStream = null;

    int blePower;
    int batteryLevel;
    int lastHeartRate = 0;
    long lastAccSampleTime = 0;
    long baseAccEpochTime = 0;
    long baseAccDeviceTime = 0;

    boolean disconnectRequested = false;

    static final int DIALOG_SELECT_DEVICE = 0;
    static final int DIALOG_SOMETHING_ELSE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Notice PolarBleApi.ALL_FEATURES are enabled
        api = PolarBleApiDefaultImpl.defaultImplementation(this, PolarBleApi.ALL_FEATURES);
        api.setPolarFilter(false);

        final TextView textViewVersion = findViewById(R.id.textViewVersion);
        textViewVersion.setText("v." + BuildConfig.VERSION_NAME + "." + BuildConfig.VERSION_CODE);

        final Button connectButton = this.findViewById(R.id.connectButton);
        final Button disconnectButton = this.findViewById(R.id.disconnectButton);
        final Button startTestButton = this.findViewById(R.id.startTestButton);
        final Button endTestButton = this.findViewById(R.id.endTestButton);

        final Switch evoOnSwitch = this.findViewById(R.id.evoOnSwitch);

        final TextView textViewConnectionState = findViewById(R.id.textViewConnectionState);
        textViewConnectionState.setText(connectionState);

        final TextView textViewHeartRateValue = findViewById(R.id.textViewHeartRateValue);
        final TextView textViewAccelXValue = findViewById(R.id.textViewAccelXValue);
        final TextView textViewAccelYValue = findViewById(R.id.textViewAccelYValue);
        final TextView textViewAccelZValue = findViewById(R.id.textViewAccelZValue);
        final TextView textViewAccelTotalValue = findViewById(R.id.textViewAccelTotalValue);

        final MediaPlayer connectMP = MediaPlayer.create(this, R.raw.connected);
        final MediaPlayer disconnectMP = MediaPlayer.create(this, R.raw.disconnected);

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
            String filename = today + "-" + FILENAME_BASE_ACC + FILENAME_EXTENSION;
            File accFile = new File(getApplicationContext().getExternalFilesDir(null), filename);
            try {
                accFileOutputStream = new FileOutputStream(accFile,true);
            } catch (IOException e1) {
                Log.d(TAG, "exception opening: " + filename + ", " + e1.getMessage());
            }

            // now set up for heart rate data
            filename = today + "-" + FILENAME_BASE_HR + FILENAME_EXTENSION;
            File hrFile = new File(getApplicationContext().getExternalFilesDir(null), filename);
            try {
                hrFileOutputStream = new FileOutputStream(hrFile, true);
            } catch (IOException e1) {
                Log.d(TAG, "exception opening: " + filename + ", " + e1.getMessage());
            }

            // now set up for device data
            filename = today + "-" + FILENAME_BASE_LOG + FILENAME_EXTENSION;
            File devFile = new File(getApplicationContext().getExternalFilesDir(null), filename);
            try {
                logFileOutputStream = new FileOutputStream(devFile, true);
                writeLogEvent("APP_START", 0);
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
                Log.d(TAG,"CONNECTED: " + polarDeviceInfo.address);
                currentBluetoothDeviceId = polarDeviceInfo.deviceId;
                connectionState = "CONNECTED TO: " + bluetoothDeviceAddressToNameMap.get(polarDeviceInfo.address);
                textViewConnectionState.setText(connectionState);
                // reset the time adjustment variables for the accelerometer data
                baseAccEpochTime = 0;
                baseAccDeviceTime = 0;
                lastAccSampleTime = 0;
                disconnectRequested = false;
                connectMP.start();
                writeLogEvent("DEVICE_CONNECT", bluetoothDeviceAddressToNameMap.get(polarDeviceInfo.address));
            }

            @Override
            public void deviceConnecting(@NonNull PolarDeviceInfo polarDeviceInfo) {
                Log.d(TAG,"CONNECTING: " + polarDeviceInfo.address);
                connectionState = "CONNECTING TO: " + bluetoothDeviceAddressToNameMap.get(polarDeviceInfo.address);
                textViewConnectionState.setText(connectionState);
            }

            @Override
            public void deviceDisconnected(@NonNull PolarDeviceInfo polarDeviceInfo) {
                Log.d(TAG,"DISCONNECTED: " + polarDeviceInfo.address);
                connectionState = "NOT CONNECTED";
                currentBluetoothDeviceId = null;
                textViewConnectionState.setText(connectionState);
                if (!disconnectRequested) {
                    disconnectMP.start();
                }
                disconnectRequested = false;
                writeLogEvent("DEVICE_DISCONNECT", 0);
            }

            @Override
            public void accelerometerFeatureReady(@NonNull String identifier) {
                Log.d(TAG,"ACC READY: " + identifier);
                // this is a convenient place to try to set the time
                Calendar calendar = Calendar.getInstance();
                accDisposable = api.requestAccSettings(currentBluetoothDeviceId).toFlowable().flatMap((Function<PolarSensorSetting, Publisher<PolarAccelerometerData>>) settings -> {
                    // TODO find out how to configure "settings" to send one accelerometer data point at a time.
                    PolarSensorSetting sensorSetting = settings.maxSettings();
                    return api.startAccStreaming(currentBluetoothDeviceId,sensorSetting);
                    }).observeOn(AndroidSchedulers.mainThread()).subscribe(
                        polarAccelerometerData -> {
                            // try to align incoming sample times with phone epoch clock
                            if (baseAccEpochTime == 0) {
                                baseAccEpochTime = Calendar.getInstance().getTimeInMillis() * 1000000;
                                baseAccDeviceTime = polarAccelerometerData.timeStamp;
                            }
                            long timeStampTruncatedToMsec = (long)(Math.round((double)((baseAccEpochTime + polarAccelerometerData.timeStamp - baseAccDeviceTime)/1000.0)))*1000;
                            long sampleCount = polarAccelerometerData.samples.size();
                            long sampleInterval = (long)(Math.round(((double)(timeStampTruncatedToMsec - lastAccSampleTime)/sampleCount)/1000.0))*1000;
                            PolarAccelerometerData.PolarAccelerometerSample lastSample = polarAccelerometerData.samples.get(polarAccelerometerData.samples.size()-1);
                            Log.d(TAG,"New acc data: " + polarAccelerometerData.samples.size() + " samples at time: " + polarAccelerometerData.timeStamp + "," + Calendar.getInstance().getTimeInMillis() + "," + timeStampTruncatedToMsec);
                            long counter = 0;
                            long syntheticStamp = 0;
                            if (lastAccSampleTime != 0) {
                                for (PolarAccelerometerData.PolarAccelerometerSample data : polarAccelerometerData.samples) {
                                    syntheticStamp = timeStampTruncatedToMsec - (sampleCount - 1 - counter) * sampleInterval;
                                    String dataOut = Calendar.getInstance().getTimeInMillis() * 1000000 + "," + syntheticStamp + "," + data.x + "," + data.y + "," + data.z + System.lineSeparator();
                                    counter++;
                                    try {
                                        accFileOutputStream.write(dataOut.getBytes());
                                    } catch (IOException e1) {
                                        Log.d(TAG, "exception writing acc data" + ", " + e1.getMessage());
                                    }
                                }
                            }
                            lastAccSampleTime = timeStampTruncatedToMsec;
                            textViewAccelXValue.setText(lastSample.x + "");
                            textViewAccelYValue.setText(lastSample.y + "");
                            textViewAccelZValue.setText(lastSample.z + "");
                            textViewAccelTotalValue.setText((int)Math.pow(Math.pow(lastSample.x,2) + Math.pow(lastSample.y,2) + Math.pow(lastSample.z,2),0.5) + "");
                        },
                        throwable -> Log.e(TAG,""+throwable.getLocalizedMessage()),
                        () -> Log.d(TAG,"complete")
                );
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
                writeLogEvent("BATTERY_LEVEL", batteryLevel);
            }

            @Override
            public void hrNotificationReceived(@NonNull String identifier,@NonNull PolarHrData data) {
                lastHeartRate = data.hr;
                textViewHeartRateValue.setText(lastHeartRate+"");
                Log.d(TAG,"HR value: " + lastHeartRate);
                String dataOut =  Calendar.getInstance().getTimeInMillis()*1000000 + "," + data.hr + System.lineSeparator();
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

        disconnectButton.setOnClickListener(view -> {
            if (currentBluetoothDeviceId != null) {
                disconnectRequested = true;
                try {
                    api.disconnectFromDevice(currentBluetoothDeviceId);
                } catch (PolarInvalidArgument polarInvalidArgument) {
                    polarInvalidArgument.printStackTrace();
                }
            }
        });

/*        autoConnect.setOnClickListener(view -> {
            if(autoConnectDisposable != null) {
                autoConnectDisposable.dispose();
                autoConnectDisposable = null;
            }
            autoConnectDisposable = api.autoConnectToDevice(-50, "180D", null).subscribe(
                    () -> Log.d(TAG,"auto connectButton search complete"),
                    throwable -> Log.e(TAG,"" + throwable.toString())
            );
        });
*/
        connectButton.setOnClickListener(view -> {
            api.searchForDevice().observeOn(AndroidSchedulers.mainThread()).subscribe(
                polarDeviceInfo -> {
                    Log.d(TAG, "polar device found id: " + polarDeviceInfo.deviceId + " address: " + polarDeviceInfo.address + " rssi: " + polarDeviceInfo.rssi + " name: " + polarDeviceInfo.name + " isConnectable: " + polarDeviceInfo.isConnectable);
                    if (polarDeviceInfo.isConnectable) {
                        // lookup to see if it is in our device list.
                        // TODO
                    }
                },
                throwable -> Log.d(TAG, "" + throwable.getLocalizedMessage()),
                () -> {
                    Log.d(TAG, "complete");
                }
            );
            // show the dialog
            showDialog(DIALOG_SELECT_DEVICE);
        });

        startTestButton.setOnClickListener(view -> {
            writeLogEvent("MARKER_START", 0);
            Snackbar.make(findViewById(android.R.id.content),"Test Started",Snackbar.LENGTH_SHORT).show();
        });

        endTestButton.setOnClickListener(view -> {
            writeLogEvent("MARKER_END", 0);
            Snackbar.make(findViewById(android.R.id.content),"Test Ended",Snackbar.LENGTH_SHORT).show();
        });

        evoOnSwitch.setOnCheckedChangeListener((view, isChecked) -> {
            if (isChecked) {
                writeLogEvent("EVO_ON", 0);
            } else {
                writeLogEvent("EVO_OFF", 0);
            }
        });

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && savedInstanceState == null) {
            this.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    protected void writeLogEvent(String data) {
        String dataOut = Calendar.getInstance().getTimeInMillis() * 1000000 + "," + data;
        try {
            logFileOutputStream.write(dataOut.getBytes());
        } catch (IOException e1) {
            Log.d(TAG, "exception writing acc data" + ", " + e1.getMessage());
        }
    };

    protected void writeLogEvent(String event, int value) {
        String data = event + "," + value + System.lineSeparator();
        writeLogEvent(data);
    };

    protected void writeLogEvent(String event, String value) {
        String data = event + "," + value + System.lineSeparator();
        writeLogEvent(data);
    };

    protected AlertDialog onCreateDialog(int id) {
        switch(id) {
            case DIALOG_SELECT_DEVICE:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Connect to Device")
                        .setCancelable(false)
                        .setSingleChoiceItems(bluetoothDeviceNames, -1, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int item) {
                                Log.d(TAG,"Device selected:  Name: " + bluetoothDeviceNames[item] +
                                        " Address: (" + bluetoothDeviceAddresses[item] + ")");
                                selectedBluetoothDeviceName = bluetoothDeviceNames[item];
                            }
                        })
                        .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                // make the selected item active and try to connect
                                targetBluetoothDeviceName = selectedBluetoothDeviceName;
                                try {
                                    Log.d(TAG,"Try connect to Target:" + targetBluetoothDeviceName + "(" + bluetoothDeviceNameToAddressMap.get(targetBluetoothDeviceName) + ")");
                                    api.connectToDevice(bluetoothDeviceNameToAddressMap.get(targetBluetoothDeviceName));
                                    connectionState = "CONNECTING TO: " + targetBluetoothDeviceName;
                                } catch (PolarInvalidArgument polarInvalidArgument) {
                                    polarInvalidArgument.printStackTrace();
                                }
                                writeLogEvent("USER_CONNECT", targetBluetoothDeviceName);
                                Log.d(TAG,"Target device:" + targetBluetoothDeviceName);
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                // nothing to do here.  Just leave it as-is.
                                Log.d(TAG,"Device selection canceled: target device:" + targetBluetoothDeviceName);
                                targetBluetoothDeviceName = null;
                                writeLogEvent("USER_CONNECT_CANCEL", targetBluetoothDeviceName);
                                dialog.cancel();
                            }
                        });
                AlertDialog alert = builder.create();
                return alert;
            case DIALOG_SOMETHING_ELSE:
            default:
        }
        return null;
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
