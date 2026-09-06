plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xisohi.car.voiceassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xisohi.car.voiceassistant"
        // minSdk 24 覆盖 Android 7.0+，兼容 32 位车机（Android 10 = API 29）
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // 原生库架构：
        // - armeabi-v7a / arm64-v8a：真实车机（32 位老车机用 v7a）
        // - x86_64 / x86：雷电等 PC 模拟器调试用
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64", "x86")
        }

        // Picovoice AccessKey：到 console.picovoice.ai 免费申请后替换
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"REPLACE_WITH_YOUR_ACCESS_KEY\"")

        // 首次下载模型包的地址（可换成你自己的 CDN）
        buildConfigField("String", "MODEL_PACK_URL", "\"https://example.com/voice-assistant/models-v1.zip\"")
        buildConfigField("String", "MODEL_PACK_MD5", "\"\"")

        // 默认离线 TTS 引擎包名（可选，系统引擎为空字符串时用系统默认）
        buildConfigField("String", "PREFERRED_TTS_ENGINE", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 断点续传下载
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 离线语音识别（含 armeabi-v7a 原生库）
    implementation("com.alphacephei:vosk-android:0.3.47")

    // 离线唤醒词（含 armeabi-v7a 原生库）
    implementation("ai.picovoice:porcupine-android:3.0.2")
}
