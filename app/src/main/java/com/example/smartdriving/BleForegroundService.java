package com.example.smartdriving;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BleForegroundService extends Service {
    private static final String TAG = "BleForegroundService";

    public interface ServiceCallback {
        void onConnectionStateChanged(boolean connected);
        void onScoreUpdated(int score);
        void onLogsUpdated(List<LogEntry> logs);
    }

    private final IBinder binder = new LocalBinder();
    private final List<ServiceCallback> callbacks = new ArrayList<>();

    private BleManager bleManager;
    private LocationHelper locationHelper;
    private ScoreManager scoreManager;
    private LogRepository logRepository;
    private ExecutorService executorService;

    private boolean isBleConnected = false;

    public class LocalBinder extends Binder {
        public BleForegroundService getService() {
            return BleForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BleForegroundService onCreate");
        
        executorService = Executors.newSingleThreadExecutor();
        scoreManager = new ScoreManager();
        logRepository = new LogRepository(this);
        locationHelper = new LocationHelper(this);
        
        // Start background/foreground location polling
        locationHelper.startLocationUpdates();

        bleManager = new BleManager(this, new BleManager.BleListener() {
            @Override
            public void onConnectionStateChanged(boolean connected) {
                isBleConnected = connected;
                if (connected) {
                    // Reset score to 100 on connection
                    scoreManager.reset();
                    notifyScoreUpdated(scoreManager.getScore());
                }
                notifyConnectionState(connected);
                updateNotification();
            }

            @Override
            public void onDataReceived(final String jsonStr) {
                // Handle parsing on a background thread
                executorService.execute(() -> parseAndProcessPacket(jsonStr));
            }
        });

        // Begin periodic Bluetooth scan & connect cycle
        bleManager.startAutoConnect();
        
        createNotificationChannel();
        startForeground(Config.NOTIFICATION_ID, buildNotification());
    }

    public String getDeviceName(){
        return bleManager.DeviceName;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "BleForegroundService onStartCommand");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "BleForegroundService onBind");
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "BleForegroundService onDestroy");
        bleManager.stopAutoConnect();
        locationHelper.stopLocationUpdates();
        executorService.shutdown();
    }

    // Callbacks management for UI binding
    public void registerCallback(ServiceCallback callback) {
        synchronized (callbacks) {
            callbacks.add(callback);
            // Immediately dispatch the current state to the newly bound Activity
            callback.onConnectionStateChanged(isBleConnected);
            callback.onScoreUpdated(scoreManager.getScore());
            callback.onLogsUpdated(logRepository.loadLogs());
        }
    }

    public void unregisterCallback(ServiceCallback callback) {
        synchronized (callbacks) {
            callbacks.remove(callback);
        }
    }

    private void notifyConnectionState(boolean connected) {
        synchronized (callbacks) {
            for (ServiceCallback cb : callbacks) {
                cb.onConnectionStateChanged(connected);
            }
        }
    }

    private void notifyScoreUpdated(int score) {
        synchronized (callbacks) {
            for (ServiceCallback cb : callbacks) {
                cb.onScoreUpdated(score);
            }
        }
    }

    private void notifyLogsUpdated(List<LogEntry> logs) {
        synchronized (callbacks) {
            for (ServiceCallback cb : callbacks) {
                cb.onLogsUpdated(logs);
            }
        }
    }

    // Public API for Activities
    public int getScore() {
        return scoreManager.getScore();
    }

    public boolean isConnected() {
        return isBleConnected;
    }

    public List<LogEntry> getLogs() {
        return logRepository.loadLogs();
    }

    public void clearLogs() {
        logRepository.clearLogs();
        notifyLogsUpdated(new ArrayList<>());
    }

    public BleManager getBleManager() {
        return bleManager;
    }

    public void restartLocationUpdates() {
        locationHelper.stopLocationUpdates();
        locationHelper.startLocationUpdates();
    }

    /**
     * Diagnostic hook to simulate BLE packet injection from settings.
     */
    public void injectDummyData(final String jsonStr) {
        executorService.execute(() -> parseAndProcessPacket(jsonStr));
    }

    private void parseAndProcessPacket(String jsonStr) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            double x = obj.optDouble("x", 0.0);
            double z = obj.optDouble("z", 0.0);
            String dateStr = obj.optString("date", "");

            // Read potential events
            List<String> triggeredEvents = new ArrayList<>();
            if (obj.optBoolean("s_braked", false)) triggeredEvents.add("s_braked");
            if (obj.optBoolean("s_accelerated", false) || obj.optBoolean("s_acceleration", false)) triggeredEvents.add("s_accelerated");
            if (obj.optBoolean("s_steered", false)) triggeredEvents.add("s_steered");
            if (obj.optBoolean("waved", false)) triggeredEvents.add("waved");
            if (obj.optBoolean("unstable_speed", false)) triggeredEvents.add("unstable_speed");

            if (triggeredEvents.isEmpty()) {
                // No safety event flag is true. Skip deduction.
                return;
            }

            // Deduct points
            for (String event : triggeredEvents) {
                scoreManager.applyDeduction(event);
            }
            
            // Post UI updates
            new Handler(Looper.getMainLooper()).post(() -> {
                notifyScoreUpdated(scoreManager.getScore());
                updateNotification();
            });

            // Capture GPS position
            Location loc = locationHelper.getLastLocation();
            final double lat = (loc != null) ? loc.getLatitude() : 0.0;
            final double lng = (loc != null) ? loc.getLongitude() : 0.0;

            // Retrieve geocoded locality name (runs blocking network call on this executor thread)
            String placeName = "位置情報取得失敗";
            if (loc != null) {
                placeName = locationHelper.getPlaceName(lat, lng);
            }

            // Append events to log repository
            for (String event : triggeredEvents) {
                LogEntry entry = new LogEntry(
                        dateStr,
                        ScoreManager.getEventLabel(event),
                        placeName,
                        lat,
                        lng,
                        x,
                        z
                );
                logRepository.addLog(entry);
            }

            // Dispatch updated log array to main thread callbacks
            final List<LogEntry> updatedLogs = logRepository.loadLogs();
            new Handler(Looper.getMainLooper()).post(() -> notifyLogsUpdated(updatedLogs));

        } catch (Exception e) {
            Log.e(TAG, "Error processing BLE data JSON packet", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    Config.NOTIFICATION_CHANNEL_ID,
                    "SmartDriving Monitor Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors Raspberry Pi dashcam via BLE and scores safety metrics.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String statusText = isBleConnected ? "接続中(" + bleManager.DeviceName + ")" : "接続待機中";
        String scoreText = "安全運転スコア: " + scoreManager.getScore() + "点";

        return new NotificationCompat.Builder(this, Config.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("SmartDrive")
                .setContentText(statusText + " | " + scoreText)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(Config.NOTIFICATION_ID, buildNotification());
        }
    }
}
