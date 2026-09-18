plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zaralyn.ota"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zaralyn.ota"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
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
}
