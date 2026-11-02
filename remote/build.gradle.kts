plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.davidcevallos.tvremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.davidcevallos.tvremote"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.all { it.environment("FAKE_TV_DIR", System.getenv("FAKE_TV_DIR") ?: "") }
    }
}

// Igual que el launcher: sin AndroidX. JUnit solo en tests (no entra al APK).
dependencies {
    testImplementation("junit:junit:4.13.2")
}
