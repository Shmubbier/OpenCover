import java.util.Properties

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
    // Release signing from keystore.properties (not in git); without it the release build is left unsigned.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keys != null) create("release") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }
    buildTypes {
        // ponytail: no R8. Much of OpenCover reaches hidden APIs by reflection; shrinking would need keep rules for each.
        release { signingConfig = signingConfigs.findByName("release") }
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
    implementation("androidx.compose.material:material-icons-extended:1.7.8") // quick settings tile icons
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
