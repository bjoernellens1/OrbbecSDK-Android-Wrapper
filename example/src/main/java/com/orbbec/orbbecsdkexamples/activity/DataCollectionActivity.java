package com.orbbec.orbbecsdkexamples.activity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.textfield.TextInputEditText;
import com.orbbec.obsensor.AccelFrame;
import com.orbbec.obsensor.AccelStreamProfile;
import com.orbbec.obsensor.ColorFrame;
import com.orbbec.obsensor.Config;
import com.orbbec.obsensor.DepthFrame;
import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.DeviceChangedCallback;
import com.orbbec.obsensor.DeviceList;
import com.orbbec.obsensor.Format;
import com.orbbec.obsensor.FrameSet;
import com.orbbec.obsensor.FrameType;
import com.orbbec.obsensor.GyroFrame;
import com.orbbec.obsensor.GyroStreamProfile;
import com.orbbec.obsensor.IRFrame;
import com.orbbec.obsensor.LogSeverity;
import com.orbbec.obsensor.OBContext;
import com.orbbec.obsensor.OBException;
import com.orbbec.obsensor.Pipeline;
import com.orbbec.obsensor.Sensor;
import com.orbbec.obsensor.SensorType;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.StreamType;
import com.orbbec.obsensor.VideoStreamProfile;
import com.orbbec.orbbecsdkexamples.R;
import com.orbbec.orbbecsdkexamples.utils.FileUtils;
import com.orbbec.orbbecsdkexamples.view.OBGLView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Data Collection Activity
 *
 * Streams Color, Depth, IR, and IMU data simultaneously.
 * Recordings are saved in Orbbec's native .bag format (rosbag-compatible)
 * alongside an IMU CSV log.
 */
public class DataCollectionActivity extends AppCompatActivity {
    private static final String TAG = "DataCollectionActivity";

    private static final int MSG_UPDATE_IMU = 1;
    private static final int MSG_UPDATE_TIMER = 2;

    // Views
    private OBGLView mGlvColor;
    private OBGLView mGlvDepth;
    private OBGLView mGlvIr;
    private TextView mTvAccel;
    private TextView mTvGyro;
    private TextView mTvImuTs;
    private TextView mTvRecordingIndicator;
    private TextInputEditText mEtSessionName;
    private MaterialButton mBtnRecord;
    private Chip mChipColor;
    private Chip mChipDepth;
    private Chip mChipIr;
    private Chip mChipImu;

    // SDK objects
    private OBContext mOBContext;
    private Pipeline mPipeline;
    private Device mDevice;
    private Sensor mSensorAccel;
    private Sensor mSensorGyro;

    // State
    private volatile boolean mIsStreaming = false;
    private volatile boolean mIsRecording = false;
    private volatile boolean mImuEnabled = false;
    private Thread mStreamThread;
    private long mRecordStartMs = 0;
    private String mCurrentSessionPath = null;
    private FileWriter mImuCsvWriter = null;

    // Latest IMU frames
    private final Object mAccelLock = new Object();
    private AccelFrame mAccelFrame;
    private final Object mGyroLock = new Object();
    private GyroFrame mGyroFrame;

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            if (msg.what == MSG_UPDATE_IMU) {
                updateImuDisplay();
                sendEmptyMessageDelayed(MSG_UPDATE_IMU, 50);
            } else if (msg.what == MSG_UPDATE_TIMER) {
                if (mIsRecording) {
                    long elapsed = System.currentTimeMillis() - mRecordStartMs;
                    long secs = elapsed / 1000;
                    mTvRecordingIndicator.setText(String.format(Locale.US,
                            "● REC  %02d:%02d", secs / 60, secs % 60));
                    sendEmptyMessageDelayed(MSG_UPDATE_TIMER, 1000);
                }
            }
        }
    };

    // Device hotplug callback
    private final DeviceChangedCallback mDeviceChangedCallback = new DeviceChangedCallback() {
        @Override
        public void onDeviceAttach(DeviceList deviceList) {
            try {
                if (mDevice == null && deviceList.getDeviceCount() > 0) {
                    mDevice = deviceList.getDevice(0);
                    startStreaming();
                }
            } catch (Exception e) {
                Log.e(TAG, "onDeviceAttach error", e);
            } finally {
                try {
                    deviceList.close();
                } catch (Exception ignore) {
                }
            }
        }

        @Override
        public void onDeviceDetach(DeviceList deviceList) {
            stopStreaming();
            closeDevice();
            runOnUiThread(() -> Toast.makeText(DataCollectionActivity.this,
                    getString(R.string.device_not_connected), Toast.LENGTH_SHORT).show());
            try {
                deviceList.close();
            } catch (Exception ignore) {
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep screen on during data collection
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_data_collection);
        initViews();
        initSDK();
    }

    private void initViews() {
        mGlvColor = findViewById(R.id.glv_color);
        mGlvDepth = findViewById(R.id.glv_depth);
        mGlvIr = findViewById(R.id.glv_ir);
        mTvAccel = findViewById(R.id.tv_accel);
        mTvGyro = findViewById(R.id.tv_gyro);
        mTvImuTs = findViewById(R.id.tv_imu_ts);
        mTvRecordingIndicator = findViewById(R.id.tv_recording_indicator);
        mEtSessionName = findViewById(R.id.et_session_name);
        mBtnRecord = findViewById(R.id.btn_record);
        mChipColor = findViewById(R.id.chip_color);
        mChipDepth = findViewById(R.id.chip_depth);
        mChipIr = findViewById(R.id.chip_ir);
        mChipImu = findViewById(R.id.chip_imu);

        mBtnRecord.setOnClickListener(v -> {
            if (mIsRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
    }

    private void initSDK() {
        try {
            OBContext.setLoggerSeverity(LogSeverity.WARN);
            mOBContext = new OBContext(getApplicationContext(), mDeviceChangedCallback);
        } catch (OBException e) {
            Log.e(TAG, "initSDK failed", e);
        }
    }

    private void startStreaming() {
        if (mIsStreaming || mDevice == null) return;
        try {
            mPipeline = new Pipeline(mDevice);
            Config config = new Config();

            boolean colorEnabled = mChipColor.isChecked();
            boolean depthEnabled = mChipDepth.isChecked();
            boolean irEnabled = mChipIr.isChecked();
            mImuEnabled = mChipImu.isChecked();

            if (colorEnabled) {
                VideoStreamProfile cp = getStreamProfile(mPipeline, SensorType.COLOR);
                if (cp != null) {
                    config.enableStream(cp);
                    cp.close();
                }
            }
            if (depthEnabled) {
                VideoStreamProfile dp = getStreamProfile(mPipeline, SensorType.DEPTH);
                if (dp != null) {
                    config.enableStream(dp);
                    dp.close();
                }
            }
            if (irEnabled) {
                VideoStreamProfile ip = getStreamProfile(mPipeline, SensorType.IR);
                if (ip != null) {
                    config.enableStream(ip);
                    ip.close();
                }
            }

            mPipeline.start(config);
            config.close();
            mIsStreaming = true;

            // Start IMU sensors if enabled
            if (mImuEnabled) {
                startImu();
            }

            // Frame polling thread
            mStreamThread = new Thread(this::streamLoop, "StreamThread");
            mStreamThread.start();

            // Start IMU UI updates
            mHandler.sendEmptyMessage(MSG_UPDATE_IMU);
        } catch (Exception e) {
            Log.e(TAG, "startStreaming failed", e);
        }
    }

    private void startImu() {
        try {
            mSensorAccel = mDevice.getSensor(SensorType.ACCEL);
            if (mSensorAccel != null) {
                try (StreamProfileList list = mSensorAccel.getStreamProfileList()) {
                    AccelStreamProfile profile = list.getStreamProfile(0).as(StreamType.ACCEL);
                    mSensorAccel.start(profile, frame -> {
                        AccelFrame accelFrame = frame.as(FrameType.ACCEL);
                        synchronized (mAccelLock) {
                            if (mAccelFrame != null) mAccelFrame.close();
                            mAccelFrame = accelFrame;
                        }
                        writeImuCsv(accelFrame, null);
                    });
                    profile.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Accel sensor not available: " + e.getMessage());
        }
        try {
            mSensorGyro = mDevice.getSensor(SensorType.GYRO);
            if (mSensorGyro != null) {
                try (StreamProfileList list = mSensorGyro.getStreamProfileList()) {
                    GyroStreamProfile profile = list.getStreamProfile(0).as(StreamType.GYRO);
                    mSensorGyro.start(profile, frame -> {
                        GyroFrame gyroFrame = frame.as(FrameType.GYRO);
                        synchronized (mGyroLock) {
                            if (mGyroFrame != null) mGyroFrame.close();
                            mGyroFrame = gyroFrame;
                        }
                        writeImuCsv(null, gyroFrame);
                    });
                    profile.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Gyro sensor not available: " + e.getMessage());
        }
    }

    private void streamLoop() {
        while (mIsStreaming) {
            try (FrameSet frameSet = mPipeline.waitForFrameSet(1000)) {
                if (frameSet == null) continue;

                // Color frame
                try (ColorFrame colorFrame = frameSet.getColorFrame()) {
                    if (colorFrame != null) {
                        int dataSize = colorFrame.getDataSize();
                        byte[] data = new byte[dataSize];
                        colorFrame.getData(data);
                        mGlvColor.update(colorFrame.getWidth(), colorFrame.getHeight(),
                                StreamType.COLOR, colorFrame.getFormat(), data, 1.0f);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Color frame error: " + e.getMessage());
                }

                // Depth frame
                try (DepthFrame depthFrame = frameSet.getDepthFrame()) {
                    if (depthFrame != null) {
                        int dataSize = depthFrame.getDataSize();
                        byte[] data = new byte[dataSize];
                        depthFrame.getData(data);
                        mGlvDepth.update(depthFrame.getWidth(), depthFrame.getHeight(),
                                StreamType.DEPTH, depthFrame.getFormat(), data, 1.0f);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Depth frame error: " + e.getMessage());
                }

                // IR frame
                try (IRFrame irFrame = frameSet.getIrFrame()) {
                    if (irFrame != null) {
                        int dataSize = irFrame.getDataSize();
                        byte[] data = new byte[dataSize];
                        irFrame.getData(data);
                        mGlvIr.update(irFrame.getWidth(), irFrame.getHeight(),
                                StreamType.IR, irFrame.getFormat(), data, 1.0f);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "IR frame error: " + e.getMessage());
                }

            } catch (Exception e) {
                if (mIsStreaming) {
                    Log.e(TAG, "Frame loop error: " + e.getMessage());
                }
            }
        }
    }

    private void stopStreaming() {
        mIsStreaming = false;
        if (mStreamThread != null) {
            try {
                mStreamThread.join(2000);
            } catch (InterruptedException ignore) {
            }
            mStreamThread = null;
        }
        stopImu();
        try {
            if (mPipeline != null) {
                mPipeline.stop();
                mPipeline.close();
                mPipeline = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stopStreaming error", e);
        }
    }

    private void stopImu() {
        try {
            if (mSensorAccel != null) {
                mSensorAccel.stop();
                mSensorAccel.close();
                mSensorAccel = null;
            }
        } catch (Exception ignore) {
        }
        try {
            if (mSensorGyro != null) {
                mSensorGyro.stop();
                mSensorGyro.close();
                mSensorGyro = null;
            }
        } catch (Exception ignore) {
        }
        synchronized (mAccelLock) {
            if (mAccelFrame != null) {
                mAccelFrame.close();
                mAccelFrame = null;
            }
        }
        synchronized (mGyroLock) {
            if (mGyroFrame != null) {
                mGyroFrame.close();
                mGyroFrame = null;
            }
        }
    }

    // ---- Recording ----

    private void startRecording() {
        if (mPipeline == null || !mIsStreaming) {
            Toast.makeText(this, R.string.device_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        // Build session folder
        String sessionName = mEtSessionName != null
                && !TextUtils.isEmpty(mEtSessionName.getText())
                ? mEtSessionName.getText().toString().trim() : null;
        if (TextUtils.isEmpty(sessionName)) {
            sessionName = "session_" + new SimpleDateFormat("yyyyMMdd_HHmmss",
                    Locale.US).format(new Date());
        }

        String rootDir = FileUtils.getExternalSaveDir();
        if (TextUtils.isEmpty(rootDir)) {
            Toast.makeText(this, getString(R.string.recording_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        File sessionDir = new File(rootDir, sessionName);
        if (!sessionDir.mkdirs() && !sessionDir.exists()) {
            Toast.makeText(this, getString(R.string.recording_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        mCurrentSessionPath = sessionDir.getAbsolutePath();

        // Start bag recording for video streams
        String bagPath = mCurrentSessionPath + File.separator + "recording.bag";
        try {
            mPipeline.startRecord(bagPath);
        } catch (Exception e) {
            Log.e(TAG, "startRecord failed", e);
            Toast.makeText(this, getString(R.string.recording_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        // Open IMU CSV writer
        if (mImuEnabled) {
            try {
                File csvFile = new File(mCurrentSessionPath, "imu.csv");
                mImuCsvWriter = new FileWriter(csvFile, false);
                mImuCsvWriter.write("type,timestamp_us,x,y,z,temperature\n");
                mImuCsvWriter.flush();
            } catch (IOException e) {
                Log.w(TAG, "Failed to create IMU CSV: " + e.getMessage());
            }
        }

        // Write session metadata
        writeSessionMetadata(sessionDir, sessionName);

        mIsRecording = true;
        mRecordStartMs = System.currentTimeMillis();
        mHandler.sendEmptyMessage(MSG_UPDATE_TIMER);

        runOnUiThread(() -> {
            mTvRecordingIndicator.setVisibility(View.VISIBLE);
            mBtnRecord.setText(R.string.btn_stop_record);
            mBtnRecord.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.color_recording)));
        });
        Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show();
    }

    private void stopRecording() {
        if (!mIsRecording) return;
        mIsRecording = false;
        mHandler.removeMessages(MSG_UPDATE_TIMER);

        try {
            if (mPipeline != null) {
                mPipeline.stopRecord();
            }
        } catch (Exception e) {
            Log.e(TAG, "stopRecord failed", e);
        }

        closeImuCsv();

        String savedPath = mCurrentSessionPath;
        mCurrentSessionPath = null;

        runOnUiThread(() -> {
            mTvRecordingIndicator.setVisibility(View.GONE);
            mBtnRecord.setText(R.string.btn_start_record);
            mBtnRecord.setBackgroundTintList(null);
            if (savedPath != null) {
                Toast.makeText(this,
                        getString(R.string.recording_saved,
                                new File(savedPath).getName()),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void closeImuCsv() {
        if (mImuCsvWriter != null) {
            try {
                mImuCsvWriter.close();
            } catch (IOException ignore) {
            }
            mImuCsvWriter = null;
        }
    }

    private void writeImuCsv(AccelFrame accel, GyroFrame gyro) {
        if (!mIsRecording || mImuCsvWriter == null) return;
        try {
            if (accel != null) {
                float[] a = accel.getAccelData();
                mImuCsvWriter.write(String.format(Locale.US,
                        "accel,%d,%.6f,%.6f,%.6f,%.2f\n",
                        accel.getTimeStamp(), a[0], a[1], a[2],
                        accel.getTemperature()));
            }
            if (gyro != null) {
                float[] g = gyro.getGyroData();
                mImuCsvWriter.write(String.format(Locale.US,
                        "gyro,%d,%.6f,%.6f,%.6f,%.2f\n",
                        gyro.getTimeStamp(), g[0], g[1], g[2],
                        gyro.getTemperature()));
            }
        } catch (IOException e) {
            Log.w(TAG, "IMU CSV write error: " + e.getMessage());
        }
    }

    private void writeSessionMetadata(File sessionDir, String sessionName) {
        try {
            File meta = new File(sessionDir, "metadata.json");
            FileWriter fw = new FileWriter(meta);
            fw.write("{\n");
            fw.write("  \"session_name\": \"" + sessionName + "\",\n");
            fw.write("  \"timestamp_utc\": \"" + new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date()) + "\",\n");
            fw.write("  \"streams\": {\n");
            fw.write("    \"color\": " + mChipColor.isChecked() + ",\n");
            fw.write("    \"depth\": " + mChipDepth.isChecked() + ",\n");
            fw.write("    \"ir\": " + mChipIr.isChecked() + ",\n");
            fw.write("    \"imu\": " + mChipImu.isChecked() + "\n");
            fw.write("  },\n");
            fw.write("  \"format\": \"bag (rosbag v2 + imu.csv)\"\n");
            fw.write("}\n");
            fw.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to write metadata: " + e.getMessage());
        }
    }

    // ---- IMU display ----

    private void updateImuDisplay() {
        AccelFrame accel;
        synchronized (mAccelLock) {
            accel = mAccelFrame;
        }
        GyroFrame gyro;
        synchronized (mGyroLock) {
            gyro = mGyroFrame;
        }
        if (accel != null) {
            try {
                float[] a = accel.getAccelData();
                mTvAccel.setText(String.format(Locale.US, "Accel  X:%.2f  Y:%.2f  Z:%.2f m/s²",
                        a[0], a[1], a[2]));
                mTvImuTs.setText(String.format(Locale.US, "ts: %d ms", accel.getTimeStamp()));
            } catch (Exception ignore) {
            }
        }
        if (gyro != null) {
            try {
                float[] g = gyro.getGyroData();
                mTvGyro.setText(String.format(Locale.US, "Gyro   X:%.2f  Y:%.2f  Z:%.2f °/s",
                        g[0], g[1], g[2]));
            } catch (Exception ignore) {
            }
        }
    }

    // ---- Stream profile selection (same logic as BaseActivity) ----

    private VideoStreamProfile getStreamProfile(Pipeline pipeline, SensorType sensorType) {
        Format preferFormat;
        if (sensorType == SensorType.COLOR) {
            preferFormat = Format.RGB;
        } else if (sensorType == SensorType.IR) {
            preferFormat = Format.Y8;
        } else if (sensorType == SensorType.DEPTH) {
            preferFormat = Format.Y16;
        } else {
            return null;
        }
        try (StreamProfileList list = pipeline.getStreamProfileList(sensorType)) {
            List<VideoStreamProfile> profiles = new ArrayList<>();
            for (int i = 0, n = list.getStreamProfileCount(); i < n; i++) {
                VideoStreamProfile p = list.getStreamProfile(i).as(StreamType.VIDEO);
                if (p.getWidth() >= 640 && p.getWidth() <= 1280
                        && p.getHeight() >= 360
                        && p.getFormat() == preferFormat) {
                    profiles.add(p);
                } else {
                    p.close();
                }
            }
            if (profiles.isEmpty()) {
                // Fallback: any non-compressed format in range
                for (int i = 0, n = list.getStreamProfileCount(); i < n; i++) {
                    VideoStreamProfile p = list.getStreamProfile(i).as(StreamType.VIDEO);
                    if (p.getWidth() >= 640 && p.getWidth() <= 1280 && p.getHeight() >= 360
                            && p.getFormat() != Format.MJPG && p.getFormat() != Format.RVL) {
                        profiles.add(p);
                    } else {
                        p.close();
                    }
                }
            }
            if (profiles.isEmpty()) return null;
            // Sort: high fps, large width
            Collections.sort(profiles, (o1, o2) -> {
                if (o1.getFps() != o2.getFps()) return o2.getFps() - o1.getFps();
                return o2.getWidth() - o1.getWidth();
            });
            VideoStreamProfile best = profiles.get(0);
            for (int i = 1; i < profiles.size(); i++) profiles.get(i).close();
            return best;
        } catch (Exception e) {
            Log.e(TAG, "getStreamProfile failed for " + sensorType, e);
            return null;
        }
    }

    // ---- Device cleanup ----

    private void closeDevice() {
        try {
            if (mDevice != null) {
                mDevice.close();
                mDevice = null;
            }
        } catch (Exception ignore) {
        }
    }

    @Override
    protected void onDestroy() {
        if (mIsRecording) stopRecording();
        stopStreaming();
        closeDevice();
        mHandler.removeCallbacksAndMessages(null);
        try {
            if (mOBContext != null) {
                mOBContext.close();
                mOBContext = null;
            }
        } catch (Exception ignore) {
        }
        super.onDestroy();
    }
}
