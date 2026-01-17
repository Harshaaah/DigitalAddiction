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

    // Continuous Usage Tracking
    private Map<String, Long> appSessionStart = new HashMap<>(); // When did they start using this app?
    private String currentForegroundApp = "";

    @Override
    public void onCreate() {
        super.onCreate();
        // Setup Firebase
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            mDatabase = FirebaseDatabase.getInstance().getReference("users").child(currentUserId).child("usage");
        }

        // Ensure Notification Channel exists
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. Create the persistent notification
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Digital Addiction AI")
                .setContentText("Monitoring usage in background...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .build();

        // 2. Start Service in Foreground (Android won't kill it easily)
        startForeground(1, notification);

        // 3. Start the Loop
        handler.post(trackingRunnable);

        return START_STICKY; // If killed, restart automatically
    }

    private Runnable trackingRunnable = new Runnable() {
        @Override
        public void run() {
            monitorUsage();
            handler.postDelayed(this, 10000); // Check every 10 seconds for continuous alerts
        }
    };

    private void monitorUsage() {
        if (currentUserId == null) return;

        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long endTime = System.currentTimeMillis();
        long startTime = endTime - (1000 * 60 * 60 * 24); // 24 hours

        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

        if (stats != null) {
            long maxTime = 0;
            String topApp = "";

            // 1. Find which app is CURRENTLY in foreground (based on LastTimeUsed)
            for (UsageStats usage : stats) {
                if (usage.getLastTimeUsed() > maxTime) {
                    maxTime = usage.getLastTimeUsed();
                    topApp = usage.getPackageName();
                }
            }

            // 2. Continuous Usage Logic
            if (!topApp.isEmpty() && IsInterestingApp(topApp)) {
                // If app changed, reset session
                if (!topApp.equals(currentForegroundApp)) {
                    currentForegroundApp = topApp;
                    appSessionStart.put(topApp, System.currentTimeMillis());
                } else {
                    // Same app, check duration
                    Long start = appSessionStart.get(topApp);
                    if (start != null) {
                        long sessionDuration = System.currentTimeMillis() - start;

                        // ALERT: If used for > 1 hour (3600000 ms) continuously
                        if (sessionDuration > 3600000) {
                            NotificationHelper.sendRiskAlert(this, "1 Hour Break Needed! You are using " + getAppName(topApp) + " too long.");
                            // Reset timer so we don't spam every second (or set a flag)
                            appSessionStart.put(topApp, System.currentTimeMillis());
                        }
                    }
                }
            }

            // 3. Upload Data (Standard Phase 2 Logic)
            // (Only upload every minute to save data, not every 10s)
            // For simplicity, we just put the upload logic here or call a separate method
            uploadData(stats);
        }
    }

    private void uploadData(List<UsageStats> stats) {
        // ... (Paste your Phase 2 Upload Logic Here) ...
        // Ensure you check if Internet is available before uploading
        // For now, Firebase handles simple offline queuing automatically
    }

    private boolean IsInterestingApp(String pkg) {
        return !pkg.equals("com.android.systemui") && !pkg.contains("launcher");
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
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
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