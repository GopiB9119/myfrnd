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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val signingValues = listOf(
        "MYFRND_KEYSTORE", "MYFRND_STORE_PASSWORD", "MYFRND_KEY_ALIAS", "MYFRND_KEY_PASSWORD"
    ).associateWith { providers.environmentVariable(it).orNull }
    require(signingValues.values.all { it.isNullOrBlank() } ||
        signingValues.values.all { !it.isNullOrBlank() }) {
        "Release signing requires all four MYFRND signing environment variables."
    }
    if (signingValues.values.all { !it.isNullOrBlank() }) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(signingValues.getValue("MYFRND_KEYSTORE")!!)
                storePassword = signingValues["MYFRND_STORE_PASSWORD"]
                keyAlias = signingValues["MYFRND_KEY_ALIAS"]
                keyPassword = signingValues["MYFRND_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            // kotlinx.coroutines debug-agent data; unused at runtime in release builds.
            excludes += "DebugProbesKt.bin"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}