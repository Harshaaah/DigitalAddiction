package com.example.digitaladdiction;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TrackingService extends Service {
    // Add this variable at the top of the class (inside TrackingService)
    private boolean hasSentDailyLimitAlert = false; // To prevent spamming the "High Risk" alert

    private static final String TAG = "TrackingService";
    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private Handler handler = new Handler(Looper.getMainLooper());
    private DatabaseReference mDatabase;
    private String currentUserId;

    // Logic Variables
    private Map<String, Long> appSessionStart = new HashMap<>();
    private String currentForegroundApp = "";
    private long lastLateNightAlertTime = 0; // To prevent spamming late night alerts

    @Override
    public void onCreate() {
        super.onCreate();
        // 1. Setup Firebase
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            mDatabase = FirebaseDatabase.getInstance().getReference("users").child(currentUserId).child("usage");
        }

        // 2. Ensure Notification Channel exists
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 3. Create persistent notification for Foreground Service
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Digital Addiction AI")
                .setContentText("Monitoring usage in background...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);

        // 4. Start the Tracking Loop
        handler.post(trackingRunnable);

        return START_STICKY;
    }

    private Runnable trackingRunnable = new Runnable() {
        @Override
        public void run() {
            monitorUsage();
            handler.postDelayed(this, 10000); // Run every 10 seconds
        }
    };

//    private void monitorUsage() {
//        if (currentUserId == null || mDatabase == null) return;
//
//        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
//        long endTime = System.currentTimeMillis();
//
//        // 1. Calculate Midnight (00:00:00) Today for accurate daily stats
//        java.util.Calendar calendar = java.util.Calendar.getInstance();
//        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
//        calendar.set(java.util.Calendar.MINUTE, 0);
//        calendar.set(java.util.Calendar.SECOND, 0);
//        calendar.set(java.util.Calendar.MILLISECOND, 0);
//        long startTime = calendar.getTimeInMillis();
//
//        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime);
//
//        if (stats != null) {
//            long maxTime = 0;
//            String topApp = "";
//            long totalDailyUsage = 0; // To track total time for Daily Limit Alert
//
//            // A. Loop through stats to find Top App AND Calculate Total Usage
//            for (UsageStats usage : stats) {
//                long timeMs = usage.getTotalTimeInForeground();
//
//                // Add to total daily usage (ignoring system apps for accuracy)
//                if (timeMs > 0 && !isSystemApp(getPackageManager(), usage.getPackageName())) {
//                    totalDailyUsage += timeMs;
//                }
//
//                // Find the specific app currently in foreground
//                if (usage.getLastTimeUsed() > maxTime) {
//                    maxTime = usage.getLastTimeUsed();
//                    topApp = usage.getPackageName();
//                }
//            }
//
//            // B. Check Daily Total Limit (Phase 3 Logic)
//            RiskAnalyzer.RiskLevel risk = RiskAnalyzer.calculateRisk(totalDailyUsage);
//
//            // If Risk is HIGH (e.g. > 4 hours) and we haven't alerted yet today
//            if ((risk == RiskAnalyzer.RiskLevel.HIGH || risk == RiskAnalyzer.RiskLevel.SEVERE)
//                    && !hasSentDailyLimitAlert) {
//
//                NotificationHelper.sendRiskAlert(this, risk.toString());
//                hasSentDailyLimitAlert = true; // Mark as sent to prevent spamming
//            }
//
//            // Reset the alert flag if it's a new day (usage < 1 hour)
//            if (totalDailyUsage < 1000 * 60 * 60) {
//                hasSentDailyLimitAlert = false;
//            }
//
//            // C. Late Night Check (Throttled to once every 15 mins)
//            if (RiskAnalyzer.isLateNight()) {
//                long currentTime = System.currentTimeMillis();
//                if (currentTime - lastLateNightAlertTime > (15 * 60 * 1000)) {
//                    NotificationHelper.sendLateNightAlert(this);
//                    lastLateNightAlertTime = currentTime;
//                }
//            }
//
//            // D. Continuous Usage / Binge Logic
//            if (!topApp.isEmpty() && !isSystemApp(getPackageManager(), topApp)) {
//                if (!topApp.equals(currentForegroundApp)) {
//                    // App switched, reset timer
//                    currentForegroundApp = topApp;
//                    appSessionStart.put(topApp, System.currentTimeMillis());
//                } else {
//                    // Same app, check duration
//                    Long start = appSessionStart.get(topApp);
//                    if (start != null) {
//                        long sessionDuration = System.currentTimeMillis() - start;
//
//                        // ALERT: If used for > 1 hour (3600000 ms) continuously
//                        // NOTE: You can change 3600000 to 60000 to test "1 Minute" alerts
//                        if (sessionDuration > 3600000) {
//
//                            // Calculate Hours and Minutes for the message
//                            long totalMinutes = sessionDuration / 60000;
//                            long hrs = totalMinutes / 60;
//                            long mins = totalMinutes % 60;
//                            String timeString = (hrs > 0 ? hrs + " hr " : "") + mins + " min";
//
//                            NotificationHelper.sendBingeAlert(this, getAppName(topApp), timeString);
//
//                            // Reset timer to avoid spam
//                            appSessionStart.put(topApp, System.currentTimeMillis());
//                        }
//                    }
//                }
//            }
//
//            // E. Upload Data to Firebase
//            uploadData(stats);
//        }
//    }
private void monitorUsage() {
    if (currentUserId == null || mDatabase == null) return;

    UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
    long endTime = System.currentTimeMillis();

    // 1. Calculate Midnight (00:00:00) Today for accurate daily stats
    java.util.Calendar calendar = java.util.Calendar.getInstance();
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
    calendar.set(java.util.Calendar.MINUTE, 0);
    calendar.set(java.util.Calendar.SECOND, 0);
    calendar.set(java.util.Calendar.MILLISECOND, 0);
    long startTime = calendar.getTimeInMillis();

    // --- FIX: Use Events to find the REAL Foreground App Instantly ---
    // This solves the issue where notifications only appeared after opening the tracker app
    String instantTopApp = getForegroundApp(usm, endTime);
    // ----------------------------------------------------------------

    List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

    if (stats != null) {
        long totalDailyUsage = 0; // To track total time for Daily Limit Alert

        // A. Calculate Total Usage (Sum of all apps)
        for (UsageStats usage : stats) {
            long timeMs = usage.getTotalTimeInForeground();
            // Add to total daily usage (ignoring system apps for accuracy)
            if (timeMs > 0 && !isSystemApp(getPackageManager(), usage.getPackageName())) {
                totalDailyUsage += timeMs;
            }
        }

        // B. Check Daily Total Limit (Phase 3 Logic)
        RiskAnalyzer.RiskLevel risk = RiskAnalyzer.calculateRisk(totalDailyUsage);

        // If Risk is HIGH (e.g. > 4 hours) and we haven't alerted yet today
        if ((risk == RiskAnalyzer.RiskLevel.HIGH || risk == RiskAnalyzer.RiskLevel.SEVERE)
                && !hasSentDailyLimitAlert) {

            NotificationHelper.sendRiskAlert(this, risk.toString());
            hasSentDailyLimitAlert = true; // Mark as sent to prevent spamming
        }

        // Reset the alert flag if it's a new day (usage < 1 hour)
        if (totalDailyUsage < 1000 * 60 * 60) {
            hasSentDailyLimitAlert = false;
        }

        // C. Late Night Check (Throttled to once every 15 mins)
        if (RiskAnalyzer.isLateNight()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastLateNightAlertTime > (15 * 60 * 1000)) {
                NotificationHelper.sendLateNightAlert(this);
                lastLateNightAlertTime = currentTime;
            }
        }

        // D. Continuous Usage / Binge Logic (Using Instant App Detection)
        if (instantTopApp != null && !instantTopApp.isEmpty() && !isSystemApp(getPackageManager(), instantTopApp)) {

            if (!instantTopApp.equals(currentForegroundApp)) {
                // App switched, reset timer
                currentForegroundApp = instantTopApp;
                appSessionStart.put(instantTopApp, System.currentTimeMillis());
            } else {
                // Same app, check duration
                Long start = appSessionStart.get(instantTopApp);
                if (start != null) {
                    long sessionDuration = System.currentTimeMillis() - start;

                    // ALERT: If used for > 1 hour (3600000 ms) continuously
                    // NOTE: You can change 3600000 to 60000 to test "1 Minute" alerts
                    if (sessionDuration > 3600000) {

                        // Calculate Hours and Minutes for the message
                        long totalMinutes = sessionDuration / 60000;
                        long hrs = totalMinutes / 60;
                        long mins = totalMinutes % 60;
                        String timeString = (hrs > 0 ? hrs + " hr " : "") + mins + " min";

                        NotificationHelper.sendBingeAlert(this, getAppName(instantTopApp), timeString);

                        // Reset timer to avoid spam
                        appSessionStart.put(instantTopApp, System.currentTimeMillis());
                    }
                }
            }
        }

        // E. Upload Data to Firebase
        uploadData(stats);
    }
}

    // --- REQUIRED HELPER METHOD (Paste this inside TrackingService class) ---
    private String getForegroundApp(UsageStatsManager usm, long endTime) {
        // Look back 2 minutes for the latest event
        long startTime = endTime - (1000 * 60 * 2);
        android.app.usage.UsageEvents events = usm.queryEvents(startTime, endTime);
        android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();

        String currentApp = "";

        // Loop through all events to find the LAST "Move to Foreground" event
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentApp = event.getPackageName();
            }
        }
        return currentApp;
    }

    private void uploadData(List<UsageStats> stats) {
        PackageManager pm = getPackageManager();
        String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        for (UsageStats usage : stats) {
            long timeMs = usage.getTotalTimeInForeground();

            if (timeMs > 1000) {
                String pkg = usage.getPackageName();
                if (isSystemApp(pm, pkg)) continue;

                try {
                    String appName = getAppName(pkg);
                    String category = CategoryHelper.getCategory(this, pkg);
                    AppUsageData data = new AppUsageData(pkg, appName, timeMs, category);
                    String firebaseUrlKey = pkg.replace(".", "_");

                    mDatabase.child(dateKey).child(firebaseUrlKey).setValue(data)
                            .addOnFailureListener(e -> Log.e(TAG, "Upload failed: " + e.getMessage()));

                } catch (Exception e) {
                    Log.e(TAG, "Error: " + pkg);
                }
            }
        }
    }

    private boolean isSystemApp(PackageManager pm, String pkg) {
        if (pkg.equals("com.google.android.youtube") || pkg.equals("com.android.chrome")) return false;
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
    public IBinder onBind(Intent intent) {
        return null;
    }
}