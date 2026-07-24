package com.example.smartdriving;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BleManager {
    private static final String TAG = "BleManager";

    public interface BleListener {
        void onConnectionStateChanged(boolean connected);
        void onDataReceived(String jsonStr);
    }

    public String DeviceName = "";

    private final Context context;
    private final BleListener listener;
    
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;

    private boolean isScanning = false;
    private boolean isConnected = false;
    
    // User can customize target UUIDs. Defaulting to Config.
    private UUID serviceUuid = Config.SERVICE_UUID;
    private UUID charUuid = Config.CHAR_UUID; // TX (Pi Notify)
    private UUID rxUuid = UUID.fromString(Config.DEFAULT_RX_CHAR_UUID_STRING); // RX (App Write READY)

    private final Handler handler = new Handler(Looper.getMainLooper());
    
    // Scans periodically: every 15 seconds.
    private final Runnable scanIntervalRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (BleManager.this) {
                if (!isConnected && !isScanning) {
                    startScan();
                }
            }
            handler.postDelayed(this, Config.BLE_SCAN_INTERVAL_MS);
        }
    };

    private final Runnable stopScanRunnable = new Runnable() {
        @Override
        public void run() {
            stopScan();
        }
    };

    public BleManager(Context context, BleListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        initBluetooth();
    }

    private void initBluetooth() {
        bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
    }

    public synchronized void updateUuids(UUID service, UUID characteristic) {
        this.serviceUuid = service;
        this.charUuid = characteristic;
        
        // Derive RX UUID from TX UUID (Nus standard format: TX is 3, RX is 2)
        String txStr = characteristic.toString().toUpperCase();
        String rxStr;
        if (txStr.contains("6E400003")) {
            rxStr = txStr.replace("6E400003", "6E400002");
        } else {
            rxStr = txStr.replace("0003", "0002");
        }
        try {
            this.rxUuid = UUID.fromString(rxStr);
        } catch (Exception e) {
            this.rxUuid = UUID.fromString(Config.DEFAULT_RX_CHAR_UUID_STRING);
        }
        
        Log.d(TAG, "Updated targets - Service: " + serviceUuid + ", Char(TX): " + charUuid + ", Char(RX): " + rxUuid);
        if (isConnected) {
            disconnect();
        }
    }

    public synchronized void startAutoConnect() {
        Log.d(TAG, "Starting Auto-Connect Loop...");
        handler.removeCallbacks(scanIntervalRunnable);
        handler.post(scanIntervalRunnable);
    }

    public synchronized void stopAutoConnect() {
        Log.d(TAG, "Stopping Auto-Connect Loop.");
        handler.removeCallbacks(scanIntervalRunnable);
        handler.removeCallbacks(stopScanRunnable);
        stopScan();
        disconnect();
    }

    public boolean isConnected() {
        return isConnected;
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is disabled or not supported. Cannot scan.");
            return;
        }

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bluetoothLeScanner == null) {
                Log.w(TAG, "Failed to get BLE Scanner.");
                return;
            }
        }

        try {
            isScanning = true;
            Log.d(TAG, "Starting BLE Scan for Service: " + serviceUuid);

            List<ScanFilter> filters = new ArrayList<>();
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(serviceUuid))
                    .build();
            filters.add(filter);

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            bluetoothLeScanner.startScan(filters, settings, scanCallback);
            handler.postDelayed(stopScanRunnable, Config.BLE_SCAN_DURATION_MS);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: Missing Bluetooth permissions for Scan", e);
            isScanning = false;
        } catch (Exception e) {
            Log.e(TAG, "Exception during scan start", e);
            isScanning = false;
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (isScanning && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner.stopScan(scanCallback);
                Log.d(TAG, "BLE Scan stopped.");
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException: Missing Bluetooth permissions for StopScan", e);
            } catch (Exception e) {
                Log.e(TAG, "Exception during scan stop", e);
            } finally {
                isScanning = false;
            }
        }
    }

    @SuppressLint("MissingPermission")
    public synchronized void disconnect() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException e) {
                Log.e(TAG, "Failed to disconnect/close GATT", e);
            }
            bluetoothGatt = null;
        }
        if (isConnected) {
            isConnected = false;
            listener.onConnectionStateChanged(false);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            Log.d(TAG, "Found target device: " + device.getName() + " (" + device.getAddress() + ")");
            DeviceName = device.getName();

            synchronized (BleManager.this) {
                if (isConnected) return;
                stopScan();
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed with error code: " + errorCode);
            synchronized (BleManager.this) {
                isScanning = false;
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        Log.d(TAG, "Connecting to device " + device.getAddress());
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to connectGatt due to permissions", e);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT Server Connected. Requesting 225-byte MTU...");
                try {
                    gatt.requestMtu(225);
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission denied requesting MTU, discovering services directly", e);
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT Server Disconnected.");
                synchronized (BleManager.this) {
                    isConnected = false;
                }
                listener.onConnectionStateChanged(false);
                disconnect();
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU configured: " + mtu + ", status: " + status);
            try {
                gatt.discoverServices();
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied for discoverServices", e);
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "GATT services discovered.");
                BluetoothGattService service = gatt.getService(serviceUuid);
                if (service != null) {
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(charUuid);
                    if (characteristic != null) {
                        // Enable notifications locally
                        gatt.setCharacteristicNotification(characteristic, true);
                        
                        // Write Client Characteristic Configuration Descriptor (CCCD) to enable notifications on peripheral
                        UUID cccUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
                        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(cccUuid);
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                            Log.d(TAG, "Writing CCCD descriptor to enable Notify...");
                        } else {
                            Log.w(TAG, "CCCD descriptor not found.");
                            synchronized (BleManager.this) {
                                isConnected = true;
                            }
                            listener.onConnectionStateChanged(true);
                            Log.i(TAG, "BLE Connected (Descriptor missing but completed).");
                        }
                    } else {
                        Log.w(TAG, "Target characteristic not found: " + charUuid);
                    }
                } else {
                    Log.w(TAG, "Target service not found: " + serviceUuid);
                }
            } else {
                Log.w(TAG, "Service discovery failed with status: " + status);
            }
        }

        @SuppressLint("MissingPermission")
        private void writeReadySignal(BluetoothGatt gatt) {
            if (gatt == null) return;
            BluetoothGattService service = gatt.getService(serviceUuid);
            if (service != null) {
                BluetoothGattCharacteristic rxChar = service.getCharacteristic(rxUuid);
                if (rxChar != null) {
                    byte[] value = "READY".getBytes(StandardCharsets.UTF_8);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(rxChar, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    } else {
                        rxChar.setValue(value);
                        rxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                        gatt.writeCharacteristic(rxChar);
                    }
                    Log.i(TAG, "Sent READY signal to Raspberry Pi RX characteristic.");
                } else {
                    Log.w(TAG, "RX Characteristic not found. Cannot send READY signal.");
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorWrite received. Status: " + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "CCCD descriptor written successfully. BLE Notification fully set up!");
                
                // Write READY signal to Pi to trigger transmission
                writeReadySignal(gatt);

                synchronized (BleManager.this) {
                    isConnected = true;
                }
                listener.onConnectionStateChanged(true);
            } else {
                Log.w(TAG, "CCCD descriptor write failed with status: " + status);
            }
        }

        private void handleReceivedData(BluetoothGattCharacteristic characteristic, byte[] value) {
            if (charUuid.equals(characteristic.getUuid())) {
                if (value != null) {
                    String jsonStr = new String(value, StandardCharsets.UTF_8);
                    Log.d(TAG, "Data payload received: " + jsonStr);
                    listener.onDataReceived(jsonStr);
                }
            }
        }

        // Android 13+ (API 33+) callback signature
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleReceivedData(characteristic, value);
        }

        // Android 12 and below callback signature
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleReceivedData(characteristic, characteristic.getValue());
        }
    };
}
