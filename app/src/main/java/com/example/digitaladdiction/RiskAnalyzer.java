package com.example.digitaladdiction;

import java.util.Calendar;

public class RiskAnalyzer {

    public enum RiskLevel {
        LOW, MODERATE, HIGH, SEVERE
    }

    // Returns risk based on milliseconds played
    public static RiskLevel calculateRisk(long totalUsageMs) {
        long hours = totalUsageMs / (1000 * 60 * 60);

        if (hours < 2) return RiskLevel.LOW;
        if (hours < 4) return RiskLevel.MODERATE;
        if (hours < 6) return RiskLevel.HIGH;
        return RiskLevel.SEVERE;
    }

     //Checks if current time is between 11 PM and 5 AM
    public static boolean isLateNight() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY); // 24-hour format

        // Late night is 23 (11PM) to 5 (5AM)
        return (hour >= 23 || hour < 5);
    }
}