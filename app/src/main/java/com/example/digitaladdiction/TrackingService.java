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
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class TrackingService extends Service {

    private static final String TAG = "TrackingService";
    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private Handler handler = new Handler(Looper.getMainLooper());
    private DatabaseReference mDatabase;
    private String currentUserId;

    // --- LOGIC VARIABLES ---
    private Map<String, Long> appSessionStart = new HashMap<>();
    private String currentForegroundApp = "";
    private java.util.List<String> blockedAppsList = new java.util.ArrayList<>();
    private boolean hasSentDailyLimitAlert = false;
    private long lastLateNightAlertTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            mDatabase = FirebaseDatabase.getInstance().getReference("users").child(currentUserId).child("usage");

            DatabaseReference restrictionsRef = FirebaseDatabase.getInstance().getReference("users").child(currentUserId).child("restrictions");
            restrictionsRef.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                    blockedAppsList.clear();
                    for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
                        if (Boolean.TRUE.equals(child.getValue(Boolean.class))) {
                            String packageName = child.getKey().replace("_", ".");
                            blockedAppsList.add(packageName);
                        }
                    }
                }
                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError error) { }
            });
        }
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
            handler.postDelayed(this, 10000); // Check every 10 seconds
        }
    };

    // --- CORE MONITORING LOGIC ---
    private void monitorUsage() {
        if (currentUserId == null || mDatabase == null) return;

        UsageStatsManager usm =
                (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);

        long now = System.currentTimeMillis();

        // 1️⃣ Get today's midnight
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();

        // 2️⃣ Read usage EVENTS (not stats)
        UsageEvents events = usm.queryEvents(todayStart, now);
        UsageEvents.Event event = new UsageEvents.Event();

        Map<String, Long> sessionStartMap = new HashMap<>();
        long totalTodayUsage = 0;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);

            String pkg = event.getPackageName();
            int type = event.getEventType();

            if (isSystemApp(getPackageManager(), pkg)) continue;

            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                // App opened
                sessionStartMap.put(pkg, event.getTimeStamp());
            }

            if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                // App closed
                Long start = sessionStartMap.get(pkg);
                if (start != null) {
                    long sessionTime = event.getTimeStamp() - start;

                    if (sessionTime > 0) {
                        totalTodayUsage += sessionTime;
                    }

                    sessionStartMap.remove(pkg);
                }
            }
        }

        // 3️⃣ Risk Calculation (NOW ACCURATE)
        RiskAnalyzer.RiskLevel risk =
                RiskAnalyzer.calculateRisk(totalTodayUsage);

        if ((risk == RiskAnalyzer.RiskLevel.HIGH ||
                risk == RiskAnalyzer.RiskLevel.SEVERE)
                && !hasSentDailyLimitAlert) {

            NotificationHelper.sendRiskAlert(this, risk.toString());
            hasSentDailyLimitAlert = true;
        }

        // Reset alert flag next day
        if (totalTodayUsage < 60 * 60 * 1000) {
            hasSentDailyLimitAlert = false;
        }

        // 4️⃣ Late Night Check
        if (RiskAnalyzer.isLateNight()) {
            long current = System.currentTimeMillis();
            if (current - lastLateNightAlertTime > 15 * 60 * 1000) {
                NotificationHelper.sendLateNightAlert(this);
                lastLateNightAlertTime = current;
            }
        }

        // 5️⃣ Upload ACCURATE daily usage
        uploadAccurateUsage(totalTodayUsage);
    }

    private void uploadAccurateUsage(long totalUsageMs) {
        if (mDatabase == null) return;

        String dateKey =
                new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        .format(new Date());

        Map<String, Object> data = new HashMap<>();
        data.put("totalUsageMs", totalUsageMs);
        data.put("timestamp", System.currentTimeMillis());

        mDatabase.child(dateKey).child("summary").setValue(data);
    }

    // --- UPDATED UPLOAD METHOD (Use this instead of the List one) ---
    private void uploadDataMap(Map<String, UsageStats> statsMap, Map<String, Integer> launchCounts, long midnightTime) {
        if (mDatabase == null) return;
        PackageManager pm = getPackageManager();
        String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        for (UsageStats usage : statsMap.values()) {

            // --- CRITICAL FILTER FOR DATABASE ---
            // Do not upload if LastTimeUsed is before Midnight
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

                    // Upload to Today's Folder
                    mDatabase.child(dateKey).child(firebaseUrlKey).setValue(data);
                } catch (Exception e) {
                    Log.e(TAG, "Upload error: " + e.getMessage());
                }
            }
        }
    }
    // --- HELPER METHODS ---
    // NEW Upload Method for Map data
    private void uploadDataMap(java.util.Map<String, android.app.usage.UsageStats> statsMap, Map<String, Integer> launchCounts) {
        if (mDatabase == null) return;
        PackageManager pm = getPackageManager();
        String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        for (android.app.usage.UsageStats usage : statsMap.values()) {
            long timeMs = usage.getTotalTimeInForeground();

            // Filter > 1 sec and NOT System Apps
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

    // Updated uploadData to accept Map<String, UsageStats>
    private void uploadData(Map<String, UsageStats> statsMap, Map<String, Integer> launchCounts) {
        if (mDatabase == null) return;
        PackageManager pm = getPackageManager();
        String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        for (UsageStats usage : statsMap.values()) {
            long timeMs = usage.getTotalTimeInForeground();

            // Filter > 1 sec usage
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

    private String getForegroundApp(UsageStatsManager usm, long endTime) {
        long startTime = endTime - (1000 * 60 * 60 * 2);
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

//    private boolean isSystemApp(PackageManager pm, String pkg) {
//        if (pkg.contains("youtube") || pkg.contains("chrome") || pkg.contains("whatsapp") ||
//                pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("snapchat")) {
//            return false;
//        }
//        // Exclude Launchers explicitly to avoid counting Home Screen time
//        if (pkg.contains("launcher") || pkg.contains("home")) {
//            return true;
//        }
//        try {
//            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
//            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
//        } catch (PackageManager.NameNotFoundException e) {
//            return true;
//        }
//    }
private boolean isSystemApp(PackageManager pm, String pkg) {
    // Whitelist...
    if (pkg.contains("youtube") || pkg.contains("chrome") || pkg.contains("whatsapp") ||
            pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("snapchat")) {
        return false;
    }

    // --- CRITICAL FILTER FOR HOME SCREEN ---
    // If this is missing, "Phone Idle" counts as "Phone Usage"
    if (pkg.contains("launcher") || pkg.contains("home") || pkg.contains("android.systemui")) {
        return true;
    }
    // ---------------------------------------

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