package com.example.smartdriving;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.Serializable;

public class LogEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    private String date;
    private String event;
    private String locationName;
    private double latitude;
    private double longitude;
    private double gX;
    private double gZ;

    public LogEntry(String date, String event, String locationName, double latitude, double longitude, double gX, double gZ) {
        this.date = date;
        this.event = event;
        this.locationName = locationName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.gX = gX;
        this.gZ = gZ;
    }

    public String getDate() {
        return date;
    }

    public String getEvent() {
        return event;
    }

    public String getLocationName() {
        return locationName;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getgX() {
        return gX;
    }

    public double getgZ() {
        return gZ;
    }

    public JSONObject toJSONObject() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("date", date);
        obj.put("event", event);
        obj.put("locationName", locationName);
        obj.put("latitude", latitude);
        obj.put("longitude", longitude);
        obj.put("gX", gX);
        obj.put("gZ", gZ);
        return obj;
    }

    public static LogEntry fromJSONObject(JSONObject obj) throws JSONException {
        return new LogEntry(
            obj.getString("date"),
            obj.getString("event"),
            obj.getString("locationName"),
            obj.getDouble("latitude"),
            obj.getDouble("longitude"),
            obj.getDouble("gX"),
            obj.getDouble("gZ")
        );
    }
}
