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

    // --- UPDATED MONITOR USAGE ---
    private void monitorUsage() {
        if (currentUserId == null || mDatabase == null) return;

        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long endTime = System.currentTimeMillis();

        // 1. Calculate Midnight
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        long startTime = calendar.getTimeInMillis();

        // 2. Instant Detection (For Blocking/Binging)
        String instantTopApp = getForegroundApp(usm, endTime);

        // --- FIX: USE PRECISE EVENT CALCULATION ---
        // We calculate time manually to avoid "Yesterday's Data" bugs
        Map<String, Long> preciseDurationMap = calculatePreciseUsage(usm, startTime, endTime);
        Map<String, Integer> launchCounts = getLaunchCounts(usm, startTime, endTime);

        long totalDailyUsage = 0;

        // Iterate through our manually calculated list
        for (Map.Entry<String, Long> entry : preciseDurationMap.entrySet()) {
            String pkg = entry.getKey();
            long duration = entry.getValue();

            if (duration > 0 && !isSystemApp(getPackageManager(), pkg)) {
                totalDailyUsage += duration;
            }
        }

        // 3. Risk Alerts (Based on the new accurate Total)
        RiskAnalyzer.RiskLevel risk = RiskAnalyzer.calculateRisk(totalDailyUsage);
        if ((risk == RiskAnalyzer.RiskLevel.HIGH || risk == RiskAnalyzer.RiskLevel.SEVERE)
                && !hasSentDailyLimitAlert) {
            NotificationHelper.sendRiskAlert(this, risk.toString());
            hasSentDailyLimitAlert = true;
        }
        if (totalDailyUsage < 1000 * 60 * 60) hasSentDailyLimitAlert = false;

        // 4. Late Night Check
        if (RiskAnalyzer.isLateNight()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastLateNightAlertTime > (15 * 60 * 1000)) {
                NotificationHelper.sendLateNightAlert(this);
                lastLateNightAlertTime = currentTime;
            }
        }

        // 5. Binge & Blocking Logic
        if (instantTopApp != null && !instantTopApp.isEmpty()
                && !isSystemApp(getPackageManager(), instantTopApp)) {

            // Blocking
            if (blockedAppsList.contains(instantTopApp)) {
                Intent blockIntent = new Intent(this, BlockScreenActivity.class);
                blockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(blockIntent);
                return;
            }

            // Binge Logic (Detect App Switch)
            if (!instantTopApp.equals(currentForegroundApp)) {
                currentForegroundApp = instantTopApp;
                appSessionStart.put(currentForegroundApp, System.currentTimeMillis());
            } else {
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
        }

        // 6. Upload Data (Using the new Precise Map)
        uploadDataPrecise(preciseDurationMap, launchCounts);
    }

    // --- NEW HELPER: THE MATH ENGINE (Copy this into TrackingService) ---
    private Map<String, Long> calculatePreciseUsage(UsageStatsManager usm, long startTime, long endTime) {
        Map<String, Long> durationMap = new HashMap<>();
        Map<String, Long> openEvents = new HashMap<>();

        // Query EVENTS (Logs), not STATS (Buckets)
        UsageEvents events = usm.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            String pkg = event.getPackageName();

            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                // App opened: Record Start Time
                openEvents.put(pkg, event.getTimeStamp());
            }
            else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                // App closed: Calculate Duration
                if (openEvents.containsKey(pkg)) {
                    long start = openEvents.get(pkg);
                    long duration = event.getTimeStamp() - start;

                    // Add to total duration map
                    long currentTotal = durationMap.getOrDefault(pkg, 0L);
                    durationMap.put(pkg, currentTotal + duration);

                    // Remove from open list
                    openEvents.remove(pkg);
                }
            }
        }

        // Handle apps that are CURRENTLY open (No "Background" event yet)
        for (Map.Entry<String, Long> entry : openEvents.entrySet()) {
            String pkg = entry.getKey();
            long start = entry.getValue();
            long duration = endTime - start; // Time until now

            long currentTotal = durationMap.getOrDefault(pkg, 0L);
            durationMap.put(pkg, currentTotal + duration);
        }

        return durationMap;
    }

    // --- NEW UPLOAD HELPER (Compatible with new Map) ---
    private void uploadDataPrecise(Map<String, Long> durationMap, Map<String, Integer> launchCounts) {
        if (mDatabase == null) return;
        PackageManager pm = getPackageManager();
        String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        for (Map.Entry<String, Long> entry : durationMap.entrySet()) {
            String pkg = entry.getKey();
            long timeMs = entry.getValue();

            // Filter small usage and system apps
            if (timeMs > 1000 && !isSystemApp(pm, pkg)) {
                try {
                    String appName = getAppName(pkg);
                    String category = CategoryHelper.getCategory(this, pkg);
                    int count = launchCounts.getOrDefault(pkg, 0);
                    // LastUsed is not easily available in Events, using current time approx
                    long lastUsed = System.currentTimeMillis();

                    AppUsageData data = new AppUsageData(pkg, appName, timeMs, category, count, lastUsed);
                    String firebaseUrlKey = pkg.replace(".", "_");
                    mDatabase.child(dateKey).child(firebaseUrlKey).setValue(data);
                } catch (Exception e) {
                    Log.e(TAG, "Upload error: " + e.getMessage());
                }
            }
        }
    }

    // --- HELPER: Upload Logic ---


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