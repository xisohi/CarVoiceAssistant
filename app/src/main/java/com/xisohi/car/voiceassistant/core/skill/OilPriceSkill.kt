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

        // 地级市→省份映射：油价数据只有省级，用户说"蚌埠油价"或IP定位到"蚌埠"时自动转"安徽"
        // 直辖市（北京/上海/天津/重庆）本身就是省名，无需映射
        private val CITY_TO_PROVINCE = mapOf(
            // 安徽
            "合肥" to "安徽", "芜湖" to "安徽", "蚌埠" to "安徽", "淮南" to "安徽", "马鞍山" to "安徽",
            "淮北" to "安徽", "铜陵" to "安徽", "安庆" to "安徽", "黄山" to "安徽", "滁州" to "安徽",
            "阜阳" to "安徽", "宿州" to "安徽", "六安" to "安徽", "亳州" to "安徽", "池州" to "安徽", "宣城" to "安徽",
            // 广东
            "广州" to "广东", "深圳" to "广东", "珠海" to "广东", "汕头" to "广东", "佛山" to "广东",
            "韶关" to "广东", "湛江" to "广东", "肇庆" to "广东", "江门" to "广东", "茂名" to "广东",
            "惠州" to "广东", "梅州" to "广东", "汕尾" to "广东", "河源" to "广东", "阳江" to "广东",
            "清远" to "广东", "东莞" to "广东", "中山" to "广东", "潮州" to "广东", "揭阳" to "广东", "云浮" to "广东",
            // 浙江
            "杭州" to "浙江", "宁波" to "浙江", "温州" to "浙江", "嘉兴" to "浙江", "湖州" to "浙江",
            "绍兴" to "浙江", "金华" to "浙江", "衢州" to "浙江", "舟山" to "浙江", "台州" to "浙江", "丽水" to "浙江",
            // 江苏
            "南京" to "江苏", "无锡" to "江苏", "徐州" to "江苏", "常州" to "江苏", "苏州" to "江苏",
            "南通" to "江苏", "连云港" to "江苏", "淮安" to "江苏", "盐城" to "江苏", "扬州" to "江苏",
            "镇江" to "江苏", "泰州" to "江苏", "宿迁" to "江苏",
            // 山东
            "济南" to "山东", "青岛" to "山东", "淄博" to "山东", "枣庄" to "山东", "东营" to "山东",
            "烟台" to "山东", "潍坊" to "山东", "济宁" to "山东", "泰安" to "山东", "威海" to "山东",
            "日照" to "山东", "临沂" to "山东", "德州" to "山东", "聊城" to "山东", "滨州" to "山东", "菏泽" to "山东",
            // 四川
            "成都" to "四川", "自贡" to "四川", "攀枝花" to "四川", "泸州" to "四川", "德阳" to "四川",
            "绵阳" to "四川", "广元" to "四川", "遂宁" to "四川", "内江" to "四川", "乐山" to "四川",
            "南充" to "四川", "眉山" to "四川", "宜宾" to "四川", "广安" to "四川", "达州" to "四川",
            "雅安" to "四川", "巴中" to "四川", "资阳" to "四川",
            // 湖北
            "武汉" to "湖北", "黄石" to "湖北", "十堰" to "湖北", "宜昌" to "湖北", "襄阳" to "湖北",
            "鄂州" to "湖北", "荆门" to "湖北", "孝感" to "湖北", "荆州" to "湖北", "黄冈" to "湖北",
            "咸宁" to "湖北", "随州" to "湖北", "恩施" to "湖北",
            // 湖南
            "长沙" to "湖南", "株洲" to "湖南", "湘潭" to "湖南", "衡阳" to "湖南", "邵阳" to "湖南",
            "岳阳" to "湖南", "常德" to "湖南", "张家界" to "湖南", "益阳" to "湖南", "郴州" to "湖南",
            "永州" to "湖南", "怀化" to "湖南", "娄底" to "湖南", "湘西" to "湖南",
            // 福建
            "福州" to "福建", "厦门" to "福建", "莆田" to "福建", "三明" to "福建", "泉州" to "福建",
            "漳州" to "福建", "南平" to "福建", "龙岩" to "福建", "宁德" to "福建",
            // 河南
            "郑州" to "河南", "开封" to "河南", "洛阳" to "河南", "平顶山" to "河南", "安阳" to "河南",
            "鹤壁" to "河南", "新乡" to "河南", "焦作" to "河南", "濮阳" to "河南", "许昌" to "河南",
            "漯河" to "河南", "三门峡" to "河南", "南阳" to "河南", "商丘" to "河南", "信阳" to "河南",
            "周口" to "河南", "驻马店" to "河南",
            // 河北
            "石家庄" to "河北", "唐山" to "河北", "秦皇岛" to "河北", "邯郸" to "河北", "邢台" to "河北",
            "保定" to "河北", "张家口" to "河北", "承德" to "河北", "沧州" to "河北", "廊坊" to "河北", "衡水" to "河北",
            // 辽宁
            "沈阳" to "辽宁", "大连" to "辽宁", "鞍山" to "辽宁", "抚顺" to "辽宁", "本溪" to "辽宁",
            "丹东" to "辽宁", "锦州" to "辽宁", "营口" to "辽宁", "阜新" to "辽宁", "辽阳" to "辽宁",
            "盘锦" to "辽宁", "铁岭" to "辽宁", "朝阳" to "辽宁", "葫芦岛" to "辽宁",
            // 吉林
            "长春" to "吉林", "吉林市" to "吉林", "四平" to "吉林", "辽源" to "吉林", "通化" to "吉林",
            "白山" to "吉林", "松原" to "吉林", "白城" to "吉林", "延边" to "吉林",
            // 黑龙江
            "哈尔滨" to "黑龙江", "齐齐哈尔" to "黑龙江", "鸡西" to "黑龙江", "鹤岗" to "黑龙江",
            "双鸭山" to "黑龙江", "大庆" to "黑龙江", "伊春" to "黑龙江", "佳木斯" to "黑龙江",
            "七台河" to "黑龙江", "牡丹江" to "黑龙江", "黑河" to "黑龙江", "绥化" to "黑龙江",
            // 山西
            "太原" to "山西", "大同" to "山西", "阳泉" to "山西", "长治" to "山西", "晋城" to "山西",
            "朔州" to "山西", "晋中" to "山西", "运城" to "山西", "忻州" to "山西", "临汾" to "山西", "吕梁" to "山西",
            // 陕西
            "西安" to "陕西", "铜川" to "陕西", "宝鸡" to "陕西", "咸阳" to "陕西", "渭南" to "陕西",
            "延安" to "陕西", "汉中" to "陕西", "榆林" to "陕西", "安康" to "陕西", "商洛" to "陕西",
            // 江西
            "南昌" to "江西", "景德镇" to "江西", "萍乡" to "江西", "九江" to "江西", "新余" to "江西",
            "鹰潭" to "江西", "赣州" to "江西", "吉安" to "江西", "宜春" to "江西", "抚州" to "江西", "上饶" to "江西",
            // 云南
            "昆明" to "云南", "曲靖" to "云南", "玉溪" to "云南", "保山" to "云南", "昭通" to "云南",
            "丽江" to "云南", "普洱" to "云南", "临沧" to "云南", "楚雄" to "云南", "红河" to "云南",
            "文山" to "云南", "西双版纳" to "云南", "大理" to "云南", "德宏" to "云南", "怒江" to "云南", "迪庆" to "云南",
            // 贵州
            "贵阳" to "贵州", "六盘水" to "贵州", "遵义" to "贵州", "安顺" to "贵州", "毕节" to "贵州",
            "铜仁" to "贵州", "黔东南" to "贵州", "黔南" to "贵州", "黔西南" to "贵州",
            // 广西
            "南宁" to "广西", "柳州" to "广西", "桂林" to "广西", "梧州" to "广西", "北海" to "广西",
            "防城港" to "广西", "钦州" to "广西", "贵港" to "广西", "玉林" to "广西", "百色" to "广西",
            "贺州" to "广西", "河池" to "广西", "来宾" to "广西", "崇左" to "广西",
            // 海南
            "海口" to "海南", "三亚" to "海南", "三沙" to "海南", "儋州" to "海南",
            // 甘肃
            "兰州" to "甘肃", "嘉峪关" to "甘肃", "金昌" to "甘肃", "白银" to "甘肃", "天水" to "甘肃",
            "武威" to "甘肃", "张掖" to "甘肃", "平凉" to "甘肃", "酒泉" to "甘肃", "庆阳" to "甘肃",
            "定西" to "甘肃", "陇南" to "甘肃", "临夏" to "甘肃", "甘南" to "甘肃",
            // 青海
            "西宁" to "青海", "海东" to "青海", "海北" to "青海", "黄南" to "青海", "海南州" to "青海",
            "果洛" to "青海", "玉树" to "青海", "海西" to "青海",
            // 内蒙古
            "呼和浩特" to "内蒙古", "包头" to "内蒙古", "乌海" to "内蒙古", "赤峰" to "内蒙古",
            "通辽" to "内蒙古", "鄂尔多斯" to "内蒙古", "呼伦贝尔" to "内蒙古", "巴彦淖尔" to "内蒙古",
            "乌兰察布" to "内蒙古", "兴安" to "内蒙古", "锡林郭勒" to "内蒙古", "阿拉善" to "内蒙古",
            // 宁夏
            "银川" to "宁夏", "石嘴山" to "宁夏", "吴忠" to "宁夏", "固原" to "宁夏", "中卫" to "宁夏",
            // 新疆
            "乌鲁木齐" to "新疆", "克拉玛依" to "新疆", "吐鲁番" to "新疆", "哈密" to "新疆",
            "昌吉" to "新疆", "博尔塔拉" to "新疆", "巴音郭楞" to "新疆", "阿克苏" to "新疆",
            "克孜勒苏" to "新疆", "喀什" to "新疆", "和田" to "新疆", "伊犁" to "新疆", "塔城" to "新疆", "阿勒泰" to "新疆",
            // 西藏
            "拉萨" to "西藏", "日喀则" to "西藏", "昌都" to "西藏", "林芝" to "西藏", "山南" to "西藏",
            "那曲" to "西藏", "阿里" to "西藏"
        )
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
        // 地级市→省份映射：用户说"蚌埠油价"或IP定位到"蚌埠"时自动转"安徽"
        val mappedProvince = targetProvince?.let { city ->
            CITY_TO_PROVINCE[city] ?: city
        }
        if (mappedProvince != targetProvince && targetProvince != null) {
            android.util.Log.d(TAG, "省市映射: $targetProvince → $mappedProvince")
        }
        android.util.Log.d(TAG, "油价查询省份: ${mappedProvince ?: "全国均价"}")

        // 获取油价数据（带缓存）
        val prices = getOilPrices()
        if (prices.isEmpty()) {
            return "油价查询失败，请检查网络连接后再试"
        }

        // 查找目标省份
        val targetNorm = mappedProvince?.let { normalizeProvince(it) }
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
                if (mappedProvince.isNullOrBlank()) {
                    // 没指定省份或定位失败：直接播报全国均价（avg.province 已是"全国均价"）
                    formatPriceText(avg)
                } else {
                    // 指定了省份但找不到（如冷门地名）：说明原因后播报全国均价
                    "未找到${mappedProvince}的油价数据，${formatPriceText(avg)}"
                }
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
