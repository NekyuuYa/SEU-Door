plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nkyuu.dooropener"
    compileSdk = 34

    signingConfigs {
        getByName("debug")
        create("release") {
            initWith(getByName("debug"))
        }
    }

    defaultConfig {
        applicationId = "com.nkyuu.dooropener"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        // Only ship the locales this app currently targets.
        resourceConfigurations.addAll(listOf("en", "zh-rCN"))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = false
    }

    // Strip metadata that's only needed for Kotlin reflection / build tooling.
    // This app uses no kotlin-reflect (only `Foo::class.java`), so these are dead weight.
    packaging {
        resources {
            excludes += setOf(
                "**/*.kotlin_builtins",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/**/*.kotlin_module",
            )
        }
    }
}

dependencies {
    // No third-party runtime dependencies on purpose: the app uses only platform
    // APIs and the Kotlin stdlib supplied by the Kotlin plugin. Dropping core-ktx,
    // activity, and view binding keeps the dex focused on app code only.
}
