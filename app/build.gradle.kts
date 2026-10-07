plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.readboy.otadownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readboy.otadownloader"
        // minSdk 21 = Android 5.0+，已覆盖老机型 Android 7（API 24/25）
        minSdk = 21
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = File(rootDir, "otf.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
            } else {
                storeFile = file("otf.jks")
            }
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "OTFiles-ABC345abc"
            keyAlias = System.getenv("KEY_ALIAS") ?: "OTFiles"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "OTFiles-ABC345abc"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        // Java 8+ API 脱糖：保证 API 21~23 老系统上调用 Java 8 默认方法/新 API 不抱 NoSuchMethodError
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
