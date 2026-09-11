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

    init {
        loadPlaces(context)
    }

    /**
     * 从 assets/places.json 加载地名词库
     */
    private fun loadPlaces(context: Context) {
        try {
            val json = context.assets.open("places.json").bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val pinyin = obj.optString("pinyin", "").ifEmpty {
                    // 如果 JSON 中没预计算拼音，运行时转换
                    toPinyin(name)
                }
                places.add(Place(name, pinyin))
            }
            android.util.Log.d("PlaceMatcher", "已加载 ${places.size} 个地名")
        } catch (e: Exception) {
            android.util.Log.w("PlaceMatcher", "加载地名词库失败: ${e.message}")
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
