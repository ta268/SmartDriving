package com.example.smartdriving;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class LogDetailActivity extends AppCompatActivity {
    public static final String EXTRA_LOG_ENTRY = "extra_log_entry";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_detail);

        ImageButton buttonBack = findViewById(R.id.buttonBack);
        TextView detailEventName = findViewById(R.id.detailEventName);
        TextView detailDate = findViewById(R.id.detailDate);
        TextView detailLocationName = findViewById(R.id.detailLocationName);
        TextView detailCoordinates = findViewById(R.id.detailCoordinates);
        TextView detailGForce = findViewById(R.id.detailGForce);

        buttonBack.setOnClickListener(v -> finish());

        LogEntry log = (LogEntry) getIntent().getSerializableExtra(EXTRA_LOG_ENTRY);
        if (log != null) {
            detailEventName.setText(log.getEvent());
            detailDate.setText(log.getDate());
            detailLocationName.setText(log.getLocationName());
            
            detailCoordinates.setText(String.format(Locale.getDefault(), "%.6f, %.6f", log.getLatitude(), log.getLongitude()));
            detailGForce.setText(String.format(Locale.getDefault(), "X: %.3f G, Z: %.3f G", log.getgX(), log.getgZ()));

            // Visually align event color
            String event = log.getEvent();
            if ("急ブレーキ".equals(event) || "急ハンドル".equals(event)) {
                detailEventName.setTextColor(ContextCompat.getColor(this, R.color.score_red));
            } else {
                detailEventName.setTextColor(ContextCompat.getColor(this, R.color.score_yellow));
            }
        }
    }
}
