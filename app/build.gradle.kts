plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "com.maxim.marquee"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maxim.marquee"
        minSdk = 23
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Fixed key so every new build installs over the previous one.
    signingConfigs {
        create("marquee") {
            storeFile = file("marquee.jks")
            storePassword = "marquee"
            keyAlias = "marquee"
            keyPassword = "marquee"
        }
    }

    buildTypes {
        release {
            // R8 makes Compose noticeably faster on TV boxes.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("marquee")
        }
        debug {
            signingConfig = signingConfigs.getByName("marquee")
        }
    }

    compileOptions {
        // NewPipe Extractor uses newer Java APIs than Android 6–12 ship.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Names each composable in performance traces (only active while a trace is being recorded).
    implementation("androidx.compose.runtime:runtime-tracing:1.7.5")

    implementation("androidx.core:core-ktx:1.13.1")
    // Installs the bundled Compose baseline profiles on sideloaded installs: pre-compiled UI code, smoother from first launch.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // QR codes for "Set up from your phone".
    implementation("com.google.zxing:core:3.5.3")

    val media3 = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Streams over the app's own HTTP client: connection reuse, HTTP/2, sturdier on flaky links.
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // Software decoders for DTS / TrueHD / E-AC3 audio that most TV boxes can't decode natively.
    // Must match the media3 version above (Jellyfin publishes one build per media3 release).
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // Direct YouTube streams for the trailers behind the billboard (played by Media3, no web page).
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")
}
