package com.xisohi.car.voiceassistant.core.monitor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.xisohi.car.voiceassistant.core.LogUtils
import android.view.KeyEvent
import kotlin.jvm.Synchronized

/**
 * 音频焦点管理器
 *
 * 职责：
 * - 识别时降低媒体音量到0（专注听用户说话，提高识别率）
 * - 识别完成后恢复媒体音量到原始值
 * - 保存/恢复原始音量，避免重复保存
 * - 支持语音控制音量（调大/调小/设置到指定值）
 *
 * 车机场景：
 * - 识别时音乐/导航声音会干扰语音识别，需要临时静音
 * - 识别完成后立即恢复，不影响用户正常使用
 *
 * 双保险策略：
 * 1. 先尝试用音频焦点（标准 API，音乐 App 会自动降低音量）
 * 2. 再尝试直接改 STREAM_MUSIC 音量（有些车机允许，有些不允许）
 * 3. 音量控制：先试 adjustStreamVolume，再试 setStreamVolume，再试发送音量按键
 */
class AudioFocusManager(private val context: Context) {
    /**
     * 保存的原始音量（按音频流），null/空表示未保存。
     * 车机导航播报走 ALARM/SYSTEM 而非 MUSIC，只压 MUSIC 压不住导航，
     * 故对 MUSIC/ALARM/SYSTEM/NOTIFICATION 四流一起保存并静音。
     */
    private val originalVolumes = mutableMapOf<Int, Int>()

    /** 参与静音/恢复的音频流列表 */
    private val mutedStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION
    )

    /** 标记是否已经降低了媒体音量 */
    private var isMediaVolumeMuted = false

    /** 音频焦点申请结果 */
    private var audioFocusGranted = false

    /** 识别期独占焦点请求（API 26+，导航用途）——随 restoreMediaVolume() 统一释放 */
    private var recognitionFocusRequest: AudioFocusRequest? = null

    /** 恢复媒体音量失败计数（仅用于日志降频；失败时保留原始音量记录继续尝试，不清状态） */
    private var restoreFailCount = 0

    /** 音频焦点变化监听（空实现：焦点变化不主动处理，音量由直接修改兜底）。
     *  部分车机 ROM 对 requestAudioFocus(null,...) 处理异常，传非 null listener 更稳妥。 */
    private val focusListener = object : AudioManager.OnAudioFocusChangeListener {
        override fun onAudioFocusChange(focusChange: Int) {}
    }

    /**
     * 降低媒体音量到 0（音乐、导航等），专注听用户说话，提高识别率。
     * 第一次调用时保存原始音量，后续调用不会重复保存。
     *
     * 双保险：先申请音频焦点，再尝试直接改音量。
     */
    @Synchronized fun muteMediaVolume() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // === 第一步：申请音频焦点（标准 API，音乐 App 会自动降低音量） ===
            // ★ 2026-10-15：车机导航播报走独立"导航音量"通道，流静音压不住；MAY_DUCK
            //   焦点只要求对方"降低音量"，导航照播不误。改 EXCLUSIVE：遵循焦点的
            //   导航/音乐 App 在识别期间会主动暂停播报，识别结束放弃焦点后恢复。
            // ★ 2026-10-15（二次）：焦点用途声明成"导航指引"（与导航 App 同通道）——
            //   车机"导航播报优先"策略会把导航用途的独占焦点视为最高优先级：
            //   1) 识别期系统自动压低音乐等其它音频（无需依赖手动静音）
            //   2) 导航 App 想播报需申请同一通道焦点，被独占时让位 → 解决"压不住导航"
            //   播报/识别结束统一 abandon（restoreMediaVolume），导航恢复正常。
            if (!audioFocusGranted) {
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (recognitionFocusRequest == null) {
                        val attrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        recognitionFocusRequest = AudioFocusRequest.Builder(
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                        )
                            .setAudioAttributes(attrs)
                            .setWillPauseWhenDucked(false)
                            .build()
                    }
                    audioManager.requestAudioFocus(recognitionFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                        focusListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    )
                }
                audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                if (audioFocusGranted) {
                    LogUtils.d(TAG, "✅ 音频焦点申请成功（导航通道独占），导航/音乐 App 应暂停播报")
                } else {
                    LogUtils.w(TAG, "⚠️ 音频焦点申请失败，尝试直接改音量")
                }
            }

            // === 第二步：直接改多音频流音量到 0（车机导航播报走 ALARM/SYSTEM，只压 MUSIC 压不住） ===
            if (!isMediaVolumeMuted) {
                for (stream in mutedStreams) {
                    if (!originalVolumes.containsKey(stream)) {
                        originalVolumes[stream] = audioManager.getStreamVolume(stream)
                    }
                    try {
                        audioManager.setStreamVolume(stream, 0, 0)
                        // ★ 回读验证：车机可能 API 成功但实际未静音（写后不回读=假成功）。
                        //   若导航播报走 ALARM/SYSTEM，这里能实证"识别期到底压没压住导航"。
                        val actual = audioManager.getStreamVolume(stream)
                        if (actual != 0) {
                            LogUtils.w(TAG, "⚠️ 流 $stream 静音后回读异常：目标 0，实际 $actual（车机可能拒绝静音）")
                        } else {
                            LogUtils.d(TAG, "✅ 已直接降低流 $stream 音量到 0（原始音量: ${originalVolumes[stream]}，回读确认）")
                        }
                    } catch (e: SecurityException) {
                        LogUtils.w(TAG, "❌ 车机系统禁止直接改流 $stream 音量: ${e.message}")
                    }
                }
                isMediaVolumeMuted = true
                restoreFailCount = 0  // 新周期开始，重置失败计数
            }
        } catch (e: Exception) {
            LogUtils.w(TAG, "降低媒体音量失败: ${e.message}")
        }
    }

    /**
     * 恢复媒体音量到唤醒前的原始值。
     * 识别完成后或 TTS 播报前调用。
     */
    @Synchronized fun restoreMediaVolume() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // === 第一步：放弃音频焦点 ===
            if (audioFocusGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    recognitionFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(focusListener)
                }
                audioFocusGranted = false
                LogUtils.d(TAG, "✅ 已放弃音频焦点，导航/音乐 App 应恢复正常播报")
            }

            // === 第二步：恢复多音频流音量 ===
            if (isMediaVolumeMuted && originalVolumes.isNotEmpty()) {
                var restoreOk = true
                for ((stream, volume) in originalVolumes.entries) {
                    try {
                        audioManager.setStreamVolume(stream, volume, 0)
                        // ★ 回读验证：车机可能 API 成功但实际未恢复（"恢复假成功"=导航/音乐
                        //   永久无声的根源候选）。实测日志显示恢复成功但导航仍无声，
                        //   需要确认 setStreamVolume 后车机是否真的把音量还回来了。
                        val actual = audioManager.getStreamVolume(stream)
                        if (actual != volume) {
                            LogUtils.w(TAG, "⚠️ 流 $stream 恢复后回读异常：目标 $volume，实际 $actual（车机可能拒绝恢复）")
                            restoreOk = false
                        } else {
                            LogUtils.d(TAG, "✅ 已直接恢复流 $stream 音量到: $volume（回读确认）")
                        }
                    } catch (e: SecurityException) {
                        LogUtils.w(TAG, "⚠️ 车机系统禁止恢复流 $stream 音量: ${e.message}")
                        restoreOk = false
                    }
                }
                if (restoreOk) {
                    // 全部恢复成功才清状态
                    originalVolumes.clear()
                    isMediaVolumeMuted = false
                    restoreFailCount = 0
                } else {
                    // ★ 2026-10-16 实证补丁：恢复失败**不能清状态**——originalVolumes 是原始音量
                    //   的唯一记录，清掉后下次 muteMediaVolume() 会把当前值（可能是 0）当原始值
                    //   存下，之后 restore 恢复的也是 0 → 永久静音（导航无声的候选根源之一）。
                    //   保留记录 + isMediaVolumeMuted=true：下次识别结束继续尝试恢复；
                    //   副作用是下次识别不再静音（车机拒绝恢复时不折腾），日志降频避免刷屏。
                    restoreFailCount++
                    if (restoreFailCount == 1 || restoreFailCount % 10 == 0) {
                        LogUtils.w(TAG, "已累计 $restoreFailCount 次恢复失败，保留原始音量记录继续尝试（不清状态）")
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.w(TAG, "恢复媒体音量失败: ${e.message}")
        }
    }

    /**
     * 重置状态：先恢复音量并放弃音频焦点，再清空记录。
     *
     * ★ 修复：原实现只清状态、不恢复音量也不 abandonAudioFocus —— 这是个危险函数。
     * 若在静音中被调用：四路流留在 0，而"原始音量"这份唯一记录已被丢弃，下次
     * muteMediaVolume() 会把当前值 0 当成原始音量存下来，之后 restore 恢复的也是 0，
     * 最终永久静音。
     *
     * 现在的语义：
     *  - 先走 [restoreMediaVolume]（恢复音量 + 放弃焦点 + 成功时自行清状态）；
     *  - 仅在已恢复正常音量（isMediaVolumeMuted 已复位）时才补清残留记录；
     *  - 恢复失败则**保留** originalVolumes，交给 restoreMediaVolume() 的
     *    restoreFailCount/MAX_RESTORE_FAILS 重试逻辑继续尝试，不能在这里把记录抹掉。
     *
     * 必须 @Synchronized：与 muteMediaVolume()/restoreMediaVolume() 共用同一把锁，
     * 否则"恢复 → 清状态"之间可能被 muteMediaVolume() 插入，同样会丢掉原始音量。
     */
    @Synchronized
    fun reset() {
        restoreMediaVolume()
        if (!isMediaVolumeMuted) {
            // 兜底：正常路径上 restoreMediaVolume() 已清过，这里只防御未来改动
            originalVolumes.clear()
            audioFocusGranted = false
        }
    }

    /**
     * 获取当前媒体音量。
     */
    fun getCurrentVolume(): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            LogUtils.w(TAG, "获取媒体音量失败: ${e.message}")
            0
        }
    }

    /**
     * 获取媒体音量最大值。
     */
    fun getMaxVolume(): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            LogUtils.w(TAG, "获取最大音量失败: ${e.message}")
            15
        }
    }

    /**
     * 设置媒体音量到指定值。
     * 三级策略：
     * 1. 先试 setStreamVolume（标准 API）
     * 2. 失败再试 adjustStreamVolume（调整方式，权限要求可能更低）
     * 3. 再失败就发送音量按键（模拟用户按音量键）
     */
    @Synchronized fun setVolume(volume: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = getMaxVolume()
        val targetVolume = volume.coerceIn(0, maxVolume)
        val currentVolume = getCurrentVolume()

        LogUtils.d(TAG, "尝试设置音量: $targetVolume (当前: $currentVolume, 最大: $maxVolume)")

        // === 第一级：setStreamVolume ===
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            LogUtils.d(TAG, "✅ setStreamVolume 成功，音量已设置为: $targetVolume")
            return
        } catch (e: SecurityException) {
            LogUtils.w(TAG, "⚠️ setStreamVolume 被禁止: ${e.message}")
        } catch (e: Exception) {
            LogUtils.w(TAG, "⚠️ setStreamVolume 失败: ${e.message}")
        }

        // === 第二级：adjustStreamVolume（多次调整到目标值） ===
        try {
            val diff = targetVolume - currentVolume
            if (diff != 0) {
                val direction = if (diff > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                val steps = kotlin.math.abs(diff)
                LogUtils.d(TAG, "尝试 adjustStreamVolume，方向: ${if (direction == AudioManager.ADJUST_RAISE) "增大" else "减小"}, 步数: $steps")
                repeat(steps) {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        direction,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                LogUtils.d(TAG, "✅ adjustStreamVolume 完成")
                return
            }
        } catch (e: SecurityException) {
            LogUtils.w(TAG, "⚠️ adjustStreamVolume 被禁止: ${e.message}")
        } catch (e: Exception) {
            LogUtils.w(TAG, "⚠️ adjustStreamVolume 失败: ${e.message}")
        }

        // === 第三级：发送音量按键（模拟用户按音量键） ===
        try {
            val diff = targetVolume - currentVolume
            if (diff != 0) {
                val keyCode = if (diff > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
                val steps = kotlin.math.abs(diff)
                LogUtils.d(TAG, "尝试发送音量按键，按键码: $keyCode, 次数: $steps")
                repeat(steps) {
                    val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                    val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                    audioManager.dispatchMediaKeyEvent(down)
                    audioManager.dispatchMediaKeyEvent(up)
                }
                LogUtils.d(TAG, "✅ 音量按键发送完成")
                return
            }
        } catch (e: Exception) {
            LogUtils.w(TAG, "⚠️ 音量按键发送失败: ${e.message}")
        }

        LogUtils.e(TAG, "❌ 所有音量控制方式都失败了！车机系统限制太严格")
        LogUtils.e(TAG, "💡 建议用户直接用车机物理按键或方向盘按键调音量")
    }

    /**
     * 音量调大（+1格）
     */
    fun volumeUp() {
        val current = getCurrentVolume()
        setVolume(current + 1)
    }

    /**
     * 音量调小（-1格）
     */
    fun volumeDown() {
        val current = getCurrentVolume()
        setVolume(current - 1)
    }

    companion object {
        private const val TAG = "AudioFocusManager"
    }
}
