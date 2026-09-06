package com.xisohi.car.voiceassistant.core

import android.content.Context
import com.xisohi.car.voiceassistant.download.ModelManager
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/** 解析出的意图：action 为动作名，params 为槽位键值 */
data class VoiceIntent(
    val id: String,
    val action: String,
    val params: Map<String, String>
)

private data class IntentRule(
    val id: String,
    val action: String,
    val patterns: List<Pattern>,
    val slots: List<String>,
    val grammar: List<String>
)

/**
 * 轻量本地 NLU 规则引擎（封闭域，无需任何网络与模型）。
 *
 * 意图模板来源（可热更新，无需联网）：
 *  1. 优先 filesDir/va/config/intents.json（OTA / U 盘更新的配置包）
 *  2. 兜底 assets/intents.json（随 APK 内置）
 *
 * 每条规则含一个或多个正则 pattern，正则中使用命名组 (?<slot>…) 提取槽位。
 */
class IntentParser(context: Context) {

    private val rules: List<IntentRule> = loadRules(context)

    /** 解析文本，命中返回 [VoiceIntent]，否则 null */
    fun parse(text: String): VoiceIntent? {
        // 去掉所有空格：Vosk 识别结果可能在词之间加空格（如"播放 音乐"），
        // 而 intents.json 里的正则是无空格的（如"播放音乐"）
        val trimmed = text.replace(" ", "").trim()
        if (trimmed.isEmpty()) return null
        for (rule in rules) {
            for (pattern in rule.patterns) {
                val matcher = pattern.matcher(trimmed)
                if (matcher.find()) {
                    val params = HashMap<String, String>()
                    for (slot in rule.slots) {
                        try {
                            matcher.group(slot)?.takeIf { it.isNotBlank() }?.let {
                                params[slot] = it
                            }
                        } catch (_: Exception) {
                        }
                    }
                    return VoiceIntent(rule.id, rule.action, params)
                }
            }
        }
        return null
    }

    /**
     * 生成 Vosk grammar（JSGF 词表）——可选增强。
     *
     * 默认 intents.json 中 grammar 为空数组 → 返回空列表 → 自由识别
     * （NLU 用正则抽取，开箱即用）。
     * 若你希望限定识别域以提升准确率，可为每个意图填写 grammar 短语，
     * 例如 ["音量 [unk]", "[unk] 音量 [unk]"]（[unk] 为任意词通配）。
     * 注意：启用后识别只输出能匹配 grammar 的指令，需要逐条验证。
     */
    fun grammarPhrases(): List<String> {
        val phrases = LinkedHashSet<String>()
        for (rule in rules) {
            phrases.addAll(rule.grammar)
        }
        return phrases.toList()
    }

    private fun loadRules(context: Context): List<IntentRule> {
        val json = readConfig(context) ?: return emptyList()
        return try {
            val arr = json.getJSONArray("intents")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.optString("id", "")
                val action = o.optString("action", "")
                val slots = o.optJSONArray("slots")
                    ?.let { (0 until it.length()).map { j -> it.getString(j) } }
                    ?: emptyList()
                val patterns = o.optJSONArray("patterns")
                    ?.let { pa ->
                        (0 until pa.length()).mapNotNull { j ->
                            val re = pa.getJSONObject(j).optString("re", "")
                            if (re.isEmpty()) null else Pattern.compile(re)
                        }
                    }
                    ?: emptyList()
                val grammar = o.optJSONArray("grammar")
                    ?.let { ga -> (0 until ga.length()).map { j -> ga.getString(j) } }
                    ?: emptyList()
                if (id.isEmpty() || patterns.isEmpty()) null
                else IntentRule(id, action, patterns, slots, grammar)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readConfig(context: Context): JSONObject? {
        // 1) 可热更新配置
        val configFile = File(ModelManager.configDir(context), "intents.json")
        if (configFile.exists()) {
            return try { JSONObject(configFile.readText()) } catch (_: Exception) { null }
        }
        // 2) APK 内置配置
        return try {
            val text = context.assets.open("intents.json").bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }
}
