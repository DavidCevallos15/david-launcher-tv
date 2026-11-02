plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.davidcevallos.launchertv"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.davidcevallos.launchertv"
        minSdk = 21
        targetSdk = 34
        versionCode = 6
        versionName = "0.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Firma de release: configúrala localmente, nunca subas el keystore al repo.
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
}

// Sin dependencias de AndroidX: framework puro + Kotlin stdlib.
// Menos clases cargadas = menos RAM en un TV con 1.5–2 GB.
