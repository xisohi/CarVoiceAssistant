package com.xisohi.car.voiceassistant.core

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务：自动在高德地图车机版搜索框中填入目的地并触发搜索。
 *
 * 工作流程：
 * 1. 导航功能启动时，通过静态方法 setPendingDestination() 设置待输入的目的地
 * 2. 无障碍服务监听窗口变化，检测到高德车机版搜索页（含"请输入目的地"编辑框）
 * 3. 自动填入目的地文本，然后触发搜索（点击搜索按钮或发送回车）
 *
 * 需要用户在系统设置中授权无障碍服务。
 */
class AutoInputService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoInputService"
        private const val TARGET_PACKAGE = "com.autonavi.amapauto"

        @Volatile
        private var pendingDestination: String? = null

        /** 设置待自动输入的目的地（导航功能启动时调用） */
        fun setPendingDestination(dest: String) {
            pendingDestination = dest
            Log.d(TAG, "设置待输入目的地: $dest")
        }

        /** 清除待输入目的地 */
        fun clearPendingDestination() {
            pendingDestination = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val dest = pendingDestination ?: return
        if (event.packageName != TARGET_PACKAGE) return

        try {
            val rootNode = rootInActiveWindow ?: return
            // 查找搜索框（EditText，含"请输入目的地"hint）
            val editText = findEditText(rootNode)
            if (editText != null) {
                Log.d(TAG, "找到搜索框，输入目的地: $dest")
                inputText(editText, dest)
                // 延迟触发搜索
                android.os.Handler(mainLooper).postDelayed({
                    try {
                        triggerSearch(rootInActiveWindow)
                    } catch (_: Exception) {
                    }
                }, 500)
                pendingDestination = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动输入失败: ${e.message}")
        }
    }

    /** 查找可输入的 EditText */
    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className == "android.widget.EditText") {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) return found
        }
        return null
    }

    /** 在 EditText 中输入文本 */
    private fun inputText(editText: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** 触发搜索：点击搜索按钮，或发送回车 */
    private fun triggerSearch(rootNode: AccessibilityNodeInfo?) {
        if (rootNode == null) return
        // 查找"搜索"按钮
        val searchButton = findButtonByText(rootNode, "搜索")
        if (searchButton != null) {
            Log.d(TAG, "点击搜索按钮")
            searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        // 没找到搜索按钮，给搜索框发送回车
        val editText = findEditText(rootNode)
        if (editText != null) {
            Log.d(TAG, "发送回车触发搜索")
            editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.os.Handler(mainLooper).postDelayed({
                try {
                    val arguments = Bundle()
                    arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        "\n"
                    )
                    editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                } catch (_: Exception) {
                }
            }, 200)
        }
    }

    /** 按文本查找按钮 */
    private fun findButtonByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.contains(text) == true && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findButtonByText(child, text)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {}
}
