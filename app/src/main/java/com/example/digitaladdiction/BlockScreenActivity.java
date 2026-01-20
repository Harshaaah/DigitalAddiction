package com.example.digitaladdiction;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class BlockScreenActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_block_screen);

        // When they click close, go to Home Screen
        findViewById(R.id.btnGoHome).setOnClickListener(v -> {
            Intent startMain = new Intent(Intent.ACTION_MAIN);
            startMain.addCategory(Intent.CATEGORY_HOME);
            startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(startMain);
            finish();
        });
    }

    // Disable Back Button so they can't sneak back into the app
    @Override
    public void onBackPressed() {
        // Do nothing (Block the button)
    }
}