package com.xisohi.car.voiceassistant.core.autoinput

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xisohi.car.voiceassistant.core.AutoInputService

/** 歌曲信息 */
data class SongInfo(val title: String, val artist: String, val index: Int)

/**
 * MusicFree（fun.upup.musicfree）自动搜索播放处理器。
 *
 * 功能：说"播放XX"时，自动在 MusicFree 中搜索，列出结果让用户选择，然后播放指定序号。
 *
 * 工作流程：
 * 1. 外部调用 [setPendingMusicSearch] 设置待搜索的关键词
 * 2. 无障碍服务检测到 MusicFree 窗口后，自动找到搜索入口并点击
 * 3. 找到搜索框，点击获取焦点，等待稳定
 * 4. 用 ACTION_SET_TEXT 设置关键词，验证是否成功
 * 5. 点击搜索按钮，等待搜索结果
 * 6. 外部调用 [getSearchResults] 读取结果列表，播报给用户
 * 7. 用户选择后，外部调用 [clickSearchResult] 播放指定序号
 *
 * 技术说明：
 * - MusicFree 是 React Native 应用，搜索框有 contentDescription="搜索框"
 * - 必须先点击获取焦点，等待 UI 稳定后再设置文本，否则 React Native 不响应
 * - 设置文本后需要验证（检查搜索框 text 是否变化）
 * - 加 isProcessing 标志防止 onAccessibilityEvent 频繁触发导致重复执行
 *
 * 注意事项：
 * - 修改本类时不要影响 [AmapInputHandler]
 */
class MusicFreeInputHandler : AutoInputHandler() {

    companion object {
        private const val TAG = "AutoInput_MusicFree"
        const val PACKAGE = "fun.upup.musicfree"

        /** 自动操作的阶段 */
        private enum class Step {
            IDLE,               // 空闲
            NEED_OPEN_SEARCH,   // 需要打开搜索页
            NEED_FOCUS_INPUT,   // 需要聚焦输入框
            NEED_SET_TEXT,      // 需要设置文本
            NEED_CLICK_SEARCH,  // 需要点击搜索按钮
            NEED_CLICK_RESULT   // 需要点击第一个搜索结果
        }

        @Volatile
        private var pendingMusicSearch: String? = null

        @Volatile
        private var currentStep = Step.IDLE

        /** 防止 onAccessibilityEvent 频繁触发导致重复执行 */
        @Volatile
        private var isProcessing = false

        /** 设置待搜索播放的歌曲关键词（语音助手识别到"播放XX"时调用） */
        fun setPendingMusicSearch(query: String) {
            pendingMusicSearch = query
            currentStep = Step.NEED_OPEN_SEARCH
            isProcessing = false
            Log.d(TAG, "设置待搜索歌曲: $query")
        }

        fun clearPendingMusicSearch() {
            pendingMusicSearch = null
            currentStep = Step.IDLE
            isProcessing = false
        }

        /** 搜索是否已完成（结果列表已显示） */
        fun isSearchCompleted(): Boolean = currentStep == Step.NEED_CLICK_RESULT

        /**
         * 读取当前搜索结果列表。
         * 从 MusicFree 的搜索结果 UI 中提取歌曲名和歌手名。
         * @return 搜索结果列表，最多返回 10 首
         */
        fun getSearchResults(): List<SongInfo> {
            val rootNode = AutoInputService.currentRootNode ?: return emptyList()
            val results = mutableListOf<SongInfo>()
            try {
                collectSearchResults(rootNode, results, 10)
                Log.d(TAG, "读取到 ${results.size} 首搜索结果")
                results.forEachIndexed { index, song ->
                    Log.d(TAG, "  ${index + 1}. ${song.title} - ${song.artist}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取搜索结果失败: ${e.message}")
            }
            return results
        }

        /**
         * 点击指定序号的搜索结果。
         * @param index 序号（从 1 开始）
         * @return 是否成功点击
         */
        fun clickSearchResult(index: Int): Boolean {
            val rootNode = AutoInputService.currentRootNode ?: return false
            return try {
                val target = findSearchResultByIndex(rootNode, index)
                if (target != null) {
                    Log.d(TAG, "点击第 $index 首搜索结果")
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    pendingMusicSearch = null
                    currentStep = Step.IDLE
                    isProcessing = false
                    true
                } else {
                    Log.w(TAG, "未找到第 $index 首搜索结果")
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "点击搜索结果失败: ${e.message}")
                false
            }
        }

        /** 递归收集搜索结果 */
        private fun collectSearchResults(node: AccessibilityNodeInfo, results: MutableList<SongInfo>, max: Int) {
            if (results.size >= max) return
            // 搜索结果通常是可点击的 ViewGroup，包含歌曲名和歌手名
            if (node.isClickable && node.childCount > 0) {
                val desc = node.contentDescription?.toString() ?: ""
                val text = node.text?.toString() ?: ""
                // 跳过搜索框、搜索按钮、历史记录等
                if (desc.contains("搜索") || text.contains("搜索") ||
                    text == "历史记录" || text == "清空" ||
                    node.className == "android.widget.EditText") {
                    // 继续遍历子节点
                } else if (desc.isNotEmpty() || hasSongText(node)) {
                    val song = parseSongInfo(node, results.size + 1)
                    if (song != null && results.none { it.title == song.title && it.artist == song.artist }) {
                        results.add(song)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectSearchResults(child, results, max)
            }
        }

        /** 节点是否包含歌曲文本（TextView 有非空文本） */
        private fun hasSongText(node: AccessibilityNodeInfo): Boolean {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.className == "android.widget.TextView" && !child.text.isNullOrEmpty()) {
                    return true
                }
            }
            return false
        }

        /** 从节点解析歌曲信息 */
        private fun parseSongInfo(node: AccessibilityNodeInfo, index: Int): SongInfo? {
            val desc = node.contentDescription?.toString() ?: ""
            // 尝试从 contentDescription 解析，格式可能是 "歌曲: XXX 歌手: YYY"
            if (desc.contains("歌曲") || desc.contains("歌手")) {
                val title = Regex("歌曲[:：]\\s*(.+?)(?=\\s*歌手|$)").find(desc)?.groupValues?.get(1)?.trim() ?: ""
                val artist = Regex("歌手[:：]\\s*(.+)").find(desc)?.groupValues?.get(1)?.trim() ?: ""
                if (title.isNotEmpty()) {
                    return SongInfo(title, artist, index)
                }
            }
            // 从子 TextView 提取，第一个是歌曲名，第二个是歌手名
            val texts = mutableListOf<String>()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.className == "android.widget.TextView" && !child.text.isNullOrEmpty()) {
                    texts.add(child.text.toString())
                }
            }
            if (texts.isNotEmpty()) {
                val title = texts[0]
                val artist = if (texts.size > 1) texts[1] else ""
                return SongInfo(title, artist, index)
            }
            return null
        }

        /** 按序号查找搜索结果节点 */
        private fun findSearchResultByIndex(node: AccessibilityNodeInfo, targetIndex: Int): AccessibilityNodeInfo? {
            val results = mutableListOf<AccessibilityNodeInfo>()
            collectClickableSongNodes(node, results, targetIndex)
            return if (results.size >= targetIndex) results[targetIndex - 1] else null
        }

        /** 收集可点击的歌曲节点 */
        private fun collectClickableSongNodes(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>, max: Int) {
            if (results.size >= max) return
            if (node.isClickable && node.childCount > 0) {
                val desc = node.contentDescription?.toString() ?: ""
                val text = node.text?.toString() ?: ""
                if (!desc.contains("搜索") && !text.contains("搜索") &&
                    text != "历史记录" && text != "清空" &&
                    node.className != "android.widget.EditText" &&
                    (desc.isNotEmpty() || hasSongText(node))) {
                    results.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectClickableSongNodes(child, results, max)
            }
        }
    }

    override val targetPackage = PACKAGE
    private val handler = Handler(Looper.getMainLooper())

    override fun hasPendingTask(): Boolean = pendingMusicSearch != null

    override fun clearPendingTask() {
        pendingMusicSearch = null
        currentStep = Step.IDLE
        isProcessing = false
    }

    override fun handle(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo) {
        val query = pendingMusicSearch ?: return
        if (isProcessing) {
            Log.d(TAG, "正在处理中，跳过事件: ${event.eventType}")
            return
        }
        try {
            Log.d(TAG, "阶段: $currentStep, 事件: ${event.eventType}")
            when (currentStep) {
                Step.NEED_OPEN_SEARCH -> handleOpenSearch(rootNode)
                Step.NEED_FOCUS_INPUT -> handleFocusInput(rootNode)
                Step.NEED_SET_TEXT -> handleSetText(rootNode)
                Step.NEED_CLICK_SEARCH -> handleClickSearch(rootNode)
                // NEED_CLICK_RESULT 阶段不自动操作，等待外部调用 getSearchResults() 和 clickSearchResult()
                Step.NEED_CLICK_RESULT -> {}
                Step.IDLE -> {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动操作失败: ${e.message}")
            isProcessing = false
        }
    }

    // ---------- 阶段1：打开搜索页 ----------

    private fun handleOpenSearch(rootNode: AccessibilityNodeInfo) {
        val searchEntry = findSearchEntry(rootNode)
        if (searchEntry != null) {
            Log.d(TAG, "找到搜索入口，点击")
            isProcessing = true
            searchEntry.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.NEED_FOCUS_INPUT
            // 等待搜索页打开
            handler.postDelayed({
                isProcessing = false
                handleFocusInputDelayed()
            }, 1000)
        } else {
            Log.d(TAG, "未找到搜索入口，等待...")
        }
    }

    // ---------- 阶段2：聚焦输入框 ----------

    private fun handleFocusInput(rootNode: AccessibilityNodeInfo) {
        val editText = findEditTextByDesc(rootNode, "搜索框")
        if (editText != null) {
            val rect = Rect()
            editText.getBoundsInScreen(rect)
            val centerX = (rect.left + rect.right) / 2f
            val centerY = (rect.top + rect.bottom) / 2f
            Log.d(TAG, "找到搜索框，点击获取焦点: ($centerX, $centerY), 当前text='${editText.text}'")
            isProcessing = true
            // 点击搜索框获取焦点
            AutoInputService.tap(centerX, centerY)
            currentStep = Step.NEED_SET_TEXT
            // 等待焦点稳定
            handler.postDelayed({
                isProcessing = false
                handleSetTextDelayed()
            }, 800)
        } else {
            Log.d(TAG, "未找到搜索框，等待...")
            dumpNodeTree(rootNode, 0, 2, TAG)
        }
    }

    private fun handleFocusInputDelayed() {
        val rootNode = AutoInputService.currentRootNode ?: return
        handleFocusInput(rootNode)
    }

    // ---------- 阶段3：设置文本 ----------

    private fun handleSetText(rootNode: AccessibilityNodeInfo) {
        val query = pendingMusicSearch ?: return
        val editText = findEditTextByDesc(rootNode, "搜索框")
        if (editText != null) {
            Log.d(TAG, "设置文本前: text='${editText.text}', isFocused=${editText.isFocused}")
            isProcessing = true
            // 先清空
            val clearArgs = android.os.Bundle()
            clearArgs.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                ""
            )
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            // 设置关键词
            val setArgs = android.os.Bundle()
            setArgs.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                query
            )
            val result = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
            Log.d(TAG, "ACTION_SET_TEXT 返回: $result")
            currentStep = Step.NEED_CLICK_SEARCH
            // 等待文本设置完成，然后验证
            handler.postDelayed({
                try {
                    val currentRoot = AutoInputService.currentRootNode
                    val currentEditText = currentRoot?.let { findEditTextByDesc(it, "搜索框") }
                    Log.d(TAG, "设置文本后: text='${currentEditText?.text}'")
                    isProcessing = false
                    handleClickSearchDelayed()
                } catch (e: Exception) {
                    Log.w(TAG, "验证文本失败: ${e.message}")
                    isProcessing = false
                }
            }, 800)
        } else {
            Log.d(TAG, "未找到搜索框，等待...")
        }
    }

    private fun handleSetTextDelayed() {
        val rootNode = AutoInputService.currentRootNode ?: return
        handleSetText(rootNode)
    }

    // ---------- 阶段4：点击搜索按钮 ----------

    private fun handleClickSearch(rootNode: AccessibilityNodeInfo) {
        // 查找"搜索"按钮（ViewGroup with desc='搜索'）
        val searchButton = findClickableByDesc(rootNode, "搜索")
        if (searchButton != null) {
            Log.d(TAG, "找到搜索按钮，点击")
            isProcessing = true
            searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.NEED_CLICK_RESULT
            // 搜索完成，等待外部调用 getSearchResults() 和 clickSearchResult()
            handler.postDelayed({
                isProcessing = false
                Log.d(TAG, "搜索完成，等待用户选择")
            }, 2000)
        } else {
            Log.d(TAG, "未找到搜索按钮，尝试回车搜索")
            val editText = findEditTextByDesc(rootNode, "搜索框")
            if (editText != null) {
                isProcessing = true
                inputText(editText, "\n")
                currentStep = Step.NEED_CLICK_RESULT
                handler.postDelayed({
                    isProcessing = false
                    Log.d(TAG, "搜索完成，等待用户选择")
                }, 2000)
            }
        }
    }

    private fun handleClickSearchDelayed() {
        val rootNode = AutoInputService.currentRootNode ?: return
        handleClickSearch(rootNode)
    }

    // ---------- 阶段5：点击第一个搜索结果 ----------

    private fun handleClickResult(rootNode: AccessibilityNodeInfo) {
        val firstResult = findFirstSearchResult(rootNode)
        if (firstResult != null) {
            Log.d(TAG, "找到第一个搜索结果，点击播放")
            firstResult.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            pendingMusicSearch = null
            currentStep = Step.IDLE
            isProcessing = false
        } else {
            Log.d(TAG, "未找到搜索结果，等待...")
        }
    }

    private fun handleClickResultDelayed() {
        val rootNode = AutoInputService.currentRootNode ?: return
        handleClickResult(rootNode)
    }

    // ---------- 辅助方法 ----------

    /** 查找搜索入口（搜索图标或搜索按钮） */
    private fun findSearchEntry(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 先找 contentDescription 包含"搜索"的可点击元素
        val byDesc = findClickableByContentDesc(node, "搜索", "search", "Search")
        if (byDesc != null) return byDesc
        // 再找 text 包含"搜索"的
        val byText = findClickableByText(node, "搜索")
        if (byText != null) return byText
        return null
    }

    /** 按 contentDescription 查找 EditText */
    private fun findEditTextByDesc(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val nodeDesc = node.contentDescription?.toString() ?: ""
        if (node.className == "android.widget.EditText" && nodeDesc.contains(desc)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditTextByDesc(child, desc)
            if (found != null) return found
        }
        return null
    }

    /** 按 contentDescription 查找可点击元素 */
    private fun findClickableByDesc(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val nodeDesc = node.contentDescription?.toString() ?: ""
        if (node.isClickable && nodeDesc.contains(desc)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableByDesc(child, desc)
            if (found != null) return found
        }
        return null
    }

    /**
     * 查找第一个搜索结果。
     * 排除搜索框、搜索按钮、历史记录标题等 UI 元素，
     * 只找包含歌曲信息的可点击元素。
     */
    private fun findFirstSearchResult(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val results = mutableListOf<AccessibilityNodeInfo>()
        collectClickableNodes(node, results)
        for (result in results) {
            val text = result.text?.toString() ?: ""
            val desc = result.contentDescription?.toString() ?: ""
            // 跳过搜索相关元素
            if (text.contains("搜索") || desc.contains("搜索")) continue
            if (result.className == "android.widget.EditText") continue
            // 跳过"历史记录"、"清空"等标题
            if (text == "历史记录" || text == "清空") continue
            // 找到第一个有子元素（歌曲信息通常包含标题、歌手等）的可点击元素
            if (result.childCount > 0) {
                return result
            }
        }
        // 如果没找到有子元素的，返回第一个有文本的可点击元素
        for (result in results) {
            val text = result.text?.toString() ?: ""
            if (text.isNotEmpty() && text != "搜索" && text != "历史记录" && text != "清空") {
                return result
            }
        }
        return null
    }
}
