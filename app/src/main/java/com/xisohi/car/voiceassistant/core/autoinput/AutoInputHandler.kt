package com.xisohi.car.voiceassistant.core.autoinput

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍操作处理器基类。
 *
 * 每个第三方 APP 的自动操作逻辑独立实现一个 Handler，
 * 避免不同 APP 的代码混在一起，方便维护和扩展。
 *
 * 新增 APP 支持时：
 * 1. 继承本类，实现 [targetPackage]、[canHandle]、[handle]
 * 2. 在 [AutoInputService] 的 handler 列表中注册
 */
abstract class AutoInputHandler {

    /** 目标 APP 的包名 */
    abstract val targetPackage: String

    /** 当前是否有待处理的任务 */
    abstract fun hasPendingTask(): Boolean

    /** 处理无障碍事件 */
    abstract fun handle(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo)

    /** 清除待处理任务 */
    abstract fun clearPendingTask()

    // ---------- 通用工具方法（子类可直接使用） ----------

    /** 查找 EditText */
    protected fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className == "android.widget.EditText") return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) return found
        }
        return null
    }

    /** 在 EditText 中输入文本 */
    protected fun inputText(editText: AccessibilityNodeInfo, text: String) {
        val arguments = android.os.Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** 按 text 查找可点击元素 */
    protected fun findClickableByText(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        if (node.text?.contains(text) == true && node.isClickable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableByText(child, text)
            if (found != null) return found
        }
        return null
    }

    /** 按 contentDescription 查找可点击元素 */
    protected fun findClickableByContentDesc(
        node: AccessibilityNodeInfo,
        vararg keywords: String
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        if (node.isClickable && keywords.any { desc.contains(it, ignoreCase = true) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableByContentDesc(child, *keywords)
            if (found != null) return found
        }
        return null
    }

    /** 收集所有可点击节点 */
    protected fun collectClickableNodes(
        node: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isClickable) results.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableNodes(child, results)
        }
    }

    /** 打印 UI 树（调试用） */
    protected fun dumpNodeTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        tag: String
    ) {
        if (depth > maxDepth) return
        val indent = "  ".repeat(depth)
        val text = node.text?.toString()?.take(30) ?: ""
        val desc = node.contentDescription?.toString()?.take(30) ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        android.util.Log.d(tag, "$indent[$cls] text='$text' desc='$desc' clickable=${node.isClickable}")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodeTree(child, depth + 1, maxDepth, tag)
        }
    }
}
