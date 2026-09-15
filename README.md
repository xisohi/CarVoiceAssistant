# 车载语音助手

面向 **32 位安卓车机（Allwinner 全志/鼎微方案，Android 10，armeabi-v7a）** 的语音助手 APP。**有网络时优先使用百度在线语音识别（识别率更高），无网络时自动回退到 Vosk 离线识别**，进出隧道、地库、无信号路段都不受影响。

## 功能特性

- **双引擎识别**：百度在线识别（优先，识别率更高）+ Vosk 离线识别（兜底，无网络自动切换）
- **谁使用谁的 Key**：百度语音识别的 App ID/API Key/Secret Key 由用户在设置页自行填写，不内置开发者 Key
- **首次联网仅用于初始化**：检测到本地无模型时引导下载（断点续传 + MD5 校验）
- **三唤醒词常驻监听**：支持"小娜"、"你好小娜"、"小娜小娜"，前台服务 + 低功耗唤醒，播报期间自动暂停监听（防回声误触发）
- **车控封闭域语义**：本地规则引擎（模板 + 槽位 + 正则），23 个内置意图，意图模板可热更新
- **地名拼音模糊匹配**：pinyin4j + Levenshtein 距离，支持 57 个内置地名，可自行扩展
- **同音字纠正**：内置同音字纠正机制，提高识别准确率
- **RNNoise 深度学习降噪**：唤醒阶段启用 RNNoise 降噪，提高嘈杂环境下的唤醒率
- **自适应静音判定**：RMS 能量端点检测 + 动态静音阈值，长句中间停顿不截断
- **可调增益**：唤醒阈值、音频增益、识别增益三档可调，支持低/中/高 预设
- **自动校准**：一键自动校准环境噪音阈值和增益参数
- **支持指令**：音量调节、音乐控制、导航、空调/车窗（需接入厂商 SDK）、蓝牙、WiFi、报时、天气、帮助、打开应用
- **armv7 友好**：Vosk 带 32 位原生库，模型约 42MB；百度 SDK 支持 armeabi-v7a/arm64-v8a
- **机器人卡通悬浮窗**：唤醒时显示卡通机器人，识别状态可视化
- **导出 U 盘**：支持导出日志和配置到 U 盘，方便调试
- **开机自启**：WorkManager + BootReceiver 双重兜底，确保车机开机后自动启动语音服务

## 技术选型

| 模块 | 方案 | 说明 |
| --- | --- | --- |
| 唤醒词 KWS | ONNX 自定义模型 | `multi_xiaona.onnx`，三唤醒词（小娜/你好小娜/小娜小娜），低功耗常驻监听 |
| 语音识别 ASR（在线） | 百度语音识别 SDK | `bdasr_aipd_V3_20250717`，AipeEventManagerFactory 动态传入用户 Key，infile 模式识别 |
| 语音识别 ASR（离线） | Vosk (small-cn) | 离线中文识别，模型约 42MB；支持 armv7；无网络时自动回退 |
| 降噪 | RNNoise + AudioRecord 内置 | RNNoise 深度学习降噪（唤醒阶段）；NoiseSuppressor/AutomaticGainControl（硬件支持时） |
| 端点检测 VAD | RMS 能量 + 动态阈值 | 自适应静音阈值，已识别内容→3000ms，未识别→1500ms |
| 语义理解 NLU | 本地规则引擎 | 模板 + 槽位 + 正则；意图配置 `intents.json`，23 个内置意图，可热更新 |
| 地名匹配 | pinyin4j + Levenshtein | 拼音模糊匹配，57 个内置地名，支持同音字纠正 |
| 语音合成 TTS | 系统离线 TTS | 多数车机预装中文离线音色；可换 sherpa-onnx VITS |
| 模型下载 | OkHttp Range 断点续传 | 支持续传 + MD5 校验 + 解压安装 |
| 开机自启 | WorkManager + BootReceiver | 双重兜底，确保车机开机后自动启动语音服务 |
| 悬浮窗 | WindowManager + 自定义 View | 机器人卡通悬浮窗，唤醒/识别/播报状态可视化 |

> 百度语音识别免费额度：个人认证 15万次/180天，企业认证 200万次/180天，到期清零不重置。

## 架构

```
┌─ 应用层 ─────────────────────────────────────┐
│ MainActivity（设置页：状态/配置/灵敏度/日志）  │
│ CalibrationActivity（自动校准）                │
│ LogViewerActivity（日志查看）                  │
│ PlaceManagerActivity（地名词库管理）            │
└──────────────┬───────────────────────────────┘
┌─ 服务层 ─────────────────────────────────────┐
│ VoiceAssistantService（前台服务）              │
│ 状态机: IDLE→LISTENING→PROCESSING→SPEAKING   │
│ FloatViewService（悬浮窗服务）                 │
└──────────────┬───────────────────────────────┘
┌─ 引擎层 ─────────────────────────────────────┐
│ WakeWordEngine(ONNX 唤醒)                     │
│   → BaiduAsrManager(百度在线识别，优先)        │
│   → SpeechRecognizer(Vosk 离线识别，兜底)      │
│   → IntentParser(规则引擎 NLU)                 │
│   → PlaceMatcher(地名拼音匹配)                 │
│   → SkillExecutor(执行器)                      │
│   → TtsEngine(离线 TTS)                        │
│   → RnNoiseDenoiser(深度学习降噪)              │
│   → AudioNoiseReducer(音频降噪管理)            │
└──────────────┬───────────────────────────────┘
┌─ 模型层（filesDir/va/，私有目录）─────────────┐
│ models/asr/   Vosk 模型（识别用）              │
│ assets/       ONNX 唤醒模型 + 配置             │
│   multi_xiaona.onnx  唤醒词模型                │
│   melspectrogram.onnx  梅尔频谱模型            │
│   intents.json  意图定义（23个）               │
│   places.json   地名词库（57个）               │
└───────────────────────────────────────────────┘
```

## 目录结构

```
CarVoiceAssistant/
├── app/
│   ├── build.gradle.kts        # 依赖与构建配置（含模型包地址）
│   ├── libs/
│   │   └── bdasr_aipd_V3_20250717_*.aar  # 百度语音识别 SDK
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── intents.json     # 意图模板（23个，正则 + 槽位）
│       │   ├── places.json      # 地名词库（57个，拼音匹配）
│       │   ├── multi_xiaona.onnx  # 唤醒词模型（小娜/你好小娜/小娜小娜）
│       │   ├── melspectrogram.onnx  # 梅尔频谱模型
│       │   └── model_info.json  # 模型信息
│       ├── java/com/xisohi/car/voiceassistant/
│       │   ├── MainActivity.kt            # 设置页（状态/配置/灵敏度/日志）
│       │   ├── VoiceAssistantApp.kt       # Application 类
│       │   ├── BootReceiver.kt            # 开机自启广播
│       │   ├── CalibrationActivity.kt     # 自动校准页面
│       │   ├── LogViewerActivity.kt       # 日志查看页面
│       │   ├── PlaceManagerActivity.kt    # 地名词库管理页面
│       │   ├── core/
│       │   │   ├── VoiceAssistantService.kt  # 前台服务编排（状态机）
│       │   │   ├── BaiduAsrManager.kt        # 百度语音识别封装（单例模式）
│       │   │   ├── WakeWordEngine.kt         # ONNX 唤醒词检测
│       │   │   ├── SpeechRecognizer.kt       # Vosk 离线识别
│       │   │   ├── IntentParser.kt           # 规则引擎 NLU
│       │   │   ├── PlaceMatcher.kt           # 地名拼音模糊匹配
│       │   │   ├── SkillExecutor.kt          # 执行器（车控扩展点）
│       │   │   ├── TtsEngine.kt              # 离线 TTS
│       │   │   ├── RnNoiseDenoiser.kt        # RNNoise 深度学习降噪
│       │   │   ├── AudioNoiseReducer.kt      # 音频降噪管理
│       │   │   ├── FloatViewService.kt       # 机器人悬浮窗服务
│       │   │   ├── AutoStartWorker.kt        # WorkManager 开机自启兜底
│       │   │   └── LogUtils.kt               # 文件日志工具
│       │   └── download/
│       │       ├── ModelManager.kt           # 模型目录与状态
│       │       ├── ModelDownloader.kt        # 断点续传下载
│       │       └── ModelInstaller.kt         # 解压安装（防 zip-slip）
│       ├── jniLibs/
│       │   ├── armeabi-v7a/librnnoise.so    # RNNoise 32位原生库
│       │   ├── arm64-v8a/librnnoise.so      # RNNoise 64位原生库
│       │   ├── x86/librnnoise.so             # RNNoise x86 原生库
│       │   └── x86_64/librnnoise.so          # RNNoise x86_64 原生库
│       └── res/                  # 布局 / 主题 / 图标
└── build.gradle.kts / settings.gradle.kts / gradlew(.bat)
```

## 构建

环境要求：JDK 17 + Android Studio（或 Gradle 8.7 命令行）。

1. 用 Android Studio **打开项目根目录**（`CarVoiceAssistant/`），等待 Gradle 同步。
2. 命令行构建：`gradlew.bat assembleDebug`，产物在 `app/build/outputs/apk/debug/`。
3. 原生库已打包 armeabi-v7a / arm64-v8a（真实车机）+ x86_64 / x86（雷电等 PC 模拟器调试）；安装后先在主界面完成"下载离线语音包"。
4. 百度语音识别 SDK 已通过 AAR 方式集成，无需额外配置。

> 本机若提示需要下载 Gradle 8.7 / Android SDK，按 Android Studio 引导完成即可。

## 部署前必须准备的 3 件事

### 1. Vosk 中文模型（必须，离线识别用）
- 下载：https://alphacephei.com/vosk/models 中的 `vosk-model-small-cn-0.22`（约 42MB，适合 32 位车机；资源充足可换大模型 `vosk-model-cn-0.22`）
- 解压后确认目录内包含 `am/`、`conf/`、`graph/` 等，重命名为 `model/`
- 唤醒词当前为"小娜"、"你好小娜"、"小娜小娜"（ONNX 模型，可自行训练替换）

### 2. 模型包打包与托管（必须）
把 Vosk 模型打包为 `models.zip`，**目录结构必须如下**：

```
models.zip
└── asr/model/             # Vosk 模型根（含 am/ conf/ graph/）
```

上传到你的下载服务器（或对象存储），然后：
- `app/build.gradle.kts` → `MODEL_PACK_URL` 填 zip 地址
- `MODEL_PACK_MD5` 填 zip 的 MD5（可选，填写则强制校验）

### 3. 百度语音识别配置（推荐，在线识别用）
**谁使用谁的 Key**：不内置开发者 Key，用户在设置页自行填写。

1. 登录百度智能云控制台：https://console.bce.baidu.com/ai/
2. 创建应用，开通"语音识别"服务（个人认证免费 15万次）
3. **重要**：创建应用时，语音包名必须填 `com.xisohi.car.voiceassistant`
4. 获取 App ID（9位数字）、API Key（24位）、Secret Key（32位）
5. 在 APP 设置页填写并保存，或通过配置文件导入

**配置文件格式**（任意文件名，`.json` 后缀）：
```json
{
  "app_id": "198506079",
  "api_key": "QRfgvs5lvNfT7Gg8Aevzx88L",
  "secret_key": "qkAAIhhvMtPZIHVIIz6lNWKnTeTfJZlPr"
}
```

> 不配置百度语音识别也可以使用，APP 会自动使用 Vosk 离线识别。

## 设置页功能

设置页采用紧凑布局，包含以下功能模块：

### 服务状态 + 权限设置 + 离线模型（三列卡片）
- **服务状态**：显示当前服务状态（已停止/运行中）、语音状态，一键启动/停止服务
- **权限设置**：悬浮窗权限检测、开机自启开关、系统自启设置跳转
- **离线模型**：模型状态检测、下载进度、一键下载语音包

### 状态明细条
- 最后识别意图显示
- 录音 RMS 实时显示
- TTS 状态显示 + TTS 设置跳转

### 唤醒灵敏度
- 低/中/高 三档预设按钮
- 检测阈值滑块（0.001~0.1）
- 音频增益滑块（1.0~10.0x）
- 识别增益滑块（3.0~8.0x）
- 自动校准 / 应用参数 / 恢复默认 / 同音字词库 / 查看日志 五个功能按钮

### 百度语音识别配置
- App ID 输入框（整行）
- API Key + Secret Key 输入框（两列，支持密码显示/隐藏）
- 保存配置 / 测试连接 / 导入配置 三个按钮（一行三列）
- 配置状态显示（未配置/已配置/初始化中/初始化成功/初始化失败）

### 运行日志
- 实时显示 APP 运行日志
- 可滚动查看历史日志
- 支持导出到 U 盘

## 32 位车机兼容要点

- **NDK 验证**：Vosk AAR 和百度 SDK 均含 `armeabi-v7a`，但不同车机 ROM 的权限与音频策略差异大，务必在**真实车机**上验证：
  - 唤醒后能否正常打开第二个 AudioRecord（部分 ROM 对并发录音有限制，本实现已做"唤醒→释放→再录音"的顺序处理）
  - 麦克风增益、风噪/胎噪下的误唤醒率（ONNX 模型只识别唤醒词，误唤醒率较低）
  - 内存占用：Vosk small 模型推理约几十 MB，唤醒和识别不同时运行，2GB 内存车机可运行
- **回声消除**：车机播报时麦克风会收到喇叭声，量产前建议上**双麦阵列 + AEC**，并保持"播报期间不响应唤醒"的策略（已内置）
- **U盘路径**：鼎微/全志方案 U 盘通常挂载在 `/storage/usb1`，APP 支持自动检测多个 U 盘路径
- **开机自启**：部分车机 ROM 限制第三方应用自启，APP 已使用 WorkManager + BootReceiver 双重兜底，仍需在系统设置中手动允许自启

## 扩展：接入车机厂商 SDK（空调/车窗等）

`SkillExecutor` 提供了 `CarControlProvider` 接口，实现后注入即可：

```kotlin
skillExecutor.carControlProvider = object : SkillExecutor.CarControlProvider {
    override fun setClimate(on: Boolean) = CarSdk.setAc(on)          // 厂商 SDK
    override fun setTemperature(degree: Int) = CarSdk.setTemp(degree)
    override fun openWindow(position: String) = CarSdk.window(position, true)
    override fun closeWindow(position: String) = CarSdk.window(position, false)
}
```

未接入时，空调/车窗指令会返回明确提示（不会静默失败）。

## 扩展：自定义意图

编辑 `assets/intents.json`（或运行时放入 `filesDir/va/config/intents.json` 实现热更新）：

```json
{
  "id": "volume.set",
  "action": "volume.set",
  "slots": ["value"],
  "grammar": [],
  "patterns": [
    { "re": "音量\\s*(调到|设为)?\\s*(?<value>\\d{1,2})" }
  ]
}
```

- `action` 对应 `SkillExecutor.execute()` 里的分支；新增动作需同步扩展执行器
- `grammar` 是**可选的** Vosk 识别域限定词表（JSGF 短语，`[unk]` 表示任意词）。默认全空 = 自由识别 + 正则抽取，开箱即用；识别准确率不足时再按意图填写 grammar 并逐条实测

## 扩展：自定义地名

编辑 `assets/places.json`（或在设置页"词库"中管理）：

```json
[
  {"name": "蚌埠站", "pinyin": "bengbuzhan"},
  {"name": "银泰城", "pinyin": "yintaicheng"}
]
```

- APP 使用 pinyin4j 自动生成拼音，也可手动指定
- 匹配时使用 Levenshtein 距离计算相似度，支持同音字和近音字
- 导航指令会自动拉起高德地图车机版（`androidauto://keywordNavi`）

## 已知限制

- 自由听写模式下 small 模型对复杂句准确率有限——车控指令简短，配合百度在线识别可显著提升
- 天气等需要外部数据的技能需要网络连接，离线模式下返回友好提示
- 音乐控制当前通过媒体按键广播，部分车机 ROM 可能不响应，建议接厂商媒体 SDK
- 首次下载依赖你的模型托管服务器可用性；生产环境建议支持断点续传（已实现）与失败重试
- 百度语音识别需要网络连接，无网络时自动回退到 Vosk 离线识别
- 空调/车窗等车控功能需要接入厂商 SDK，未接入时返回提示

## 安全与合规提示

- 百度语音识别的语音数据会上传到百度服务器进行识别，请在隐私政策中明确说明
- Vosk 离线识别的语音数据全部本地处理，不上传
- **谁使用谁的 Key**：百度语音识别的 App ID/API Key/Secret Key 由用户自行填写，不内置开发者 Key
- 接入厂商 SDK 前确认其授权协议；Vosk 为 Apache 2.0 开源协议，可自由商用
- RNNoise 为 BSD 开源协议，可自由商用
- pinyin4j 为 GPLv2 开源协议，商用需注意

## 许可证

本项目采用 Apache License 2.0 开源协议。

- Vosk：Apache License 2.0
- RNNoise：BSD License
- pinyin4j：GPLv2
- 百度语音识别 SDK：百度智能云服务协议
- ONNX Runtime：MIT License
