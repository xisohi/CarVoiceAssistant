package com.xisohi.car.voiceassistant.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xisohi.car.voiceassistant.core.autoinput.AmapInputHandler
import com.xisohi.car.voiceassistant.core.autoinput.BaiduMapInputHandler
import com.xisohi.car.voiceassistant.core.autoinput.MusicFreeInputHandler

class AutoInputService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoInputService"

        @Volatile
        var currentRootNode: AccessibilityNodeInfo? = null
            private set

        @Volatile
        var serviceContext: android.content.Context? = null
            private set

        @Volatile
        private var instance: AutoInputService? = null

        private val handler = Handler(Looper.getMainLooper())

        fun tap(x: Float, y: Float) {
            val service = instance ?: return
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "模拟点击: ($x, $y)")
        }

        fun longPress(x: Float, y: Float, duration: Long = 800) {
            val service = instance ?: return
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "模拟长按: ($x, $y)")
        }

        fun triggerAfterDelay(delayMs: Long) {
            instance?.triggerAfterDelayInternal(delayMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceContext = this
        Log.d(TAG, "无障碍服务已连接")
    }

    private val handlers = listOf(
        AmapInputHandler(),
        BaiduMapInputHandler(),
        MusicFreeInputHandler()
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // 优先使用事件自带的 source，比 rootInActiveWindow 更及时
        val sourceNode = event.source
        val rootNode = if (sourceNode != null) {
            // 从 source 向上找到根节点
            var root: AccessibilityNodeInfo = sourceNode
            var parent = root.parent
            while (parent != null) {
                root = parent
                parent = root.parent
            }
            root
        } else {
            rootInActiveWindow
        }

        if (rootNode == null) {
            Log.w(TAG, "无法获取窗口根节点")
            return
        }

        // 回收旧的根节点（避免内存泄漏）
        currentRootNode?.recycle()
        currentRootNode = rootNode

        for (handler in handlers) {
            if (handler.targetPackage == packageName && handler.hasPendingTask()) {
                Log.d(TAG, "分发事件给 ${handler.targetPackage}")
                try {
                    handler.handle(event, rootNode)
                } catch (e: Exception) {
                    Log.e(TAG, "Handler执行失败: ${handler.targetPackage}", e)
                    handler.clearPendingTask()
                }
                return
            }
        }
    }

    private fun triggerAfterDelayInternal(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            try {
                val rootNode = rootInActiveWindow ?: return@postDelayed
                val packageName = rootNode.packageName?.toString() ?: run {
                    rootNode.recycle()
                    return@postDelayed
                }

                // 更新当前根节点
                currentRootNode?.recycle()
                currentRootNode = rootNode

                for (handler in handlers) {
                    if (handler.targetPackage == packageName && handler.hasPendingTask()) {
                        Log.d(TAG, "主动触发分发给 ${handler.targetPackage}")
                        try {
                            handler.handle(AccessibilityEvent.obtain(), rootNode)
                        } catch (e: Exception) {
                            Log.e(TAG, "主动触发Handler失败", e)
                            handler.clearPendingTask()
                        }
                        return@postDelayed
                    }
                }

                // 没有匹配的handler，回收节点
                rootNode.recycle()

            } catch (e: Exception) {
                Log.w(TAG, "主动触发失败: ${e.message}")
            }
        }, delayMs)
    }

    override fun onInterrupt() {
        currentRootNode?.recycle()
        currentRootNode = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        currentRootNode?.recycle()
        currentRootNode = null
        instance = null
        serviceContext = null
        super.onDestroy()
    }
}