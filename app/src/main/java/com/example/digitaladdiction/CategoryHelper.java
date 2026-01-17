package com.example.digitaladdiction;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

public class CategoryHelper {

    public static String getCategory(Context context, String packageName) {
        // 1. Check for specific popular apps (Manual Override)
        if (packageName.contains("whatsapp") || packageName.contains("instagram") || packageName.contains("facebook") || packageName.contains("tiktok") || packageName.contains("snapchat") || packageName.contains("twitter")) {
            return "Social Media";
        }
        if (packageName.contains("youtube") || packageName.contains("netflix") || packageName.contains("primevideo") || packageName.contains("disney")) {
            return "Entertainment";
        }
        if (packageName.contains("pubg") || packageName.contains("freefire") || packageName.contains("roblox") || packageName.contains("candycrush") || packageName.contains("clash")) {
            return "Games";
        }
        if (packageName.contains("chrome") || packageName.contains("firefox") || packageName.contains("edge")) {
            return "Browsers";
        }

        // 2. Use Android's System Category (API 26+)
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int cat = appInfo.category;
                switch (cat) {
                    case ApplicationInfo.CATEGORY_GAME:
                        return "Games";
                    case ApplicationInfo.CATEGORY_SOCIAL:
                        return "Social Media";
                    case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                        return "Productivity";
                    case ApplicationInfo.CATEGORY_VIDEO:
                    case ApplicationInfo.CATEGORY_AUDIO:
                        return "Entertainment";
                    case ApplicationInfo.CATEGORY_MAPS:
                        return "Travel";
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        // 3. Fallback based on package name keywords
        String lowerPkg = packageName.toLowerCase();
        if (lowerPkg.contains("game")) return "Games";
        if (lowerPkg.contains("learn") || lowerPkg.contains("study") || lowerPkg.contains("edu")) return "Education";
        if (lowerPkg.contains("calc") || lowerPkg.contains("note") || lowerPkg.contains("office")) return "Productivity";

        return "Other";
    }
}