plugins {
    id("com.android.application")
}

android {
    namespace = "com.hidsquid.rootrecoveryhelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hidsquid.rootrecoveryhelper"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // API 29 is an explicit requirement for this dedicated Android 10 device app.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
