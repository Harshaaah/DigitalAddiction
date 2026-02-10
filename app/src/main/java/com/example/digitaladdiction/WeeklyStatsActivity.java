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
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WeeklyStatsActivity extends AppCompatActivity {

    private BarChart barChart;
    private TextView tvSummary, tvPrediction; // Added Prediction TextView

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weekly_stats);

        barChart = findViewById(R.id.barChart);
        tvSummary = findViewById(R.id.tvWeeklySummary);
        tvPrediction = findViewById(R.id.tvPrediction); // Bind View
        Button btnBack = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());

        loadWeeklyData();
    }

    private void loadWeeklyData() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        PackageManager pm = getPackageManager();

        ArrayList<BarEntry> entries = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();

        // List to store history for AI Prediction
        List<Double> historyForAI = new ArrayList<>();

        long totalWeeklyTime = 0;

        // Loop for the last 7 days (6 days ago -> Today)
        for (int i = 6; i >= 0; i--) {
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_YEAR, -i);

            // Start: 00:00:00
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startTime = calendar.getTimeInMillis();

            // End: 23:59:59
            Calendar endCal = (Calendar) calendar.clone();
            endCal.add(Calendar.DAY_OF_YEAR, 1);
            long endTime = endCal.getTimeInMillis();

            // Get Data
            long dailyTotal = calculateDailyUsage(usm, pm, startTime, endTime);

            // Convert to Hours
            float hours = dailyTotal / (1000f * 60 * 60);

            // Add to Graph
            entries.add(new BarEntry(6 - i, hours));

            // Add to AI History (Double format)
            historyForAI.add((double) hours);

            String dayName = new SimpleDateFormat("EEE", Locale.getDefault()).format(calendar.getTime());
            labels.add(dayName);

            totalWeeklyTime += dailyTotal;
        }

        // --- 1. SETUP GRAPH ---
        BarDataSet dataSet = new BarDataSet(entries, "Daily Usage (Hours)");
        dataSet.setColors(ColorTemplate.MATERIAL_COLORS);
        dataSet.setValueTextSize(12f);

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.6f);

        barChart.setData(barData);
        barChart.getDescription().setEnabled(false);
        barChart.animateY(1500);

        XAxis xAxis = barChart.getXAxis();
        xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setDrawGridLines(false);

        // --- 2. SETUP SUMMARY ---
        long avgTime = totalWeeklyTime / 7;
        long avgHrs = avgTime / (1000 * 60 * 60);
        long avgMins = (avgTime / (1000 * 60)) % 60;
        tvSummary.setText("Average Usage: " + avgHrs + "h " + avgMins + "m / day");

        // --- 3. RUN AI PREDICTION ---
        runPredictionEngine(historyForAI);
    }

    private void runPredictionEngine(List<Double> history) {
        // Use your PredictionAI class logic
        double predictedHours = PredictionAI.predictNextDayUsage(history);

        String msg;
        if (predictedHours > 4.0) {
            msg = String.format("⚠️ WARNING: Your usage trend is increasing. AI predicts you will reach %.1f hours tomorrow. Try to reduce screen time today.", predictedHours);
            tvPrediction.setTextColor(Color.RED);
        } else if (predictedHours < 2.0) {
            msg = String.format("✅ HEALTHY TREND: Great job! Predicted usage for tomorrow is %.1f hours. Keep maintaining this balance.", predictedHours);
            tvPrediction.setTextColor(Color.parseColor("#4CAF50")); // Green
        } else {
            msg = String.format("ℹ️ STABLE: Your usage pattern is stable. Predicted usage: %.1f hours.", predictedHours);
            tvPrediction.setTextColor(Color.BLACK);
        }
        tvPrediction.setText(msg);
    }

    private long calculateDailyUsage(UsageStatsManager usm, PackageManager pm, long start, long end) {
        if (end > System.currentTimeMillis()) end = System.currentTimeMillis();

        Map<String, UsageStats> statsMap = usm.queryAndAggregateUsageStats(start, end);
        long total = 0;

        if (statsMap != null) {
            for (UsageStats usage : statsMap.values()) {
                if (usage.getLastTimeUsed() < start) continue;
                long timeMs = usage.getTotalTimeInForeground();
                if (timeMs > 0 && !isSystemApp(pm, usage.getPackageName())) {
                    total += timeMs;
                }
            }
        }
        return total;
    }

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