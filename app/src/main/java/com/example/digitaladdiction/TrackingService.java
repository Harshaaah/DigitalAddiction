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
import java.util.List;
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
    // Add this list to store blocked packages dynamically
    private java.util.List<String> blockedAppsList = new java.util.ArrayList<>();
    private boolean hasSentDailyLimitAlert = false;
    private long lastLateNightAlertTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Setup Firebase
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();

            // Reference to upload usage data
            mDatabase = FirebaseDatabase.getInstance().getReference("users").child(currentUserId).child("usage");

            // --- NEW: Reference to LISTEN for Blocked Apps ---
            DatabaseReference restrictionsRef = FirebaseDatabase.getInstance().getReference("users").child(currentUserId).child("restrictions");

            // Attach a Real-Time Listener
            restrictionsRef.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                    // Clear old list
                    blockedAppsList.clear();

                    // Loop through children (e.g., com_facebook: true)
                    for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
                        // If the value is TRUE (Blocked)
                        if (Boolean.TRUE.equals(child.getValue(Boolean.class))) {
                            // Convert Firebase key back to package name (replace _ with .)
                            // Example: com_instagram_android -> com.instagram.android
                            String packageName = child.getKey().replace("_", ".");
                            blockedAppsList.add(packageName);
                            Log.d(TAG, "REMOTE BLOCK ADDED: " + packageName);
                        }
                    }
                }

                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError error) {
                    Log.e(TAG, "Failed to sync restrictions");
                }
            });
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
            handler.postDelayed(this, 10000); // Check every 10 seconds
        }
    };

    // --- CORE MONITORING LOGIC ---
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

        // 2. Instant Detection (Using Events to find current app instantly)
        String instantTopApp = getForegroundApp(usm, endTime);

        // --- FIX PART 1: Detect App Switch (Global) ---
        // This MUST happen before checking if it is a system app.
        // If we switch to "Launcher" (Home Screen), we must record that the previous app stopped.
        if (instantTopApp != null && !instantTopApp.isEmpty()) {
            if (!instantTopApp.equals(currentForegroundApp)) {
                currentForegroundApp = instantTopApp; // Update current tracked app
                appSessionStart.put(currentForegroundApp, System.currentTimeMillis()); // Reset timer for new app
            }
        }
        // ------------------------------------------------------------------

        // 3. Get Aggregated Stats & Launch Counts
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime);
        Map<String, Integer> launchCounts = getLaunchCounts(usm, startTime, endTime);

        if (stats != null) {
            long totalDailyUsage = 0;

            // A. Calculate Total Daily Usage
            for (UsageStats usage : stats) {
                long timeMs = usage.getTotalTimeInForeground();
                // Add to total daily usage (ignoring system apps for accuracy)
                if (timeMs > 0 && !isSystemApp(getPackageManager(), usage.getPackageName())) {
                    totalDailyUsage += timeMs;
                }
            }

            // B. Check Daily Total Limit (e.g. > 4 Hours)
            RiskAnalyzer.RiskLevel risk = RiskAnalyzer.calculateRisk(totalDailyUsage);

            if ((risk == RiskAnalyzer.RiskLevel.HIGH || risk == RiskAnalyzer.RiskLevel.SEVERE)
                    && !hasSentDailyLimitAlert) {
                NotificationHelper.sendRiskAlert(this, risk.toString());
                hasSentDailyLimitAlert = true; // Prevent spamming
            }

            // Reset flag if it's a new day (usage < 1 hour)
            if (totalDailyUsage < 1000 * 60 * 60) {
                hasSentDailyLimitAlert = false;
            }

            // C. Late Night Check
            if (RiskAnalyzer.isLateNight()) {
                long currentTime = System.currentTimeMillis();
                // Throttle alerts to once every 15 minutes
                if (currentTime - lastLateNightAlertTime > (15 * 60 * 1000)) {
                    NotificationHelper.sendLateNightAlert(this);
                    lastLateNightAlertTime = currentTime;
                }
            }

            // --- FIX PART 2: Blocking & Binge Logic (User Apps Only) ---
            // Only run if current app is a USER app (not Launcher/System)
            if (currentForegroundApp != null && !currentForegroundApp.isEmpty()
                    && !isSystemApp(getPackageManager(), currentForegroundApp)) {

                // === 🔴 BLOCKING LOGIC START ===
                // If the app is YouTube or Instagram -> BLOCK IT immediately
//                if (currentForegroundApp.equals("com.google.android.youtube")) {
                // NEW DYNAMIC CODE:
            // Check if the current app exists in our downloaded list
                if (blockedAppsList.contains(currentForegroundApp)) {
                    Log.d("BLOCK_TEST", "Detected Blocked App: " + currentForegroundApp);
                    Intent blockIntent = new Intent(this, BlockScreenActivity.class);
                    // Flags are crucial for Service launching an Activity
                    blockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(blockIntent);

                    return; // ⛔ STOP HERE! Do not count binge time or upload stats for blocked apps.
                }
                // === 🔴 BLOCKING LOGIC END ===

                // Binge Alert Logic
                Long start = appSessionStart.get(currentForegroundApp);
                if (start != null) {
                    long sessionDuration = System.currentTimeMillis() - start;

                    // ALERT: 1 Hour Binge Limit (3600000 ms)
                    // (Note: Change to 60000 if you want to test 1 minute)
                    if (sessionDuration > 3600000) {
                        long totalMinutes = sessionDuration / 60000;
                        long hrs = totalMinutes / 60;
                        long mins = totalMinutes % 60;
                        String timeString = (hrs > 0 ? hrs + " hr " : "") + mins + " min";

                        NotificationHelper.sendBingeAlert(this, getAppName(currentForegroundApp), timeString);

                        // Reset timer to avoid spamming the alert every 10 seconds
                        appSessionStart.put(currentForegroundApp, System.currentTimeMillis());
                    }
                }
            }
            // ----------------------------------------------------

            // E. Upload Data
            uploadData(stats, launchCounts);
        }
    }

    // --- HELPER METHODS ---

    // 1. Get Instant App (Look back 5 mins)
//    private String getForegroundApp(UsageStatsManager usm, long endTime) {
//        long startTime = endTime - (1000 * 60 * 5);
//        UsageEvents events = usm.queryEvents(startTime, endTime);
//        UsageEvents.Event event = new UsageEvents.Event();
//
//        String currentApp = "";
//        while (events.hasNextEvent()) {
//            events.getNextEvent(event);
//            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
//                currentApp = event.getPackageName();
//            }
//        }
//        return currentApp;
//    }
    private String getForegroundApp(UsageStatsManager usm, long endTime) {
        // FIX: Change 5 minutes to 2 HOURS (1000 * 60 * 60 * 2)
        // This ensures we find the app even if it was opened a long time ago.
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
    // 2. Count how many times apps opened today
    private Map<String, Integer> getLaunchCounts(UsageStatsManager usm, long startTime, long endTime) {
        Map<String, Integer> launchCounts = new HashMap<>();
        UsageEvents events = usm.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                String pkg = event.getPackageName();
                int count = launchCounts.getOrDefault(pkg, 0);
                launchCounts.put(pkg, count + 1);
            }
        }
        return launchCounts;
    }

    // 3. Updated Upload Data
    private void uploadData(List<UsageStats> stats, Map<String, Integer> launchCounts) {
        if (mDatabase == null) return;
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

                    // Get Frequency & Time
                    int count = launchCounts.getOrDefault(pkg, 0);
                    long lastUsed = usage.getLastTimeUsed();

                    // Create object with NEW fields
                    AppUsageData data = new AppUsageData(pkg, appName, timeMs, category, count, lastUsed);

                    String firebaseUrlKey = pkg.replace(".", "_");
                    mDatabase.child(dateKey).child(firebaseUrlKey).setValue(data);
                } catch (Exception e) {
                    Log.e(TAG, "Upload error: " + e.getMessage());
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
        } catch (Exception e) {
            return pkg;
        }
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