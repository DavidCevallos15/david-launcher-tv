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
        versionCode = 4
        versionName = "0.3.0"
    }

    // Firma de release estable (la misma en cada versión, para que las actualizaciones se instalen encima).
    // En CI viene de los secretos del repo; en local, de variables de entorno. Sin ellas, se firma con debug.
    val releaseStore = System.getenv("RELEASE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
