package com.example.smartdriving;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends AppCompatActivity {
    private BleForegroundService service;
    private boolean isBound = false;

    private SwitchMaterial switchGpsEnable;
    private Spinner spinnerGpsInterval;
    private Spinner spinnerGpsAccuracy;
    private TextView textPermissionStatus;
    private Button buttonOpenPermissionSettings;

    private SwitchMaterial switchAutoSaveHistory;
    private Spinner spinnerHistoryLimit;
    private Button buttonClearLogs;

    private Spinner spinnerThemeMode;

    private SharedPreferences prefs;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            BleForegroundService.LocalBinder localBinder = (BleForegroundService.LocalBinder) binder;
            service = localBinder.getService();
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("SmartDrivingPrefs", MODE_PRIVATE);

        // Connect to Persistent Service
        Intent intent = new Intent(this, BleForegroundService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);

        initViews();
        setupGpsSettings();
        setupHistorySettings();
        setupThemeSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkLocationPermissionStatus();
    }

    private void initViews() {
        ImageButton buttonBack = findViewById(R.id.buttonBack);
        buttonBack.setOnClickListener(v -> finish());

        switchGpsEnable = findViewById(R.id.switchGpsEnable);
        spinnerGpsInterval = findViewById(R.id.spinnerGpsInterval);
        spinnerGpsAccuracy = findViewById(R.id.spinnerGpsAccuracy);
        textPermissionStatus = findViewById(R.id.textPermissionStatus);
        buttonOpenPermissionSettings = findViewById(R.id.buttonOpenPermissionSettings);

        switchAutoSaveHistory = findViewById(R.id.switchAutoSaveHistory);
        spinnerHistoryLimit = findViewById(R.id.spinnerHistoryLimit);
        buttonClearLogs = findViewById(R.id.buttonClearLogs);

        spinnerThemeMode = findViewById(R.id.spinnerThemeMode);
    }

    private void setupGpsSettings() {
        // GPS Enable switch
        boolean gpsEnabled = prefs.getBoolean("gps_enabled", true);
        switchGpsEnable.setChecked(gpsEnabled);
        switchGpsEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("gps_enabled", isChecked).apply();
            Toast.makeText(this, isChecked ? "GPS機能を有効にしました" : "GPS機能を無効にしました", Toast.LENGTH_SHORT).show();
        });

        // GPS Interval spinner
        setupSpinner(spinnerGpsInterval, R.array.gps_interval_entries, R.array.gps_interval_values, "gps_interval", "2000");

        // GPS Accuracy spinner
        setupSpinner(spinnerGpsAccuracy, R.array.gps_accuracy_entries, R.array.gps_accuracy_values, "gps_accuracy", "high");

        // Open permission settings
        buttonOpenPermissionSettings.setOnClickListener(v -> {
            Intent appSettingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(appSettingsIntent);
        });
    }

    private void checkLocationPermissionStatus() {
        boolean fineGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (fineGranted || coarseGranted) {
            textPermissionStatus.setText("ステータス: 位置情報の利用が許可されています");
        } else {
            textPermissionStatus.setText("ステータス: 権限が未許可です (設定画面で許可してください)");
        }
    }

    private void setupHistorySettings() {
        // Auto Save History switch
        boolean autoSave = prefs.getBoolean("auto_save_history", true);
        switchAutoSaveHistory.setChecked(autoSave);
        switchAutoSaveHistory.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("auto_save_history", isChecked).apply();
            Toast.makeText(this, isChecked ? "走行履歴の自動保存を有効にしました" : "走行履歴の自動保存を無効にしました", Toast.LENGTH_SHORT).show();
        });

        // History Limit spinner
        setupSpinner(spinnerHistoryLimit, R.array.history_limit_entries, R.array.history_limit_values, "history_limit", "50");

        // Clear logs button
        buttonClearLogs.setOnClickListener(v -> {
            if (isBound && service != null) {
                service.clearLogs();
                Toast.makeText(this, "すべての運転ログを削除しました", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "ログ削除を実行しました", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupThemeSettings() {
        String[] entries = getResources().getStringArray(R.array.theme_entries);
        String[] values = getResources().getStringArray(R.array.theme_values);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerThemeMode.setAdapter(adapter);

        String currentTheme = prefs.getString("theme_mode", "system");
        int initialPos = getIndexInArray(values, currentTheme);
        if (initialPos >= 0) {
            spinnerThemeMode.setSelection(initialPos);
        }

        spinnerThemeMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedValue = values[position];
                String savedValue = prefs.getString("theme_mode", "system");

                if (!selectedValue.equals(savedValue)) {
                    prefs.edit().putString("theme_mode", selectedValue).apply();
                    applyThemeMode(selectedValue);
                    recreate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    public static void applyThemeMode(String themeMode) {
        switch (themeMode) {
            case "light":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case "dark":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case "system":
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    private void setupSpinner(Spinner spinner, int entriesResId, int valuesResId, String prefKey, String defaultValue) {
        String[] entries = getResources().getStringArray(entriesResId);
        String[] values = getResources().getStringArray(valuesResId);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        String currentValue = prefs.getString(prefKey, defaultValue);
        int initialPos = getIndexInArray(values, currentValue);
        if (initialPos >= 0) {
            spinner.setSelection(initialPos);
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedValue = values[position];
                prefs.edit().putString(prefKey, selectedValue).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private int getIndexInArray(String[] array, String value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }
}
