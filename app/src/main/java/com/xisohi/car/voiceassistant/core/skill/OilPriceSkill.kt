package com.xisohi.car.voiceassistant.core.skill

import android.content.Context
import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent
import java.util.regex.Pattern

/**
 * 今日油价查询 Skill
 *
 * 职责：查询全国各省份今日油价（92号、95号、98号汽油、0号柴油）
 * 依赖：Context + 网络
 *
 * 数据源（双源自动切换）：
 * - 主源：youjia.abapi.cn（数据来源：国家发改委油价调整公告，每日08:00更新）
 * - 备用源：汽车之家 autohome.com.cn/oil
 * 主源失败时自动切换到备用源，无需 API Key
 *
 * 公开接口（供 VoiceAssistantService 调用）：
 * - queryOilPrice(province) — 查询指定省份油价，null 时自动 IP 定位
 */
class OilPriceSkill(private val context: Context) {

    companion object {
        private const val TAG = "OilPriceSkill"
        private const val PRIMARY_URL = "https://youjia.abapi.cn/"
        private const val FALLBACK_URL = "https://www.autohome.com.cn/oil/"

        // 油价缓存：同一天内不重复请求
        private var cachedDate: String? = null
        private var cachedPrices: Map<String, OilPriceInfo>? = null
        private var cachedSource: String? = null
    }

    /**
     * 单个省份的油价信息
     */
    data class OilPriceInfo(
        val province: String,
        val price92: String,
        val price95: String,
        val price98: String,
        val price0: String
    )

    /**
     * SkillExecutor.execute() 的兜底分支
     * 实际油价查询由 VoiceAssistantService 在后台线程直接调用 queryOilPrice()
     */
    fun execute(intent: VoiceIntent): ExecutionResult = when (intent.action) {
        "ask.oil_price" -> ExecutionResult(false, "油价查询需要联网，请通过语音唤醒后说'今日油价'")
        else -> ExecutionResult(false, "不支持的油价指令")
    }

    /**
     * 通过IP定位获取当前省份（不需要定位权限，只要联网就能用）
     * 失败时返回 null，调用方应回退到全国均价
     */
    private fun getCurrentProvinceByIp(): String? {
        return try {
            val url = java.net.URL("https://ipapi.co/json/")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (conn.responseCode != 200) return null

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val obj = org.json.JSONObject(json)
            val region = obj.optString("region", "")
            when {
                region.isNotBlank() && region != "null" -> region
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "IP定位失败: ${e.message}")
            null
        }
    }

    /**
     * 获取今天的日期字符串（用于缓存判断）
     */
    private fun todayStr(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        return sdf.format(java.util.Date())
    }

    /**
     * 查询今日油价（在后台线程调用，不能在主线程执行）
     * @param province 指定省份，传 null 或空字符串时自动IP定位
     * @return 油价播报文本
     */
    fun queryOilPrice(province: String? = null): String {
        val targetProvince = when {
            !province.isNullOrBlank() -> province
            else -> {
                val located = getCurrentProvinceByIp()
                if (located == null) {
                    android.util.Log.w(TAG, "IP定位失败，查询全国均价")
                    null
                } else {
                    located
                }
            }
        }
        android.util.Log.d(TAG, "油价查询省份: ${targetProvince ?: "全国均价"}")

        // 获取油价数据（带缓存）
        val prices = getOilPrices()
        if (prices.isEmpty()) {
            return "油价查询失败，请检查网络连接后再试"
        }

        // 查找目标省份
        val targetNorm = targetProvince?.let { normalizeProvince(it) }
        val matched = if (!targetNorm.isNullOrBlank()) {
            prices.entries.firstOrNull { (name, _) ->
                val nameNorm = normalizeProvince(name)
                nameNorm.contains(targetNorm, ignoreCase = true) ||
                        targetNorm.contains(nameNorm, ignoreCase = true)
            }?.value
        } else {
            null
        }

        return if (matched != null) {
            formatPriceText(matched)
        } else {
            // 没找到指定省份，播报全国均价
            val avg = calculateAverage(prices)
            if (avg != null) {
                "未找到${targetProvince}的油价数据，全国均价：${formatPriceText(avg)}"
            } else {
                "暂未获取到油价数据，请稍后再试"
            }
        }
    }

    /**
     * 获取油价数据（带当日缓存，双源自动切换）
     */
    private fun getOilPrices(): Map<String, OilPriceInfo> {
        val today = todayStr()
        if (cachedDate == today && cachedPrices != null) {
            android.util.Log.d(TAG, "使用今日缓存数据，来源: $cachedSource，共${cachedPrices!!.size}个省份")
            return cachedPrices!!
        }

        // 先尝试主源
        var prices = fetchOilPricesFromUrl(PRIMARY_URL, "youjia.abapi.cn")
        var source = "youjia.abapi.cn(发改委数据)"

        // 主源失败，尝试备用源
        if (prices.isEmpty()) {
            android.util.Log.w(TAG, "主源获取失败，切换到备用源: 汽车之家")
            prices = fetchOilPricesFromUrl(FALLBACK_URL, "汽车之家")
            source = "汽车之家"
        }

        if (prices.isNotEmpty()) {
            cachedDate = today
            cachedPrices = prices
            cachedSource = source
            android.util.Log.d(TAG, "油价数据获取成功，来源: $source，共${prices.size}个省份")
        }
        return prices
    }

    /**
     * 从指定 URL 获取并解析油价表格
     * @param url 油价页面 URL
     * @param sourceName 数据源名称（用于日志）
     */
    private fun fetchOilPricesFromUrl(url: String, sourceName: String): Map<String, OilPriceInfo> {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                android.util.Log.w(TAG, "$sourceName 返回码: $responseCode")
                return emptyMap()
            }

            val html = conn.inputStream.bufferedReader(java.nio.charset.StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val prices = parseOilPriceTable(html)
            if (prices.isEmpty()) {
                android.util.Log.w(TAG, "$sourceName 解析结果为空")
            }
            prices
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.w(TAG, "$sourceName 请求超时: ${e.message}")
            emptyMap()
        } catch (e: java.net.UnknownHostException) {
            android.util.Log.w(TAG, "$sourceName 域名解析失败: ${e.message}")
            emptyMap()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "$sourceName 请求失败: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 解析 HTML 中的油价表格
     * 兼容两种表格结构：
     * - youjia.abapi.cn：第1行标题，第2行表头，第3行起数据
     * - 汽车之家：第1行表头，第2行起数据
     */
    private fun parseOilPriceTable(html: String): Map<String, OilPriceInfo> {
        val result = LinkedHashMap<String, OilPriceInfo>()

        try {
            // 查找第一个表格
            val tablePattern = Pattern.compile("<table[^>]*>(.*?)</table>", Pattern.DOTALL)
            val tableMatcher = tablePattern.matcher(html)
            if (!tableMatcher.find()) {
                android.util.Log.w(TAG, "未找到油价表格")
                return emptyMap()
            }
            val table = tableMatcher.group(1)

            // 查找所有行
            val rowPattern = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL)
            val rowMatcher = rowPattern.matcher(table)

            while (rowMatcher.find()) {
                val row = rowMatcher.group(1)

                // 提取单元格
                val cellPattern = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL)
                val cellMatcher = cellPattern.matcher(row)
                val cells = mutableListOf<String>()
                while (cellMatcher.find()) {
                    val cell = cellMatcher.group(1)
                    // 去掉 HTML 标签，去掉空白
                    val clean = cell.replace(Regex("<[^>]+>"), "").trim()
                    cells.add(clean)
                }

                // 跳过表头行（第一列包含"地区"或"省份"）
                if (cells.isNotEmpty() && (cells[0].contains("地区") || cells[0].contains("省份") || cells[0].contains("📍"))) {
                    continue
                }

                // 数据行需要至少5列：省份、92、95、98、0
                if (cells.size >= 5) {
                    val province = cells[0]
                    val price92 = cells[1]
                    val price95 = cells[2]
                    val price98 = cells[3]
                    val price0 = cells[4]

                    // 验证价格格式（应该是数字），省份名不能为空
                    if (price92.matches(Regex("\\d+\\.\\d+")) && province.isNotBlank()) {
                        result[province] = OilPriceInfo(province, price92, price95, price98, price0)
                    }
                }
            }

            android.util.Log.d(TAG, "解析到${result.size}个省份的油价数据")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "油价表格解析失败: ${e.message}")
        }

        return result
    }

    /**
     * 省份名标准化：处理"安徽省"→"安徽"、"广西壮族自治区"→"广西"等
     */
    private fun normalizeProvince(name: String): String {
        var result = name.trim()
        result = result.removeSuffix("省")
        result = result.removeSuffix("市")
        result = result.removeSuffix("自治区")
        result = result.removeSuffix("壮族自治区")
        result = result.removeSuffix("回族自治区")
        result = result.removeSuffix("维吾尔自治区")
        return result
    }

    /**
     * 计算全国均价
     */
    private fun calculateAverage(prices: Map<String, OilPriceInfo>): OilPriceInfo? {
        if (prices.isEmpty()) return null

        var sum92 = 0.0
        var sum95 = 0.0
        var sum98 = 0.0
        var sum0 = 0.0
        var count = 0

        for (info in prices.values) {
            try {
                sum92 += info.price92.toDouble()
                sum95 += info.price95.toDouble()
                sum98 += info.price98.toDouble()
                sum0 += info.price0.toDouble()
                count++
            } catch (_: NumberFormatException) {
                // 跳过无效价格
            }
        }

        if (count == 0) return null

        return OilPriceInfo(
            province = "全国均价",
            price92 = String.format("%.2f", sum92 / count),
            price95 = String.format("%.2f", sum95 / count),
            price98 = String.format("%.2f", sum98 / count),
            price0 = String.format("%.2f", sum0 / count)
        )
    }

    /**
     * 格式化油价播报文本
     */
    private fun formatPriceText(info: OilPriceInfo): String {
        val sb = StringBuilder()
        sb.append("${info.province}今日油价：")
        sb.append("92号汽油${info.price92}元每升，")
        sb.append("95号汽油${info.price95}元每升")
        if (info.price98.isNotBlank() && info.price98.matches(Regex("\\d+\\.\\d+"))) {
            sb.append("，98号汽油${info.price98}元每升")
        }
        if (info.price0.isNotBlank() && info.price0.matches(Regex("\\d+\\.\\d+"))) {
            sb.append("，0号柴油${info.price0}元每升")
        }
        return sb.toString()
    }

    /**
     * 从用户文本中提取省份名
     * 例如"安徽油价"→"安徽"，"广东今日油价"→"广东"
     */
    fun extractProvince(text: String?): String? {
        if (text.isNullOrBlank()) return null
        var result: String = text
        // 去掉常见的油价相关词
        val removeWords = listOf(
            "今日油价", "今天油价", "油价", "油价格", "汽油价格", "柴油价格",
            "今日", "今天", "查询", "多少", "多少钱", "现在", "当前",
            "的", "是", "呢", "啊", "吧", "吗", "呀"
        )
        for (word in removeWords) {
            result = result.replace(word, "")
        }
        result = result.trim()
        return if (result.isNotBlank()) result else null
    }
}
