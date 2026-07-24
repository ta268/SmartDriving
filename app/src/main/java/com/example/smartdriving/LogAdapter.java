package com.example.smartdriving;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {

    public interface OnLogClickListener {
        void onLogClick(LogEntry log);
    }

    private final List<LogEntry> logs = new ArrayList<>();
    private final OnLogClickListener listener;

    public LogAdapter(OnLogClickListener listener) {
        this.listener = listener;
    }

    public void setLogs(List<LogEntry> newLogs) {
        this.logs.clear();
        this.logs.addAll(newLogs);
        // Sort in reverse order (newest first)
        Collections.reverse(this.logs);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(logs.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return logs.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final View eventIndicator;
        private final TextView textEventName;
        private final TextView textLocation;
        private final TextView textDateTime;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            eventIndicator = itemView.findViewById(R.id.viewEventIndicator);
            textEventName = itemView.findViewById(R.id.textEventName);
            textLocation = itemView.findViewById(R.id.textLocation);
            textDateTime = itemView.findViewById(R.id.textDateTime);
        }

        public void bind(final LogEntry log, final OnLogClickListener listener) {
            textEventName.setText(log.getEvent());
            textLocation.setText(log.getLocationName());
            textDateTime.setText(log.getDate());

            // Event-specific visual feedback via color dot indicator
            int color;
            Context context = itemView.getContext();
            String event = log.getEvent();
            
            // Map event labels to colors
            if ("急ブレーキ".equals(event) || "急ハンドル".equals(event)) {
                color = ContextCompat.getColor(context, R.color.score_red);
            } else if ("急加速".equals(event) || "ふらつき".equals(event)) {
                color = ContextCompat.getColor(context, R.color.score_yellow);
            } else {
                color = ContextCompat.getColor(context, R.color.score_yellow); // e.g. unstable speed
            }
            
            eventIndicator.setBackgroundTintList(ColorStateList.valueOf(color));

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onLogClick(log);
                }
            });
        }
    }
}
