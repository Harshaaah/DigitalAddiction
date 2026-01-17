package com.example.digitaladdiction;

import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MAIN_ACTIVITY";
    private Handler handler = new Handler(Looper.getMainLooper());

    // Firebase
    private FirebaseAuth mAuth;

    // UI Elements
    private TextView tvUserEmail, tvRiskLevel, tvTotalTime;
    private LinearLayout layoutRisk;
    private Button btnLogout, btnParentSettings;

    // Logic State
    private long totalDailyUsage = 0;
    private RiskAnalyzer.RiskLevel currentRisk = RiskAnalyzer.RiskLevel.LOW;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Initialize Notification Channel
        NotificationHelper.createNotificationChannel(this);

        // 2. Setup Firebase Auth
        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();

        if (currentUser == null) {
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
            return;
        }

        // 3. Bind UI Elements
        tvUserEmail = findViewById(R.id.tvUserEmail);
        tvRiskLevel = findViewById(R.id.tvRiskLevel);
        tvTotalTime = findViewById(R.id.tvTotalTime);
        layoutRisk = findViewById(R.id.layoutRisk);
        btnLogout = findViewById(R.id.btnLogout);
        btnParentSettings = findViewById(R.id.btnParentSettings);

        tvUserEmail.setText("Account: " + currentUser.getEmail());

        // 4. Set Button Listeners with PIN Protection
        btnLogout.setOnClickListener(v -> showPinDialog(() -> {
            // Stop service on logout so we don't track the next user
            stopService(new Intent(this, TrackingService.class));

            mAuth.signOut();
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
        }));

        btnParentSettings.setOnClickListener(v -> showPinDialog(() -> {
            Toast.makeText(this, "Parent Settings Unlocked", Toast.LENGTH_SHORT).show();
        }));

        // 5. Check Permissions & Start Service
        if (!hasUsagePermission()) {
            Toast.makeText(this, "Please grant usage permission", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } else {
            // STEP A: Start the Background Service (The Real Tracker)
            startSystemTracking();

            // STEP B: Start UI Updates (Just for visuals)
            startUIDashboardUpdates();
        }
    }

    // --- STEP A: Start the Background Service ---
    private void startSystemTracking() {
        Intent serviceIntent = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    // --- STEP B: Local UI Dashboard Updates ---
    private void startUIDashboardUpdates() {
        handler.post(uiRunnable);
    }

    // This Runnable ONLY updates the screen. It does NOT upload to Firebase.
    // The Service handles uploads now.
    private Runnable uiRunnable = new Runnable() {
        @Override
        public void run() {
            refreshDashboard();
            handler.postDelayed(this, 10000); // Refresh UI every 10 seconds
        }
    };

    private void refreshDashboard() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        PackageManager pm = getPackageManager();

        long endTime = System.currentTimeMillis();
        long startTime = endTime - (1000 * 60 * 60 * 24); // Last 24 hours

        totalDailyUsage = 0;

        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

        if (stats != null) {
            for (UsageStats usage : stats) {
                long timeMs = usage.getTotalTimeInForeground();
                if (timeMs > 1000) {
                    // Filter system apps just for the total calculation
                    if (!isSystemApp(pm, usage.getPackageName())) {
                        totalDailyUsage += timeMs;
                    }
                }
            }
            // Update the colored card
            updateRiskUI();
        }
    }

    private void updateRiskUI() {
        long hours = totalDailyUsage / (1000 * 60 * 60);
        long minutes = (totalDailyUsage / 1000 / 60) % 60;

        currentRisk = RiskAnalyzer.calculateRisk(totalDailyUsage);

        runOnUiThread(() -> {
            tvTotalTime.setText(hours + "h " + minutes + "m used today");
            tvRiskLevel.setText(currentRisk.toString());

            switch (currentRisk) {
                case LOW:
                    layoutRisk.setBackgroundColor(Color.parseColor("#4CAF50")); // Green
                    break;
                case MODERATE:
                    layoutRisk.setBackgroundColor(Color.parseColor("#FF9800")); // Orange
                    break;
                case HIGH:
                case SEVERE:
                    layoutRisk.setBackgroundColor(Color.parseColor("#F44336")); // Red
                    break;
            }
        });
    }

    // --- Helpers ---

    private void showPinDialog(Runnable onSuccess) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Parent Verification");
        builder.setMessage("Enter Parent PIN (Default: 1234)");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("Unlock", (dialog, which) -> {
            String pin = input.getText().toString();
            if (pin.equals("1234")) {
                onSuccess.run();
            } else {
                Toast.makeText(MainActivity.this, "Incorrect PIN", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private boolean isSystemApp(PackageManager pm, String pkg) {
        if (pkg.equals("com.google.android.youtube") || pkg.equals("com.android.chrome")) {
            return false;
        }
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private boolean hasUsagePermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                getPackageName()
        );
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(uiRunnable);
        // Note: We do NOT stop the Service here. We want it to run even if the app closes.
    }
}