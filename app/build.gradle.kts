import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.statusclear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.conqueror102.statusclear"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"
    }

    // FFmpeg + x264 native code is ~30 MB per CPU type, so build one APK per type:
    // arm64-v8a (almost every phone since 2017), armeabi-v7a (older 32-bit phones),
    // x86_64 (emulator only — not published).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // Release signing from signing.properties (never committed — see .gitignore).
    // Its storeFile points at a keystore kept outside the repository.
    val signingProps = Properties().also { props ->
        val f = rootProject.file("signing.properties")
        if (f.exists()) f.inputStream().use { props.load(it) }
    }
    signingConfigs {
        create("release") {
            if (signingProps.containsKey("storeFile")) {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Compress the big FFmpeg .so files inside sideloaded APKs (~3x smaller
        // download); Android extracts them once at install.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    // FFmpeg with the x264 encoder (GPL). x264 gives far better quality per bit than
    // the phone's hardware encoder, which is what keeps WhatsApp from re-compressing.
    implementation("com.antonkarpenko:ffmpeg-kit-full-gpl:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
