package com.example.smartdriving;

import android.content.Context;
import android.util.Log;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class LogRepository {
    private static final String TAG = "LogRepository";
    private final Context context;
    private final File file;
    private final String masterKeyAlias;

    public LogRepository(Context context) {
        this.context = context.getApplicationContext();
        this.file = new File(context.getFilesDir(), Config.LOG_FILE_NAME);
        try {
            // Android KeyStore initialized master key for encrypting file
            this.masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Android KeyStore master key", e);
            throw new RuntimeException("Could not create master key", e);
        }
    }

    private EncryptedFile getEncryptedFile() throws Exception {
        return new EncryptedFile.Builder(
                file,
                context,
                masterKeyAlias,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build();
    }

    /**
     * Read and decrypt logs from internal secure storage.
     */
    public synchronized List<LogEntry> loadLogs() {
        List<LogEntry> logs = new ArrayList<>();
        if (!file.exists()) {
            return logs;
        }

        try {
            EncryptedFile encryptedFile = getEncryptedFile();
            try (InputStream inputStream = encryptedFile.openFileInput();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                
                byte[] buffer = new byte[1024];
                int size;
                while ((size = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, size);
                }
                
                String jsonStr = outputStream.toString(StandardCharsets.UTF_8.name());
                JSONArray array = new JSONArray(jsonStr);
                for (int i = 0; i < array.length(); i++) {
                    logs.add(LogEntry.fromJSONObject(array.getJSONObject(i)));
                }
                Log.d(TAG, "Successfully loaded " + logs.size() + " logs.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading encrypted logs. File might be corrupted or tampered with.", e);
            // In case of tampering or corruption (e.g. Decryption failure), purge corrupted logs to maintain safety
            file.delete();
        }
        return logs;
    }

    /**
     * Encrypt and save complete logs to internal secure storage.
     */
    public synchronized void saveLogs(List<LogEntry> logs) {
        try {
            // EncryptedFile requires the file to be deleted if we want to overwrite it cleanly,
            // otherwise openFileOutput throws IOException if file already exists on some API versions.
            if (file.exists()) {
                boolean deleted = file.delete();
                if (!deleted) {
                    Log.w(TAG, "Failed to delete existing log file before rewrite");
                }
            }

            JSONArray array = new JSONArray();
            for (LogEntry entry : logs) {
                array.put(entry.toJSONObject());
            }

            EncryptedFile encryptedFile = getEncryptedFile();
            try (OutputStream outputStream = encryptedFile.openFileOutput()) {
                outputStream.write(array.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
            Log.d(TAG, "Saved " + logs.size() + " logs securely.");
        } catch (Exception e) {
            Log.e(TAG, "Error saving encrypted logs", e);
        }
    }

    /**
     * Append a single log entry.
     */
    public synchronized void addLog(LogEntry entry) {
        List<LogEntry> logs = loadLogs();
        logs.add(entry);
        saveLogs(logs);
    }

    /**
     * Clear all logs.
     */
    public synchronized void clearLogs() {
        if (file.exists()) {
            boolean deleted = file.delete();
            Log.d(TAG, "Clear logs. File deleted: " + deleted);
        }
    }
}
