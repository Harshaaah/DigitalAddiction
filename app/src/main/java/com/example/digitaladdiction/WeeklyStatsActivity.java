package com.example.digitaladdiction;

import android.app.usage.UsageEvents;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WeeklyStatsActivity extends AppCompatActivity {

    private BarChart barChart;
    private TextView tvSummary, tvPrediction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weekly_stats);

        barChart = findViewById(R.id.barChart);
        tvSummary = findViewById(R.id.tvWeeklySummary);
        tvPrediction = findViewById(R.id.tvPrediction);
        Button btnBack = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());

        loadWeeklyData();
    }

    private void loadWeeklyData() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        PackageManager pm = getPackageManager();

        ArrayList<BarEntry> entries = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        List<Double> historyForAI = new ArrayList<>();
        long totalWeeklyTime = 0;

        // Loop for the last 7 days
        for (int i = 6; i >= 0; i--) {
            Calendar startCal = Calendar.getInstance();
            startCal.add(Calendar.DAY_OF_YEAR, -i);

            // Set Start Time: 00:00:00
            startCal.set(Calendar.HOUR_OF_DAY, 0);
            startCal.set(Calendar.MINUTE, 0);
            startCal.set(Calendar.SECOND, 0);
            startCal.set(Calendar.MILLISECOND, 0);
            long startTime = startCal.getTimeInMillis();

            // Set End Time
            long endTime;
            if (i == 0) {
                // TODAY: Stop exactly NOW
                endTime = System.currentTimeMillis();
            } else {
                // PAST: Stop at 23:59:59
                Calendar endCal = (Calendar) startCal.clone();
                endCal.set(Calendar.HOUR_OF_DAY, 23);
                endCal.set(Calendar.MINUTE, 59);
                endCal.set(Calendar.SECOND, 59);
                endTime = endCal.getTimeInMillis();
            }

            // --- USE NEW PRECISE CALCULATION ---
            long dailyTotal = calculatePreciseDailyUsage(usm, pm, startTime, endTime);

            // Convert to Hours
            float hours = dailyTotal / (1000f * 60 * 60);
            entries.add(new BarEntry(6 - i, hours));
            historyForAI.add((double) hours);

            String dayName = new SimpleDateFormat("EEE", Locale.getDefault()).format(startCal.getTime());
            labels.add(dayName);

            totalWeeklyTime += dailyTotal;
        }

        // Chart Setup
        BarDataSet dataSet = new BarDataSet(entries, "Daily Usage (Hours)");
        dataSet.setColors(ColorTemplate.MATERIAL_COLORS);
        dataSet.setValueTextSize(12f);

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.6f);

        barChart.setData(barData);
        barChart.getDescription().setEnabled(false);
        barChart.animateY(1000);

        XAxis xAxis = barChart.getXAxis();
        xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setDrawGridLines(false);

        // Summary
        long avgTime = totalWeeklyTime / 7;
        long avgHrs = avgTime / (1000 * 60 * 60);
        long avgMins = (avgTime / (1000 * 60)) % 60;
        tvSummary.setText("Average Usage: " + avgHrs + "h " + avgMins + "m / day");

        runPredictionEngine(historyForAI);
    }

    // --- NEW: EVENT-BASED CALCULATION (100% Accurate) ---
    private long calculatePreciseDailyUsage(UsageStatsManager usm, PackageManager pm, long start, long end) {
        // Query Raw Events instead of Aggregated Stats
        UsageEvents events = usm.queryEvents(start, end);
        UsageEvents.Event event = new UsageEvents.Event();

        Map<String, Long> appStartMap = new HashMap<>();
        Map<String, Long> appDurationMap = new HashMap<>();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            String pkg = event.getPackageName();

            // Filter out system apps immediately to save processing
            if (isSystemApp(pm, pkg)) continue;

            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                appStartMap.put(pkg, event.getTimeStamp());
            }
            else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (appStartMap.containsKey(pkg)) {
                    long startTime = appStartMap.get(pkg);
                    long duration = event.getTimeStamp() - startTime;

                    appDurationMap.put(pkg, appDurationMap.getOrDefault(pkg, 0L) + duration);
                    appStartMap.remove(pkg);
                }
            }
        }

        // Handle apps that are currently OPEN (No Background event yet)
        for (Map.Entry<String, Long> entry : appStartMap.entrySet()) {
            long duration = end - entry.getValue();
            appDurationMap.put(entry.getKey(), appDurationMap.getOrDefault(entry.getKey(), 0L) + duration);
        }

        // Sum it all up
        long totalDayUsage = 0;
        for (Long duration : appDurationMap.values()) {
            totalDayUsage += duration;
        }
        return totalDayUsage;
    }

    private boolean isSystemApp(PackageManager pm, String pkg) {
        if (pkg.contains("youtube") || pkg.contains("chrome") || pkg.contains("whatsapp") ||
                pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("snapchat")) return false;

        if (pkg.contains("launcher") || pkg.contains("home") || pkg.contains("nexus") ||
                pkg.contains("trebuchet") || pkg.contains("android.systemui")) return true;

        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        } catch (Exception e) { return true; }
    }

    private void runPredictionEngine(List<Double> history) {
        double predictedHours = PredictionAI.predictNextDayUsage(history);
        String msg;
        if (predictedHours > 4.0) {
            msg = String.format("⚠️ WARNING: Trend increasing. Predicted: %.1f hours tomorrow.", predictedHours);
            tvPrediction.setTextColor(Color.RED);
        } else if (predictedHours < 2.0) {
            msg = String.format("✅ GOOD TREND: Predicted: %.1f hours tomorrow.", predictedHours);
            tvPrediction.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            msg = String.format("ℹ️ STABLE: Predicted: %.1f hours tomorrow.", predictedHours);
            tvPrediction.setTextColor(Color.BLACK);
        }
        tvPrediction.setText(msg);
    }
}