package com.example.smartdriving;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private TextView textScore;
    private TextView textStatus;
    private RecyclerView recyclerLogs;
    private LogAdapter logAdapter;

    // Video components
    private FrameLayout videoFrame;
    private VideoView videoView;
    private TextView textVideoPlaceholder;
    private View layoutVideoControls;
    private ImageButton buttonPlayPause;
    private SeekBar seekBarVideo;

    private BleForegroundService service;
    private boolean isBound = false;

    private final Handler seekHandler = new Handler(Looper.getMainLooper());
    private final Runnable seekRunnable = new Runnable() {
        @Override
        public void run() {
            if (videoView != null && videoView.isPlaying()) {
                seekBarVideo.setProgress(videoView.getCurrentPosition());
            }
            seekHandler.postDelayed(this, 250);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            BleForegroundService.LocalBinder localBinder = (BleForegroundService.LocalBinder) binder;
            service = localBinder.getService();
            isBound = true;

            // Bind real-time service updates to UI
            service.registerCallback(serviceCallback);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    private final BleForegroundService.ServiceCallback serviceCallback = new BleForegroundService.ServiceCallback() {
        @Override
        public void onConnectionStateChanged(final boolean connected) {
            runOnUiThread(() -> {
                if(connected){
                    String deviceName = service.getDeviceName();
                    textStatus.setText("ドライブレコーダー\n接続中(" + deviceName + ")");
                }
                else {
                    textStatus.setText("ドライブレコーダー\n接続待機中");
                }
                textStatus.setTextColor(connected ? ContextCompat.getColor(MainActivity.this, R.color.score_green) : ContextCompat.getColor(MainActivity.this, R.color.text_secondary));
            });
        }

        @Override
        public void onScoreUpdated(final int score) {
            runOnUiThread(() -> {
                textScore.setText(String.valueOf(score));
                textScore.setTextColor(ScoreManager.getScoreColor(score));
            });
        }

        @Override
        public void onLogsUpdated(final List<LogEntry> logs) {
            runOnUiThread(() -> logAdapter.setLogs(logs));
        }
    };

    // Permission launch configurations
    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) {
                        allGranted = false;
                        break;
                    }
                }

                if (allGranted) {
                    startAndBindService();
                    checkBackgroundLocationPermission();
                } else {
                    Toast.makeText(this, "BLEスキャンやGPS動作に必要な権限が拒否されました", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> requestBackgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    if (isBound && service != null) {
                        service.restartLocationUpdates();
                    }
                } else {
                    Toast.makeText(this, "バックグラウンド位置情報の取得権限が拒否されました。位置ログが乱れる可能性があります", Toast.LENGTH_LONG).show();
                }
            });

    // Video Pick Launcher
    private final ActivityResultLauncher<String> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    loadAndPlayVideo(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.content.SharedPreferences prefs = getSharedPreferences("SmartDrivingPrefs", MODE_PRIVATE);
        String themeMode = prefs.getString("theme_mode", "system");
        SettingsActivity.applyThemeMode(themeMode);

        setContentView(R.layout.activity_main);

        // Bind Views
        textScore = findViewById(R.id.textScore);
        textStatus = findViewById(R.id.textStatus);
        recyclerLogs = findViewById(R.id.recyclerLogs);

        videoFrame = findViewById(R.id.videoFrame);
        videoView = findViewById(R.id.videoView);
        textVideoPlaceholder = findViewById(R.id.textVideoPlaceholder);
        layoutVideoControls = findViewById(R.id.layoutVideoControls);
        buttonPlayPause = findViewById(R.id.buttonPlayPause);
        seekBarVideo = findViewById(R.id.seekBarVideo);

        ImageButton buttonSettings = findViewById(R.id.buttonSettings);
        Button buttonImportVideo = findViewById(R.id.buttonImportVideo);

        // Set up logs recycler view
        recyclerLogs.setLayoutManager(new LinearLayoutManager(this));
        logAdapter = new LogAdapter(log -> {
            // Tap log row -> navigate to detail activity
            Intent detailIntent = new Intent(MainActivity.this, LogDetailActivity.class);
            detailIntent.putExtra(LogDetailActivity.EXTRA_LOG_ENTRY, log);
            startActivity(detailIntent);
        });
        recyclerLogs.setAdapter(logAdapter);

        // Open settings activity
        buttonSettings.setOnClickListener(v -> {
            Intent settingsIntent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(settingsIntent);
        });

        // Pick local video file
        buttonImportVideo.setOnClickListener(v -> pickVideoLauncher.launch("video/*"));

        // Setup Video View listeners
        setupVideoPlayer();

        // Check primary app permissions, start service
        checkAndRequestPermissions();
    }

    private void setupVideoPlayer() {
        videoView.setOnPreparedListener(mp -> {
            int videoWidth = mp.getVideoWidth();
            int videoHeight = mp.getVideoHeight();
            
            if (videoWidth > 0 && videoHeight > 0) {
                final float videoAspect = (float) videoWidth / videoHeight;
                
                videoFrame.post(() -> {
                    int containerWidth = videoFrame.getWidth();
                    int containerHeight = videoFrame.getHeight();
                    float containerAspect = (float) containerWidth / containerHeight;
                    
                    ViewGroup.LayoutParams lp = videoView.getLayoutParams();
                    if (videoAspect > containerAspect) {
                        // Landscape video: constrain width, adjust height
                        lp.width = containerWidth;
                        lp.height = (int) (containerWidth / videoAspect);
                    } else {
                        // Portrait/Square video: constrain height, adjust width
                        lp.height = containerHeight;
                        lp.width = (int) (containerHeight * videoAspect);
                    }
                    videoView.setLayoutParams(lp);
                });
            }

            seekBarVideo.setMax(videoView.getDuration());
            layoutVideoControls.setVisibility(View.VISIBLE);
            textVideoPlaceholder.setVisibility(View.GONE);

            videoView.start();
            buttonPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            
            // Start updating seekbar progress
            seekHandler.removeCallbacks(seekRunnable);
            seekHandler.post(seekRunnable);
        });

        buttonPlayPause.setOnClickListener(v -> {
            if (videoView.isPlaying()) {
                videoView.pause();
                buttonPlayPause.setImageResource(android.R.drawable.ic_media_play);
            } else {
                videoView.start();
                buttonPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            }
        });

        seekBarVideo.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    videoView.seekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        videoView.setOnCompletionListener(mp -> {
            buttonPlayPause.setImageResource(android.R.drawable.ic_media_play);
            seekBarVideo.setProgress(0);
        });
    }

    private void loadAndPlayVideo(Uri uri) {
        try {
            videoView.setVideoURI(uri);
        } catch (Exception e) {
            Toast.makeText(this, "動画ファイルのロードに失敗しました", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();
        
        // Location is required for scanning BLE and logging GPS
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        // Android 13+ Notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        // Android 12+ BLE Connect & Scan permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
        }

        List<String> permissionsToRequest = new ArrayList<>();
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toArray(new String[0]));
        } else {
            startAndBindService();
            checkBackgroundLocationPermission();
        }
    }

    private void checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                
                // Explain to user why background location is required
                new AlertDialog.Builder(this)
                        .setTitle("常時位置情報の許可が必要です")
                        .setMessage("このアプリはバックグラウンド動作時でも事故や危険運転発生時の位置を記録するため、位置情報の権限設定を「常に許可」にしていただく必要があります。")
                        .setPositiveButton("設定する", (dialog, which) -> {
                            requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                        })
                        .setNegativeButton("キャンセル", null)
                        .show();
            }
        }
    }

    private void startAndBindService() {
        Intent intent = new Intent(this, BleForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isBound && service != null) {
            service.registerCallback(serviceCallback);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isBound && service != null) {
            service.unregisterCallback(serviceCallback);
        }
        seekHandler.removeCallbacks(seekRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}