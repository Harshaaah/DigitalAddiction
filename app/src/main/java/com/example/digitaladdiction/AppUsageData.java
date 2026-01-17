package com.example.digitaladdiction;

public class AppUsageData {
    public String packageName;
    public String appName;
    public long durationMs;
    public String category; // e.g., "Social", "Game"

    // Empty constructor required for Firebase
    public AppUsageData() { }

    public AppUsageData(String packageName, String appName, long durationMs, String category) {
        this.packageName = packageName;
        this.appName = appName;
        this.durationMs = durationMs;
        this.category = category;
    }
}