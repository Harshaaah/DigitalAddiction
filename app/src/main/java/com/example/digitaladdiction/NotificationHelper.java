package com.example.digitaladdiction;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public class NotificationHelper {

    private static final String CHANNEL_ID = "addiction_alerts";
    private static final String CHANNEL_NAME = "Addiction Alerts";
    private static final int NOTIF_ID_RISK = 1001;
    private static final int NOTIF_ID_NIGHT = 1002;

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH // High importance makes it pop up
            );
            channel.setDescription("Alerts for high usage and late night activity");

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static void sendRiskAlert(Context context, String level) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert) // Use a built-in icon
                .setContentTitle("Digital Wellness Alert")
                .setContentText("Usage Level is now " + level + ". Please take a break.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIF_ID_RISK, builder.build());
    }

    public static void sendLateNightAlert(Context context) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Late Night Usage Detected")
                .setContentText("It is sleeping time. Put the phone away!")
                .setPriority(NotificationCompat.PRIORITY_MAX) // Max priority for sleep warnings
                .setDefaults(NotificationCompat.DEFAULT_ALL) // Sound and Vibrate
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIF_ID_NIGHT, builder.build());
    }
}