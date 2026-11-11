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
        versionCode = 2
        versionName = "0.2"
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
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true // Compose runs far smoother optimized; proguard-rules.pro leaves OpenCover's own code alone
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
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
