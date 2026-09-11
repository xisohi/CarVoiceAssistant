import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 读取签名配置（CI 构建时由工作流生成 keystore.properties）
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
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
        // 注意：使用 splits 按 ABI 拆分 APK 时，不能同时设置 ndk.abiFilters
        // ABI 列表在下方 splits 块中配置

        // 首次下载模型包的地址（Vosk 官网中文大模型，1.3GB，识别准确率更高）
        // 小模型（42MB）：https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip
        // 大模型（1.3GB）：https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip
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

    // 按 ABI 架构拆分 APK，每个架构生成单独的 APK
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64", "x86")
            // 不生成通用 APK，只生成各架构单独的 APK
            isUniversalApk = false
        }
    }

    // 自定义 APK 输出文件名
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            val abi = output.filters.find { it.filterType == "ABI" }?.identifier ?: ""
            val abiName = when (abi) {
                "armeabi-v7a" -> "v7a"
                "arm64-v8a" -> "v8a"
                "x86" -> "x86"
                "x86_64" -> "x86_64"
                else -> abi
            }
            output.outputFileName = "CarVoiceAssistant-${abiName}.apk"
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

    // 排除重复的 META-INF 文件（JNA 和其他库可能有重复）
    packaging {
        resources {
            excludes += "META-INF/AL2.0"
            excludes += "META-INF/LGPL2.1"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    // 断点续传下载
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 离线语音识别（含 armeabi-v7a 原生库），同时用于唤醒（grammar 模式）和识别
    implementation("com.alphacephei:vosk-android:0.3.47")

    // JNA（Java Native Access）：用于直接调用 RNNoise 原生降噪库，无需自己编译 JNI
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // pinyin4j：中文转拼音库（Maven Central，无需 JitPack，用于地名同音字模糊匹配）
    implementation("com.belerweb:pinyin4j:2.5.1")
}
