plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.digitaladdiction"

    // FIX: Update this to 36
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.digitaladdiction"
        minSdk = 24

        // FIX: Update this to 36 as well
        targetSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    // --- Default Android Libraries ---
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // --- FIREBASE INTEGRATION ---
    // Import the BoM for the Firebase platform
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))

    // Phase 1: Authentication
    implementation("com.google.firebase:firebase-auth")

    // Phase 2: Realtime Database (REQUIRED for App Usage Tracking)
    implementation("com.google.firebase:firebase-database")

    // Optional: Analytics
    implementation("com.google.firebase:firebase-analytics")

    // --- Testing Libraries ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}