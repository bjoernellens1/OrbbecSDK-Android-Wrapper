package com.orbbec.orbbecsdkexamples.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.orbbec.obsensor.DeviceChangedCallback;
import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.DeviceInfo;
import com.orbbec.obsensor.DeviceList;
import com.orbbec.obsensor.LogSeverity;
import com.orbbec.obsensor.OBContext;
import com.orbbec.obsensor.OBException;
import com.orbbec.orbbecsdkexamples.R;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("orbbecsdkexamples");
    }

    private OBContext mOBContext;
    private volatile boolean mDeviceConnected = false;
    private String mDeviceName = "";
    private String mDeviceSn = "";
    private String mDeviceFw = "";

    // Views
    private TextView mTvConnectionStatus;
    private LinearLayout mLayoutDeviceInfo;
    private TextView mTvDeviceName;
    private TextView mTvDeviceSn;
    private TextView mTvDeviceFw;
    private TextView mTvSdkVersion;
    private MaterialButton mBtnStartCollection;

    private final ActivityResultLauncher<String[]> mPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) {
                        allGranted = false;
                        break;
                    }
                }
                if (!allGranted) {
                    Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
                }
                initSDK();
            });

    private final DeviceChangedCallback mDeviceChangedCallback = new DeviceChangedCallback() {
        @Override
        public void onDeviceAttach(DeviceList deviceList) {
            try {
                if (deviceList != null && deviceList.getDeviceCount() > 0) {
                    try (Device device = deviceList.getDevice(0)) {
                        DeviceInfo info = device.getInfo();
                        mDeviceName = info.getName() != null ? info.getName() : "Unknown";
                        mDeviceSn = info.getSerialNumber() != null ? info.getSerialNumber() : "--";
                        mDeviceFw = info.getFirmwareVersion() != null ? info.getFirmwareVersion() : "--";
                        info.close();
                    }
                    mDeviceConnected = true;
                    runOnUiThread(MainActivity.this::updateDeviceUI);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    if (deviceList != null) deviceList.close();
                } catch (Exception ignore) {
                }
            }
        }

        @Override
        public void onDeviceDetach(DeviceList deviceList) {
            mDeviceConnected = false;
            mDeviceName = "";
            mDeviceSn = "";
            mDeviceFw = "";
            runOnUiThread(MainActivity.this::updateDeviceUI);
            try {
                if (deviceList != null) deviceList.close();
            } catch (Exception ignore) {
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        requestPermissionsIfNeeded();
    }

    private void initViews() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mTvConnectionStatus = findViewById(R.id.tv_connection_status);
        mLayoutDeviceInfo = findViewById(R.id.layout_device_info);
        mTvDeviceName = findViewById(R.id.tv_device_name);
        mTvDeviceSn = findViewById(R.id.tv_device_sn);
        mTvDeviceFw = findViewById(R.id.tv_device_fw);
        mTvSdkVersion = findViewById(R.id.tv_sdk_version);

        mBtnStartCollection = findViewById(R.id.btn_start_collection);
        mBtnStartCollection.setOnClickListener(v -> startActivity(
                new Intent(this, DataCollectionActivity.class)));

        MaterialButton btnSessions = findViewById(R.id.btn_sessions);
        btnSessions.setOnClickListener(v -> startActivity(
                new Intent(this, SessionsActivity.class)));

        // Developer example buttons
        bindExampleButton(R.id.btn_example_color, ColorViewerActivity.class);
        bindExampleButton(R.id.btn_example_depth, DepthViewerActivity.class);
        bindExampleButton(R.id.btn_example_imu, ImuActivity.class);
        bindExampleButton(R.id.btn_example_pointcloud, PointCloudActivity.class);
        bindExampleButton(R.id.btn_example_sync, SyncAlignViewerActivity.class);
        bindExampleButton(R.id.btn_example_record, RecordPlaybackActivity.class);
        bindExampleButton(R.id.btn_example_sensor_control, SensorControlActivity.class);
        bindExampleButton(R.id.btn_example_firmware, FirmwareUpgradeActivity.class);

        updateDeviceUI();
    }

    private void bindExampleButton(int btnId, Class<?> activityClass) {
        View btn = findViewById(btnId);
        if (btn != null) {
            btn.setOnClickListener(v -> startActivity(new Intent(this, activityClass)));
        }
    }

    private void requestPermissionsIfNeeded() {
        List<String> needed = new ArrayList<>();
        needed.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.READ_MEDIA_VIDEO);
            needed.add(Manifest.permission.READ_MEDIA_IMAGES);
        }
        List<String> toRequest = new ArrayList<>();
        for (String perm : needed) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(perm);
            }
        }
        if (toRequest.isEmpty()) {
            initSDK();
        } else {
            mPermissionLauncher.launch(toRequest.toArray(new String[0]));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Re-init SDK only if it was destroyed (e.g. after onDestroy/recreate).
        // We intentionally do NOT release SDK in onStop because the USB permission
        // dialog pauses/stops this activity and destroying OBContext would lose the
        // pending permission result and device callbacks.
        if (mOBContext == null) {
            initSDK();
        }
    }

    @Override
    protected void onDestroy() {
        releaseSDK();
        super.onDestroy();
    }

    private void initSDK() {
        if (mOBContext != null) return;
        try {
            if (com.orbbec.orbbecsdkexamples.BuildConfig.DEBUG) {
                OBContext.setLoggerSeverity(LogSeverity.WARN);
            }
            mOBContext = new OBContext(getApplicationContext(), mDeviceChangedCallback);
            String sdkVersion = OBContext.getVersionName();
            runOnUiThread(() -> {
                if (mTvSdkVersion != null) {
                    mTvSdkVersion.setText(getString(R.string.sdk_version, sdkVersion));
                }
            });
        } catch (OBException e) {
            e.printStackTrace();
        }
    }

    private void releaseSDK() {
        try {
            if (mOBContext != null) {
                mOBContext.close();
                mOBContext = null;
            }
        } catch (OBException e) {
            e.printStackTrace();
        }
    }

    private void updateDeviceUI() {
        if (mDeviceConnected) {
            mTvConnectionStatus.setText(R.string.status_connected);
            mTvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.color_connected));
            mLayoutDeviceInfo.setVisibility(View.VISIBLE);
            mTvDeviceName.setText(getString(R.string.device_label_name, mDeviceName));
            mTvDeviceSn.setText(getString(R.string.device_label_sn, mDeviceSn));
            mTvDeviceFw.setText(getString(R.string.device_label_fw, mDeviceFw));
            mBtnStartCollection.setEnabled(true);
        } else {
            mTvConnectionStatus.setText(R.string.status_disconnected);
            mTvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.color_disconnected));
            mLayoutDeviceInfo.setVisibility(View.GONE);
            mBtnStartCollection.setEnabled(false);
        }
    }

}