package app.botdrop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import com.termux.R;
import com.termux.app.AnalyticsManager;
import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;


import java.io.File;

/**
 * Launcher activity with two phases:
 *
 * Phase 1 (Welcome): Guided permission requests — user taps buttons to grant
 * notification permission and battery optimization exemption, with clear explanations.
 *
 * Phase 2 (Loading): Routes to the appropriate screen based on installation state:
 * 1. If bootstrap not extracted -> Wait for TermuxInstaller
 * 2. If OpenClaw not installed/configured -> SetupActivity (agent -> install -> auth)
 * 3. If channel not configured -> SetupActivity (channel setup)
 * 4. All ready -> DashboardActivity
 */
public class BotDropLauncherActivity extends Activity {

    private static final String LOG_TAG = "BotDropLauncherActivity";
    private static final int REQUEST_CODE_NOTIFICATION_SETTINGS = 1001;
    private static final int REQUEST_CODE_BATTERY_OPTIMIZATION = 1002;
    private static final int REQUEST_CODE_ALL_FILES_ACCESS = 1003;
    private static final String PREFS_NAME = "botdrop_launcher";
    private static final String PREF_ONBOARDING_CONTINUE = "onboarding_continue_clicked";

    // Views
    private View mWelcomeContainer;
    private View mLoadingContainer;
    private TextView mStatusText;
    private Button mNotificationButton;
    private Button mBatteryButton;
    private Button mStorageButton;
    private Button mBackgroundSettingsButton;
    private Button mContinueButton;
    private TextView mNotificationStatus;
    private TextView mBatteryStatus;
    private TextView mStorageStatus;
    private TextView mBackgroundHintText;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mPermissionsPhaseComplete = false;
    private boolean mContinueClickedPersisted = false;
    private boolean mUpdateManagementDisabled;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_launcher);

        mWelcomeContainer = findViewById(R.id.welcome_container);
        mLoadingContainer = findViewById(R.id.loading_container);
        mStatusText = findViewById(R.id.launcher_status_text);
        mNotificationButton = findViewById(R.id.btn_notification_permission);
        mBatteryButton = findViewById(R.id.btn_battery_permission);
        mStorageButton = findViewById(R.id.btn_storage_permission);
        mBackgroundSettingsButton = findViewById(R.id.btn_background_settings);
        mContinueButton = findViewById(R.id.btn_continue);
        mNotificationStatus = findViewById(R.id.notification_status);
        mBatteryStatus = findViewById(R.id.battery_status);
        mStorageStatus = findViewById(R.id.storage_status);
        mBackgroundHintText = findViewById(R.id.background_hint_text);
        mUpdateManagementDisabled = BundledOpenclawUtils.shouldDisableUpdateManagement(this);

        mContinueClickedPersisted = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_ONBOARDING_CONTINUE, false);

        // Upgrade migration: clean deprecated keys from existing OpenClaw config.
        BotDropConfig.sanitizeLegacyConfig();

        // Trigger update check early (results stored for Dashboard to display)
        if (!mUpdateManagementDisabled) {
            UpdateChecker.check(this, null);
        }

        mNotificationButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "launcher_notification_tap");
            openNotificationSettings();
        });
        mBatteryButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "launcher_battery_tap");
            requestBatteryOptimization();
        });
        mStorageButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "launcher_storage_tap");
            requestStoragePermission();
        });
        mBackgroundSettingsButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "launcher_background_tap");
            openAdvancedBackgroundSettings();
        });
        mContinueButton.setOnClickListener(v -> {
            AnalyticsManager.logEvent(this, "launcher_continue_tap");
            mPermissionsPhaseComplete = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ONBOARDING_CONTINUE, true)
                .apply();
            mContinueClickedPersisted = true;
            showLoadingPhase();
            mHandler.postDelayed(this::checkAndRoute, 300);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mPermissionsPhaseComplete || mContinueClickedPersisted) {
            // User has explicitly continued before; proceed automatically.
            showLoadingPhase();
            mHandler.postDelayed(this::checkAndRoute, 300);
            return;
        }

        // Show welcome screen and update permission status. Do not auto-advance:
        // the user must tap Continue to start bootstrap/setup work.
        showWelcomePhase();
        updatePermissionStatus();
        // Continue with cached permission status and setup flow.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    // --- Phase management ---

    private void showWelcomePhase() {
        mWelcomeContainer.setVisibility(View.VISIBLE);
        mLoadingContainer.setVisibility(View.GONE);
        AnalyticsManager.logScreen(this, "launcher_welcome", "BotDropLauncherActivity");
    }

    private void showLoadingPhase() {
        mWelcomeContainer.setVisibility(View.GONE);
        mLoadingContainer.setVisibility(View.VISIBLE);
        AnalyticsManager.logScreen(this, "launcher_loading", "BotDropLauncherActivity");
    }

    // --- Permission checks ---

    private boolean areNotificationsEnabled() {
        return NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private boolean isBatteryOptimizationExempt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true; // Pre-Android M: no battery optimization
    }

    private int getRestrictBackgroundStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
            return cm.getRestrictBackgroundStatus();
        } catch (Exception ignored) {
            return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
        }
    }

    // --- Permission requests ---

    /**
     * Open app notification settings page.
     * targetSdk=28 means requestPermissions(POST_NOTIFICATIONS) is a no-op on Android 13+.
     * Opening the settings page works reliably across all Android versions.
     */
    private void openNotificationSettings() {
        Logger.logInfo(LOG_TAG, "Opening notification settings");
        try {
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("app_package", getPackageName());
                intent.putExtra("app_uid", getApplicationInfo().uid);
            }
            startActivityForResult(intent, REQUEST_CODE_NOTIFICATION_SETTINGS);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to open notification settings: " + e.getMessage());
        }
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Logger.logInfo(LOG_TAG, "Requesting battery optimization exemption");
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_BATTERY_OPTIMIZATION);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to request battery optimization: " + e.getMessage());
            }
        }
    }

    /**
     * Request MANAGE_EXTERNAL_STORAGE permission for Android 11+.
     * This is required for proot to manage Linux rootfs and bot data files.
     */
    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Logger.logInfo(LOG_TAG, "Requesting MANAGE_EXTERNAL_STORAGE permission");
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_ALL_FILES_ACCESS);
            } catch (Exception e) {
                // Some devices don't support the exact package intent, fallback to generic
                Logger.logWarn(LOG_TAG, "Failed to open app-specific storage settings, trying generic: " + e.getMessage());
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_CODE_ALL_FILES_ACCESS);
                } catch (Exception e2) {
                    Logger.logError(LOG_TAG, "Failed to open storage settings: " + e2.getMessage());
                }
            }
        }
    }

    /**
     * Check if storage permission is granted.
     * Android 11+: MANAGE_EXTERNAL_STORAGE via Environment.isExternalStorageManager()
     * Android 10 and below: automatically granted
     */
    private boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    /**
     * Open OEM/system pages that commonly control background throttling.
     * This cannot be fully automated across all OEMs, but we can take the user to the right place quickly.
     */
    private void openAdvancedBackgroundSettings() {
        // 1) If background data is restricted, take user straight to the Data Saver allowlist screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int status = getRestrictBackgroundStatus();
            if (status == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                try {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {
                    // fall through
                }
                try {
                    // ACTION_DATA_SAVER_SETTINGS is not present on some compile SDKs; use literal.
                    startActivity(new Intent("android.settings.DATA_SAVER_SETTINGS"));
                    return;
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }

        // 2) Otherwise, open app details where most ROMs surface Battery: Unrestricted.
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to open app details settings: " + e.getMessage());
        }

        // 3) Last resort: general settings.
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception ignored) {}
    }

    // --- Permission results ---

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_NOTIFICATION_SETTINGS) {
            if (areNotificationsEnabled()) {
                Logger.logInfo(LOG_TAG, "Notifications enabled");
            } else {
                Logger.logWarn(LOG_TAG, "Notifications still disabled");
            }
            updatePermissionStatus();
        } else if (requestCode == REQUEST_CODE_BATTERY_OPTIMIZATION) {
            if (isBatteryOptimizationExempt()) {
                Logger.logInfo(LOG_TAG, "Battery optimization exemption granted");
            } else {
                Logger.logWarn(LOG_TAG, "Battery optimization exemption denied");
            }
            updatePermissionStatus();
        } else if (requestCode == REQUEST_CODE_ALL_FILES_ACCESS) {
            if (isStoragePermissionGranted()) {
                Logger.logInfo(LOG_TAG, "Storage permission granted");
            } else {
                Logger.logWarn(LOG_TAG, "Storage permission denied");
            }
            updatePermissionStatus();
        }
    }

    // --- UI updates ---

    private void updatePermissionStatus() {
        boolean notifGranted = areNotificationsEnabled();
        boolean batteryExempt = isBatteryOptimizationExempt();
        boolean storageGranted = isStoragePermissionGranted();
        int backgroundStatus = getRestrictBackgroundStatus();

        // Notification status
        if (notifGranted) {
            mNotificationStatus.setText("✓");
            mNotificationStatus.setVisibility(View.VISIBLE);
            mNotificationButton.setEnabled(false);
            mNotificationButton.setText(R.string.botdrop_enabled);
        } else {
            mNotificationStatus.setVisibility(View.GONE);
            mNotificationButton.setEnabled(true);
            mNotificationButton.setText(R.string.botdrop_allow);
        }

        // Battery status
        if (batteryExempt) {
            mBatteryStatus.setText("✓");
            mBatteryStatus.setVisibility(View.VISIBLE);
            mBatteryButton.setEnabled(false);
            mBatteryButton.setText(R.string.botdrop_granted);
        } else {
            mBatteryStatus.setVisibility(View.GONE);
            mBatteryButton.setEnabled(true);
            mBatteryButton.setText(R.string.botdrop_allow);
        }

        // Storage status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mStorageButton.setVisibility(View.VISIBLE);
            mStorageStatus.setVisibility(storageGranted ? View.VISIBLE : View.GONE);
            if (storageGranted) {
                mStorageStatus.setText("✓");
                mStorageButton.setEnabled(false);
                mStorageButton.setText(R.string.botdrop_granted);
            } else {
                mStorageButton.setEnabled(true);
                mStorageButton.setText(R.string.botdrop_allow);
            }
        } else {
            // Android 10 and below: storage permission auto-granted, hide the row
            mStorageButton.setVisibility(View.GONE);
            mStorageStatus.setVisibility(View.GONE);
            // Also hide the parent row — find the storage permission LinearLayout
            // by walking up from the button
            View storageRow = (View) mStorageButton.getParent();
            if (storageRow != null) storageRow.setVisibility(View.GONE);
            storageGranted = true;
        }

        // Background hint status (informational)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (backgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                mBackgroundHintText.setText(R.string.botdrop_background_data_restricted);
            } else if (backgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED) {
                mBackgroundHintText.setText(R.string.botdrop_background_data_allowed);
            } else {
                mBackgroundHintText.setText(R.string.botdrop_background_data_hint);
            }
        }

        // Enable continue when all required permissions are granted
        mContinueButton.setEnabled(notifGranted && batteryExempt && storageGranted);
    }

    // --- Routing ---

    private void checkAndRoute() {
        // Check 1: Bootstrap installed?
        if (!BotDropService.isBootstrapInstalled()) {
            Logger.logInfo(LOG_TAG, "Bootstrap not ready, waiting for TermuxInstaller");
            mStatusText.setText(R.string.botdrop_setting_up_environment);

            TermuxInstaller.setupBootstrapIfNeeded(this, this::checkAndRoute);
            return;
        }

        // Check 2: AstrBot installed?
        if (!BotDropService.isAstrBotInstalled()) {
            Logger.logInfo(LOG_TAG, "AstrBot not installed, routing to setup");
            mStatusText.setText(R.string.botdrop_setup_required);

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
            finish();
            return;
        }

        // Check 3: AstrBot configured?
        // AstrBot manages its own config via WebUI. If installed, we assume it's ready.
        // Users configure AI and channels through AstrBot's WebUI (:6185).

        // All ready - go to DashboardActivity
        Logger.logInfo(LOG_TAG, "All ready, routing to dashboard");
        mStatusText.setText(R.string.botdrop_starting_status);

        Intent intent = new Intent(this, DashboardActivity.class);
        startActivity(intent);
        finish();
    }

    private boolean hasChannelConfigured() {
        return ChannelSetupHelper.hasAnyChannelConfigured();
    }
}
