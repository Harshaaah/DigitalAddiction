package com.example.digitaladdiction;

import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ParentSettingsActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private LimitAdapter adapter;
    private List<AppLimitModel> appList = new ArrayList<>();
    private Map<String, Long> existingLimits = new HashMap<>(); // Store downloaded limits

    private DatabaseReference limitsRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_settings);

        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        limitsRef = FirebaseDatabase.getInstance().getReference("users").child(uid).child("restrictions").child("limits");

        recyclerView = findViewById(R.id.recyclerLimits);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new LimitAdapter(appList);
        recyclerView.setAdapter(adapter);

        // 1. Load Existing Limits from Firebase First
        loadLimitsFromFirebase();
    }

    private void loadLimitsFromFirebase() {
        limitsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                existingLimits.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    // Firebase Key (com_youtube) -> App Package (com.youtube)
                    String pkg = child.getKey().replace("_", ".");
                    Long limit = child.getValue(Long.class);
                    existingLimits.put(pkg, limit);
                }
                // 2. Once limits are loaded, load installed apps
                loadInstalledApps();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void loadInstalledApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        appList.clear();

        for (ApplicationInfo app : apps) {
            // Filter System Apps (Use same logic as TrackingService)
            if (!isSystemApp(pm, app.packageName)) {
                String name = pm.getApplicationLabel(app).toString();

                // Check if we have a limit for this app
                long limit = existingLimits.getOrDefault(app.packageName, 0L);

                appList.add(new AppLimitModel(name, app.packageName, limit));
            }
        }

        // Sort alphabetically
        Collections.sort(appList, (a, b) -> a.name.compareTo(b.name));
        adapter.notifyDataSetChanged();
    }

    private boolean isSystemApp(PackageManager pm, String pkg) {
        if (pkg.contains("youtube") || pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("whatsapp")) return false;
        if (pkg.contains("launcher") || pkg.contains("home")) return true;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) { return true; }
    }

    // --- DIALOG TO SET LIMIT ---
    private void showSetLimitDialog(AppLimitModel app) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Set Limit for " + app.name);
        builder.setMessage("Enter daily limit in minutes (0 to remove limit):");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("e.g. 30");
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String val = input.getText().toString();
            if (!val.isEmpty()) {
                long minutes = Long.parseLong(val);
                saveLimitToFirebase(app.pkg, minutes);
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void saveLimitToFirebase(String pkg, long minutes) {
        // Firebase keys cannot have ".", replace with "_"
        String key = pkg.replace(".", "_");

        if (minutes > 0) {
            limitsRef.child(key).setValue(minutes);
            Toast.makeText(this, "Limit set to " + minutes + " mins", Toast.LENGTH_SHORT).show();
        } else {
            limitsRef.child(key).removeValue(); // Remove limit if 0
            Toast.makeText(this, "Limit removed", Toast.LENGTH_SHORT).show();
        }

        // Refresh UI
        loadLimitsFromFirebase();
    }

    // --- INTERNAL DATA MODEL ---
    class AppLimitModel {
        String name, pkg;
        long currentLimit; // 0 means no limit

        public AppLimitModel(String name, String pkg, long currentLimit) {
            this.name = name; this.pkg = pkg; this.currentLimit = currentLimit;
        }
    }

    // --- RECYCLER ADAPTER ---
    class LimitAdapter extends RecyclerView.Adapter<LimitAdapter.ViewHolder> {
        List<AppLimitModel> list;
        public LimitAdapter(List<AppLimitModel> list) { this.list = list; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_limit, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppLimitModel item = list.get(position);
            holder.name.setText(item.name);

            if (item.currentLimit > 0) {
                holder.limit.setText("Limit: " + item.currentLimit + " mins/day");
                holder.limit.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            } else {
                holder.limit.setText("No Limit Set");
                holder.limit.setTextColor(getResources().getColor(android.R.color.darker_gray));
            }

            try {
                Drawable icon = getPackageManager().getApplicationIcon(item.pkg);
                holder.icon.setImageDrawable(icon);
            } catch (Exception e) {}

            holder.btnSet.setOnClickListener(v -> showSetLimitDialog(item));
        }

        @Override
        public int getItemCount() { return list.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView icon; TextView name, limit; Button btnSet;
            public ViewHolder(View v) {
                super(v);
                icon = v.findViewById(R.id.imgIcon);
                name = v.findViewById(R.id.tvAppName);
                limit = v.findViewById(R.id.tvCurrentLimit);
                btnSet = v.findViewById(R.id.btnSetLimit);
            }
        }
    }
}