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

        // 首次下载模型包的地址（sherpa-onnx 中文流式模型，约 31MB，识别准确率高于 Vosk small）
        // 流式 Zipformer 14M 小模型：sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23（推荐车机）
        // 更高准确率可换：sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30 等
        // 模型包需上传到该地址（sherpa-onnx 模型，asr/model/ 结构，约 25.4MB）
        buildConfigField("String", "MODEL_PACK_URL", "\"https://lcjly.cn/car/sherpa/models.zip\"")
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
        // sherpa-onnx AAR 自带 libonnxruntime.so，与 libs/onnxruntime-android-1.27.1.aar（唤醒词用）同名 so 冲突。
        // 两者版本已对齐（均为 onnxruntime 1.27.1），pickFirst 保留任意一个均可正常链接。
        jniLibs {
            pickFirsts += "lib/armeabi-v7a/libonnxruntime.so"
            pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
            pickFirsts += "lib/x86/libonnxruntime.so"
            pickFirsts += "lib/x86_64/libonnxruntime.so"
        }
    }
}

dependencies {
    // 百度语音识别 SDK + sherpa-onnx 离线识别引擎 + onnxruntime（唤醒词 ONNX 推理）
    // 全部直接引用 libs/ 下的 AAR（Gradle 自动提取 so/合并 Manifest/处理资源）
    //
    // ⚠ 版本对齐说明（崩溃修复，勿随意改动）：
    // onnxruntime 从 1.27 起对导出符号启用版本化（如 OrtGetApiBase@@VERS_1.27.1），
    // Android linker 按"符号版本"精确匹配，因此下面三者必须完全一致：
    //   1. sherpa-onnx AAR 内置的 onnxruntime（libsherpa-onnx-jni.so 引用 OrtGetApiBase@VERS_1.27.1）
    //   2. libs/onnxruntime-android-1.27.1.aar（唤醒词 ai.onnxruntime Java 绑定的 JNI 层引用 @VERS_1.27.1）
    //   3. 任一版本不一致 → UnsatisfiedLinkError: cannot locate symbol "OrtGetApiBase"
    // 已实测：sherpa-onnx v1.13.8 内置 onnxruntime 1.28.2（VERS_1.28.2），而 Maven Central 的
    // onnxruntime-android 只有 1.28.0/1.29.0/1.30.0（VERS_1.28.0/1.29.0/1.30.0），无 1.28.2 包，
    // 两者无法匹配 → 本项目固定在 sherpa-onnx v1.13.7 + onnxruntime 1.27.1（官方 NuGet 渠道）。
    // sherpa-onnx 1.13.7 AAR：https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar
    // onnxruntime-android 1.27.1 AAR：NuGet 包 Microsoft.ML.OnnxRuntime 1.27.1 内 runtimes/android/native/onnxruntime.aar
    // 两者均含 armeabi-v7a / arm64-v8a / x86 / x86_64 原生库（32 位老车机用 armeabi-v7a）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 唤醒词 ONNX 推理（ai.onnxruntime Java API）不再走 Maven 依赖，
    // 改用 libs/onnxruntime-android-1.27.1.aar（Maven Central 无 1.27.1；1.28.0+ 与 sherpa 1.13.7 符号版本不匹配）
    // 断点续传下载
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JNA（Java Native Access）：用于直接调用 RNNoise 原生降噪库，无需自己编译 JNI
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // pinyin4j：中文转拼音库（Maven Central，无需 JitPack，用于地名同音字模糊匹配）
    implementation("com.belerweb:pinyin4j:2.5.1")

    // WorkManager：周期性任务调度，重启后自动恢复，不依赖开机广播
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
