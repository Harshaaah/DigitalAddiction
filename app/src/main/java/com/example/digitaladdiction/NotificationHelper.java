//package com.example.digitaladdiction;
//
//import android.app.NotificationChannel;
//import android.app.NotificationManager;
//import android.content.Context;
//import android.os.Build;
//import androidx.core.app.NotificationCompat;
//
//public class NotificationHelper {
//
//    private static final String CHANNEL_ID = "addiction_alerts";
//    private static final String CHANNEL_NAME = "Addiction Alerts";
//
//    // Unique IDs to ensure notifications don't overwrite each other
//    private static final int NOTIF_ID_RISK = 1001;
//    private static final int NOTIF_ID_NIGHT = 1002;
//    private static final int NOTIF_ID_BINGE = 1003;
//
//    public static void createNotificationChannel(Context context) {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            NotificationChannel channel = new NotificationChannel(
//                    CHANNEL_ID,
//                    CHANNEL_NAME,
//                    NotificationManager.IMPORTANCE_HIGH
//            );
//            channel.setDescription("Alerts for high usage and late night activity");
//
//            NotificationManager manager = context.getSystemService(NotificationManager.class);
//            if (manager != null) {
//                manager.createNotificationChannel(channel);
//            }
//        }
//    }
//
//    // 1. General Risk Level Alert (e.g., "High Risk")
//    public static void sendRiskAlert(Context context, String level) {
//        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
//                .setSmallIcon(android.R.drawable.ic_dialog_alert)
//                .setContentTitle("Daily Usage Warning")
//                .setContentText("Risk Level is now " + level + ". Please slow down.")
//                .setPriority(NotificationCompat.PRIORITY_HIGH)
//                .setAutoCancel(true);
//
//        notify(context, NOTIF_ID_RISK, builder);
//    }
//
//    // 2. Late Night Alert
//    public static void sendLateNightAlert(Context context) {
//        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
//                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
//                .setContentTitle("Late Night Detected")
//                .setContentText("It is sleeping time. Put the phone away!")
//                .setPriority(NotificationCompat.PRIORITY_MAX)
//                .setDefaults(NotificationCompat.DEFAULT_ALL)
//                .setAutoCancel(true);
//
//        notify(context, NOTIF_ID_NIGHT, builder);
//    }
//
//    // 3. NEW: Specific Binge Alert with Time
//    public static void sendBingeAlert(Context context, String appName, String durationStr) {
//        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
//                .setSmallIcon(android.R.drawable.stat_sys_warning)
//                .setContentTitle("Break Needed!")
//                .setContentText("You've used " + appName + " for " + durationStr + " continuously.")
//                .setStyle(new NotificationCompat.BigTextStyle()
//                        .bigText("You have been using " + appName + " for " + durationStr + " without a break. Close it now!"))
//                .setPriority(NotificationCompat.PRIORITY_HIGH)
//                .setAutoCancel(true);
//
//        notify(context, NOTIF_ID_BINGE, builder);
//    }
//
//    private static void notify(Context context, int id, NotificationCompat.Builder builder) {
//        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
//        if (manager != null) manager.notify(id, builder.build());
//    }
//}