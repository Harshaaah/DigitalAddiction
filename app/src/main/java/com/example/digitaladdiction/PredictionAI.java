package com.example.digitaladdiction;

import java.util.List;

public class PredictionAI {

    /**
     * Performs Simple Linear Regression on historical usage data.
     * Formula: y = mx + c
     * @param pastUsageHours List of daily usage in Hours (e.g., [2.5, 3.0, 4.2])
     * @return The predicted usage hours for the NEXT day.
     */
    public static double predictNextDayUsage(List<Double> pastUsageHours) {
        int n = pastUsageHours.size();

        // We need at least 2 days of data to draw a trend line
        if (n < 2) return 0.0;

        double sumX = 0;  // Sum of Day Indices (1, 2, 3...)
        double sumY = 0;  // Sum of Usage Hours
        double sumXY = 0; // Sum of (Day * Usage)
        double sumX2 = 0; // Sum of (Day^2)

        for (int i = 0; i < n; i++) {
            double x = i + 1; // Day 1, Day 2...
            double y = pastUsageHours.get(i);

            sumX += x;
            sumY += y;
            sumXY += (x * y);
            sumX2 += (x * x);
        }

        // Calculate Slope (m) - The Trend Direction
        double denominator = (n * sumX2 - sumX * sumX);
        if (denominator == 0) return pastUsageHours.get(n - 1); // Fallback if data is flat

        double m = (n * sumXY - sumX * sumY) / denominator;

        // Calculate Intercept (c) - The Baseline
        double c = (sumY - m * sumX) / n;

        // Predict for Tomorrow (x = n + 1)
        double nextDayIndex = n + 1;
        double predictedUsage = (m * nextDayIndex) + c;

        // Return result (ensure it's not negative)
        return Math.max(0, predictedUsage);
    }
}