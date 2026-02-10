package com.example.digitaladdiction;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.utils.ColorTemplate;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

public class WeeklyStatsActivity extends AppCompatActivity {

    private BarChart barChart;
    private TextView tvSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weekly_stats);

        barChart = findViewById(R.id.barChart);
        tvSummary = findViewById(R.id.tvWeeklySummary);
        Button btnBack = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());

        loadWeeklyData();
    }

    private void loadWeeklyData() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        PackageManager pm = getPackageManager();

        ArrayList<BarEntry> entries = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        long totalWeeklyTime = 0;

        // Loop for the last 7 days (including today)
        for (int i = 6; i >= 0; i--) {
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_YEAR, -i); // Go back 'i' days

            // Set Start time: 00:00:00
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startTime = calendar.getTimeInMillis();

            // Set End time: 23:59:59 (End of that day)
            Calendar endCal = (Calendar) calendar.clone();
            endCal.add(Calendar.DAY_OF_YEAR, 1);
            long endTime = endCal.getTimeInMillis();

            // --- GET USAGE FOR THIS SPECIFIC DAY ---
            long dailyTotal = calculateDailyUsage(usm, pm, startTime, endTime);

            // Convert to Hours (Float for graph)
            float hours = dailyTotal / (1000f * 60 * 60);

            // Add to Graph Data (x = index, y = hours)
            entries.add(new BarEntry(6 - i, hours));

            // Get Day Name (e.g., "Mon")
            String dayName = new SimpleDateFormat("EEE", Locale.getDefault()).format(calendar.getTime());
            labels.add(dayName);

            totalWeeklyTime += dailyTotal;
        }

        // --- SETUP CHART VISUALS ---
        BarDataSet dataSet = new BarDataSet(entries, "Daily Usage (Hours)");
        dataSet.setColors(ColorTemplate.MATERIAL_COLORS);
        dataSet.setValueTextSize(12f);

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.6f); // Slimmer bars

        barChart.setData(barData);
        barChart.getDescription().setEnabled(false);
        barChart.animateY(1500); // Animation

        // Configure X-Axis (Days)
        XAxis xAxis = barChart.getXAxis();
        xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setDrawGridLines(false);

        // Update Summary Text
        long avgTime = totalWeeklyTime / 7;
        long avgHrs = avgTime / (1000 * 60 * 60);
        long avgMins = (avgTime / (1000 * 60)) % 60;

        tvSummary.setText("Average Usage: " + avgHrs + "h " + avgMins + "m / day");
    }

    // Reuse the accurate calculation logic we fixed in Phase 4
    private long calculateDailyUsage(UsageStatsManager usm, PackageManager pm, long start, long end) {
        // Limit 'end' to current time if we are calculating Today (to avoid future errors)
        if (end > System.currentTimeMillis()) end = System.currentTimeMillis();

        Map<String, UsageStats> statsMap = usm.queryAndAggregateUsageStats(start, end);
        long total = 0;

        if (statsMap != null) {
            for (UsageStats usage : statsMap.values()) {
                if (usage.getLastTimeUsed() < start) continue; // Ignore old data
                long timeMs = usage.getTotalTimeInForeground();
                if (timeMs > 0 && !isSystemApp(pm, usage.getPackageName())) {
                    total += timeMs;
                }
            }
        }
        return total;
    }

    // Same Filter as TrackingService
    private boolean isSystemApp(PackageManager pm, String pkg) {
        if (pkg.contains("youtube") || pkg.contains("chrome") || pkg.contains("whatsapp") ||
                pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("snapchat")) return false;

        if (pkg.contains("launcher") || pkg.contains("home")) return true;

        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        } catch (Exception e) { return true; }
    }
}