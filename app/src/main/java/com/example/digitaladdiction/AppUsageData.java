package com.example.digitaladdiction;

public class AppUsageData {
    public String packageName;
    public String appName;
    public long durationMs;
    public String category;

    // --- NEW FIELDS ADDED ---
    public int launchCount;      // How many times opened
    public long lastTimeUsed;    // When it was last used (Timestamp)

    // Empty constructor is REQUIRED for Firebase
    public AppUsageData() { }

    // Updated Constructor to accept ALL 6 values
    public AppUsageData(String packageName, String appName, long durationMs, String category, int launchCount, long lastTimeUsed) {
        this.packageName = packageName;
        this.appName = appName;
        this.durationMs = durationMs;
        this.category = category;
        this.launchCount = launchCount;
        this.lastTimeUsed = lastTimeUsed;
    }
}