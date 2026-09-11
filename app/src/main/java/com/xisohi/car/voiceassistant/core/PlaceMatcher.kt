package com.xisohi.car.voiceassistant.core

import android.content.Context
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

/**
 * 地名匹配器：用于导航场景下的同音字模糊匹配
 *
 * 原理：
 * 1. 把识别到的地名转成拼音（如"牛为春" -> "niuweichun"）
 * 2. 和地名词库中的拼音做编辑距离（Levenshtein）相似度匹配
 * 3. 相似度超过阈值就自动替换为正确地名
 *
 * 解决 Vosk 小模型词汇量有限，生僻字（如"圩"）只能识别成同音字的问题
 */
class PlaceMatcher(context: Context) {

    data class Place(
        val name: String,      // 正确地名
        val pinyin: String     // 地名拼音（预计算，避免运行时转换）
    )

    private val places = mutableListOf<Place>()

    // 相似度阈值：0.0~1.0，超过此值认为匹配成功
    // 0.7 表示允许约 30% 的字符差异（如"牛为春" vs "牛圩村"，拼音差异很小）
    private val similarityThreshold = 0.70f

    // 内置地名集合（用于判断是否可删除）
    private val builtinNames = mutableSetOf<String>()
    private var appContext: Context? = null

    init {
        appContext = context
        loadPlaces(context)
    }

    /**
     * 加载地名词库：先加载内置词库，再加载外部自定义词库（追加/覆盖）
     *
     * 外部自定义词库路径：/sdcard/Android/data/com.xisohi.car.voiceassistant/files/places_custom.json
     * 用户可以直接编辑该文件添加地名，不需要重新打包 APK
     */
    private fun loadPlaces(context: Context) {
        // 1. 加载内置词库（assets/places.json）
        var builtinCount = 0
        try {
            val json = context.assets.open("places.json").bufferedReader().use { it.readText() }
            builtinCount = parseAndAddPlaces(json)
            // 记录内置地名（用于判断是否可删除）
            builtinNames.addAll(places.map { it.name })
            android.util.Log.d("PlaceMatcher", "内置词库加载: $builtinCount 个地名")
        } catch (e: Exception) {
            android.util.Log.w("PlaceMatcher", "内置词库加载失败: ${e.message}")
        }

        // 2. 加载外部自定义词库（/sdcard/Android/data/<pkg>/files/places_custom.json）
        var customCount = 0
        try {
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir != null) {
                val customFile = java.io.File(externalDir, "places_custom.json")
                if (customFile.exists()) {
                    val json = customFile.readText()
                    customCount = parseAndAddPlaces(json, override = true)
                    android.util.Log.d("PlaceMatcher", "自定义词库加载: $customCount 个地名（来自 ${customFile.absolutePath}）")
                } else {
                    // 外部文件不存在，创建示例文件，方便用户编辑
                    createSampleCustomFile(customFile)
                    android.util.Log.d("PlaceMatcher", "已创建自定义词库示例: ${customFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("PlaceMatcher", "自定义词库加载失败: ${e.message}")
        }

        android.util.Log.d("PlaceMatcher", "地名总数: ${places.size}（内置 $builtinCount + 自定义 $customCount）")
    }

    /**
     * 解析 JSON 并添加到词库
     * @param override true=同名地名覆盖已有，false=跳过
     * @return 实际添加的数量
     */
    private fun parseAndAddPlaces(json: String, override: Boolean = false): Int {
        val array = JSONArray(json)
        var added = 0
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getString("name")
            val pinyin = obj.optString("pinyin", "").ifEmpty {
                toPinyin(name)
            }
            if (override) {
                // 覆盖模式：先移除同名，再添加
                places.removeAll { it.name == name }
                places.add(Place(name, pinyin))
                added++
            } else {
                // 追加模式：跳过同名
                if (places.none { it.name == name }) {
                    places.add(Place(name, pinyin))
                    added++
                }
            }
        }
        return added
    }

    /**
     * 创建自定义词库示例文件
     */
    private fun createSampleCustomFile(file: java.io.File) {
        try {
            val sample = """[
  {"name": "示例小区", "pinyin": "shilixiaoqu"},
  {"name": "示例公司", "pinyin": "shiligongsi"}
]"""
            file.writeText(sample)
        } catch (e: Exception) {
            android.util.Log.w("PlaceMatcher", "创建示例文件失败: ${e.message}")
        }
    }

    // pinyin4j 输出格式配置（小写、无声调）
    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    /**
     * 中文转拼音（无声调，小写，去空格）
     * 如："牛为春" -> "niuweichun"
     */
    private fun toPinyin(text: String): String {
        return try {
            val sb = StringBuilder()
            for (ch in text) {
                // 判断是否为汉字（Unicode CJK 统一表意文字范围）
                if (ch.code in 0x4E00..0x9FFF) {
                    val pinyinArray = PinyinHelper.toHanyuPinyinStringArray(ch, pinyinFormat)
                    if (pinyinArray != null && pinyinArray.isNotEmpty()) {
                        sb.append(pinyinArray[0])  // 多音字取第一个（最常用）
                    } else {
                        sb.append(ch)
                    }
                } else {
                    sb.append(ch)  // 非汉字直接保留
                }
            }
            sb.toString().replace(" ", "")
        } catch (e: Exception) {
            android.util.Log.w("PlaceMatcher", "拼音转换失败: ${e.message}")
            text.lowercase().replace(" ", "")
        }
    }

    /**
     * 尝试匹配地名
     * @param input 识别到的地名（可能含同音字错误）
     * @return 匹配到的正确地名，如果没匹配到则返回 null
     */
    fun match(input: String): String? {
        if (input.isBlank() || places.isEmpty()) return null

        // 先精确匹配（识别正确的情况直接返回）
        val exact = places.firstOrNull { it.name == input }
        if (exact != null) {
            android.util.Log.d("PlaceMatcher", "精确匹配: '$input' -> '${exact.name}'")
            return exact.name
        }

        // 转拼音后做模糊匹配
        val inputPinyin = toPinyin(input)
        if (inputPinyin.isBlank()) return null

        var bestMatch: Place? = null
        var bestSimilarity = 0f

        for (place in places) {
            val similarity = calculateSimilarity(inputPinyin, place.pinyin)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestMatch = place
            }
        }

        return if (bestMatch != null && bestSimilarity >= similarityThreshold) {
            android.util.Log.d("PlaceMatcher",
                "模糊匹配: '$input'($inputPinyin) -> '${bestMatch.name}'(${bestMatch.pinyin}), 相似度=${String.format("%.2f", bestSimilarity)}")
            bestMatch.name
        } else {
            android.util.Log.d("PlaceMatcher",
                "未匹配: '$input'($inputPinyin), 最相似='${bestMatch?.name}'(${String.format("%.2f", bestSimilarity)})")
            null
        }
    }

    // ========== 地名管理方法（供设置页调用） ==========

    /** 获取所有地名（内置+自定义） */
    fun getAllPlaces(): List<Place> = places.toList()

    /** 判断是否为内置地名（内置的不能删除） */
    fun isBuiltin(name: String): Boolean {
        return builtinNames.contains(name)
    }

    /**
     * 添加地名到自定义词库
     * @return true=添加成功，false=已存在或失败
     */
    fun addPlace(name: String, pinyin: String = ""): Boolean {
        if (name.isBlank()) return false
        if (places.any { it.name == name }) return false
        val py = pinyin.ifEmpty { toPinyin(name) }
        places.add(Place(name, py))
        saveCustomPlaces()
        android.util.Log.d("PlaceMatcher", "添加地名: $name ($py)")
        return true
    }

    /**
     * 删除自定义地名（内置地名不能删）
     * @return true=删除成功，false=是内置地名或不存在
     */
    fun removePlace(name: String): Boolean {
        if (isBuiltin(name)) {
            android.util.Log.w("PlaceMatcher", "内置地名不能删除: $name")
            return false
        }
        val removed = places.removeAll { it.name == name }
        if (removed) {
            saveCustomPlaces()
            android.util.Log.d("PlaceMatcher", "删除地名: $name")
        }
        return removed
    }

    /** 保存自定义词库到外部文件 */
    private fun saveCustomPlaces() {
        try {
            val externalDir = appContext?.getExternalFilesDir(null) ?: return
            val customFile = java.io.File(externalDir, "places_custom.json")
            val customPlaces = places.filter { !builtinNames.contains(it.name) }
            val array = org.json.JSONArray()
            for (p in customPlaces) {
                val obj = org.json.JSONObject()
                obj.put("name", p.name)
                obj.put("pinyin", p.pinyin)
                array.put(obj)
            }
            customFile.writeText(array.toString(2))
            android.util.Log.d("PlaceMatcher", "已保存 ${customPlaces.size} 个自定义地名")
        } catch (e: Exception) {
            android.util.Log.w("PlaceMatcher", "保存自定义词库失败: ${e.message}")
        }
    }

    // 内置地名集合（用于判断是否可删除）

    /**
     * 计算两个字符串的相似度（基于编辑距离/Levenshtein）
     * @return 0.0~1.0，1.0 表示完全相同
     */
    private fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0f

        val distance = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        return 1.0f - distance.toFloat() / maxLen.toFloat()
    }

    /**
     * 计算 Levenshtein 编辑距离（将 s1 变成 s2 需要的最少操作次数）
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        // 创建二维数组，dp[i][j] 表示 s1[0..i-1] 到 s2[0..j-1] 的编辑距离
        val dp = Array(m + 1) { IntArray(n + 1) }

        // 初始化：空字符串到任意字符串的距离就是字符串长度
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        // 动态规划计算编辑距离
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1,      // 删除
                        dp[i][j - 1] + 1),      // 插入
                    dp[i - 1][j - 1] + cost     // 替换
                )
            }
        }

        return dp[m][n]
    }
}
