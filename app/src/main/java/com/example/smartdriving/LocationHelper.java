package com.example.smartdriving;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class LocationHelper {
    private static final String TAG = "LocationHelper";
    private final Context context;
    private final LocationManager locationManager;
    private Location lastLocation = null;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLocation = location;
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    public LocationHelper(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Start requesting location updates using configured preferences.
     * Permission checks must be handled by the caller.
     */
    @SuppressLint("MissingPermission")
    public void startLocationUpdates() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("SmartDrivingPrefs", Context.MODE_PRIVATE);
            boolean isGpsFeatureEnabled = prefs.getBoolean("gps_enabled", true);
            if (!isGpsFeatureEnabled) {
                Log.d(TAG, "GPS location updates disabled in settings.");
                return;
            }

            long minTimeMs = Long.parseLong(prefs.getString("gps_interval", "2000"));
            String accuracyMode = prefs.getString("gps_accuracy", "high");

            boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            boolean useGps = ("high".equals(accuracyMode) || "medium".equals(accuracyMode)) && isGpsEnabled;
            boolean useNetwork = ("high".equals(accuracyMode) || "low".equals(accuracyMode)) && isNetworkEnabled;

            if (useGps) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, 5, locationListener);
                lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (useNetwork) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTimeMs, 5, locationListener);
                if (lastLocation == null) {
                    lastLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
            }
            Log.d(TAG, "Location updates started with interval: " + minTimeMs + "ms, mode: " + accuracyMode);
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing for starting updates", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start location updates", e);
        }
    }

    /**
     * Stop requesting location updates to save battery.
     */
    public void stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener);
            Log.d(TAG, "Location updates stopped.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop location updates", e);
        }
    }

    /**
     * Get the latest retrieved location.
     */
    public Location getLastLocation() {
        return lastLocation;
    }

    /**
     * Reverse geocodes a location to a human readable city/street name.
     * Must be run on a worker thread if called in synchronous context, as Geocoder network requests can block.
     */
    public String getPlaceName(double latitude, double longitude) {
        Geocoder geocoder = new Geocoder(context, Locale.getDefault());
        try {
            // Geocoder.getFromLocation(double, double, int) is blocking.
            List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address address = addresses.get(0);
                
                String adminArea = address.getAdminArea();     // Prefecture (e.g. 東京都)
                String locality = address.getLocality();       // City (e.g. 新宿区)
                String subLocality = address.getSubLocality(); // Town (e.g. 西新宿)
                
                StringBuilder place = new StringBuilder();
                if (adminArea != null) place.append(adminArea);
                if (locality != null) place.append(locality);
                if (subLocality != null) place.append(subLocality);
                
                if (place.length() > 0) {
                    return place.toString();
                } else {
                    String feature = address.getFeatureName();
                    if (feature != null) {
                        return feature;
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Network or Geocoder service unavailable", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to geocode location", e);
        }
        // Fallback representation
        return String.format(Locale.getDefault(), "経緯度: %.4f, %.4f", latitude, longitude);
    }
}
