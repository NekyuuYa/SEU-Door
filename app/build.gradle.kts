plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nkyuu.dooropener"
    compileSdk = 34

    // 仅当本地存在 debug keystore 时才为 release 配置签名（方便本地 adb install）。
    // CI 上没有该文件，release 会产出未签名包，交给 release.yml 用 apksigner 单独签名。
    val debugKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")
    signingConfigs {
        if (debugKeystore.exists()) {
            create("release") {
                initWith(getByName("debug"))
            }
        }
    }

    defaultConfig {
        // NFC 唤起：门锁标签内嵌 AAR(com.whxinna.userplatform)，系统贴卡时按 AAR 找安装包。
        // AAR 包未安装时不会回落到第三方 NDEF 过滤器，所以这里直接“冒充”该包名，
        // 让 AAR 指向本 app → 贴卡即唤起本 app（namespace/代码仍用 com.nkyuu.dooropener）。
        applicationId = "com.whxinna.userplatform"
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
            signingConfig = signingConfigs.findByName("release")
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
        aidl = true
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
