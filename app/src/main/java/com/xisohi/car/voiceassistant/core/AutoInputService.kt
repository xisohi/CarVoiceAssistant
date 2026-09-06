package com.xisohi.car.voiceassistant.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xisohi.car.voiceassistant.core.autoinput.AmapInputHandler
import com.xisohi.car.voiceassistant.core.autoinput.MusicFreeInputHandler

/**
 * 无障碍服务：自动操作第三方 APP。
 *
 * 本类只负责事件分发和手势执行，具体 APP 的操作逻辑由独立的 Handler 实现：
 * - [AmapInputHandler]：高德地图车机版（导航自动填入）
 * - [MusicFreeInputHandler]：MusicFree（搜索播放）
 *
 * 新增 APP 支持时：
 * 1. 在 [autoinput] 包下新建一个 Handler，继承 [com.xisohi.car.voiceassistant.core.autoinput.AutoInputHandler]
 * 2. 在本类的 [handlers] 列表中注册
 * 3. 不要修改其他 APP 的 Handler
 */
class AutoInputService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoInputService"

        /** 当前窗口根节点（供 Handler 延迟回调时使用） */
        @Volatile
        var currentRootNode: AccessibilityNodeInfo? = null
            private set

        /** 服务 Context（供 Handler 使用，如操作剪贴板） */
        @Volatile
        var serviceContext: android.content.Context? = null
            private set

        /** 服务实例（供 Handler 调用 dispatchGesture 等服务方法） */
        @Volatile
        private var instance: AutoInputService? = null

        /**
         * 模拟点击指定坐标。
         * @param x 屏幕 x 坐标
         * @param y 屏幕 y 坐标
         */
        fun tap(x: Float, y: Float) {
            val service = instance ?: return
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "模拟点击: ($x, $y)")
        }

        /**
         * 模拟长按指定坐标（弹出系统菜单，如粘贴）。
         * @param x 屏幕 x 坐标
         * @param y 屏幕 y 坐标
         * @param duration 长按时长（毫秒），默认 800ms
         */
        fun longPress(x: Float, y: Float, duration: Long = 800) {
            val service = instance ?: return
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "模拟长按: ($x, $y), 时长=${duration}ms")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceContext = this
        Log.d(TAG, "无障碍服务已连接")
    }

    /** 已注册的 APP 处理器列表 */
    private val handlers = listOf(
        AmapInputHandler(),
        MusicFreeInputHandler()
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val rootNode = rootInActiveWindow ?: return
        currentRootNode = rootNode

        // 分发给对应 APP 的 Handler
        for (handler in handlers) {
            if (handler.targetPackage == packageName && handler.hasPendingTask()) {
                Log.d(TAG, "分发事件给 ${handler.targetPackage}")
                handler.handle(event, rootNode)
                return
            }
        }
    }

    override fun onInterrupt() {
        currentRootNode = null
    }
}
