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
 * getDetectionThreshold/getAudioGain/resetSkipCounter 等签名均不变。
 *
 * 注意：
 *   - 增益/阈值修改采用"惰性重建"：唤醒线程下次 process() 时重建引擎（KWS 阈值在构造时
 *     编译进 ContextGraph），避免与正在运行的唤醒线程竞态。
 *   - 阈值只在构造时编译进 ContextGraph，**不存在运行时覆盖**；日志阈值取自 detectionThreshold。
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
    /** 唤醒词列表（与 keywords.txt 的 @ 后原始词一致，仅供显示/日志） */
    private static final String[] WAKE_WORDS = {"小飞", "小飞小飞", "您好小飞"};

    public static final int SAMPLE_RATE = 16000;
    /** 录音帧大小（保持旧 mel 方案的 16080≈1秒，WakeAudioThread 依赖此值分配缓冲） */
    public static final int AUDIO_SAMPLES_NEEDED = 16080;
    /** 流式处理块大小：0.1s = 1600 采样（与 sherpa KWS 内部子块粒度一致） */
    public static final int CHUNK_SAMPLES = SAMPLE_RATE / 10;

    // ===== 可调参数（静态字段，设置页读写） =====
    /** 音频增益系数：放大输入幅度，提高小声说话的检测率（唤醒阶段专用）。 */
    private static volatile float audioGain = 4.5f;
    /** 识别专用增益：指令识别阶段使用（与唤醒无关，sherpa-onnx 离线识别用）。 */
    private static volatile float asrGain = 8.0f;
    /** 灵敏度档位：0=低, 1=中(默认), 2=高 */
    private static volatile int sensitivityLevel = 1;
    // KWS 是触发式引擎：触发阈值 = KeywordSpotterConfig.keywordsThreshold（ContextGraph 编译期固化）。
    // 三档预设（KWS 语义重标定，2026-09 用户拍板中档 0.18）：
    // 低=0.25（官方默认，保守）/ 中=0.18（平衡，默认，车机验证值）/ 高=0.12（灵敏，行驶/小声）
    private static final float[] GAIN_BY_LEVEL = {3.5f, 4.5f, 5.5f};
    private static final float[] THRESHOLD_BY_LEVEL = {0.25f, 0.18f, 0.12f};
    private static final String[] LEVEL_NAMES = {"低", "中", "高"};
    private static volatile float detectionThreshold = THRESHOLD_BY_LEVEL[1];

    // 冷启动防误唤醒：跳过前 N 帧（每帧≈1秒，2帧≈2秒，滤麦克风启动爆音）
    private static final int STARTUP_SKIP_FRAMES = 2;
    /** 流式冷启动跳过块数（20块 = 2秒，等价原2帧语义；块=0.1s） */
    private static final int STARTUP_SKIP_BLOCKS = 20;
    private int framesProcessed = 0;
    /** 帧号计数器，用于调试日志时序定位 */
    private int frameCounter = 0;
    /** 流式块计数器（0.1s/块）；块号/10 ≈ 帧号，保持 [WakeProb] frame=N 的秒级语义 */
    private int blockCounter = 0;
    /** RMS 累计（流式调试日志：每 500 块 ≈ 50s 打印一次平均） */
    private double rmsAccum = 0;
    private int rmsBlocks = 0;

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

    /** 获取实际生效的阈值（仅供日志显示） */
    public static float getEffectiveThreshold() {
        return detectionThreshold;
    }

    // ===== 实例方法 =====

    public WakeWordEngine(Context context) {
        appContext = context.getApplicationContext();
        // ★ 懒加载：构造时不加载模型（5.4MB 主线程加载会吃穿 startForegroundService 的 5 秒窗口，
        //   导致 RemoteServiceException 杀进程）。首次 process()（唤醒线程）时 ensureLoaded() 加载，
        //   此时灵敏度/增益配置已应用（setSensitivity/setGainAndThreshold 置 rebuildRequested）。
        LogUtils.i(TAG, "KWS 引擎懒加载模式（首次唤醒时加载模型，唤醒词: " + getWakeWordDisplay() + "）");
    }

    /** 确保 KWS 引擎已加载（唤醒线程 process 前调用；WakeAudioThread 单线程调用，安全） */
    private boolean ensureLoaded() {
        if (spotter != null) return true;
        try {
            rebuildInternal(appContext);
            loaded = true;
            // 首次加载已应用最新阈值/增益（rebuildInternal 读取当前静态字段），
            // 清除重建标志，避免紧接的惰性重建再重复加载一次
            rebuildRequested = false;
            LogUtils.i(TAG, "KWS 引擎加载成功，唤醒词: " + getWakeWordDisplay()
                    + "（threshold=" + detectionThreshold + ", gain=" + audioGain + "）");
            return true;
        } catch (Exception e) {
            LogUtils.e(TAG, "Failed to load KWS models — check assets/kws/", e);
            errorMessage = e.getMessage();
            loaded = false;
            return false;
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
        model.setModelType("zipformer2"); // ★ 该 KWS 模型为 zipformer2 架构（metadata model_type=zipformer2），标错为 zipformer 会走 v1 解析强读 attention_dims 缺失导致 native _Exit 杀进程
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
        // ★ createStream("")：空串 = 使用 keywordsFile 中的全部关键词（ContextGraph 已编译）。
        //   切勿传中文逗号串——native 编码失败会导致进程崩溃（SIGSEGV）。
        stream = spotter.createStream("");
        framesProcessed = 0;
        frameCounter = 0;
        blockCounter = 0;
        rmsAccum = 0;
        rmsBlocks = 0;
    }

    public boolean isLoaded() { return loaded; }
    public String getErrorMessage() { return errorMessage; }

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

    /** 重置冷启动跳过计数（每次唤醒线程启动时调用） */
    public void resetSkipCounter() {
        framesProcessed = 0;
        debugLogCount = 0;
        frameCounter = 0;
        blockCounter = 0;
        rmsAccum = 0;
        rmsBlocks = 0;
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
        if (!ensureLoaded()) return null;
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
        // 启动保护期：前3帧忽略触发（KWS 阈值编译进 ContextGraph 无法临时放大，等效旧"阈值1.5倍"），
        // 但唤醒线程照常推理并打印音量日志（2026-10-15：与流式接口保持一致，5帧→3帧）
        boolean protectPeriod = frameCounter < 3;

        try {
            // short → float（-1~1），×音频增益（保持旧增益语义）
            if (reuseFloatAudio == null || reuseFloatAudio.length != audio.length) {
                reuseFloatAudio = new float[audio.length];
            }
            float[] floatAudio = reuseFloatAudio;
            for (int i = 0; i < audio.length; i++) {
                floatAudio[i] = audio[i] * audioGain / 32768.0f;
            }

            // ★ 流式喂法（官方用法）：无条件 acceptWaveform，内部按 0.1s 子块喂入（zipformer2
            //   chunk-8≈160ms 粒度）。每子块 accept 后 while is_ready → decode，且【每次 decode 后
            //   立即 getResult】——keyword result 在 decode 内部实时更新，若等到帧尾才读，命中假设
            //   会被后续 decode 的 beam 竞争覆盖（长关键词如"您好小飞"实测漏检）。命中即 reset 并
            //   停止本帧剩余喂入。
            final int SUB_BLOCK = SAMPLE_RATE / 10;  // 0.1s = 1600 采样
            String hitKw = null;
            for (int off = 0; off < floatAudio.length; off += SUB_BLOCK) {
                int len = Math.min(SUB_BLOCK, floatAudio.length - off);
                float[] chunk = java.util.Arrays.copyOfRange(floatAudio, off, off + len);
                stream.acceptWaveform(chunk, SAMPLE_RATE);
                while (spotter.isReady(stream)) {
                    spotter.decode(stream);
                    KeywordSpotterResult rr = spotter.getResult(stream);
                    if (rr != null && rr.getKeyword() != null && !rr.getKeyword().isEmpty()) {
                        // 命中即复位流（防止同帧连续触发），记录后跳出
                        spotter.reset(stream);
                        hitKw = rr.getKeyword();
                        break;
                    }
                }
                if (hitKw != null) break;  // 已命中，本帧剩余子块不再喂
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
                return null;
            }

            if (hitKw != null) {
                String kw = hitKw;

                String logLine = String.format(Locale.US,
                        "[WakeProb] frame=%d word=%s prob=1.0000 threshold=%.4f gain=%.1f rms=%.0f TRIGGER",
                        frameCounter, kw, getEffectiveThreshold(), audioGain, rms);
                LogUtils.d(TAG, logLine);

                return new DetectionResult(kw, 1.0f, 0f, 1);
            }

            return null;
        } catch (Exception e) {
            LogUtils.e(TAG, "KWS inference error", e);
            return null;
        }
    }

    /**
     * 流式处理（2026-10-15 新增）：每次喂 0.1s（1600 采样）音频块，块末立即检查触发。
     *
     * 相比 process(整帧批处理)，检测粒度从"帧末（≤1s 帧对齐 + 整帧推理）"降到"块末（0.1s）"：
     * 用户说完"小飞"约 0.1~0.2s 即可触发，消除唤醒线程欠实时时 1~2.5s 的排队延迟。
     * 推理总量不变（模型流式状态机连续推进，喂入内容相同），CPU 负载与原来一致。
     *
     * KWS 触发式语义不变：命中关键词返回 DetectionResult，未命中返回 null。
     * 冷启动跳过（20块=2s）与保护期（前50块=5s）保持原帧级语义（2帧/5帧）。
     */
    public DetectionResult processChunk(short[] chunk) {
        if (!ensureLoaded()) return null;

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

        // 冷启动跳过前 20 块（约2秒，滤麦克风启动爆音；原2帧语义等价）
        if (framesProcessed < STARTUP_SKIP_BLOCKS) {
            framesProcessed++;
            return null;
        }
        // 启动保护期：前 30 块（≈3s）忽略触发（原5s过保守，实测误唤醒没有；
        // 对话结束监听重启后用户 3~5s 内再次唤醒能及时响应）
        blockCounter++;
        boolean protectPeriod = blockCounter < 30;

        try {
            // short → float（-1~1），×音频增益（保持旧增益语义）
            if (reuseFloatAudio == null || reuseFloatAudio.length != chunk.length) {
                reuseFloatAudio = new float[chunk.length];
            }
            float[] floatAudio = reuseFloatAudio;
            for (int i = 0; i < chunk.length; i++) {
                floatAudio[i] = chunk[i] * audioGain / 32768.0f;
            }

            // 喂入 + 立即解码 + 立即取结果（keyword result 在 decode 内部实时更新，
            // 命中即 reset 防止同词连续触发）
            String hitKw = null;
            stream.acceptWaveform(floatAudio, SAMPLE_RATE);
            while (spotter.isReady(stream)) {
                spotter.decode(stream);
                KeywordSpotterResult rr = spotter.getResult(stream);
                if (rr != null && rr.getKeyword() != null && !rr.getKeyword().isEmpty()) {
                    spotter.reset(stream);
                    hitKw = rr.getKeyword();
                    break;
                }
            }

            // RMS 累计（调试日志：每 500 块 ≈ 50s 打印一次平均环境音量）
            float rms = 0;
            for (short s : chunk) rms += s * s;
            rms = (float) Math.sqrt(rms / chunk.length);
            rmsAccum += rms;
            rmsBlocks++;
            if (rmsBlocks >= 500) {
                LogUtils.d(TAG, String.format(Locale.US,
                        "[WakeFrame] frame=%d rms=%.0f gain=%.1f",
                        blockCounter / 10, rmsAccum / rmsBlocks, audioGain));
                rmsAccum = 0;
                rmsBlocks = 0;
            }

            if (protectPeriod) {
                return null;
            }

            if (hitKw != null) {
                String kw = hitKw;
                String logLine = String.format(Locale.US,
                        "[WakeProb] frame=%d word=%s prob=1.0000 threshold=%.4f gain=%.1f rms=%.0f TRIGGER",
                        blockCounter / 10, kw, getEffectiveThreshold(), audioGain, rms);
                LogUtils.d(TAG, logLine);

                return new DetectionResult(kw, 1.0f, 0f, 1);
            }

            return null;
        } catch (Exception e) {
            LogUtils.e(TAG, "KWS inference error", e);
            return null;
        }
    }

    /** 流式块大小（0.1s，WakeAudioThread 分配缓冲用） */
    public int getChunkSamples() { return CHUNK_SAMPLES; }

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
