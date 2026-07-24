package com.example.smartdriving;

import java.util.UUID;

public class Config {
    // BLE Service and Characteristic UUIDs (Placeholders, can be modified via settings or code)
    // Common custom service/char UUIDs. User will configure these or we auto-connect to devices matching this.
    public static final String DEFAULT_SERVICE_UUID_STRING = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String DEFAULT_RX_CHAR_UUID_STRING = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"; // App Write
    public static final String DEFAULT_CHAR_UUID_STRING = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";    // App Notify (TX from Pi)

    public static final UUID SERVICE_UUID = UUID.fromString(DEFAULT_SERVICE_UUID_STRING);
    public static final UUID CHAR_UUID = UUID.fromString(DEFAULT_CHAR_UUID_STRING);

    // Target device name for optional filtering
    public static final String TARGET_DEVICE_NAME = "SmartDashcam";

    // Scan parameters
    public static final long BLE_SCAN_INTERVAL_MS = 15000; // 15 seconds
    public static final long BLE_SCAN_DURATION_MS = 6000;  // Scan for 6 seconds, then wait 9 seconds (total 15s interval)

    // Storage
    public static final String LOG_FILE_NAME = "driving_logs.json";

    // Notifications
    public static final String NOTIFICATION_CHANNEL_ID = "SmartDriving_Channel";
    public static final int NOTIFICATION_ID = 1204;
}
