package com.example.digitaladdiction;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TrackingService extends Service {

    private static final String TAG = "TrackingService";
    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private Handler handler = new Handler(Looper.getMainLooper());
    private DatabaseReference mDatabase;
    private String currentUserId;

    // State Variables
    private Map<String, Long> appSessionStart = new HashMap<>();
    private String currentForegroundApp = "";
    private List<String> blockedAppsList = new ArrayList<>();
    private boolean hasSentDailyLimitAlert = false;
    private long lastLateNightAlertTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            mDatabase = FirebaseDatabase.getInstance().getReference("users").child(currentUserId).child("usage");

            // Listen for Blocked Apps from Cloud
            DatabaseReference restrictionsRef = FirebaseDatabase.getInstance().getReference("users").child(currentUserId).child("restrictions");
            restrictionsRef.addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    blockedAppsList.clear();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        if (Boolean.TRUE.equals(child.getValue(Boolean.class))) {
                            blockedAppsList.add(child.getKey().replace("_", "."));
                        }
                    }
                }
                @Override
                public void onCancelled(DatabaseError error) {}
            });
        }
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start Foreground Service Notification
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Digital Addiction AI")
                .setContentText("Monitoring usage in background...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(1, notification);

        handler.post(trackingRunnable);
        return START_STICKY;
    }

    private Runnable trackingRunnable = new Runnable() {
        @Override
        public void run() {
            monitorUsage();
            handler.postDelayed(this, 10000); // Loop every 10 seconds
        }
    };

    private void monitorUsage() {
        if (currentUserId == null || mDatabase == null) return;

        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long endTime = System.currentTimeMillis();

        // 1. Calculate Midnight (00:00:00 Today)
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startTime = calendar.getTimeInMillis();

        // 2. Instant App Detection (Using UsageEvents)
        String instantTopApp = getForegroundApp(usm, endTime);

        // 3. Detect App Switch (Reset Timer)
        if (instantTopApp != null && !instantTopApp.isEmpty()) {
            if (!instantTopApp.equals(currentForegroundApp)) {
                currentForegroundApp = instantTopApp;
                appSessionStart.put(currentForegroundApp, System.currentTimeMillis());
            }
        }

        // 4. Get Aggregated Stats (Use queryAndAggregate to fix "Yesterday" bug)
        Map<String, UsageStats> statsMap = usm.queryAndAggregateUsageStats(startTime, endTime);
        Map<String, Integer> launchCounts = getLaunchCounts(usm, startTime, endTime);

        if (statsMap != null && !statsMap.isEmpty()) {
            long totalDailyUsage = 0;

            for (UsageStats usage : statsMap.values()) {
                // FILTER: Ignore apps not touched today
                if (usage.getLastTimeUsed() < startTime) continue;

                long timeMs = usage.getTotalTimeInForeground();
                if (timeMs > 0 && !isSystemApp(getPackageManager(), usage.getPackageName())) {
                    totalDailyUsage += timeMs;
                }
            }

            // 5. Risk Analysis
            RiskAnalyzer.RiskLevel risk = RiskAnalyzer.calculateRisk(totalDailyUsage);
            if ((risk == RiskAnalyzer.RiskLevel.HIGH || risk == RiskAnalyzer.RiskLevel.SEVERE)
                    && !hasSentDailyLimitAlert) {
                NotificationHelper.sendRiskAlert(this, risk.toString());
                hasSentDailyLimitAlert = true;
            }
            if (totalDailyUsage < 1000 * 60 * 60) hasSentDailyLimitAlert = false; // Reset flag next day

            // 6. Late Night Alert
            if (RiskAnalyzer.isLateNight()) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLateNightAlertTime > (15 * 60 * 1000)) {
                    NotificationHelper.sendLateNightAlert(this);
                    lastLateNightAlertTime = currentTime;
                }
            }

            // 7. Binge & Blocking Logic
            if (currentForegroundApp != null && !currentForegroundApp.isEmpty()
                    && !isSystemApp(getPackageManager(), currentForegroundApp)) {

                // Blocking
                if (blockedAppsList.contains(currentForegroundApp)) {
                    Intent blockIntent = new Intent(this, BlockScreenActivity.class);
                    blockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(blockIntent);
                    return; // Stop here if blocked
                }

                // Binge Alert
                Long start = appSessionStart.get(currentForegroundApp);
                if (start != null) {
                    long sessionDuration = System.currentTimeMillis() - start;
                    if (sessionDuration > 3600000) { // 1 Hour
                        String timeString = (sessionDuration / 60000) + " mins";
                        NotificationHelper.sendBingeAlert(this, getAppName(currentForegroundApp), timeString);
                        appSessionStart.put(currentForegroundApp, System.currentTimeMillis());
                    }
                }
            }

            // 8. Upload Data
            uploadData(statsMap, launchCounts, startTime);
        }
    }

    // --- HELPER: Upload Logic ---
    private void uploadData(Map<String, UsageStats> statsMap, Map<String, Integer> launchCounts, long midnightTime) {
        if (mDatabase == null) return;
        PackageManager pm = getPackageManager();
        String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        for (UsageStats usage : statsMap.values()) {
            // CRITICAL: Don't upload data from yesterday
            if (usage.getLastTimeUsed() < midnightTime) continue;

            long timeMs = usage.getTotalTimeInForeground();
            if (timeMs > 1000) {
                String pkg = usage.getPackageName();
                if (isSystemApp(pm, pkg)) continue;

                try {
                    String appName = getAppName(pkg);
                    String category = CategoryHelper.getCategory(this, pkg);
                    int count = launchCounts.getOrDefault(pkg, 0);
                    long lastUsed = usage.getLastTimeUsed();

                    AppUsageData data = new AppUsageData(pkg, appName, timeMs, category, count, lastUsed);
                    String firebaseUrlKey = pkg.replace(".", "_");
                    mDatabase.child(dateKey).child(firebaseUrlKey).setValue(data);
                } catch (Exception e) {
                    Log.e(TAG, "Upload error: " + e.getMessage());
                }
            }
        }
    }

    // --- HELPER: Instant Detection ---
    private String getForegroundApp(UsageStatsManager usm, long endTime) {
        long startTime = endTime - (1000 * 60 * 60 * 2); // Look back 2 hours
        UsageEvents events = usm.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();
        String currentApp = "";
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentApp = event.getPackageName();
            }
        }
        return currentApp;
    }

    // --- HELPER: Get Counts ---
    private Map<String, Integer> getLaunchCounts(UsageStatsManager usm, long startTime, long endTime) {
        Map<String, Integer> launchCounts = new HashMap<>();
        UsageEvents events = usm.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                String pkg = event.getPackageName();
                launchCounts.put(pkg, launchCounts.getOrDefault(pkg, 0) + 1);
            }
        }
        return launchCounts;
    }

    // --- HELPER: Filter System Apps ---
    private boolean isSystemApp(PackageManager pm, String pkg) {
        // Whitelist (Always track these)
        if (pkg.contains("youtube") || pkg.contains("chrome") || pkg.contains("whatsapp") ||
                pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("snapchat")) {
            return false;
        }
        // Blacklist (Ignore Launchers/Home Screen to fix "Idle Time" bug)
        if (pkg.contains("launcher") || pkg.contains("home") || pkg.contains("nexus") || pkg.contains("trebuchet")) {
            return true;
        }
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private String getAppName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return (String) pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (Exception e) { return pkg; }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(trackingRunnable);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}