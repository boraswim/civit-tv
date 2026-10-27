import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Personal settings (TMDB key, weather city) live in local.properties, outside the source.
// A -P flag wins over it, e.g. -Ptmdb.apiKey= to build a release without a key.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
}
fun setting(name: String, default: String) =
    ((findProperty(name) as String?) ?: localProps.getProperty(name, default)).trim()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tvenhance.home"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvenhance.home"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        buildConfigField("String", "TMDB_KEY", "\"${setting("tmdb.apiKey", "")}\"")
        buildConfigField("String", "WEATHER_CITY", "\"${setting("weather.city", "Istanbul")}\"")
    }

    buildTypes {
        release {
            // Compose debug builds are noticeably slow on TV boxes, so we ship an
            // R8-optimised release signed with the local debug key.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // App-specific extras kept out of the repository (git-ignored): src/personal is compiled in
    // when it exists, otherwise src/stock supplies an Extras that adds nothing.
    val personal = file("src/personal")
    sourceSets {
        getByName("main") {
            java.srcDir(if (personal.exists()) "src/personal/java" else "src/stock/java")
            if (personal.exists()) res.srcDir("src/personal/res")
        }
        if (personal.resolve("AndroidManifest.xml").exists()) {
            getByName("debug").manifest.srcFile("src/personal/AndroidManifest.xml")
            getByName("release").manifest.srcFile("src/personal/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("dev.mobile:dadb:1.2.9") {
        // dadb 1.2.9 leaks its JUnit test runtime into its published dependencies.
        exclude(group = "org.junit.platform")
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.graalvm.buildtools")
    }
}
