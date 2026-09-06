package com.xisohi.car.voiceassistant.core.autoinput

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xisohi.car.voiceassistant.core.AutoInputService

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
            // 多种方式查找搜索框
            val editText = findEditTextByMultipleStrategies(rootNode)
            if (editText != null) {
                Log.d(TAG, "找到搜索框，当前文本: '${editText.text}'")

                // 清空已有文本
                val clearArgs = Bundle()
                clearArgs.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    ""
                )
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

                // 设置目的地
                val setArgs = Bundle()
                setArgs.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    dest
                )
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
                Log.d(TAG, "已填入目的地: $dest")

                // 延迟点击搜索按钮
                handler.postDelayed({
                    try {
                        val currentRoot = AutoInputService.currentRootNode
                        if (currentRoot != null) {
                            triggerSearch(currentRoot)
                        }
                    } catch (_: Exception) {
                    }
                }, 500)
                pendingDestination = null
            } else {
                Log.w(TAG, "未找到搜索框，尝试dump UI树")
                dumpNodeTree(rootNode, 0, 3, TAG)
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动输入失败: ${e.message}")
        }
    }

    // ---------- 多种策略查找搜索框 ----------

    /**
     * 多种策略查找搜索框
     * 策略1：className == EditText 且 hint/desc 包含"搜索"或"目的地"
     * 策略2：className == EditText 且是页面中唯一的可输入框
     * 策略3：通过contentDescription查找
     */
    private fun findEditTextByMultipleStrategies(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 策略1：hint包含"搜索"或"目的地"
        val byHint = findEditTextByHint(node, "搜索", "目的地", "输入", "search")
        if (byHint != null) return byHint

        // 策略2：contentDescription包含相关关键词
        val byDesc = findEditTextByContentDesc(node, "搜索", "search", "输入")
        if (byDesc != null) return byDesc

        // 策略3：className为EditText，且是页面中唯一的EditText
        val allEditTexts = mutableListOf<AccessibilityNodeInfo>()
        collectAllEditTexts(node, allEditTexts)
        if (allEditTexts.size == 1) {
            return allEditTexts[0]
        }

        return null
    }

    private fun findEditTextByHint(node: AccessibilityNodeInfo, vararg hints: String): AccessibilityNodeInfo? {
        val hint = node.hintText?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        if (node.className == "android.widget.EditText") {
            for (h in hints) {
                if (hint.contains(h, ignoreCase = true) || text.contains(h, ignoreCase = true)) {
                    return node
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditTextByHint(child, *hints)
            if (found != null) return found
        }
        return null
    }

    private fun findEditTextByContentDesc(node: AccessibilityNodeInfo, vararg keywords: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        if (node.className == "android.widget.EditText") {
            for (k in keywords) {
                if (desc.contains(k, ignoreCase = true)) {
                    return node
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditTextByContentDesc(child, *keywords)
            if (found != null) return found
        }
        return null
    }

    private fun collectAllEditTexts(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        if (node.className == "android.widget.EditText") {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllEditTexts(child, results)
        }
    }

    // ---------- 触发搜索 ----------

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