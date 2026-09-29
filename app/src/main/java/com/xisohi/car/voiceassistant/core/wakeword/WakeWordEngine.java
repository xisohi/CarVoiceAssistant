package com.xisohi.car.voiceassistant.core.wakeword;

import android.content.Context;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.QnnConfig;
import com.xisohi.car.voiceassistant.core.LogUtils;

import java.util.Locale;

/**
 * Wake word inference engine — sherpa-onnx 官方 KWS（zipformer transducer，open vocabulary）。
 *
 * 替换原自研 melspectrogram.onnx + 分类器 pipeline：
 *   - 官方 3M 中英 KWS 模型（sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20，int8 组合 ~5.4MB）
 *   - 免训练自定义关键词：assets/kws/keywords.txt（当前：小飞 / 小飞小飞 / 您好小飞）
 *   - 触发式语义：KeywordSpotter 只在关键词完整命中时返回结果（ContextGraph 阈值），
 *     与旧"逐帧 sigmoid 概率"不同——日志/回调已相应调整为触发驱动。
 *
 * 对外接口保持兼容：process(short[])/setSensitivity/setGainAndThreshold/setAsrGain/
 * getDetectionThreshold/getAudioGain/resetSkipCounter/DetectionListener 等签名均不变。
 *
 * 注意：
 *   - 增益/阈值修改采用"惰性重建"：唤醒线程下次 process() 时重建引擎（KWS 阈值在构造时
 *     编译进 ContextGraph），避免与正在运行的唤醒线程竞态。
 *   - tempThresholdOverride 保留接口但不生效（KWS 不支持运行时阈值覆盖），仅供日志显示。
 */
public class WakeWordEngine {

    private static final String TAG = "WakeWordEngine";
    /** KWS 模型资源目录（assets 相对路径） */
    private static final String KWS_DIR = "kws";
    private static final String KWS_ENCODER = KWS_DIR + "/encoder.onnx";
    private static final String KWS_DECODER = KWS_DIR + "/decoder.onnx";
    private static final String KWS_JOINER = KWS_DIR + "/joiner.onnx";
    private static final String KWS_TOKENS = KWS_DIR + "/tokens.txt";
    private static final String KWS_KEYWORDS = KWS_DIR + "/keywords.txt";
    /** 唤醒词列表（与 keywords.txt 的 @ 后原始词一致，createStream 时显式传入） */
    private static final String[] WAKE_WORDS = {"小飞", "小飞小飞", "您好小飞"};
    private static final String WAKE_WORDS_CSV = "小飞,小飞小飞,您好小飞";

    public static final int SAMPLE_RATE = 16000;
    /** 录音帧大小（保持旧 mel 方案的 16080≈1秒，WakeAudioThread 依赖此值分配缓冲） */
    public static final int AUDIO_SAMPLES_NEEDED = 16080;

    // ===== 可调参数（静态字段，UI/校准逻辑兼容） =====
    /** 音频增益系数：放大输入幅度，提高小声说话的检测率（唤醒阶段专用）。 */
    private static volatile float audioGain = 4.5f;
    /** 识别专用增益：指令识别阶段使用（与唤醒无关，sherpa-onnx 离线识别用）。 */
    private static volatile float asrGain = 8.0f;
    /** 灵敏度档位：0=低, 1=中(默认), 2=高 */
    private static volatile int sensitivityLevel = 1;
    // KWS 是触发式引擎：触发阈值 = KeywordSpotterConfig.keywordsThreshold（ContextGraph 编译期固化）。
    // 三档预设（对齐官方默认 0.25）：
    // 低=0.30（保守，误唤醒少）/ 中=0.25（官方默认）/ 高=0.15（灵敏，适合行驶/小声）
    private static final float[] GAIN_BY_LEVEL = {3.5f, 4.5f, 5.5f};
    private static final float[] THRESHOLD_BY_LEVEL = {0.30f, 0.25f, 0.15f};
    private static final String[] LEVEL_NAMES = {"低", "中", "高"};
    private static volatile float detectionThreshold = THRESHOLD_BY_LEVEL[1];

    // 冷启动防误唤醒：跳过前 N 帧（每帧≈1秒，2帧≈2秒，滤麦克风启动爆音）
    private static final int STARTUP_SKIP_FRAMES = 2;
    private int framesProcessed = 0;
    /** 帧号计数器，用于调试日志时序定位 */
    private int frameCounter = 0;
    /** 临时阈值覆盖（KWS 不支持运行时生效，仅日志显示；-1 表示不覆盖） */
    private static volatile float tempThresholdOverride = -1f;

    // ===== KWS 引擎 =====
    private KeywordSpotter spotter;
    private OnlineStream stream;
    private Context appContext;
    private boolean loaded;
    private String errorMessage = null;
    /** 惰性重建标志：增益/阈值修改后置位，由唤醒线程下次 process() 时执行重建 */
    private static volatile boolean rebuildRequested = false;

    // 复用缓冲（单线程，仅 WakeAudioThread 调用 process()）
    private float[] reuseFloatAudio = null;

    // ===== 唤醒灵敏度测试日志（保持原接口） =====
    public interface TestLogListener {
        void onLog(String line);
    }

    /** 实时检测回调接口（校准向导用） */
    public interface DetectionListener {
        /**
         * 每帧检测结果回调
         * @param word 触发的唤醒词（null=未触发）
         * @param prob 触发概率（KWS 触发式语义：触发=1.0，未触发=0.0，非逐帧 sigmoid）
         * @param melMean 音频能量指标（KWS 无 mel，此参数为 RMS 音量）
         * @param triggered 是否触发
         */
        void onDetection(String word, float prob, float melMean, boolean triggered);
    }
    private static DetectionListener detectionListener = null;
    private static TestLogListener testLogListener = null;
    private static final java.util.List<String> testLogs = new java.util.ArrayList<>();
    private static boolean testLogging = false;
    private static String testScenario = "";

    /** Detection result with specific wake word name. */
    public static class DetectionResult {
        public final String wakeWord;
        public final float probability;
        /** Mean probability across ALL models — represents background noise level. */
        public final float backgroundMean;
        /** Per-model recommended consecutive frames. */
        public final int recommendedConsFrames;

        public DetectionResult(String wakeWord, float probability, float backgroundMean,
                               int recommendedConsFrames) {
            this.wakeWord = wakeWord;
            this.probability = probability;
            this.backgroundMean = backgroundMean;
            this.recommendedConsFrames = recommendedConsFrames;
        }
    }

    // ===== 静态接口（保持原签名） =====

    /** 设置灵敏度档位（0=低, 1=中, 2=高）；惰性重建，下次唤醒即生效 */
    public static void setSensitivity(int level) {
        if (level < 0 || level > 2) level = 1;
        sensitivityLevel = level;
        audioGain = GAIN_BY_LEVEL[level];
        detectionThreshold = THRESHOLD_BY_LEVEL[level];
        rebuildRequested = true;
        LogUtils.i(TAG, "灵敏度设置为: " + LEVEL_NAMES[level]
                + " (增益=" + audioGain + ", 阈值=" + detectionThreshold + ", 下次唤醒生效)");
    }

    /** 获取当前灵敏度档位 */
    public static int getSensitivity() { return sensitivityLevel; }

    /** 获取当前灵敏度名称 */
    public static String getSensitivityName() {
        if (sensitivityLevel < 0 || sensitivityLevel >= LEVEL_NAMES.length) return "自定义";
        return LEVEL_NAMES[sensitivityLevel];
    }

    /** 获取当前音频增益（唤醒阶段） */
    public static float getAudioGain() { return audioGain; }

    /** 获取当前检测阈值 */
    public static float getDetectionThreshold() { return detectionThreshold; }

    /** 获取识别专用增益（指令识别阶段） */
    public static float getAsrGain() { return asrGain; }

    /** 设置识别专用增益（建议 5.0~8.0，限幅 [5.0, 10.0]） */
    public static void setAsrGain(float gain) {
        asrGain = Math.max(5.0f, Math.min(10.0f, gain));
        LogUtils.i(TAG, "识别增益设置为: " + asrGain);
    }

    /** 直接设置增益和阈值（手动调参/校准用）；惰性重建，下次唤醒即生效 */
    public static void setGainAndThreshold(float gain, float threshold) {
        audioGain = gain;
        detectionThreshold = threshold;
        sensitivityLevel = -1;  // 自定义档位
        rebuildRequested = true;
        LogUtils.i(TAG, "唤醒参数已更新(自定义): gain=" + gain + ", threshold=" + threshold + "（下次唤醒生效）");
    }

    /** 获取实际生效的阈值（临时覆盖优先，仅供日志显示） */
    public static float getEffectiveThreshold() {
        return tempThresholdOverride > 0 ? tempThresholdOverride : detectionThreshold;
    }

    /** 设置临时阈值覆盖（KWS ContextGraph 阈值编译期固化，不支持运行时生效；保留接口兼容） */
    public static void setTempThresholdOverride(float threshold) {
        tempThresholdOverride = threshold;
        LogUtils.i(TAG, "临时阈值覆盖(仅显示，KWS引擎不支持运行时生效): " + (threshold < 0 ? "清除" : threshold));
    }

    /** 设置实时检测回调 */
    public static void setDetectionListener(DetectionListener listener) {
        detectionListener = listener;
    }

    /** 设置测试日志监听器 */
    public static void setTestLogListener(TestLogListener listener) {
        testLogListener = listener;
    }

    /** 开始记录测试日志 */
    public static void startTestLogging(String scenario) {
        testScenario = scenario;
        testLogging = true;
        testLogs.clear();
        testLogs.add("=== 测试场景: " + scenario + " ===");
        testLogs.add("时间, 唤醒词, 概率, 阈值, 增益, 是否触发");
    }

    /** 停止记录测试日志 */
    public static void stopTestLogging() {
        testLogging = false;
    }

    /** 获取测试日志 */
    public static java.util.List<String> getTestLogs() {
        return new java.util.ArrayList<>(testLogs);
    }

    /** 清除测试日志 */
    public static void clearTestLogs() {
        testLogs.clear();
    }

    // ===== 实例方法 =====

    public WakeWordEngine(Context context) {
        appContext = context.getApplicationContext();
        try {
            // 构造时立即加载引擎（首次唤醒即用默认参数）
            rebuildInternal(appContext);
            loaded = true;
            LogUtils.i(TAG, "KWS 引擎加载成功，唤醒词: " + getWakeWordDisplay()
                    + "（threshold=" + detectionThreshold + ", gain=" + audioGain + "）");
        } catch (Exception e) {
            LogUtils.e(TAG, "Failed to load KWS models — check assets/kws/", e);
            errorMessage = e.getMessage();
            loaded = false;
        }
    }

    /** 重建 KWS 引擎（阈值/增益变更时由唤醒线程惰性调用，单线程安全） */
    private void rebuildInternal(Context context) throws Exception {
        if (spotter != null) { try { spotter.release(); } catch (Exception ignored) {} }
        if (stream != null) { try { stream.release(); } catch (Exception ignored) {} }

        FeatureConfig feat = new FeatureConfig();
        feat.setSampleRate(SAMPLE_RATE);
        feat.setFeatureDim(80);
        feat.setDither(0.0f);

        OnlineTransducerModelConfig trans = new OnlineTransducerModelConfig(
                KWS_ENCODER, KWS_DECODER, KWS_JOINER, new QnnConfig("", "", ""));

        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(trans);
        model.setTokens(KWS_TOKENS);
        model.setNumThreads(2);
        model.setProvider("cpu");
        model.setModelType("zipformer");
        model.setModelingUnit("phone+ppinyin");

        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setFeatConfig(feat);
        config.setModelConfig(model);
        config.setMaxActivePaths(4);
        config.setKeywordsFile(KWS_KEYWORDS);
        config.setKeywordsScore(1.0f);
        config.setKeywordsThreshold(detectionThreshold);
        config.setNumTrailingBlanks(1);

        spotter = new KeywordSpotter(context.getAssets(), config);
        // 显式传入关键词（与 keywords.txt 一致），不依赖 createStream 的 keywordsFile 解析
        stream = spotter.createStream(WAKE_WORDS_CSV);
        framesProcessed = 0;
        frameCounter = 0;
    }

    public boolean isLoaded() { return loaded; }
    public String getErrorMessage() { return errorMessage; }

    /** mel 帧数（KWS 无 mel，返回 1 兼容旧调用方） */
    public int getMelFramesNeeded() { return 1; }
    /** 录音帧大小（保持不变，WakeAudioThread 依赖） */
    public int getAudioSamplesNeeded() { return AUDIO_SAMPLES_NEEDED; }

    /** Pipe-separated display string of all wake words. */
    public String getWakeWordDisplay() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < WAKE_WORDS.length; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(WAKE_WORDS[i]);
        }
        return sb.toString();
    }

    /** Number of wake word models loaded. */
    public int getModelCount() { return WAKE_WORDS.length; }
    public boolean isDscnnMode() { return false; }

    /** 重置冷启动跳过计数（每次唤醒线程启动时调用） */
    public void resetSkipCounter() {
        framesProcessed = 0;
        debugLogCount = 0;
        frameCounter = 0;
        LogUtils.d(TAG, "冷启动跳过计数已重置");
    }

    private int debugLogCount = 0;
    private static final int DEBUG_LOG_MAX = 50;

    /**
     * Run inference on raw 16-bit PCM audio (16kHz, ~1s frame).
     *
     * KWS 触发式语义：命中关键词才返回 DetectionResult；未命中返回 null。
     * 调用方（WakeAudioThread）判断 result != null && wakeWord != null 即触发。
     */
    public DetectionResult process(short[] audio) {
        if (!loaded || spotter == null || stream == null) return null;
        frameCounter++;

        // 惰性重建：增益/阈值修改后，下次 process 时重建引擎（单线程安全）
        if (rebuildRequested) {
            try {
                rebuildInternal(appContext);
                rebuildRequested = false;
                LogUtils.i(TAG, "KWS 引擎已重建（新参数生效）: threshold=" + detectionThreshold + ", gain=" + audioGain);
            } catch (Exception e) {
                LogUtils.e(TAG, "KWS 引擎重建失败，保持旧引擎", e);
                rebuildRequested = false;
            }
        }

        // 冷启动跳过前 N 帧（约2秒，滤麦克风启动爆音；原5秒过久，对话结束立刻唤醒被吞）
        if (framesProcessed < STARTUP_SKIP_FRAMES) {
            framesProcessed++;
            return null;
        }
        // 启动保护期：前5帧忽略触发（KWS 阈值编译进 ContextGraph 无法临时放大，等效旧"阈值1.5倍"），
        // 但保持喂音频与回调，校准向导/音量显示不中断
        boolean protectPeriod = frameCounter < 5;

        try {
            // short → float（-1~1），×音频增益（保持旧增益语义）
            if (reuseFloatAudio == null || reuseFloatAudio.length != audio.length) {
                reuseFloatAudio = new float[audio.length];
            }
            float[] floatAudio = reuseFloatAudio;
            for (int i = 0; i < audio.length; i++) {
                floatAudio[i] = audio[i] * audioGain / 32768.0f;
            }

            if (spotter.isReady(stream)) {
                stream.acceptWaveform(floatAudio, SAMPLE_RATE);
                spotter.decode(stream);
            }

            // 每50帧打印音频整体状态（判断环境噪音水平）
            float rms = 0;
            for (short s : audio) rms += s * s;
            rms = (float) Math.sqrt(rms / audio.length);
            if (frameCounter % 50 == 0) {
                LogUtils.d(TAG, String.format(Locale.US,
                        "[WakeFrame] frame=%d rms=%.0f gain=%.1f",
                        frameCounter, rms, audioGain));
            }

            if (protectPeriod) {
                if (detectionListener != null) {
                    detectionListener.onDetection(null, 0f, rms, false);
                }
                return null;
            }

            KeywordSpotterResult r = spotter.getResult(stream);
            if (r != null && r.getKeyword() != null && !r.getKeyword().isEmpty()) {
                String kw = r.getKeyword();
                // 触发后复位流，等待下一次唤醒
                spotter.reset(stream);

                String logLine = String.format(Locale.US,
                        "[WakeProb] frame=%d word=%s prob=1.0000 threshold=%.4f gain=%.1f rms=%.0f TRIGGER",
                        frameCounter, kw, getEffectiveThreshold(), audioGain, rms);
                LogUtils.d(TAG, logLine);

                // 测试日志记录
                if (testLogging) {
                    String time = new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                            .format(new java.util.Date());
                    String csvLine = String.format(Locale.US,
                            "%s, %s, 1.000, %.2f, %.1f, YES",
                            time, kw, getEffectiveThreshold(), audioGain);
                    testLogs.add(csvLine);
                    if (testLogListener != null) {
                        testLogListener.onLog(csvLine);
                    }
                }

                // 实时检测回调（触发时）
                if (detectionListener != null) {
                    detectionListener.onDetection(kw, 1.0f, rms, true);
                }

                return new DetectionResult(kw, 1.0f, 0f, 1);
            }

            // 未触发：每帧回调（校准向导需要持续数据）
            if (detectionListener != null) {
                detectionListener.onDetection(null, 0f, rms, false);
            }
            return null;
        } catch (Exception e) {
            LogUtils.e(TAG, "KWS inference error", e);
            return null;
        }
    }

    public void close() {
        try {
            if (stream != null) stream.release();
            if (spotter != null) spotter.release();
            stream = null;
            spotter = null;
            // 注意：sherpa-onnx 由 JNI 管理生命周期，release() 已释放引擎资源，无需关闭全局环境。
        } catch (Exception e) {
            LogUtils.e(TAG, "Error closing KWS engine", e);
        }
    }
}
