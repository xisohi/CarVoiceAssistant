package com.xisohi.car.voiceassistant

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xisohi.car.voiceassistant.core.wakeword.WakeWordEngine
import java.util.Locale
import kotlin.math.ln

/**
 * 唤醒灵敏度自动校准向导
 * 四步测试：静音→正常音量→小声→干扰，自动计算最佳 gain 和 threshold
 */
class CalibrationActivity : AppCompatActivity() {

    companion object {
        private const val STEP_SILENT = 0
        private const val STEP_NORMAL = 1
        private const val STEP_QUIET = 2
        private const val STEP_NOISE = 3
        private const val STEP_RESULT = 4

        private const val DURATION_SILENT = 30_000L  // 静音测试30秒
        private const val DURATION_NOISE = 30_000L   // 干扰测试30秒
        private const val TARGET_COUNT = 10            // 正常/小声测试需要10次
    }

    private lateinit var tvStep: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvLiveData: TextView
    private lateinit var tvCurrentParams: TextView
    private lateinit var btnResetParams: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnNext: Button

    private val handler = Handler(Looper.getMainLooper())
    private var currentStep = STEP_SILENT
    private var isRunning = false

    // 收集的数据
    private val silentProbs = mutableListOf<Float>()
    private val normalProbs = mutableListOf<Float>()
    private val quietProbs = mutableListOf<Float>()
    private val noiseProbs = mutableListOf<Float>()

    // 计时
    private var startTime = 0L
    private var triggerCount = 0

    // 计算结果
    private var calculatedGain = 1.5f
    private var calculatedThreshold = 0.5f

    private val detectionListener = object : WakeWordEngine.DetectionListener {
        override fun onDetection(word: String?, prob: Float, melMean: Float, triggered: Boolean) {
            // 数据记录直接在回调线程做，避免 runOnUiThread 异步导致时机错误
            when (currentStep) {
                STEP_SILENT -> if (isRunning) silentProbs.add(prob)
                STEP_NOISE -> if (isRunning) noiseProbs.add(prob)
                STEP_NORMAL -> if (isRunning && triggered) {
                    normalProbs.add(prob)
                    triggerCount++
                }
                STEP_QUIET -> if (isRunning && triggered) {
                    quietProbs.add(prob)
                    triggerCount++
                }
            }
            // UI 更新放到主线程
            runOnUiThread {
                updateLiveData(prob, melMean, triggered)
                if (currentStep == STEP_NORMAL || currentStep == STEP_QUIET) {
                    updateProgress()
                }
            }
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val elapsed = System.currentTimeMillis() - startTime
            val duration = when (currentStep) {
                STEP_SILENT -> DURATION_SILENT
                STEP_NOISE -> DURATION_NOISE
                else -> 0L
            }
            if (duration > 0) {
                val remaining = (duration - elapsed) / 1000
                tvProgress.text = "剩余 ${remaining}秒"
                progressBar.progress = (elapsed * 100 / duration).toInt()
                if (elapsed >= duration) {
                    finishStep()
                    return
                }
            }
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        tvStep = findViewById(R.id.tvStep)
        tvInstruction = findViewById(R.id.tvInstruction)
        tvProgress = findViewById(R.id.tvProgress)
        tvLiveData = findViewById(R.id.tvLiveData)
        tvCurrentParams = findViewById(R.id.tvCurrentParams)
        btnResetParams = findViewById(R.id.btnResetParams)
        progressBar = findViewById(R.id.progressBar)
        btnStart = findViewById(R.id.btnStart)
        btnNext = findViewById(R.id.btnNext)

        btnStart.setOnClickListener { startStep() }
        btnNext.setOnClickListener { goNext() }
        btnResetParams.setOnClickListener {
            WakeWordEngine.setSensitivity(1)  // 重置为中档预设
            updateCurrentParams()
            Toast.makeText(this, "已重置为默认参数（中档）", Toast.LENGTH_SHORT).show()
        }

        updateCurrentParams()

        WakeWordEngine.setDetectionListener(detectionListener)
        showStep()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(timerRunnable)
        WakeWordEngine.setDetectionListener(null)
    }

    /**
     * 更新当前参数显示，如果参数是极端值则给出警告
     */
    private fun updateCurrentParams() {
        val threshold = WakeWordEngine.getDetectionThreshold()
        val gain = WakeWordEngine.getAudioGain()
        val isExtreme = (gain > 3.0f || threshold < 0.05f)
        val isRecommended = (threshold in 0.10f..0.35f && gain in 1.5f..2.5f)

        val statusText = when {
            isExtreme -> "⚠️ 当前参数较极端，可能影响校准准确性，建议重置为默认"
            isRecommended -> "✅ 当前参数在推荐范围内，适合校准测试"
            else -> "⚠️ 当前参数偏离推荐范围，建议重置为默认"
        }

        tvCurrentParams.text = "当前：threshold=${String.format("%.2f", threshold)}, gain=${String.format("%.1f", gain)}x\n$statusText\n推荐：threshold=0.10~0.35, gain=1.5~2.5x（中档默认）"
    }

    private fun showStep() {
        isRunning = false
        handler.removeCallbacks(timerRunnable)
        btnStart.isEnabled = true
        btnNext.isEnabled = false
        progressBar.progress = 0
        tvLiveData.text = "实时数据：等待开始"

        when (currentStep) {
            STEP_SILENT -> {
                tvStep.text = "第1步/共4步：静音测试"
                tvInstruction.text = "【车辆状态】车辆熄火（ACC关闭），关闭音乐、空调和通风\n\n" +
                        "【测试条件】保持环境安静，不要说话\n\n" +
                        "【时长】30秒\n\n" +
                        "【目的】测量安静环境下的背景噪音误唤醒概率，确定阈值下限\n\n" +
                        "点击开始后请保持安静，不要说话。"
                tvProgress.text = "30秒"
            }
            STEP_NORMAL -> {
                tvStep.text = "第2步/共4步：正常音量测试"
                tvInstruction.text = "【车辆状态】车辆熄火或怠速（停车状态），关闭音乐\n\n" +
                        "【测试条件】坐在正常驾驶位置，用平时说话的音量\n\n" +
                        "【次数】说10次唤醒词（小娜或你好小娜），每次间隔2-3秒\n\n" +
                        "【目的】测量正常音量下的检测概率分布\n\n" +
                        "点击开始后，用正常音量说10次唤醒词。只有成功触发的才会被记录。"
                triggerCount = 0
                tvProgress.text = "0 / $TARGET_COUNT"
            }
            STEP_QUIET -> {
                tvStep.text = "第3步/共4步：小声测试"
                tvInstruction.text = "【车辆状态】车辆熄火或怠速（停车状态），关闭音乐\n\n" +
                        "【测试条件】坐在正常驾驶位置，用你能接受的最小音量\n\n" +
                        "【次数】说10次唤醒词，每次间隔2-3秒\n\n" +
                        "【目的】测量小声说话时的检测概率，确定需要的增益\n\n" +
                        "点击开始后，用很小的声音说10次唤醒词。如果太小声触发不了，可以稍微加大一点音量。"
                triggerCount = 0
                tvProgress.text = "0 / $TARGET_COUNT"
            }
            STEP_NOISE -> {
                tvStep.text = "第4步/共4步：干扰测试"
                tvInstruction.text = "【车辆状态】车辆启动行驶中（或怠速开空调），开启通风/空调\n\n" +
                        "【测试条件】模拟真实驾驶环境，保持噪音源开启，不要说唤醒词\n\n" +
                        "【时长】30秒\n\n" +
                        "【目的】测量干扰环境下的误唤醒概率，验证阈值安全性\n\n" +
                        "点击开始后，请保持干扰源开启，不要说唤醒词。"
                tvProgress.text = "30秒"
            }
            STEP_RESULT -> {
                tvStep.text = "校准完成！"
                showResult()
            }
        }
    }

    private fun startStep() {
        if (currentStep == STEP_RESULT) return
        isRunning = true
        btnStart.isEnabled = false
        startTime = System.currentTimeMillis()
        when (currentStep) {
            STEP_SILENT -> silentProbs.clear()
            STEP_NORMAL -> normalProbs.clear()
            STEP_QUIET -> quietProbs.clear()
            STEP_NOISE -> noiseProbs.clear()
        }
        handler.post(timerRunnable)
        tvLiveData.text = "实时数据：测试中..."
    }

    private fun updateLiveData(prob: Float, melMean: Float, triggered: Boolean) {
        val triggerStr = if (triggered) "✅触发" else ""
        tvLiveData.text = String.format(Locale.US,
            "实时：prob=%.5f  melMean=%.1f  %s", prob, melMean, triggerStr)
    }

    private fun updateProgress() {
        tvProgress.text = "$triggerCount / $TARGET_COUNT"
        progressBar.progress = triggerCount * 100 / TARGET_COUNT
        if (triggerCount >= TARGET_COUNT) {
            finishStep()
        }
    }

    private fun finishStep() {
        isRunning = false
        handler.removeCallbacks(timerRunnable)
        btnNext.isEnabled = true
        btnStart.isEnabled = false

        val stats = when (currentStep) {
            STEP_SILENT -> "静音测试完成：收集 ${silentProbs.size} 帧，最大prob=${percentile(silentProbs, 100f)}"
            STEP_NORMAL -> "正常测试完成：记录 ${normalProbs.size} 次，最小prob=${percentile(normalProbs, 0f)}"
            STEP_QUIET -> "小声测试完成：记录 ${quietProbs.size} 次，最小prob=${percentile(quietProbs, 0f)}"
            STEP_NOISE -> "干扰测试完成：收集 ${noiseProbs.size} 帧，最大prob=${percentile(noiseProbs, 100f)}"
            else -> ""
        }
        tvProgress.text = stats
        tvLiveData.text = "✅ 本步完成，点击下一步继续"
    }

    private fun goNext() {
        if (currentStep < STEP_RESULT) {
            currentStep++
            showStep()
            updateCurrentParams()
        }
    }

    private fun showResult() {
        // 计算阈值（如果静音/干扰没有数据，用合理默认值）
        val silentP95 = if (silentProbs.isNotEmpty()) percentile(silentProbs, 95f) else 0.08f
        val noiseMax = if (noiseProbs.isNotEmpty()) percentile(noiseProbs, 100f) else 0.10f
        calculatedThreshold = maxOf(silentP95 + 0.05f, noiseMax + 0.05f)
        calculatedThreshold = calculatedThreshold.coerceIn(0.20f, 0.6f)

        // 计算增益
        val quietAvg = if (quietProbs.isNotEmpty()) quietProbs.average().toFloat() else 0.3f
        val quietMin = if (quietProbs.isNotEmpty()) percentile(quietProbs, 0f) else 0.2f
        // 目标：让小声说话的最小prob也能超过阈值+0.05安全余量
        val targetProb = (calculatedThreshold + 0.05f).coerceAtMost(0.8f)
        val currentGain = WakeWordEngine.getAudioGain()

        // 增益计算：如果当前小声最小prob已经达到目标，保持当前增益
        // 如果未达到，按 prob 比值粗略估算需要的增益（增益放大输入，prob相应增大）
        calculatedGain = if (quietMin >= targetProb) {
            // 当前已经足够，保持当前增益（不降低，因为降低可能导致正常音量也不稳定）
            currentGain
        } else {
            // 需要增大增益，按 prob 比值估算（粗略，因为sigmoid非线性）
            val ratio = targetProb / quietMin.coerceAtLeast(0.01f)
            (currentGain * ratio).coerceAtMost(3.5f)
        }
        calculatedGain = calculatedGain.coerceIn(1.0f, 3.5f)

        val normalMin = percentile(normalProbs, 0f)

        tvInstruction.text = """
            测试数据统计：
            ─────────────────
            静音测试：${silentProbs.size}帧，P95=${String.format("%.3f", silentP95)}${if (silentProbs.isEmpty()) "（无数据，用默认值0.08）" else ""}
            干扰测试：${noiseProbs.size}帧，最大=${String.format("%.3f", noiseMax)}${if (noiseProbs.isEmpty()) "（无数据，用默认值0.10）" else ""}
            正常音量：${normalProbs.size}次，最小=${String.format("%.3f", normalMin)}
            小声测试：${quietProbs.size}次，最小=${String.format("%.3f", quietMin)}，平均=${String.format("%.3f", quietAvg)}
            
            推荐参数：
            ─────────────────
            检测阈值 threshold = ${String.format("%.2f", calculatedThreshold)}
            音频增益 gain = ${String.format("%.1f", calculatedGain)}x
            
            说明：
            ─────────────────
            阈值基于静音/干扰的最大prob+0.05安全余量
            增益基于小声测试的平均prob，目标达到阈值+0.1
        """.trimIndent()

        tvProgress.text = ""
        tvLiveData.text = ""
        progressBar.progress = 100

        btnStart.text = "应用推荐参数"
        btnStart.isEnabled = true
        btnStart.setOnClickListener {
            applyResult()
        }
        btnNext.text = "返回"
        btnNext.isEnabled = true
        btnNext.setOnClickListener { finish() }
    }

    private fun applyResult() {
        // 保存到 SharedPreferences
        getSharedPreferences("voice_assistant_prefs", MODE_PRIVATE).edit()
            .putFloat("wake_gain_override", calculatedGain)
            .putFloat("wake_threshold_override", calculatedThreshold)
            .apply()

        // 直接应用（需要服务重启才完全生效，但静态变量立即更新）
        WakeWordEngine.setGainAndThreshold(calculatedGain, calculatedThreshold)

        Toast.makeText(this, "参数已应用！建议重启语音助手服务使完全生效", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun percentile(data: List<Float>, p: Float): Float {
        if (data.isEmpty()) return 0f
        val sorted = data.sorted()
        val idx = (p / 100f * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun probToLogit(prob: Float): Float {
        val p = prob.coerceIn(0.001f, 0.999f)
        return ln(p / (1 - p))
    }
}
