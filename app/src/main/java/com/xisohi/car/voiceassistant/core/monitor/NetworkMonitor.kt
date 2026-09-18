package com.xisohi.car.voiceassistant.core.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网络监控器
 *
 * 职责：
 * - 监听网络状态变化（连接/断开）
 * - 检测网络可用性（WiFi/蜂窝/以太网）
 * - 检测网络连通性（是否能访问外网，HTTP探测）
 * - 缓存检测结果，避免重复检测
 *
 * 车机场景优化：
 * - WiFi 已连接但可能无外网（手机热点没开流量、路由器断网）
 * - 网络重连频繁，防抖处理避免频繁检测
 * - 异步检测不阻塞主线程，避免ANR
 *
 * 使用方式：
 * ```
 * val monitor = NetworkMonitor(context, scope)
 * monitor.start(object : NetworkMonitor.Callback {
 *     override fun onNetworkAvailable() { ... }
 *     override fun onNetworkLost() { ... }
 * })
 * val available = monitor.isAvailable()
 * val reachable = monitor.isReachable()
 * monitor.checkReachableAsync()
 * monitor.stop()
 * ```
 */
class NetworkMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    /** 网络状态变化回调 */
    interface Callback {
        /** 网络已连接 */
        fun onNetworkAvailable()
        /** 网络已断开 */
        fun onNetworkLost()
    }

    // 网络连通性缓存
    @Volatile
    private var networkReachable = true

    // 缓存是否有效（true=直接用缓存，false=需要重新检测）
    @Volatile
    private var networkCacheValid = false

    // 是否正在检测中（避免重复启动检测线程）
    private val networkCheckInProgress = AtomicBoolean(false)

    // 网络变化回调
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 上次网络变化时间（用于防抖）
    private var lastNetworkChangeTime = 0L

    // 网络变化防抖时间（5秒内的连续回调只处理一次）
    private val NETWORK_CHANGE_DEBOUNCE_MS = 5000L

    // 网络探测URL（百度AI主站，中性探测，不会触发限流）
    private val PROBE_URL = "https://aip.baidubce.com"

    // 探测超时时间（2秒）
    private val PROBE_TIMEOUT_MS = 2000

    /**
     * 检查网络是否可用（是否连接了 WiFi/蜂窝/以太网）
     */
    fun isAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network != null) {
                val capabilities = cm.getNetworkCapabilities(network)
                capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查网络状态失败: ${e.message}")
            false
        }
    }

    /**
     * 获取缓存的网络连通性结果。
     * 只有当 isCacheValid() 为 true 时，这个值才可靠。
     */
    fun isReachable(): Boolean = networkReachable

    /**
     * 缓存是否有效。
     * true=直接用 isReachable() 的值；false=需要调用 checkReachableAsync() 重新检测。
     */
    fun isCacheValid(): Boolean = networkCacheValid

    /**
     * 标记网络不可用（没连网时调用）。
     */
    fun markUnavailable() {
        networkReachable = false
        networkCacheValid = true
    }

    /**
     * 使缓存失效，下次访问时重新检测。
     */
    fun invalidateCache() {
        networkCacheValid = false
    }

    /**
     * 后台异步检测网络连通性（不阻塞主线程，检测完成后更新缓存）。
     *
     * 为什么用异步而不是同步？
     * - 如果同步等待，会阻塞主线程，导致ANR
     * - 首次唤醒假设网络通，走在线识别；如果实际网络不通，在线失败后自动回退离线
     * - 检测完成后更新缓存，后续唤醒用正确的网络状态
     */
    fun checkReachableAsync() {
        // CAS：如果已经在检测中，直接返回，避免重复启动
        if (!networkCheckInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "网络检测已在进行中，跳过")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "开始异步检测网络连通性...")
                val reachable = probeNetwork()
                if (reachable) {
                    // 只有检测到"通"时才写缓存
                    // "不通"可能是网络还没就绪（DHCP/DNS没配置好），写缓存会导致误判
                    networkReachable = true
                    networkCacheValid = true
                    Log.i(TAG, "网络连通性检测: 正常（能访问外网，下次唤醒走在线）")
                } else {
                    // 检测到不通，不写缓存，让下次唤醒重新检测
                    Log.w(TAG, "网络连通性检测: 不通（不写缓存，下次唤醒重新检测）")
                }
            } catch (e: Exception) {
                Log.w(TAG, "网络连通性检测异常: ${e.message}（不写缓存）")
            } finally {
                networkCheckInProgress.set(false)
            }
        }
    }

    /**
     * 同步探测网络连通性（HTTP HEAD 请求）。
     * 2xx/3xx/4xx 都说明网络通（4xx 是业务错误，不是网络问题），
     * 只有 5xx 或连不上才说明网络有问题。
     */
    private fun probeNetwork(): Boolean {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(PROBE_URL).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "HEAD"  // HEAD 比 GET 更轻量，只拿响应头
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = false  // 不跟随重定向，3xx 也算通
            val code = conn.responseCode
            if (code in 200..499) {
                Log.d(TAG, "网络连通性检测: $PROBE_URL → HTTP $code（网络通）")
                true
            } else {
                Log.w(TAG, "网络连通性检测: $PROBE_URL → HTTP $code（网络异常）")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "网络连通性检测失败: ${e.message}（网络不通）")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 开始监听网络状态变化。
     */
    fun start(callback: Callback) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 防抖：5秒内的连续回调只处理一次
                    val now = System.currentTimeMillis()
                    if (now - lastNetworkChangeTime < NETWORK_CHANGE_DEBOUNCE_MS) return
                    lastNetworkChangeTime = now
                    Log.d(TAG, "网络已连接，延迟1秒后检测（等网络完全就绪）")
                    // 延迟1秒再检测，避免网络刚连上还没就绪时误判为不通
                    invalidateCache()
                    scope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(1000)
                        checkReachableAsync()
                    }
                    callback.onNetworkAvailable()
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "网络已断开，清空缓存（下次唤醒重新判断）")
                    // onLost 不用防抖，只清缓存，不做耗时操作
                    invalidateCache()
                    callback.onNetworkLost()
                }
            }
            this.networkCallback = networkCallback
            cm.registerDefaultNetworkCallback(networkCallback)
            Log.i(TAG, "网络变化监听已注册")
        } catch (e: Exception) {
            Log.w(TAG, "注册网络变化监听失败: ${e.message}")
        }
    }

    /**
     * 停止监听网络状态变化。
     */
    fun stop() {
        try {
            networkCallback?.let {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
                Log.d(TAG, "网络变化监听已注销")
            }
        } catch (e: Exception) {
            Log.w(TAG, "注销网络变化监听失败: ${e.message}")
        } finally {
            networkCallback = null
        }
    }

    companion object {
        private const val TAG = "NetworkMonitor"
    }
}
