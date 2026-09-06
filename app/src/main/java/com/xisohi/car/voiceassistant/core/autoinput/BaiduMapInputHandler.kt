package com.xisohi.car.voiceassistant.core.autoinput

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xisohi.car.voiceassistant.core.AutoInputService

/**
 * 百度地图汽车版（com.baidu.naviauto）自动输入处理器。
 *
 * 功能：导航时自动在搜索框中填入目的地并触发搜索。
 *
 * 注意事项：
 * - 百度地图汽车版支持 baidumap:// URI scheme，优先尝试 URI 自动填入
 * - URI 失败时用无障碍服务兜底
 * - 修改本类时不要影响 [AmapInputHandler] 和 [MusicFreeInputHandler]
 */
class BaiduMapInputHandler : AutoInputHandler() {

    companion object {
        private const val TAG = "AutoInput_BaiduMap"
        const val PACKAGE = "com.baidu.naviauto"

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
                Log.d(TAG, "找到搜索框，当前文本: '${editText.text}', hint: '${editText.hintText}'")

                // 点击获取焦点
                editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                // 延迟设置文本（等待焦点获取）
                handler.postDelayed({
                    try {
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
                    } catch (e: Exception) {
                        Log.w(TAG, "设置文本失败: ${e.message}")
                    }
                }, 300)

                pendingDestination = null
            } else {
                Log.w(TAG, "未找到搜索框，等待...")
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动输入失败: ${e.message}")
        }
    }

    // ---------- 多种策略查找搜索框 ----------

    /**
     * 多种策略查找搜索框
     * 策略1：className == EditText 且 hint/desc/text 包含"搜索"或"目的地"或"地点"
     * 策略2：className == EditText 且是页面中唯一的可输入框
     * 策略3：通过contentDescription查找
     */
    private fun findEditTextByMultipleStrategies(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 策略1：hint包含相关关键词
        val byHint = findEditTextByHint(node, "搜索", "目的地", "输入", "地点", "地址", "search")
        if (byHint != null) return byHint

        // 策略2：contentDescription包含相关关键词
        val byDesc = findEditTextByContentDesc(node, "搜索", "search", "输入", "目的地", "地点")
        if (byDesc != null) return byDesc

        // 策略3：className为EditText，且是页面中唯一的EditText
        val allEditTexts = mutableListOf<AccessibilityNodeInfo>()
        collectAllEditTexts(node, allEditTexts)
        if (allEditTexts.size == 1) {
            return allEditTexts[0]
        }

        // 策略4：找可点击且包含"搜索"文本的节点的父容器中的EditText
        val searchContainer = findClickableByText(node, "搜索")
        if (searchContainer != null) {
            val parent = searchContainer.parent
            if (parent != null) {
                val editInParent = findFirstEditText(parent)
                if (editInParent != null) return editInParent
            }
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

    private fun findFirstEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className == "android.widget.EditText") return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditText(child)
            if (found != null) return found
        }
        return null
    }

    // ---------- 触发搜索 ----------

    private fun triggerSearch(rootNode: AccessibilityNodeInfo) {
        // 查找"搜索"按钮（精确匹配）
        val searchButton = findExactSearchButton(rootNode)
        if (searchButton != null) {
            Log.d(TAG, "点击搜索按钮（精确匹配）")
            searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // 同时用手势点击兜底
            val rect = android.graphics.Rect()
            searchButton.getBoundsInScreen(rect)
            AutoInputService.tap(rect.centerX().toFloat(), rect.centerY().toFloat())
            return
        }

        // 查找包含"搜索"文本的可点击节点
        val searchByText = findClickableByText(rootNode, "搜索")
        if (searchByText != null) {
            Log.d(TAG, "点击搜索按钮（文本匹配）")
            searchByText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // 没找到搜索按钮，给搜索框发送回车
        val editText = findFirstEditText(rootNode)
        if (editText != null) {
            Log.d(TAG, "发送回车触发搜索")
            inputText(editText, "\n")
        }
    }

    /** 精确匹配搜索按钮（排除搜索框） */
    private fun findExactSearchButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        // 精确匹配"搜索"，排除"搜索框"
        if (node.isClickable && (text == "搜索" || desc == "搜索") &&
            text != "搜索框" && desc != "搜索框") {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findExactSearchButton(child)
            if (found != null) return found
        }
        return null
    }
}
