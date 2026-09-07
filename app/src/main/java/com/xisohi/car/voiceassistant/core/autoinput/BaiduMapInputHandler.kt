package com.xisohi.car.voiceassistant.core.autoinput

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xisohi.car.voiceassistant.core.AutoInputService

class BaiduMapInputHandler : AutoInputHandler() {

    companion object {
        private const val TAG = "AutoInput_BaiduMap"
        const val PACKAGE = "com.baidu.naviauto"

        private enum class Step {
            IDLE,
            WAIT_FOCUS,
            WAIT_TEXT
        }

        @Volatile
        private var pendingDestination: String? = null

        @Volatile
        private var currentStep = Step.IDLE

        @Volatile
        private var isProcessing = false

        private val handler = Handler(Looper.getMainLooper())

        fun setPendingDestination(dest: String) {
            pendingDestination = dest
            currentStep = Step.IDLE
            isProcessing = false
            Log.d(TAG, "设置待输入目的地: $dest")
        }

        fun clearPendingDestination() {
            pendingDestination = null
            currentStep = Step.IDLE
            isProcessing = false
            handler.removeCallbacksAndMessages(null)
        }
    }

    override val targetPackage = PACKAGE

    override fun hasPendingTask(): Boolean = pendingDestination != null

    override fun clearPendingTask() = clearPendingDestination()

    override fun handle(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo) {
        val dest = pendingDestination ?: return
        if (isProcessing) {
            Log.d(TAG, "正在处理中，跳过事件")
            return
        }
        Log.d(TAG, "处理阶段: $currentStep, 事件: ${event.eventType}")

        try {
            when (currentStep) {
                Step.IDLE -> handleIdle(rootNode)
                Step.WAIT_FOCUS -> handleWaitFocus(rootNode)
                Step.WAIT_TEXT -> handleWaitText(rootNode)
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动输入出错: ${e.message}")
            isProcessing = false
        }
    }

    // ---------- 阶段1: 查找并聚焦搜索框 ----------
    private fun handleIdle(rootNode: AccessibilityNodeInfo) {
        val inputNode = findInputNode(rootNode)
        if (inputNode == null) {
            Log.d(TAG, "未找到输入框，等待界面加载")
            return
        }

        Log.d(TAG, "找到输入框，尝试聚焦")
        isProcessing = true
        // 点击输入框获取焦点
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // 手势点击兜底
        val rect = Rect()
        inputNode.getBoundsInScreen(rect)
        AutoInputService.tap(rect.centerX().toFloat(), rect.centerY().toFloat())

        currentStep = Step.WAIT_FOCUS
        // 延迟等待焦点稳定
        handler.postDelayed({
            isProcessing = false
            // 触发下一次检查
            AutoInputService.triggerAfterDelay(100)
        }, 500)
    }

    // ---------- 阶段2: 输入文本 ----------
    private fun handleWaitFocus(rootNode: AccessibilityNodeInfo) {
        val inputNode = findInputNode(rootNode)
        if (inputNode == null || !inputNode.isFocused) {
            Log.w(TAG, "输入框未获得焦点，重试聚焦")
            currentStep = Step.IDLE
            isProcessing = false
            AutoInputService.triggerAfterDelay(300)
            return
        }

        val dest = pendingDestination ?: return
        Log.d(TAG, "输入框已聚焦，开始设置文本: $dest")

        // 清空已有文本
        val clearArgs = Bundle()
        clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

        // 设置目的地
        val setArgs = Bundle()
        setArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, dest)
        val success = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)

        if (!success) {
            Log.w(TAG, "ACTION_SET_TEXT 失败")
        }

        currentStep = Step.WAIT_TEXT
        isProcessing = true
        // 延迟验证文本是否设置成功
        handler.postDelayed({
            isProcessing = false
            AutoInputService.triggerAfterDelay(100)
        }, 600)
    }

    // ---------- 阶段3: 验证输入并触发搜索 ----------
    private fun handleWaitText(rootNode: AccessibilityNodeInfo) {
        val dest = pendingDestination ?: return
        val inputNode = findInputNode(rootNode)
        val currentText = inputNode?.text?.toString() ?: ""

        if (currentText != dest) {
            Log.w(TAG, "文本设置验证失败: 期望 '$dest', 实际 '$currentText'")
            // 重试设置
            currentStep = Step.WAIT_FOCUS
            isProcessing = false
            AutoInputService.triggerAfterDelay(300)
            return
        }

        Log.d(TAG, "文本设置成功，点击搜索按钮")
        // 触发搜索
        if (!triggerSearch(rootNode)) {
            // 如果点击失败，尝试按回车键
            inputNode?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, currentText + "\n")
            inputNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "已发送回车键")
        }

        // 搜索触发后，清空 pending（认为流程成功）
        clearPendingDestination()
        currentStep = Step.IDLE
        isProcessing = false
    }

    // ---------- 辅助查找方法 ----------
    private fun findInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 策略1：查找 EditText 或可编辑 TextView，且 hint/desc/text 包含关键词
        val keywords = listOf("搜索", "目的地", "输入", "地点", "地址", "search")
        val result = findNodeByCondition(node) { n ->
            val className = n.className?.toString() ?: ""
            val isEditable = className.contains("EditText") ||
                    (className.contains("TextView") && n.isEditable)
            if (!isEditable) return@findNodeByCondition false
            val hint = n.hintText?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val text = n.text?.toString() ?: ""
            keywords.any { hint.contains(it, ignoreCase = true) ||
                    desc.contains(it, ignoreCase = true) ||
                    text.contains(it, ignoreCase = true) }
        }
        if (result != null) return result

        // 策略2：收集所有可输入节点，如果只有一个，直接使用
        val inputNodes = mutableListOf<AccessibilityNodeInfo>()
        collectInputNodes(node, inputNodes)
        if (inputNodes.size == 1) {
            return inputNodes[0]
        }

        // 策略3：查找包含“搜索”文本的按钮的父容器，再在其中找输入框
        val searchBtn = findNodeByText(node, "搜索")
        searchBtn?.let { btn ->
            val parent = btn.parent
            if (parent != null) {
                val found = findInputNode(parent)
                if (found != null) return found
            }
        }

        return null
    }

    private fun collectInputNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectInputNodes(child, list)
        }
    }

    private fun findNodeByCondition(node: AccessibilityNodeInfo, condition: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (condition(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByCondition(child, condition)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        if (nodeText == text) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            if (found != null) return found
        }
        return null
    }

    // ---------- 触发搜索 ----------
    private fun triggerSearch(rootNode: AccessibilityNodeInfo): Boolean {
        // 精确查找“搜索”按钮（文本或描述匹配）
        val searchBtn = findExactSearchButton(rootNode)
        if (searchBtn != null) {
            Log.d(TAG, "点击搜索按钮（ACTION_CLICK）")
            searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // 手势点击兜底
            val rect = Rect()
            searchBtn.getBoundsInScreen(rect)
            AutoInputService.tap(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }

        // 更宽松的查找
        val anySearch = findNodeByText(rootNode, "搜索")
        if (anySearch != null && anySearch.isClickable) {
            anySearch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        return false
    }

    private fun findExactSearchButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        if (node.isClickable && (text == "搜索" || desc == "搜索")) {
            // 排除搜索框本身（通常不可点击或内容为搜索框）
            if (text != "搜索框" && desc != "搜索框") {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findExactSearchButton(child)
            if (found != null) return found
        }
        return null
    }
}