package com.xisohi.car.voiceassistant.core.skill

import android.content.Context
import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent

/**
 * 天气查询 Skill
 *
 * 职责：天气查询、城市提取、时间词解析
 * 依赖：Context + 网络
 *
 * 公开接口（供 VoiceAssistantService 调用）：
 * - queryWeather(city, timeIndex)
 * - getTimeIndex(text)
 * - isOnlyTimeWord(word)
 * - extractCity(word)
 */
class WeatherSkill(private val context: Context) {

    /**
     * 注意：此方法仅作为 SkillExecutor.execute() 的兜底分支，
     * 实际天气查询不走这里，而是由 VoiceAssistantService 直接调用
     * queryWeather()（在后台线程执行，因为需要网络请求）。
     *
     * 如果用户在离线模式下通过 execute() 触发 ask.weather，
     * 返回提示文案。
     */
    fun execute(intent: VoiceIntent): ExecutionResult = when (intent.action) {
        "ask.weather" -> ExecutionResult(true, "离线模式下暂时查不了天气，建议联网后使用")
        else -> ExecutionResult(false, "不支持的天气指令")
    }

    /**
     * 通过IP定位获取当前城市（不需要定位权限，只要联网就能用）
     * 使用 ipapi.co 免费API，支持HTTPS
     * 失败时返回 null，调用方应回退到默认城市
     */
    private fun getCurrentCityByIp(): String? {
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
            val city = obj.optString("city", "")
            val region = obj.optString("region", "")
            when {
                city.isNotBlank() && city != "null" -> city
                region.isNotBlank() && region != "null" -> region
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w("WeatherSkill", "IP定位失败: ${e.message}")
            null
        }
    }

    /**
     * 查询天气（在后台线程调用，不能在主线程执行）
     * @param city 指定城市，传 null 或空字符串时自动IP定位
     * @param timeIndex 时间索引：0=今天，1=明天，2=后天
     * @return 天气播报文本；定位失败时返回 null（让上层提示用户说城市名）
     * 使用 wttr.in 免费API，不需要注册账号
     * API: https://wttr.in/城市名?format=j1
     */
    fun queryWeather(city: String? = null, timeIndex: Int = 0): String? {
        val targetCity = when {
            !city.isNullOrBlank() -> city
            else -> {
                val located = getCurrentCityByIp()
                if (located == null) {
                    android.util.Log.w("WeatherSkill", "IP定位失败，返回null让上层提示用户")
                    return null
                }
                located
            }
        }
        android.util.Log.d("WeatherSkill", "天气查询城市: $targetCity")

        return try {
            val url = java.net.URL("https://wttr.in/${java.net.URLEncoder.encode(targetCity, "UTF-8")}?format=j1&lang=zh")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "curl/7.68.0")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                return "天气查询失败，服务器返回 $responseCode"
            }

            val inputStream = conn.inputStream
            val json = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()
            conn.disconnect()

            parseWeatherJson(json, targetCity, timeIndex)
        } catch (e: java.net.SocketTimeoutException) {
            "天气查询超时，请检查网络连接"
        } catch (e: java.net.UnknownHostException) {
            "网络不可用，请连接网络后再试"
        } catch (e: Exception) {
            "天气查询失败：${e.message ?: "未知错误"}"
        }
    }

    /**
     * 英文天气描述 → 中文映射表
     */
    private val weatherDescMap = mapOf(
        "Sunny" to "晴",
        "Clear" to "晴朗",
        "Partly cloudy" to "局部多云",
        "Cloudy" to "多云",
        "Overcast" to "阴天",
        "Mist" to "薄雾",
        "Fog" to "雾",
        "Freezing fog" to "冻雾",
        "Patchy rain possible" to "可能有零星小雨",
        "Patchy rain nearby" to "附近有零星小雨",
        "Patchy light rain" to "零星小雨",
        "Light rain" to "小雨",
        "Moderate rain at times" to "间歇性中雨",
        "Moderate rain" to "中雨",
        "Heavy rain at times" to "间歇性大雨",
        "Heavy rain" to "大雨",
        "Light freezing rain" to "小冻雨",
        "Moderate or heavy freezing rain" to "中到大冻雨",
        "Light sleet" to "小雨夹雪",
        "Moderate or heavy sleet" to "中到大雨夹雪",
        "Patchy light snow" to "零星小雪",
        "Light snow" to "小雪",
        "Patchy moderate snow" to "零星中雪",
        "Moderate snow" to "中雪",
        "Patchy heavy snow" to "零星大雪",
        "Heavy snow" to "大雪",
        "Ice pellets" to "冰粒",
        "Light rain shower" to "小阵雨",
        "Moderate or heavy rain shower" to "中到大阵雨",
        "Torrential rain shower" to "暴雨",
        "Light sleet showers" to "小阵雨夹雪",
        "Moderate or heavy sleet showers" to "中到大阵雨夹雪",
        "Light snow showers" to "小阵雪",
        "Moderate or heavy snow showers" to "中到大阵雪",
        "Light showers of ice pellets" to "小阵冰粒",
        "Moderate or heavy showers of ice pellets" to "中到大阵冰粒",
        "Patchy light rain with thunder" to "零星小雷雨",
        "Moderate or heavy rain with thunder" to "中到大雷雨",
        "Patchy light snow with thunder" to "零星小雷雪",
        "Moderate or heavy snow with thunder" to "中到大雷雪",
        "Thundery outbreaks possible" to "可能有雷阵雨",
        "Blowing snow" to "吹雪",
        "Blizzard" to "暴风雪",
        "Smoky haze" to "霾",
        "Haze" to "薄雾",
        "Smoke" to "烟雾",
        "Widespread dust" to "大范围浮尘",
        "Dust" to "浮尘",
        "Sandstorm" to "沙尘暴",
        "Sand/dust whirls" to "沙尘",
        "Strong sandstorm" to "强沙尘暴",
        "Whiteout" to "白茫茫",
        "Freezing drizzle" to "冻毛毛雨",
        "Heavy freezing drizzle" to "大冻毛毛雨",
        "Patchy freezing drizzle possible" to "可能有零星冻毛毛雨",
        "Patchy freezing drizzle nearby" to "附近有零星冻毛毛雨"
    )

    /**
     * 时间词列表
     */
    private val timeWords = listOf(
        "今日", "今天", "明日", "明天", "后天", "大后天",
        "昨日", "昨天", "前天", "大前天",
        "现在", "当前", "目前", "此刻",
        "这周", "本周", "下周", "下星期",
        "这个月", "本月", "下个月", "下月",
        "今年", "本年", "明年", "来年"
    )

    /**
     * 判断字符串是否只包含时间词（没有城市名）
     */
    fun isOnlyTimeWord(word: String?): Boolean {
        if (word.isNullOrBlank()) return false
        var result: String = word
        for (tw in timeWords) {
            result = result.replace(tw, "")
        }
        return result.isBlank()
    }

    /**
     * 从包含时间词的字符串中提取城市名
     */
    fun extractCity(word: String?): String? {
        if (word.isNullOrBlank()) return null
        var result: String = word
        for (tw in timeWords) {
            result = result.replace(tw, "")
        }
        result = result.trim()
        return if (result.isNotBlank()) result else null
    }

    /**
     * 从文本中提取时间索引
     * 0=今天，1=明天，2=后天
     */
    fun getTimeIndex(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return when {
            text.contains("后天") || text.contains("大后天") -> 2
            text.contains("明天") || text.contains("明日") -> 1
            else -> 0
        }
    }

    /**
     * 把英文天气描述翻译成中文
     */
    private fun translateWeatherDesc(english: String): String {
        val trimmed = english.trim()
        return weatherDescMap[trimmed] ?: run {
            val lower = trimmed.lowercase()
            weatherDescMap.entries.firstOrNull { it.key.lowercase() == lower }?.value
                ?: run {
                    android.util.Log.w("WeatherSkill", "未翻译的天气描述: $english")
                    trimmed
                }
        }
    }

    /**
     * 解析 wttr.in 返回的 JSON
     */
    private fun parseWeatherJson(json: String, city: String, timeIndex: Int = 0): String {
        return try {
            val obj = org.json.JSONObject(json)
            val weatherArray = obj.getJSONArray("weather")

            val idx = timeIndex.coerceIn(0, weatherArray.length() - 1)
            val dayWeather = weatherArray.getJSONObject(idx)

            val dayLabel = when (idx) {
                0 -> "今天"
                1 -> "明天"
                2 -> "后天"
                else -> "第${idx + 1}天"
            }

            val hourly = dayWeather.getJSONArray("hourly")
            val midday = hourly.getJSONObject((hourly.length() - 1) / 2)
            val weatherEn = midday.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
            val weatherDesc = translateWeatherDesc(weatherEn)

            val maxTemp = dayWeather.getString("maxtempC")
            val minTemp = dayWeather.getString("mintempC")

            val sb = StringBuilder()
            if (idx == 0) {
                val current = obj.getJSONArray("current_condition").getJSONObject(0)
                val tempC = current.getString("temp_C")
                val feelsLike = current.getString("FeelsLikeC")
                val humidity = current.getString("humidity")
                val windSpeed = current.getString("windspeedKmph")

                sb.append("${city}今天${weatherDesc}，")
                sb.append("当前温度${tempC}度，体感${feelsLike}度，")
                sb.append("今天${minTemp}到${maxTemp}度，")
                sb.append("湿度${humidity}%，风速${windSpeed}公里每小时")
            } else {
                sb.append("${city}${dayLabel}${weatherDesc}，")
                sb.append("温度${minTemp}到${maxTemp}度")
            }

            sb.toString()
        } catch (e: Exception) {
            "天气数据解析失败：${e.message ?: "未知错误"}"
        }
    }
}
