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
    // Stores the "Risk Score" (Time * Multiplier)
    private double weightedDailyUsage = 0;

    // Stores limits in milliseconds (e.g., "com.youtube" -> 1800000)
    private Map<String, Long> appTimeLimits = new HashMap<>();


    // --- NEW VARIABLES FOR BASELINE LOGIC ---
    private Map<String, Long> baselineUsageMap = new HashMap<>(); // Stores usage at the exact time limit was set
    private Map<String, Long> currentKnownLimits = new HashMap<>(); // Helps detect if a parent changed a limit
    // ----------------------------------------

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
//                    for (DataSnapshot child : snapshot.getChildren()) {
//                        if (Boolean.TRUE.equals(child.getValue(Boolean.class))) {
//                            blockedAppsList.add(child.getKey().replace("_", "."));
                    for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
                        // Only get boolean blocks here
                        if (child.getValue() instanceof Boolean && (Boolean) child.getValue()) {
                            blockedAppsList.add(child.getKey().replace("_", "."));
                        }
                    }
                }
                @Override
//                public void onCancelled(DatabaseError error) {}
                public void onCancelled(com.google.firebase.database.DatabaseError error) {}
            });
            // --- 2. NEW: TIME LIMITS LISTENER ---
            DatabaseReference limitsRef = FirebaseDatabase.getInstance().getReference("users").child(currentUserId).child("restrictions").child("limits");
            limitsRef.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                    appTimeLimits.clear();
                    for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
                        String pkg = child.getKey().replace("_", ".");
                        // Firebase stores minutes (Long). Convert to Milliseconds.
                        Long limitMins = child.getValue(Long.class);
                        if (limitMins != null) {
                            appTimeLimits.put(pkg, limitMins * 60 * 1000);
                            Log.d(TAG, "Limit Set: " + pkg + " = " + limitMins + " mins");
                        }
                    }
                }
                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError error) {}
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

        // 2. Instant Detection
        String instantTopApp = getForegroundApp(usm, endTime);

        // 3. Calculate Time AND Weighted Risk
        // (This helper now updates 'weightedDailyUsage' automatically)
        Map<String, Long> preciseDurationMap = calculatePreciseUsage(usm, startTime, endTime);
        Map<String, Integer> launchCounts = getLaunchCounts(usm, startTime, endTime);

        long totalDailyUsage = 0; // Real physical time (for Database)

        // Sum up REAL time for upload/display
        for (Map.Entry<String, Long> entry : preciseDurationMap.entrySet()) {
            String pkg = entry.getKey();
            long duration = entry.getValue();
            // System apps are already filtered in calculatePreciseUsage, but double check doesn't hurt
            if (duration > 0) {
                totalDailyUsage += duration;
            }
        }

        // 4. Risk Alerts (Using WEIGHTED Risk)
        // If user played 2 hours at night, 'weightedDailyUsage' will be 5 hours -> HIGH RISK
        RiskAnalyzer.RiskLevel risk = RiskAnalyzer.calculateRisk((long) weightedDailyUsage);

        if ((risk == RiskAnalyzer.RiskLevel.HIGH || risk == RiskAnalyzer.RiskLevel.SEVERE)
                && !hasSentDailyLimitAlert) {

            String msg = risk.toString();
            // Add context if night usage caused the spike
            if (weightedDailyUsage > totalDailyUsage * 1.5) {
                msg += " (Elevated due to Late Night usage)";
            }

            NotificationHelper.sendRiskAlert(this, msg);
            hasSentDailyLimitAlert = true;
        }

        // Reset flag if usage is low (new day)
        if (totalDailyUsage < 1000 * 60 * 60) hasSentDailyLimitAlert = false;

        // 5. Late Night Check
        if (RiskAnalyzer.isLateNight()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastLateNightAlertTime > (15 * 60 * 1000)) {
                NotificationHelper.sendLateNightAlert(this);
                lastLateNightAlertTime = currentTime;
            }
        }

        // 6. Binge & Blocking Logic
//
//        if (instantTopApp != null && !instantTopApp.isEmpty()
//                && !isSystemApp(getPackageManager(), instantTopApp)) {
//
//            // Blocking
//            if (blockedAppsList.contains(instantTopApp)) {
//                Intent blockIntent = new Intent(this, BlockScreenActivity.class);
//                blockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
//                startActivity(blockIntent);
//                return;
//            }
//
//            // Binge Logic
//            if (!instantTopApp.equals(currentForegroundApp)) {
//                currentForegroundApp = instantTopApp;
//                appSessionStart.put(currentForegroundApp, System.currentTimeMillis());
//            } else {
//                Long start = appSessionStart.get(currentForegroundApp);
//                if (start != null) {
//                    long sessionDuration = System.currentTimeMillis() - start;
//                    // Binge Limit: 30 Mins (1800000)
//                    if (sessionDuration > 1800000) {
//                        String timeString = (sessionDuration / 60000) + " mins";
//                        NotificationHelper.sendBingeAlert(this, getAppName(currentForegroundApp), timeString);
//                        appSessionStart.put(currentForegroundApp, System.currentTimeMillis());
//                    }
//                }
//            }
        // 6. Binge & Blocking Logic
        if (instantTopApp != null && !instantTopApp.isEmpty()
                && !isSystemApp(getPackageManager(), instantTopApp)) {

            boolean shouldBlock = false;

            // --- A. Manual Block Check ---
            if (blockedAppsList.contains(instantTopApp)) {
                shouldBlock = true;
            }

//            // --- B. Time Limit Check (New Feature) ---
//            // Check if there is a specific time limit set for this app
//            if (appTimeLimits.containsKey(instantTopApp)) {
//
//                // Get how much we used this app TODAY (from the map we calculated earlier)
//                long timeUsedToday = 0;
//                if (preciseDurationMap.containsKey(instantTopApp)) {
//                    timeUsedToday = preciseDurationMap.get(instantTopApp);
//                }
//
//                // Get the limit stored in the Map
//                long limit = appTimeLimits.get(instantTopApp);
//
//                // If usage exceeds limit -> Block it
//                if (timeUsedToday > limit) {
//                    Log.d(TAG, "Time Limit Exceeded for: " + instantTopApp);
//                    shouldBlock = true;
//                }
//            }
            // --- B. Time Limit Check (Baseline Logic) ---

            // 1. First, check for NEW or CHANGED limits from the parent
            for (Map.Entry<String, Long> entry : appTimeLimits.entrySet()) {
                String pkg = entry.getKey();
                Long newLimit = entry.getValue();
                Long oldLimit = currentKnownLimits.get(pkg);

                // If the parent just added a limit, OR changed an existing limit
                if (oldLimit == null || !oldLimit.equals(newLimit)) {
                    // Record exactly how much time the child has already used TODAY
                    long currentUsage = preciseDurationMap.getOrDefault(pkg, 0L);

                    // Set this as the "Baseline" (Start counting from 0 from here)
                    baselineUsageMap.put(pkg, currentUsage);
                    currentKnownLimits.put(pkg, newLimit);
                    Log.d(TAG, "New Baseline set for " + pkg + ". Starting new timer.");
                }
            }

            // Clean up old baselines if the parent deleted a limit
            baselineUsageMap.keySet().retainAll(appTimeLimits.keySet());
            currentKnownLimits.keySet().retainAll(appTimeLimits.keySet());

            // 2. Now, enforce the limit for the current app on screen
            if (appTimeLimits.containsKey(instantTopApp)) {

                // Get total used today
                long totalUsedToday = preciseDurationMap.getOrDefault(instantTopApp, 0L);

                // Get the baseline (What was used BEFORE the limit was set)
                long baseline = baselineUsageMap.getOrDefault(instantTopApp, 0L);

                // THE MATH: Total Today - Past Usage = NEW Usage
                long timeUsedSinceLimitSet = totalUsedToday - baseline;
                long limit = appTimeLimits.get(instantTopApp);

                // If the NEW usage exceeds the limit -> Block it
                if (timeUsedSinceLimitSet > limit) {
                    Log.d(TAG, "New Session Time Limit Exceeded for: " + instantTopApp);
                    shouldBlock = true;
                }
            }
            // ---------------------------------------------

            // --- C. Execute Block (If A or B is true) ---
            if (shouldBlock) {
                Intent blockIntent = new Intent(this, BlockScreenActivity.class);
                blockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(blockIntent);

                return; // ⛔ STOP HERE. Do not process binge logic for blocked apps.
            }

            // --- D. Binge Logic (Only runs if App is Allowed) ---

            // 1. Detect App Switch
            // If the app on screen is different from the last tracked app, reset the timer.
            if (!instantTopApp.equals(currentForegroundApp)) {
                currentForegroundApp = instantTopApp;
                appSessionStart.put(currentForegroundApp, System.currentTimeMillis());
            } else {
                // 2. Same App -> Check Session Duration
                Long start = appSessionStart.get(currentForegroundApp);
                if (start != null) {
                    long sessionDuration = System.currentTimeMillis() - start;

                    // Binge Limit: 30 Mins (1800000 ms)
                    // (Change to 60000 for 1-minute testing)
                    if (sessionDuration > 1800000) {
                        String timeString = (sessionDuration / 60000) + " mins";
                        NotificationHelper.sendBingeAlert(this, getAppName(currentForegroundApp), timeString);

                        // Reset timer to prevent spamming the notification every 10 seconds
                        appSessionStart.put(currentForegroundApp, System.currentTimeMillis());
                    }
                }
            }
        }


        // 7. Upload Data (Sends REAL Time, not Weighted Time)
        uploadDataPrecise(preciseDurationMap, launchCounts);
    }
    // --- NEW HELPER: THE MATH ENGINE (Copy this into TrackingService) ---
    private Map<String, Long> calculatePreciseUsage(UsageStatsManager usm, long startTime, long endTime) {
        Map<String, Long> durationMap = new HashMap<>();
        Map<String, Long> openEvents = new HashMap<>();
        PackageManager pm = getPackageManager();

        // Reset Weighted Score for this calculation loop
        weightedDailyUsage = 0;

        UsageEvents events = usm.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            String pkg = event.getPackageName();

            // Filter System Apps IMMEDIATELY (so Launcher doesn't count towards Risk)
            if (isSystemApp(pm, pkg)) continue;

            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                openEvents.put(pkg, event.getTimeStamp());
            }
            else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (openEvents.containsKey(pkg)) {
                    long start = openEvents.get(pkg);
                    long duration = event.getTimeStamp() - start;

                    // --- WEIGHTED LOGIC ---
                    if (isTimestampNight(start)) {
                        weightedDailyUsage += (duration * 2.5); // 2.5x Penalty for Night
                    } else {
                        weightedDailyUsage += duration; // 1.0x Standard
                    }
                    // ----------------------

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

            // Apply Weight to current session too
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

    // Helper to check time window (11 PM - 5 AM)
    private boolean isTimestampNight(long timestamp) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        return (hour >= 23 || hour < 5);
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