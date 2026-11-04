plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lf.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lf.launcher"
        minSdk = 28
        // 车机 Android 9：targetSdk 停在 28，可避开后台启动限制与分区存储
        targetSdk = 28
        versionCode = 54
        versionName = "0.9.43-m54"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.cardview:cardview:1.0.0")
    // 读 md3Music 的歌词必须用 Media3 的 MediaController：
    // 歌词在 MediaItem.mediaMetadata.extras 里，而 framework 的 MediaMetadata 没有 extras 字段
    implementation("androidx.media3:media3-session:1.4.1")
}
