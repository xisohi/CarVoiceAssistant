package com.xisohi.car.voiceassistant.core.autoinput

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 高德地图车机版（com.autonavi.amapauto）自动输入处理器。
 *
 * 功能：导航时自动在搜索框中填入目的地并触发搜索。
 *
 * 注意事项：
 * - 高德车机版不支持 URI 参数自动填入搜索框，必须用无障碍服务
 * - 搜索框 hint 为"请输入目的地"
 * - 修改本类时不要影响 [MusicFreeInputHandler]
 */
class AmapInputHandler : AutoInputHandler() {

    companion object {
        private const val TAG = "AutoInput_Amap"
        const val PACKAGE = "com.autonavi.amapauto"

        @Volatile
        private var pendingDestination: String? = null

        /** 设置待自动输入的导航目的地（导航功能启动时调用） */
        fun setPendingDestination(dest: String) {
            pendingDestination = dest
            Log.d(TAG, "设置待输入目的地: $dest")
        }

        fun clearPendingDestination() {
            pendingDestination = null
        }
    }

    override val targetPackage = PACKAGE
    private val handler = Handler(Looper.getMainLooper())

    override fun hasPendingTask(): Boolean = pendingDestination != null

    override fun clearPendingTask() {
        pendingDestination = null
    }

    override fun handle(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo) {
        val dest = pendingDestination ?: return
        try {
            val editText = findEditText(rootNode)
            if (editText != null) {
                Log.d(TAG, "找到搜索框，输入目的地: $dest")
                inputText(editText, dest)
                // 延迟触发搜索（等待输入完成）
                handler.postDelayed({
                    try {
                        triggerSearch(rootNode)
                    } catch (_: Exception) {
                    }
                }, 500)
                pendingDestination = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动输入失败: ${e.message}")
        }
    }

    private fun triggerSearch(rootNode: AccessibilityNodeInfo) {
        // 查找"搜索"按钮
        val searchButton = findClickableByText(rootNode, "搜索")
        if (searchButton != null) {
            Log.d(TAG, "点击搜索按钮")
            searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        // 没找到搜索按钮，给搜索框发送回车
        val editText = findEditText(rootNode)
        if (editText != null) {
            Log.d(TAG, "发送回车触发搜索")
            inputText(editText, "\n")
        }
    }
}
