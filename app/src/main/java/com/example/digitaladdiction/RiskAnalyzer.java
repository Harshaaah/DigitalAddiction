//package com.example.digitaladdiction;
//
//import java.util.Calendar;
//
//public class RiskAnalyzer {
//
//    public enum RiskLevel {
//        LOW, MODERATE, HIGH, SEVERE
//    }
//
//    // Returns risk based on milliseconds played
//    public static RiskLevel calculateRisk(long totalUsageMs) {
//        long hours = totalUsageMs / (1000 * 60 * 60);
//
//        if (hours < 2) return RiskLevel.LOW;
//        if (hours < 4) return RiskLevel.MODERATE;
//        if (hours < 6) return RiskLevel.HIGH;
//        return RiskLevel.SEVERE;
//    }
//
//     //Checks if current time is between 11 PM and 5 AM
//    public static boolean isLateNight() {
//        Calendar calendar = Calendar.getInstance();
//        int hour = calendar.get(Calendar.HOUR_OF_DAY); // 24-hour format
//
//        // Late night is 23 (11PM) to 5 (5AM)
//        return (hour >= 23 || hour < 5);
//    }
//
//
//}
package com.example.digitaladdiction;

import java.util.Calendar;

public class RiskAnalyzer {

    public enum RiskLevel {
        LOW, MODERATE, HIGH, SEVERE
    }

    /**
     * FUZZY LOGIC ENGINE
     * Calculates a "Digital Addiction Score" (0-100) based on weighted usage.
     * Uses Trapezoidal/Triangular Membership Functions.
     *
     * @param weightedUsageMs Total usage in milliseconds (including Night Multiplier)
     * @return RiskLevel enum based on the Fuzzy Score
     */
    public static RiskLevel calculateRisk(long weightedUsageMs) {
        long minutes = weightedUsageMs / (1000 * 60);
        int fuzzyScore = calculateFuzzyScore(minutes);

        // Map the 0-100 Score to Logic Levels
        if (fuzzyScore < 30) return RiskLevel.LOW;
        if (fuzzyScore < 60) return RiskLevel.MODERATE;
        if (fuzzyScore < 85) return RiskLevel.HIGH;
        return RiskLevel.SEVERE;
    }

    /**
     * Calculates the Defuzzified Score (0-100)
     */
    public static int calculateFuzzyScore(long minutes) {
        // Step 1: Fuzzification (Calculate Degree of Membership 0.0 to 1.0)

        // Low: 100% at 0, drops to 0% at 180 mins
        double lowDegree = getTrapezoidalMembership(minutes, -1, 0, 120, 180);

        // Moderate: Starts 120, Peaks 180-240, Ends 360
        double modDegree = getTrapezoidalMembership(minutes, 120, 180, 240, 360);

        // High: Starts 300, Peaks 420+
        double highDegree = getTrapezoidalMembership(minutes, 300, 420, 10000, 10000);

        // Step 2: Defuzzification (Weighted Average Method)
        // We assign a "Severity Weight" to each set: Low=10, Mod=50, High=95
        double numerator = (lowDegree * 10) + (modDegree * 50) + (highDegree * 95);
        double denominator = lowDegree + modDegree + highDegree;

        if (denominator == 0) return 0; // Avoid division by zero

        int finalScore = (int) (numerator / denominator);
        return Math.min(100, Math.max(0, finalScore)); // Clamp between 0-100
    }

    // Helper: Calculates membership value (0.0 to 1.0) for a Trapezoidal shape
    private static double getTrapezoidalMembership(double x, double a, double b, double c, double d) {
        if (x <= a || x >= d) return 0.0;
        if (x >= b && x <= c) return 1.0;
        if (x > a && x < b) return (x - a) / (b - a);
        if (x > c && x < d) return (d - x) / (d - c);
        return 0.0;
    }

    // --- Time Check (Existing Logic) ---
    public static boolean isLateNight() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return (hour >= 23 || hour < 5);
    }
}