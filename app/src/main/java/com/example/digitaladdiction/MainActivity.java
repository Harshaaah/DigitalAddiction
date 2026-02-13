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
import android.net.Uri;
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
import java.util.Calendar;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

//import com.github.mikephil.charting.charts.PieChart;
//import com.github.mikephil.charting.data.PieData;
//import com.github.mikephil.charting.data.PieDataSet;
//import com.github.mikephil.charting.data.PieEntry;
//import com.github.mikephil.charting.utils.ColorTemplate;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import android.app.usage.UsageEvents;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MAIN_ACTIVITY";
    private Handler handler = new Handler(Looper.getMainLooper());

    // Firebase
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    // UI Elements
    private TextView tvUserEmail, tvRiskLevel, tvTotalTime;
    private LinearLayout layoutRisk;
    private Button btnLogout, btnParentSettings, btnWebDashboard;
//    private PieChart pieChart;

    // Logic State
    private long totalDailyUsage = 0;
    private RiskAnalyzer.RiskLevel currentRisk = RiskAnalyzer.RiskLevel.LOW;

    // --- FIX: Variable to store the Real Parent PIN ---
    private double weightedDailyUsage = 0;
    private String parentPin = "1234"; // Default fallback (will be overwritten by Firebase)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button btnWeekly = findViewById(R.id.btnWeeklyReport);


        // --- UPDATED: No PIN required for Weekly Report ---
        btnWeekly.setOnClickListener(v -> {
            // Directly open the activity without asking for PIN
            Intent intent = new Intent(MainActivity.this, WeeklyStatsActivity.class);
            startActivity(intent);
        });
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

        // --- NEW: FETCH REAL PIN FROM FIREBASE ---
        DatabaseReference pinRef = FirebaseDatabase.getInstance().getReference("users")
                .child(currentUser.getUid()).child("settings").child("pin");

        pinRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    parentPin = snapshot.getValue(String.class); // Update variable with Real PIN
                    Log.d(TAG, "Parent PIN Synced: " + parentPin);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) { }
        });
        // -----------------------------------------

        // 3. Bind UI Elements
        tvUserEmail = findViewById(R.id.tvUserEmail);
        tvRiskLevel = findViewById(R.id.tvRiskLevel);
        tvTotalTime = findViewById(R.id.tvTotalTime);
        layoutRisk = findViewById(R.id.layoutRisk);
        btnLogout = findViewById(R.id.btnLogout);
        btnParentSettings = findViewById(R.id.btnParentSettings);
        btnWebDashboard = findViewById(R.id.btnWebDashboard);
//        pieChart = findViewById(R.id.usagePieChart); // Make sure you added this in XML

        tvUserEmail.setText("Account: " + currentUser.getEmail());

        // 4. Set Button Listeners with PIN Protection
        btnLogout.setOnClickListener(v -> showPinDialog(() -> {
            // Stop service on logout
            stopService(new Intent(this, TrackingService.class));
            mAuth.signOut();
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
        }));

        btnParentSettings.setOnClickListener(v -> showPinDialog(() -> {
            Toast.makeText(this, "Parent Settings Unlocked", Toast.LENGTH_SHORT).show();
        }));

        btnWebDashboard.setOnClickListener(v -> showPinDialog(() -> {
            // Pass UID to website for Auto-Login
            String uid = mAuth.getCurrentUser().getUid();
            // REPLACE WITH YOUR REAL HOSTING URL
            String dashboardUrl = "https://digitaladdictiontracker.web.app/dashboard.html?uid=" + uid;

            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl));
            startActivity(browserIntent);
        }));

        // 5. Check Permissions & Start Service
        checkOverlayPermission();

        if (!hasUsagePermission()) {
            Toast.makeText(this, "Please enable 'Usage Access'", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } else {
            startSystemTracking();
            startUIDashboardUpdates();
        }
    }

    // --- STEP A: Start Background Service ---
    private void startSystemTracking() {
        Intent serviceIntent = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    // --- STEP B: Local UI Updates ---
    private void startUIDashboardUpdates() {
        handler.post(uiRunnable);
    }

    private Runnable uiRunnable = new Runnable() {
        @Override
        public void run() {
            refreshDashboard();
            handler.postDelayed(this, 10000); // 10 sec refresh
        }
    };

// Inside MainActivity.java

    private void refreshDashboard() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        PackageManager pm = getPackageManager();

        // Calculate Midnight
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        long startTime = calendar.getTimeInMillis();
        long endTime = System.currentTimeMillis();

        // --- FIX: USE MANUAL CALCULATION ---
        Map<String, Long> preciseDurationMap = calculatePreciseUsage(usm, startTime, endTime);

        totalDailyUsage = 0;

        for (Map.Entry<String, Long> entry : preciseDurationMap.entrySet()) {
            String pkg = entry.getKey();
            long duration = entry.getValue();

            if (duration > 0 && !isSystemApp(pm, pkg)) {
                totalDailyUsage += duration;
            }
        }
        updateRiskUI();
        // updateChartData... (if using chart, pass the map keys/values)
    }

    // Paste the calculatePreciseUsage method here inside MainActivity as well...
    // Copy this updated method into MainActivity.java
    private Map<String, Long> calculatePreciseUsage(UsageStatsManager usm, long startTime, long endTime) {
        Map<String, Long> durationMap = new HashMap<>();
        Map<String, Long> openEvents = new HashMap<>();
        PackageManager pm = getPackageManager();

        // Reset Weighted Score for UI Calculation
        weightedDailyUsage = 0;

        UsageEvents events = usm.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            String pkg = event.getPackageName();

            if (isSystemApp(pm, pkg)) continue;

            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                openEvents.put(pkg, event.getTimeStamp());
            }
            else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (openEvents.containsKey(pkg)) {
                    long start = openEvents.get(pkg);
                    long duration = event.getTimeStamp() - start;

                    // --- WEIGHTED LOGIC FOR UI COLOR ---
                    if (isTimestampNight(start)) {
                        weightedDailyUsage += (duration * 2.5); // Night Multiplier
                    } else {
                        weightedDailyUsage += duration;
                    }
                    // -----------------------------------

                    long currentTotal = durationMap.getOrDefault(pkg, 0L);
                    durationMap.put(pkg, currentTotal + duration);
                    openEvents.remove(pkg);
                }
            }
        }

        // Handle Currently Open Apps
        for (Map.Entry<String, Long> entry : openEvents.entrySet()) {
            String pkg = entry.getKey();
            long start = entry.getValue();
            long duration = endTime - start;

            if (isTimestampNight(start)) {
                weightedDailyUsage += (duration * 2.5);
            } else {
                weightedDailyUsage += duration;
            }

            long currentTotal = durationMap.getOrDefault(pkg, 0L);
            durationMap.put(pkg, currentTotal + duration);
        }

        return durationMap;
    }

    // Don't forget the helper!
    private boolean isTimestampNight(long timestamp) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        return (hour >= 23 || hour < 5);
    }
    // MAKE SURE TO COPY THE UPDATED isSystemApp from TrackingService to MainActivity too!
    private boolean isSystemApp(PackageManager pm, String pkg) {
        if (pkg.contains("youtube") || pkg.contains("chrome") || pkg.contains("whatsapp") ||
                pkg.contains("instagram") || pkg.contains("facebook")) return false;

        // This line is CRITICAL for MainActivity too
        if (pkg.contains("launcher") || pkg.contains("home")) return true;

        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private void updateRiskUI() {
        long hours = totalDailyUsage / (1000 * 60 * 60);
        long minutes = (totalDailyUsage / 1000 / 60) % 60;

        // --- FIX: Use Weighted Score for Color, but Real Time for Text ---
        currentRisk = RiskAnalyzer.calculateRisk((long) weightedDailyUsage);

        runOnUiThread(() -> {
            // Text shows REAL time
            tvTotalTime.setText(hours + "h " + minutes + "m used today");

            // Text shows Risk Level (might be SEVERE even if time is low)
            tvRiskLevel.setText(currentRisk.toString());

            // Color follows the Weighted Risk
            switch (currentRisk) {
                case LOW:
                    layoutRisk.setBackgroundColor(Color.parseColor("#4CAF50"));
                    break;
                case MODERATE:
                    layoutRisk.setBackgroundColor(Color.parseColor("#FF9800"));
                    break;
                case HIGH:
                case SEVERE:
                    layoutRisk.setBackgroundColor(Color.parseColor("#F44336"));
                    break;
            }
        });
    }

    // --- Helper: Pie Chart ---
//    private void updateChartData(List<UsageStats> stats) {
//        if (pieChart == null) return; // Safety check
//
//        long socialTime = 0;
//        long gameTime = 0;
//        long otherTime = 0;
//        PackageManager pm = getPackageManager();
//
//        for (UsageStats usage : stats) {
//            long time = usage.getTotalTimeInForeground();
//            if (time > 0 && !isSystemApp(pm, usage.getPackageName())) {
//                String category = CategoryHelper.getCategory(this, usage.getPackageName());
//                if (category.equals("Social Media")) socialTime += time;
//                else if (category.equals("Games")) gameTime += time;
//                else otherTime += time;
//            }
//        }
//
//        ArrayList<PieEntry> entries = new ArrayList<>();
//        if (socialTime > 0) entries.add(new PieEntry(socialTime, "Social"));
//        if (gameTime > 0) entries.add(new PieEntry(gameTime, "Games"));
//        if (otherTime > 0) entries.add(new PieEntry(otherTime, "Others"));
//
//        if (!entries.isEmpty()) {
//            PieDataSet dataSet = new PieDataSet(entries, "");
//            dataSet.setColors(ColorTemplate.MATERIAL_COLORS);
//            dataSet.setValueTextSize(12f);
//            PieData data = new PieData(dataSet);
//            pieChart.setData(data);
//            pieChart.getDescription().setEnabled(false);
//            pieChart.invalidate();
//        }
//    }

    // --- Helper: PIN Dialog ---
    private void showPinDialog(Runnable onSuccess) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Parent Verification");
        builder.setMessage("Enter Parent PIN");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("Unlock", (dialog, which) -> {
            String enteredPin = input.getText().toString();

            // --- FIX: Compare against parentPin variable (from Firebase) ---
            if (enteredPin.equals(parentPin)) {
                onSuccess.run();
            } else {
                Toast.makeText(MainActivity.this, "Incorrect PIN", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    // --- Permissions ---
    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Enable 'Display Over Other Apps' for Blocking", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private boolean hasUsagePermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

//    private boolean isSystemApp(PackageManager pm, String pkg) {
//        if (pkg.equals("com.google.android.youtube") || pkg.equals("com.android.chrome")) return false;
//        try {
//            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
//            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
//        } catch (PackageManager.NameNotFoundException e) {
//            return true;
//        }
//    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(uiRunnable);
    }
}