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
            currentStep = Step.NEED_CLICK_SEARCH_BOX
            Log.d(TAG, "设置待输入目的地: $dest, 阶段: NEED_CLICK_SEARCH_BOX")
        }

        fun clearPendingDestination() {
            pendingDestination = null
            currentStep = Step.IDLE
        }

        /** 处理阶段 */
        private enum class Step {
            IDLE,                       // 空闲
            NEED_CLICK_SEARCH_BOX,      // 需要点击主界面搜索框（打开搜索页）
            NEED_CLICK_SEARCH_INPUT,    // 需要点击搜索页的输入框（获取焦点）
            NEED_INPUT_TEXT,             // 需要输入目的地
            NEED_CLICK_SEARCH_BUTTON     // 需要点击搜索按钮（键盘搜索键）
        }

        @Volatile
        private var currentStep = Step.IDLE
    }

    override val targetPackage = PACKAGE
    private val handler = Handler(Looper.getMainLooper())

    override fun hasPendingTask(): Boolean = pendingDestination != null

    override fun clearPendingTask() {
        pendingDestination = null
        currentStep = Step.IDLE
    }

    override fun handle(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo) {
        val dest = pendingDestination
        if (dest == null) {
            Log.d(TAG, "handle 被调用，但 pendingDestination 为空，跳过")
            return
        }
        Log.d(TAG, "handle 被触发，待输入目的地: $dest, 阶段: $currentStep, 事件类型: ${event.eventType}")

        try {
            val rect = android.graphics.Rect()
            rootNode.getBoundsInScreen(rect)
            val screenWidth = rect.width()
            val screenHeight = rect.height()

            when (currentStep) {
                Step.NEED_CLICK_SEARCH_BOX -> {
                    // 主界面是 MapView，没有可访问性节点，直接点击搜索框的屏幕位置
                    val searchBoxX = screenWidth / 2f
                    val searchBoxY = screenHeight * 0.09f
                    Log.d(TAG, "点击主界面搜索框: ($searchBoxX, $searchBoxY)")
                    AutoInputService.tap(searchBoxX, searchBoxY)
                    currentStep = Step.NEED_CLICK_SEARCH_INPUT
                    // 延迟 2 秒后主动触发，点击搜索页输入框
                    handler.postDelayed({
                        try {
                            val currentRoot = AutoInputService.currentRootNode
                            if (currentRoot != null && pendingDestination != null) {
                                handle(AccessibilityEvent.obtain(), currentRoot)
                            }
                        } catch (_: Exception) {}
                    }, 2000)
                }

                Step.NEED_CLICK_SEARCH_INPUT -> {
                    // 搜索页已打开，点击输入框获取焦点
                    val inputX = screenWidth / 2f
                    val inputY = screenHeight * 0.09f
                    Log.d(TAG, "点击搜索页输入框: ($inputX, $inputY)")
                    AutoInputService.tap(inputX, inputY)
                    currentStep = Step.NEED_INPUT_TEXT
                    // 延迟 1 秒后长按输入框，弹出粘贴菜单
                    handler.postDelayed({
                        try {
                            val currentRoot = AutoInputService.currentRootNode
                            if (currentRoot != null && pendingDestination != null) {
                                handle(AccessibilityEvent.obtain(), currentRoot)
                            }
                        } catch (_: Exception) {}
                    }, 1000)
                }

                Step.NEED_INPUT_TEXT -> {
                    // 先把目的地复制到剪贴板
                    copyToClipboard("导航目的地", dest)
                    // 长按输入框，弹出粘贴菜单
                    val inputX = screenWidth / 2f
                    val inputY = screenHeight * 0.09f
                    Log.d(TAG, "长按输入框弹出粘贴菜单: ($inputX, $inputY), 目的地: $dest")
                    AutoInputService.longPress(inputX, inputY, 1000)
                    currentStep = Step.NEED_CLICK_SEARCH_BUTTON
                    // 延迟 1 秒后点击粘贴按钮位置（通常在输入框下方）
                    handler.postDelayed({
                        try {
                            // 粘贴按钮通常在弹出菜单的第一个选项，位置在输入框左下方
                            val pasteX = screenWidth * 0.15f
                            val pasteY = screenHeight * 0.16f
                            Log.d(TAG, "点击粘贴按钮: ($pasteX, $pasteY)")
                            AutoInputService.tap(pasteX, pasteY)

                            // 再延迟 1.5 秒后点击键盘搜索键
                            handler.postDelayed({
                                try {
                                    // 键盘搜索键通常在右下角
                                    val searchKeyX = screenWidth * 0.88f
                                    val searchKeyY = screenHeight * 0.88f
                                    Log.d(TAG, "点击键盘搜索键: ($searchKeyX, $searchKeyY)")
                                    AutoInputService.tap(searchKeyX, searchKeyY)
                                    pendingDestination = null
                                    currentStep = Step.IDLE
                                } catch (e: Exception) {
                                    Log.w(TAG, "点击搜索键失败: ${e.message}")
                                }
                            }, 1500)
                        } catch (e: Exception) {
                            Log.w(TAG, "点击粘贴失败: ${e.message}")
                        }
                    }, 1000)
                }

                else -> {
                    Log.d(TAG, "阶段: $currentStep，不处理")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动输入失败: ${e.message}")
        }
    }

    /** 复制文本到剪贴板 */
    private fun copyToClipboard(label: String, text: String) {
        try {
            val clipboard = AutoInputService.serviceContext?.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            Log.d(TAG, "已复制到剪贴板: $text")
        } catch (e: Exception) {
            Log.w(TAG, "复制到剪贴板失败: ${e.message}")
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