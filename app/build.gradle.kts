plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kmusic.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kmusic.app"
        minSdk = 29
        targetSdk = 35
        // GitHub Actions sets the run number, so every build is a valid update
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.0.$run"
    }

    // A fixed key, so each new build installs over the previous one.
    // Fine for personal use. For the Play Store, make your own private key.
    signingConfigs {
        create("release") {
            storeFile = file("kmusic.jks")
            storePassword = "kmusic123"
            keyAlias = "kmusic"
            keyPassword = "kmusic123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
}
