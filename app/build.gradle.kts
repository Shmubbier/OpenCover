plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.opencover"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.opencover"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media:media:1.7.1") // MediaControllerCompat: shuffle and repeat
    implementation("dev.rikka.shizuku:api:13.1.5")      // Shizuku: Wi-Fi, Bluetooth and other blocked toggles
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
