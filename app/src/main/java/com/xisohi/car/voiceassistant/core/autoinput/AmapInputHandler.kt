package com.xisohi.car.voiceassistant.core.autoinput

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xisohi.car.voiceassistant.core.AutoInputService

/**
 * 高德地图车机版（com.autonavi.amapauto）自动输入处理器。
 *
 * 修复要点：
 * 1. 废弃剪贴板+长按粘贴方案，改用 ACTION_SET_TEXT 直接输入
 * 2. 优先通过无障碍节点查找，坐标点击仅作为兜底
 * 3. 增加节点回收和空检查，防止内存泄漏和崩溃
 * 4. 支持高德车机版多个版本的搜索框特征
 */
class AmapInputHandler : AutoInputHandler() {

    companion object {
        private const val TAG = "AutoInput_Amap"
        const val PACKAGE = "com.autonavi.amapauto"

        @Volatile
        private var pendingDestination: String? = null

        fun setPendingDestination(dest: String) {
            pendingDestination = dest
            currentStep = Step.IDLE
            Log.d(TAG, "设置待输入目的地: $dest")
        }

        fun clearPendingDestination() {
            pendingDestination = null
            currentStep = Step.IDLE
            handler.removeCallbacksAndMessages(null)
        }

        private enum class Step {
            IDLE,
            WAIT_FOR_SEARCH_PAGE,
            INPUT_AND_SEARCH
        }

        @Volatile
        private var currentStep = Step.IDLE

        private val handler = Handler(Looper.getMainLooper())
    }

    override val targetPackage = PACKAGE

    override fun hasPendingTask(): Boolean = pendingDestination != null

    override fun clearPendingTask() = clearPendingDestination()

    override fun handle(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo) {
        val dest = pendingDestination ?: return
        Log.d(TAG, "handle: step=$currentStep, event=${event.eventType}, dest=$dest")

        when (currentStep) {
            Step.IDLE -> {
                // 刚打开高德，需要点击搜索框进入搜索页
                if (clickSearchBox(rootNode)) {
                    currentStep = Step.WAIT_FOR_SEARCH_PAGE
                    // 等待搜索页加载
                    handler.postDelayed({
                        AutoInputService.triggerAfterDelay(500)
                    }, 800)
                } else {
                    Log.w(TAG, "未找到搜索框，尝试兜底坐标点击")
                    fallbackTapSearchBox(rootNode)
                }
            }

            Step.WAIT_FOR_SEARCH_PAGE -> {
                // 搜索页已打开，查找输入框并输入
                val editText = findSearchInput(rootNode)
                if (editText != null) {
                    // 直接设置文本，不需要剪贴板
                    inputText(editText, dest)
                    Log.d(TAG, "已输入目的地: $dest")
                    currentStep = Step.INPUT_AND_SEARCH
                    // 延迟触发搜索
                    handler.postDelayed({
                        triggerSearch(rootNode)
                    }, 500)
                } else {
                    Log.w(TAG, "搜索页未找到输入框")
                    // 重试一次
                    handler.postDelayed({
                        AutoInputService.triggerAfterDelay(500)
                    }, 1000)
                }
            }

            Step.INPUT_AND_SEARCH -> {
                // 输入已完成，触发搜索
                triggerSearch(rootNode)
            }
        }
    }

    // ---------- 查找搜索框（主界面）----------

    private fun clickSearchBox(rootNode: AccessibilityNodeInfo): Boolean {
        // 策略1：通过文本查找
        val byText = findNodeByTexts(rootNode, "搜索", "请输入目的地", "搜索目的地")
        if (byText != null && byText.isClickable) {
            byText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "点击搜索框(文本匹配)")
            return true
        }

        // 策略2：通过 contentDescription
        val byDesc = findNodeByDescriptions(rootNode, "搜索", "search")
        if (byDesc != null && byDesc.isClickable) {
            byDesc.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "点击搜索框(desc匹配)")
            return true
        }

        // 策略3：查找带有搜索图标的按钮（ImageView/ImageButton）
        val searchIcon = findSearchIconButton(rootNode)
        if (searchIcon != null && searchIcon.isClickable) {
            searchIcon.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "点击搜索框(图标匹配)")
            return true
        }

        return false
    }

    // ---------- 查找搜索输入框（搜索页）----------

    private fun findSearchInput(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 策略1：className 为 EditText
        val editText = findEditText(rootNode)
        if (editText != null) {
            // 验证 hint 或 text 是否包含搜索相关关键词
            val hint = editText.hintText?.toString() ?: ""
            val text = editText.text?.toString() ?: ""
            if (hint.contains("搜索") || hint.contains("目的地") ||
                text.contains("搜索") || text.contains("目的地") ||
                hint.contains("输入") || text.isEmpty()
            ) {
                Log.d(TAG, "找到搜索输入框(EditText)")
                return editText
            }
        }

        // 策略2：通过 contentDescription 查找可输入节点
        val byDesc = findNodeByDescriptions(rootNode, "搜索", "请输入", "目的地")
        if (byDesc != null) {
            Log.d(TAG, "找到搜索输入框(desc)")
            return byDesc
        }

        // 策略3：查找页面中唯一的 EditText
        val allEditTexts = mutableListOf<AccessibilityNodeInfo>()
        collectAllEditTexts(rootNode, allEditTexts)
        if (allEditTexts.size == 1) {
            Log.d(TAG, "找到唯一EditText作为搜索框")
            return allEditTexts[0]
        }

        return null
    }

    // ---------- 触发搜索 ----------

    private fun triggerSearch(rootNode: AccessibilityNodeInfo) {
        // 策略1：点击"搜索"按钮
        val searchBtn = findClickableByText(rootNode, "搜索")
            ?: findClickableByContentDesc(rootNode, "搜索", "search")
        if (searchBtn != null) {
            searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "点击搜索按钮")
            clearPendingDestination()
            return
        }

        // 策略2：给输入框追加回车符触发搜索
        val editText = findEditText(rootNode)
        if (editText != null) {
            val currentText = editText.text?.toString() ?: ""
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                currentText + "\n"
            )
            val success = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (success) {
                Log.d(TAG, "追加回车触发搜索")
                clearPendingDestination()
                return
            }
        }

        // 策略3：兜底坐标点击搜索按钮位置（右下角）
        fallbackTapSearchButton(rootNode)
        clearPendingDestination()
    }

    // ---------- 兜底坐标点击 ----------

    private fun fallbackTapSearchBox(rootNode: AccessibilityNodeInfo) {
        val rect = android.graphics.Rect()
        rootNode.getBoundsInScreen(rect)
        val w = rect.width().toFloat()
        val h = rect.height().toFloat()
        // 高德主界面搜索框通常在顶部中央
        AutoInputService.tap(w / 2f, h * 0.08f)
    }

    private fun fallbackTapSearchButton(rootNode: AccessibilityNodeInfo) {
        val rect = android.graphics.Rect()
        rootNode.getBoundsInScreen(rect)
        val w = rect.width().toFloat()
        val h = rect.height().toFloat()
        // 搜索按钮通常在右下角
        AutoInputService.tap(w * 0.9f, h * 0.9f)
    }

    // ---------- 通用查找工具 ----------

    private fun findNodeByTexts(
        node: AccessibilityNodeInfo,
        vararg texts: String
    ): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        for (t in texts) {
            if (nodeText.contains(t)) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTexts(child, *texts)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByDescriptions(
        node: AccessibilityNodeInfo,
        vararg descs: String
    ): AccessibilityNodeInfo? {
        val nodeDesc = node.contentDescription?.toString() ?: ""
        for (d in descs) {
            if (nodeDesc.contains(d, ignoreCase = true)) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByDescriptions(child, *descs)
            if (found != null) return found
        }
        return null
    }

    private fun findSearchIconButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        if ((className.contains("ImageButton") || className.contains("ImageView")) &&
            node.isClickable
        ) {
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.contains("搜索") || desc.contains("search", ignoreCase = true)) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSearchIconButton(child)
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
}