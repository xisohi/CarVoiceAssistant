plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 读取签名配置（CI 构建时由工作流生成 keystore.properties）
val keystoreProperties = java.util.Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(java.io.FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.xisohi.car.voiceassistant"
    compileSdk = 34

    // 签名配置
    signingConfigs {
        create("release") {
            if (keystoreProperties.getProperty("RELEASE_STORE_FILE") != null) {
                storeFile = file(keystoreProperties["RELEASE_STORE_FILE"] as String)
                storePassword = keystoreProperties["RELEASE_STORE_PASSWORD"] as String
                keyAlias = keystoreProperties["RELEASE_KEY_ALIAS"] as String
                keyPassword = keystoreProperties["RELEASE_KEY_PASSWORD"] as String
            }
        }
    }

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

        // 首次下载模型包的地址（可换成你自己的 CDN）
        buildConfigField("String", "MODEL_PACK_URL", "\"https://lcjly.cn/car/models.zip\"")
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
            // 使用签名配置（如果存在）
            if (keystoreProperties.getProperty("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    // 断点续传下载
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 离线语音识别（含 armeabi-v7a 原生库），同时用于唤醒（grammar 模式）和识别
    implementation("com.alphacephei:vosk-android:0.3.47")
}
