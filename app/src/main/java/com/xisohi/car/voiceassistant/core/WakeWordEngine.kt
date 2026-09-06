package com.xisohi.car.voiceassistant.core

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import com.xisohi.car.voiceassistant.BuildConfig
import com.xisohi.car.voiceassistant.download.ModelManager
import java.io.File

/**
 * 唤醒词引擎（Porcupine，完全离线，模型 <10KB）。
 *
 * 优先级：
 *  1. 本地 kws/ 目录下的自定义 .ppn（模型包下载，如"你好小乐"）。
 *     中文唤醒词需配套中文模型 .pv（放同目录，代码自动识别）。
 *  2. SDK 内置英文词 "hey google"（兜底，避免无唤醒词可用）。
 *
 * 注意：
 *  - 自定义唤醒词需到 console.picovoice.ai 训练下载 .ppn
 *  - 中文模型 .pv 从 Porcupine 官方仓库获取（porcupine_params_zh.pv）
 *  - AccessKey 在 app/build.gradle.kts 的 PICOVOICE_ACCESS_KEY 配置
 */
class WakeWordEngine(private val context: Context) {

    interface Callback {
        fun onWakeWord(index: Int)
    }

    private var manager: PorcupineManager? = null

    /**
     * 启动唤醒监听（内部自建 AudioRecord 线程，16kHz 单声道）。
     * 返回 false 表示启动失败（AccessKey 未配置等）。
     */
    @Synchronized
    fun start(callback: Callback): Boolean {
        stop()
        val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
        if (accessKey.isBlank() || accessKey.startsWith("REPLACE_WITH")) return false

        return try {
            val builder = PorcupineManager.Builder().setAccessKey(accessKey)
            val kwsDir = File(ModelManager.modelsDir(context), "kws")
            val ppn = kwsDir.listFiles { f -> f.extension.equals("ppn", true) }
                ?.firstOrNull()
            if (ppn != null) {
                builder.setKeywordPaths(listOf(ppn.absolutePath))
                // 中文唤醒词需要中文模型 .pv（与 .ppn 同目录）
                val pv = kwsDir.listFiles { f -> f.extension.equals("pv", true) }
                    ?.firstOrNull()
                if (pv != null) builder.setModelPath(pv.absolutePath)
            } else {
                builder.setKeywords(listOf(Porcupine.BuiltInKeyword.HEY_GOOGLE))
            }
            manager = builder.build(context) { idx -> callback.onWakeWord(idx) }
            manager?.start()
            true
        } catch (e: Exception) {
            try { manager?.delete() } catch (_: Exception) {}
            manager = null
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            manager?.stop()
            manager?.delete()
        } catch (_: Exception) {
        }
        manager = null
    }
}
