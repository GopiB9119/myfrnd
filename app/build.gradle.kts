plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wellness.overlay3d"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.myfrnd.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.projectDir}/myfrnd_production_key.jks")
            storePassword = "Vamsi@298"
            keyAlias = "myfrnd"
            keyPassword = "Vamsi@298"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        noCompress += listOf("glb", "gltf")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")

    // Google Filament 3D Engine & glTF 2.0 Loader
    implementation("com.google.android.filament:filament-android:1.53.4")
    implementation("com.google.android.filament:gltfio-android:1.53.4")
    implementation("com.google.android.filament:filament-utils-android:1.53.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}