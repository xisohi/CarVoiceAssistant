package com.xisohi.car.voiceassistant.core

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 高德地图 POI 搜索（Web 服务 API）
 *
 * 用于语音导航的多结果选择：
 *   用户说"导航到大徐村" → 搜索 → 拿到结果列表 → TTS播报选项 → 用户说"选1" → 用经纬度拉起导航
 *
 * API 文档：https://lbs.amap.com/api/webservice/guide/api/search/#text
 *
 * 注意：需要在高德开放平台申请 Web 服务 Key，配置在 AMAP_WEB_KEY 常量中。
 */
object AmapSearcher {

    private const val TAG = "AmapSearcher"

    // ★ 高德 Web 服务 API Key（用户在设置页配置，谁用谁的 Key）
    // 申请地址：https://lbs.amap.com/
    // 注意：这是 Web 服务 Key，不是 Android SDK Key，也不是语音识别 Key
    private var amapWebKey: String = ""

    /**
     * 设置高德 Web 服务 API Key（在设置页保存配置时调用）
     */
    fun setKey(key: String) {
        amapWebKey = key
        Log.d(TAG, "高德 Web 服务 Key 已更新: ${if (key.isBlank()) "（空）" else key.take(8) + "..."}")
    }

    /**
     * 获取当前配置的 Key
     */
    fun getKey(): String = amapWebKey

    // 搜索结果数量上限（播报太多用户记不住）
    private const val MAX_RESULTS = 5

    /**
     * POI 搜索结果
     */
    data class PoiResult(
        val name: String,        // 地点名称
        val address: String,     // 地址
        val latitude: Double,    // 纬度
        val longitude: Double    // 经度
    )

    /**
     * 关键字搜索 POI
     *
     * @param keyword 搜索关键字（如"大徐村"）
     * @param city 城市名（如"蚌埠"），可选，用于限制搜索范围
     * @return 搜索结果列表，失败返回空列表
     */
    fun search(keyword: String, city: String? = null): List<PoiResult> {
        if (keyword.isBlank()) return emptyList()
        if (amapWebKey.isBlank()) {
            Log.w(TAG, "高德 Web 服务 Key 未配置，请在设置页配置")
            return emptyList()
        }

        return try {
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            var urlStr = "https://restapi.amap.com/v3/place/text?" +
                    "keywords=$encodedKeyword" +
                    "&offset=$MAX_RESULTS" +
                    "&page=1" +
                    "&key=$amapWebKey"
            if (!city.isNullOrBlank()) {
                urlStr += "&city=${URLEncoder.encode(city, "UTF-8")}"
            }

            Log.d(TAG, "开始搜索 POI: keyword=$keyword, city=$city")
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "搜索失败: HTTP $responseCode")
                return emptyList()
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            parseResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "搜索异常: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 解析高德搜索 API 响应
     */
    private fun parseResponse(response: String): List<PoiResult> {
        return try {
            val json = JSONObject(response)
            val status = json.optString("status", "0")
            if (status != "1") {
                val info = json.optString("info", "未知错误")
                Log.w(TAG, "搜索返回错误: status=$status, info=$info")
                return emptyList()
            }

            val count = json.optInt("count", 0)
            Log.d(TAG, "搜索到 $count 个结果")

            val pois = json.optJSONArray("pois") ?: return emptyList()
            val results = mutableListOf<PoiResult>()

            for (i in 0 until minOf(pois.length(), MAX_RESULTS)) {
                val poi = pois.optJSONObject(i) ?: continue
                val name = poi.optString("name", "")
                val address = poi.optString("address", "")
                val location = poi.optString("location", "")

                if (name.isBlank() || location.isBlank()) continue

                // location 格式："经度,纬度"
                val parts = location.split(",")
                if (parts.size != 2) continue

                val longitude = parts[0].toDoubleOrNull() ?: continue
                val latitude = parts[1].toDoubleOrNull() ?: continue

                results.add(PoiResult(name, address, latitude, longitude))
            }

            Log.d(TAG, "解析到 ${results.size} 个有效结果")
            results
        } catch (e: Exception) {
            Log.e(TAG, "解析响应异常: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 检查是否已配置 Key
     */
    fun isKeyConfigured(): Boolean = amapWebKey.isNotBlank()
}
