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
        versionCode = 65
        versionName = "0.9.54-m65"

        // 车机上只会有中文界面。不裁的话 appcompat 会带 80 多种语言的字符串
        resConfigs("zh")
    }

    packaging {
        resources {
            // Kotlin 的编译元数据、调试探针，运行时用不到
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "DebugProbesKt.bin",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
            )
        }
    }

    signingConfigs {
        // 车机上一直是覆盖安装：必须和眼下装的 debug 包同一个签名，
        // 否则系统会要求先卸载（= 配置全丢）
        create("keepDebug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("keepDebug")
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
    // 读 md3Music 的歌词必须用 Media3 的 MediaController：
    // 歌词在 MediaItem.mediaMetadata.extras 里，而 framework 的 MediaMetadata 没有 extras 字段
    implementation("androidx.media3:media3-session:1.4.1")
}
