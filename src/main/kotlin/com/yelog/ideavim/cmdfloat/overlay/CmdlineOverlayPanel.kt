package com.yelog.ideavim.cmdfloat.overlay

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.*
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ActionEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.min

class CmdlineOverlayPanel(
    private val mode: OverlayMode,
    history: CommandHistory,
    private val editor: Editor,
    private val searchCompletions: List<SearchCompletionWord> = emptyList(),
) {

    val component: JComponent
    val focusComponent: JBTextField
    val preferredSize: Dimension
        get() = component.preferredSize

    var onSubmit: ((String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onSearchPreview: ((String, Int) -> Unit)? = null
    var onSearchPreviewCancel: ((Int) -> Unit)? = null
    var onCommandPatternPreview: ((String, Int) -> Unit)? = null
    var onCommandPatternCancel: ((Int) -> Unit)? = null

    private val historySnapshot = history.snapshot()
    private var historyIndex = -1
    private var draftText: String = ""
    private var programmaticUpdate = false
    private var completionSupport: CompletionSupport? = null
    private var preferredWidth = JBUI.scale(400)
    private val basePreferredHeight: Int
    private var searchCommitted = false
    private var completionsHeight: Int? = null
    private var searchCancelled = false
    private var searchInitialCaretOffset: Int = -1
    private var commandPreviewActive = false
    private val searchResultLabel: JBLabel?

    init {
        val scheme = EditorColorsManager.getInstance().globalScheme
        focusComponent = createTextField(scheme)
        searchResultLabel = if (isSearchMode()) createSearchResultLabel() else null
        completionSupport = when (mode) {
            OverlayMode.COMMAND -> CommandCompletionSupport(focusComponent, searchCompletions)
            OverlayMode.SEARCH_FORWARD, OverlayMode.SEARCH_BACKWARD -> SearchCompletionSupport(
                focusComponent,
                searchCompletions,
            )
        }

        val inputPanel = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 2, 0)
            add(createPrefixLabel(), java.awt.BorderLayout.WEST)
            add(
                JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
                    isOpaque = false
                    add(focusComponent, java.awt.BorderLayout.CENTER)
                    searchResultLabel?.let { add(it, java.awt.BorderLayout.EAST) }
                },
                java.awt.BorderLayout.CENTER,
            )
        }

        val contentPanel = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
            isOpaque = false
            putClientProperty("JComponent.roundRect", java.lang.Boolean.TRUE)

            // 所有模式统一去除标题与边框，避免双重边框视觉重复
            border = JBUI.Borders.empty(2, 0, 2, 0)

            add(inputPanel, java.awt.BorderLayout.NORTH)
            completionSupport?.install(this)
        }

        component = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4)
            add(contentPanel, java.awt.BorderLayout.CENTER)
        }

        val computedHeight = component.preferredSize.height
        basePreferredHeight = if (computedHeight > 0) {
            computedHeight
        } else {
            JBUI.scale(48)
        }
        preferredWidth = JBUI.scale(400)
        applyPreferredSize()

        installActions(focusComponent)
        if (isSearchMode()) {
            updateSearchResultIndicator(focusComponent.text)
        }

        if (mode == OverlayMode.COMMAND && historySnapshot.isNotEmpty() && historySnapshot.firstOrNull()
                ?.startsWith("<,'>") == true
        ) {
            // no-op
        }
    }

    fun requestFocus() {
        val hasPrefix = applyVisualSelectionPrefixIfNeeded()
        if (!hasPrefix) {
            focusComponent.selectAll()
        } else {
            focusComponent.caretPosition = focusComponent.text.length
        }
        focusComponent.requestFocusInWindow()
    }

    private fun applyVisualSelectionPrefixIfNeeded(): Boolean {
        if (mode != OverlayMode.COMMAND) {
            return false
        }
        if (!IdeaVimFacade.hasVisualSelection(editor)) {
            return false
        }
        val current = focusComponent.text
        if (current.isNotEmpty()) {
            return current.startsWith("'<,'>")
        }
        focusComponent.text = "'<,'>"
        return true
    }

    fun setPreferredWidth(width: Int) {
        preferredWidth = width
        applyPreferredSize()
        completionSupport?.updatePopupWidth(width)
    }

    private fun applyPreferredSize() {
        val totalHeight = basePreferredHeight + (completionsHeight ?: 0)
        component.preferredSize = Dimension(preferredWidth, totalHeight)
        component.revalidate()
        component.repaint()
        SwingUtilities.getWindowAncestor(component)?.let { win ->
            win.pack()
            if (win.width != preferredWidth) {
                win.setSize(preferredWidth, win.height)
            }
        }
    }

    private fun adjustCompletionsHeight(completionsHeight: Int?) {
        this.completionsHeight = completionsHeight
        applyPreferredSize()
    }

    // 已通过 applyPreferredSize 调整并 pack popup，无需单独 resize 方法

    private fun handleDocumentChange(textField: JBTextField) {
        if (programmaticUpdate) {
            return
        }
        historyIndex = -1
        completionSupport?.onUserInput(textField.text)
        if (isSearchMode()) {
            updateSearchResultIndicator(textField.text)
        }
        triggerSearchPreview(textField.text)
        if (mode == OverlayMode.COMMAND) {
            updateCommandPatternPreview(textField.text)
        }
    }

    private fun createTextField(scheme: EditorColorsScheme): JBTextField {
        val inputHeight = JBUI.scale(28)
        return JBTextField().apply {
            border = JBUI.Borders.empty()
            foreground = scheme.defaultForeground ?: JBColor.foreground()
            caretColor = foreground
            font = JBFont.regular().deriveFont(Font.PLAIN, scaledFontSize(14f))
            preferredSize = Dimension(JBUI.scale(200), inputHeight)
            minimumSize = Dimension(JBUI.scale(200), inputHeight)
            margin = JBUI.emptyInsets()
            putClientProperty("JComponent.roundRect", java.lang.Boolean.TRUE)
            isOpaque = false
            focusTraversalKeysEnabled = false
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = handleDocumentChange(this@apply)
                override fun removeUpdate(event: DocumentEvent) = handleDocumentChange(this@apply)
                override fun changedUpdate(event: DocumentEvent) = handleDocumentChange(this@apply)
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun scaledFontSize(value: Float): Float {
        val scaled = runCatching {
            val clazz = Class.forName("com.intellij.util.ui.JBUIScale")
            val method = clazz.getMethod("scale", java.lang.Float.TYPE)
            (method.invoke(null, value) as? Number)?.toFloat()
        }.getOrNull()
        return scaled ?: JBUI.scale(value.toInt()).toFloat()
    }

    private fun createSearchResultLabel(): JBLabel {
        val inputHeight = JBUI.scale(28)
        return JBLabel(NO_RESULTS_TEXT, SwingConstants.RIGHT).apply {
            border = JBUI.Borders.empty(0, 8, 0, 4)
            isOpaque = false
            foreground = SEARCH_RESULT_NEUTRAL_COLOR
            font = JBFont.label().deriveFont(Font.BOLD, scaledFontSize(12f))
            preferredSize = Dimension(JBUI.scale(80), inputHeight)
            minimumSize = Dimension(JBUI.scale(60), inputHeight)
            maximumSize = Dimension(JBUI.scale(120), inputHeight)
        }
    }

    private fun createPrefixLabel(): JBLabel {
        val isSearchMode = mode == OverlayMode.SEARCH_FORWARD || mode == OverlayMode.SEARCH_BACKWARD
        val inputHeight = JBUI.scale(28)
        val label = JBLabel().apply {
            // 搜索模式：左右都加内边距，防止组合图标（放大镜+箭头）被裁剪
            // 之前右侧无内边距且总宽度仅 34，会因为 border 占用导致箭头显示不全
            val left = if (isSearchMode) 4 else 6
            val right = if (isSearchMode) 0 else 0
            border = JBUI.Borders.empty(0, left, 0, right)
            foreground = focusComponent.foreground
            font = JBFont.label().deriveFont(Font.BOLD, scaledFontSize(16f))
            // 动态计算搜索模式前缀宽度：放大镜实际宽度 + gap(=1 scaled) + 箭头宽度 + 左右内边距
            // 避免额外的空白把文本框光标推远
            if (isSearchMode) {
                val searchIconWidth = AllIcons.Actions.Search.iconWidth
                val gap = JBUI.scale(1)
                val arrowWidth = JBUI.scale(8) // 与 createSearchDirectionIcon 中保持一致
                val labelWidth = searchIconWidth + gap + arrowWidth + left + right
                preferredSize = Dimension(JBUI.scale(labelWidth), inputHeight)
            } else {
                preferredSize = Dimension(JBUI.scale(24), inputHeight)
            }
            isOpaque = false
        }
        if (isSearchMode) {
            label.icon = when (mode) {
                OverlayMode.SEARCH_FORWARD -> createSearchDirectionIcon(backward = false)
                OverlayMode.SEARCH_BACKWARD -> createSearchDirectionIcon(backward = true)
                else -> AllIcons.Actions.Search
            }
        } else {
            // 调整命令模式前缀符号的位置：进一步右移以与搜索图标视觉对齐
            val promptChar = '\uF054'
            label.text = if (label.font.canDisplay(promptChar)) promptChar.toString() else "❯"
            label.toolTipText = "命令输入"
            // 增加左侧缩进并适度增宽以居中该窄字符
            label.border = JBUI.Borders.empty(0, 14, 0, 0)
            label.preferredSize = Dimension(JBUI.scale(26), inputHeight)
        }
        return label
    }

    /**
     * 创建搜索方向图标：放大镜 + 上/下小箭头（区分 / 与 ?）
     * backward = false 表示正向搜索 (/)，显示向下箭头；backward = true 表示反向搜索 (?)，显示向上箭头。
     */
    private fun createSearchDirectionIcon(backward: Boolean): Icon {
        val search = AllIcons.Actions.Search
        // 自绘带“竖线”的箭头，以区分方向（/ 下箭头，? 上箭头）
        val gap = JBUI.scale(1)
        // 采用更窄的箭头尺寸（细线条，缩小与输入框的间距）
        val arrowWidth = JBUI.scale(8)
        val arrowHeight = JBUI.scale(9)
        val width = search.iconWidth + gap + arrowWidth
        val height = maxOf(search.iconHeight, arrowHeight)

        return object : Icon {
            override fun getIconWidth(): Int = width
            override fun getIconHeight(): Int = height
            override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
                val g2 = g as? Graphics2D ?: return
                // 抗锯齿
                g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                )
                // 绘制放大镜图标
                val searchY = y + (height - search.iconHeight) / 2
                search.paintIcon(c, g2, x, searchY)

                // 箭头区域起点
                val arrowX = x + search.iconWidth + gap
                val arrowCenterY = y + height / 2
                val arrowTop = arrowCenterY - arrowHeight / 2
                val arrowBottom = arrowTop + arrowHeight
                // 竖线改为更细 (1px)
                val shaftWidth = JBUI.scale(1)
                val shaftX = arrowX + (arrowWidth - shaftWidth) / 2
                val shaftTop: Int
                val shaftBottom: Int

                g2.color = UIUtil.getLabelForeground()

                if (backward) {
                    // 上箭头：三角在上，竖线在下（细线条版本）
                    val triangleHeight = JBUI.scale(4)
                    val triangleTop = arrowTop
                    val triangleBottom = triangleTop + triangleHeight
                    val midX = arrowX + arrowWidth / 2

                    // 三角
                    val poly = java.awt.Polygon()
                    poly.addPoint(midX, triangleTop)
                    poly.addPoint(arrowX, triangleBottom)
                    poly.addPoint(arrowX + arrowWidth - 1, triangleBottom)
                    g2.fillPolygon(poly)

                    // 竖线
                    shaftTop = triangleBottom
                    shaftBottom = arrowBottom
                    g2.fillRect(shaftX, shaftTop, shaftWidth, shaftBottom - shaftTop)
                } else {
                    // 下箭头：竖线在上，三角在下（细线条版本）
                    val triangleHeight = JBUI.scale(4)
                    val triangleBottom = arrowBottom
                    val triangleTop = triangleBottom - triangleHeight
                    val midX = arrowX + arrowWidth / 2

                    // 竖线
                    shaftTop = arrowTop
                    shaftBottom = triangleTop
                    g2.fillRect(shaftX, shaftTop, shaftWidth, shaftBottom - shaftTop)

                    // 三角
                    val poly = java.awt.Polygon()
                    poly.addPoint(midX, triangleBottom)
                    poly.addPoint(arrowX, triangleTop)
                    poly.addPoint(arrowX + arrowWidth - 1, triangleTop)
                    g2.fillPolygon(poly)
                }
            }
        }
    }

    private fun installActions(textField: JBTextField) {
        textField.addActionListener {
            completionSupport?.acceptSelection()
            onSubmit?.invoke(textField.text)
            markSearchCommitted()
            completionSupport?.dispose()
        }

        val inputMap: InputMap = textField.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap: ActionMap = textField.actionMap

        inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), ACTION_CANCEL)
        actionMap.put(ACTION_CANCEL, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                completionSupport?.dispose()
                cancelSearchPreview()
                cancelCommandPreview()
                onCancel?.invoke()
            }
        })

        actionMap.put(ACTION_HISTORY_PREVIOUS, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (completionSupport?.moveSelection(previous = true) != true) {
                    navigateHistory(previous = true, textField)
                }
            }
        })

        actionMap.put(ACTION_HISTORY_NEXT, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (completionSupport?.moveSelection(previous = false) != true) {
                    navigateHistory(previous = false, textField)
                }
            }
        })

        val navigationBindings = CmdlineOverlayKeymap.completionNavigationBindings()
        navigationBindings.previous.forEach { stroke ->
            inputMap.put(stroke, ACTION_HISTORY_PREVIOUS)
        }
        navigationBindings.next.forEach { stroke ->
            inputMap.put(stroke, ACTION_HISTORY_NEXT)
        }

        val completionPreviousAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                completionSupport?.moveSelection(previous = true)
            }
        }
        val completionNextAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                completionSupport?.moveSelection(previous = false)
            }
        }

        inputMap.put(KeyStroke.getKeyStroke("shift TAB"), ACTION_COMPLETION_PREVIOUS)
        actionMap.put(ACTION_COMPLETION_PREVIOUS, completionPreviousAction)
        inputMap.put(KeyStroke.getKeyStroke("TAB"), ACTION_COMPLETION_NEXT)
        actionMap.put(ACTION_COMPLETION_NEXT, completionNextAction)
    }

    private fun navigateHistory(previous: Boolean, textField: JBTextField) {
        if (historySnapshot.isEmpty()) {
            return
        }

        if (previous) {
            if (historyIndex + 1 >= historySnapshot.size) {
                return
            }
            if (historyIndex == -1) {
                draftText = textField.text
                historyIndex = 0
            } else {
                historyIndex += 1
            }
            applyHistoryValue(historySnapshot[historyIndex], textField)
        } else {
            if (historyIndex == -1) {
                return
            }
            if (historyIndex == 0) {
                historyIndex = -1
                applyHistoryValue(draftText, textField)
            } else {
                historyIndex -= 1
                applyHistoryValue(historySnapshot[historyIndex], textField)
            }
        }
    }

    private fun applyHistoryValue(value: String, textField: JBTextField) {
        setTextProgrammatically(textField, value)
        completionSupport?.dispose()
        triggerSearchPreview(value)
        if (mode == OverlayMode.COMMAND) {
            cancelCommandPreview()
        }
    }

    private fun updateSearchResultIndicator(query: String) {
        val label = searchResultLabel ?: return
        if (query.isEmpty()) {
            label.foreground = SEARCH_RESULT_NEUTRAL_COLOR
            label.text = NO_RESULTS_TEXT
            return
        }
        val stats = computeSearchResultStats(query)
        if (stats == null || stats.total == 0) {
            label.foreground = SEARCH_RESULT_EMPTY_COLOR
            label.text = NO_RESULTS_TEXT
        } else {
            label.foreground = SEARCH_RESULT_ACTIVE_COLOR
            label.text = "[${stats.currentIndex}/${stats.total}]"
        }
    }

    private fun computeSearchResultStats(query: String): SearchResultStats? {
        if (!isSearchMode()) {
            return null
        }
        val normalized = normalizeSearchPattern(query)
        val pattern = normalized.pattern
        if (pattern.isEmpty()) {
            return null
        }
        val ignoreCase = resolveSearchIgnoreCase(normalized)
        val matches = findMatchOffsets(pattern, ignoreCase)
        if (matches.isEmpty()) {
            return null
        }
        val baseOffset = if (searchInitialCaretOffset >= 0) {
            searchInitialCaretOffset
        } else {
            editor.caretModel.primaryCaret.offset
        }
        val currentOffset = if (mode == OverlayMode.SEARCH_BACKWARD) {
            matches.lastOrNull { it <= baseOffset } ?: matches.last()
        } else {
            matches.firstOrNull { it >= baseOffset } ?: matches.first()
        }
        val currentIndex = matches.indexOf(currentOffset).takeIf { it >= 0 } ?: 0
        return SearchResultStats(currentIndex + 1, matches.size)
    }

    private fun normalizeSearchPattern(raw: String): NormalizedPattern {
        var overrideIgnoreCase: Boolean? = null
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '\\' && index + 1 < raw.length) {
                val next = raw[index + 1]
                if (next == 'c' || next == 'C') {
                    overrideIgnoreCase = (next == 'c')
                    index += 2
                    continue
                }
            }
            builder.append(ch)
            index += 1
        }
        val normalized = builder.toString()
        val hasUppercase = normalized.any { it.isLetter() && it.isUpperCase() }
        return NormalizedPattern(normalized, overrideIgnoreCase, hasUppercase)
    }

    private fun resolveSearchIgnoreCase(pattern: NormalizedPattern): Boolean {
        pattern.overrideIgnoreCase?.let { return it }
        val ignoreCase = IdeaVimFacade.isIgnoreCaseEnabled() ?: false
        if (!ignoreCase) {
            return false
        }
        val smartCase = IdeaVimFacade.isSmartCaseEnabled() ?: false
        if (smartCase && pattern.hasUppercase) {
            return false
        }
        return true
    }

    private fun findMatchOffsets(pattern: String, ignoreCase: Boolean): List<Int> {
        if (pattern.isEmpty()) {
            return emptyList()
        }
        val documentText = editor.document.text
        if (documentText.isEmpty()) {
            return emptyList()
        }
        val regexOptions = buildSet {
            add(RegexOption.MULTILINE)
            if (ignoreCase) {
                add(RegexOption.IGNORE_CASE)
            }
        }
        val regex = runCatching { Regex(pattern, regexOptions) }.getOrNull()
        return if (regex != null) {
            regex.findAll(documentText).map { it.range.first }.toList()
        } else {
            findLiteralOffsets(documentText, pattern, ignoreCase)
        }
    }

    private fun findLiteralOffsets(text: String, pattern: String, ignoreCase: Boolean): List<Int> {
        if (pattern.isEmpty()) {
            return emptyList()
        }
        val result = mutableListOf<Int>()
        var index = 0
        val step = pattern.length.coerceAtLeast(1)
        while (index <= text.length) {
            val found = text.indexOf(pattern, index, ignoreCase)
            if (found < 0) {
                break
            }
            result.add(found)
            index = found + step
        }
        return result
    }

    private fun EditorColorsScheme.toOverlayInputBackground(): JBColor {
        // 改为使用系统默认 Popup 背景色，跟随主题（参考原生 Goto File 等弹窗）
        return JBColor.namedColor("Popup.background", UIUtil.getPanelBackground())
    }

    private fun EditorColorsScheme.toOverlayBorder(): JBColor {
        return JBColor.namedColor("Component.borderColor", JBColor(0xC9CDD8, 0x4C5057))
    }

    fun notifyClosed() {
        if (!searchCommitted && !searchCancelled) {
            cancelSearchPreview()
        }
        cancelCommandPreview()
    }

    fun setSearchInitialCaretOffset(offset: Int) {
        searchInitialCaretOffset = offset
        if (isSearchMode()) {
            updateSearchResultIndicator(focusComponent.text)
        }
    }

    fun getSearchInitialCaretOffset(): Int = searchInitialCaretOffset

    private fun triggerSearchPreview(text: String) {
        if (!isSearchMode()) {
            return
        }
        searchCommitted = false
        if (text.isEmpty()) {
            searchCancelled = true
            onSearchPreviewCancel?.invoke(searchInitialCaretOffset)
        } else {
            searchCancelled = false
            onSearchPreview?.invoke(text, searchInitialCaretOffset)
        }
    }

    private fun cancelSearchPreview() {
        if (!isSearchMode()) {
            return
        }
        if (searchCancelled) {
            return
        }
        searchCancelled = true
        searchCommitted = false
        onSearchPreviewCancel?.invoke(searchInitialCaretOffset)
    }

    private fun cancelCommandPreview() {
        if (!commandPreviewActive) {
            return
        }
        commandPreviewActive = false
        onCommandPatternCancel?.invoke(searchInitialCaretOffset)
    }

    private fun updateCommandPatternPreview(content: String) {
        val pattern = extractSubstitutionPattern(content)
        if (pattern != null && pattern.isNotEmpty()) {
            commandPreviewActive = true
            onCommandPatternPreview?.invoke(pattern, searchInitialCaretOffset)
        } else {
            cancelCommandPreview()
        }
    }

    private fun extractSubstitutionPattern(command: String): String? {
        val trimmed = command.trimStart()
        if (trimmed.isEmpty()) {
            return null
        }

        var index = 0
        fun skipRange(): Boolean {
            if (index >= trimmed.length) return false
            val ch = trimmed[index]
            return when {
                ch == '\'' && index + 1 < trimmed.length -> {
                    index += 2
                    true
                }

                ch.isWhitespace() || ch.isDigit() || ch == '%' || ch == '$' || ch == '.' || ch == ',' || ch == ';' || ch == '-' || ch == '+' -> {
                    index += 1
                    true
                }

                else -> false
            }
        }

        while (skipRange()) {
            // continue skipping range characters
        }

        if (index >= trimmed.length) {
            return null
        }

        val remaining = trimmed.substring(index)
        val commandLength = when {
            remaining.startsWith("substitute", ignoreCase = true) -> "substitute".length
            remaining.startsWith("s", ignoreCase = true) -> 1
            else -> return null
        }

        index += commandLength
        while (index < trimmed.length && trimmed[index].isWhitespace()) {
            index += 1
        }

        if (index >= trimmed.length) {
            return null
        }

        val delimiter = trimmed[index]
        if (delimiter != '/') {
            return null
        }

        val patternBuilder = StringBuilder()
        var i = index + 1
        var escaping = false
        while (i < trimmed.length) {
            val ch = trimmed[i]
            if (!escaping && ch == delimiter) {
                break
            }
            if (!escaping && ch == '\\') {
                escaping = true
            } else {
                escaping = false
            }
            patternBuilder.append(ch)
            i += 1
        }

        return patternBuilder.toString()
    }

    private fun markSearchCommitted() {
        if (isSearchMode()) {
            searchCommitted = true
        }
        cancelCommandPreview()
    }

    private fun isSearchMode(): Boolean {
        return mode == OverlayMode.SEARCH_FORWARD || mode == OverlayMode.SEARCH_BACKWARD
    }

    private interface CompletionSupport {
        fun install(parent: JComponent)
        fun onUserInput(content: String)
        fun moveSelection(previous: Boolean): Boolean
        fun acceptSelection(): Boolean
        fun updatePopupWidth(width: Int)
        fun dispose()
    }

    private inner class SearchCompletionSupport(
        private val textField: JBTextField,
        private val completions: List<SearchCompletionWord>,
    ) : CompletionSupport {
        private val model = CollectionListModel<SearchMatchCompletion>()
        private val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(20)
            border = JBUI.Borders.empty(0, 6, 0, 6)
            isOpaque = false
            foreground = textField.foreground
            putClientProperty("JComponent.roundRect", java.lang.Boolean.TRUE)
            cellRenderer = object : ColoredListCellRenderer<SearchMatchCompletion>() {
                override fun customizeCellRenderer(
                    list: JList<out SearchMatchCompletion>,
                    value: SearchMatchCompletion?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    if (value == null) {
                        return
                    }
                    val baseAttributes = value.attributes
                    appendWithHighlights(
                        text = value.word,
                        highlightIndices = value.positions,
                        normalAttrs = baseAttributes,
                        highlightAttrs = currentSearchHighlightAttributes(baseAttributes),
                    )
                }
            }
        }
        private var selectionBaseText: String? = null
        private var suppressSelectionEvent = false
        private var currentActionQuery: ActionQuery? = null
        private var currentOptionQuery: OptionQuery? = null
        private var currentExQuery: String = ""

        private val scrollPane = JBScrollPane(list).apply {
            isOpaque = false
            val lineColor = JBColor.namedColor("Popup.separatorColor", JBColor(0xD0D3D9, 0x4B4F55))
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(lineColor, 1, 0, 0, 0),
                JBUI.Borders.empty(1, 0, 3, 0),
            )
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(12)
        }

        private val maxVisibleRows = 8
        private val maxCompletions = 50
        private var parentComponent: JComponent? = null
        private var currentHeight: Int = JBUI.scale(80)

        init {
            textField.addHierarchyListener(object : HierarchyListener {
                override fun hierarchyChanged(event: HierarchyEvent) {
                    if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L &&
                        !textField.isDisplayable
                    ) {
                        dispose()
                    }
                }
            })
            list.addListSelectionListener { event ->
                if (event.valueIsAdjusting || suppressSelectionEvent) {
                    return@addListSelectionListener
                }
                val index = list.selectedIndex
                if (index >= 0) {
                    if (selectionBaseText == null) {
                        selectionBaseText = textField.text
                    }
                    val value = model.getElementAt(index)
                    setTextProgrammatically(textField, value.word)
                } else {
                    restoreSelectionBase()
                }
            }
        }

        override fun install(parent: JComponent) {
            parentComponent = parent
        }

        override fun onUserInput(content: String) {
            selectionBaseText = null
            if (completions.isEmpty()) {
                dispose()
                return
            }
            val query = content.trim()
            if (query.isEmpty()) {
                dispose()
                return
            }
            val matches = matchSearchCompletions(completions, query, maxCompletions)
            if (matches.isEmpty()) {
                dispose()
            } else {
                updateCompletions(matches)
            }
        }

        private fun updateCompletions(matches: List<SearchMatchCompletion>) {
            suppressSelection {
                if (list.selectedIndex != -1) {
                    list.clearSelection()
                }
                model.replaceAll(matches)
            }
            val parent = parentComponent
            if (model.isEmpty || parent == null) {
                removeFromParent()
                return
            }
            val visibleRows = min(model.size, maxVisibleRows)
            val rowHeight = list.fixedCellHeight.takeIf { it > 0 }
                ?: list.preferredSize.height.coerceAtLeast(JBUI.scale(20))
            val parentWidth = parent.width.takeIf { it > 0 } ?: parent.preferredSize.width
            val width = parentWidth.coerceAtLeast(JBUI.scale(200))
            val height = visibleRows * rowHeight + JBUI.scale(4)
            currentHeight = height
            val size = Dimension(width, height)
            scrollPane.preferredSize = size
            scrollPane.minimumSize = size
            scrollPane.maximumSize = size
            if (scrollPane.parent == null) {
                parent.add(scrollPane, java.awt.BorderLayout.SOUTH)
            }
            val selectedIndex = list.selectedIndex
            if (selectedIndex >= 0) {
                list.ensureIndexIsVisible(selectedIndex)
            }
            parent.revalidate()
            parent.repaint()
            adjustCompletionsHeight(height)
        }

        override fun moveSelection(previous: Boolean): Boolean {
            if (!isActive()) {
                return false
            }
            if (model.isEmpty) {
                return false
            }
            val current = list.selectedIndex
            if (current == -1) {
                val target = if (previous) model.size - 1 else 0
                if (target < 0) {
                    return false
                }
                list.selectedIndex = target
                list.ensureIndexIsVisible(target)
                return true
            }
            if (previous) {
                if (current == 0) {
                    list.clearSelection()
                    return true
                }
                val target = current - 1
                list.selectedIndex = target
                list.ensureIndexIsVisible(target)
                return true
            } else {
                if (current == model.size - 1) {
                    list.clearSelection()
                    return true
                }
                val target = current + 1
                list.selectedIndex = target
                list.ensureIndexIsVisible(target)
                return true
            }
        }

        override fun acceptSelection(): Boolean {
            if (!isActive()) {
                return false
            }
            val index = list.selectedIndex
            if (index < 0 || index >= model.size) {
                return false
            }
            val value = model.getElementAt(index)
            setTextProgrammatically(textField, value.word)
            dispose()
            return true
        }

        override fun updatePopupWidth(width: Int) {
            if (scrollPane.parent == null) return
            val size = Dimension(width, currentHeight)
            scrollPane.preferredSize = size
            scrollPane.minimumSize = size
            scrollPane.maximumSize = size
            scrollPane.parent.revalidate()
            scrollPane.parent.repaint()
        }

        override fun dispose() {
            suppressSelection {
                model.replaceAll(emptyList())
                list.clearSelection()
            }
            removeFromParent()
            selectionBaseText = null
            adjustCompletionsHeight(null)
        }

        private fun removeFromParent() {
            val parent = scrollPane.parent
            if (parent != null) {
                parent.remove(scrollPane)
            }
            parent?.revalidate()
            parent?.repaint()
        }


        // 移除独立 Popup 逻辑，改为嵌入主悬浮框

        private fun isActive(): Boolean = (model.size > 0 && scrollPane.parent != null)

        private fun restoreSelectionBase() {
            val base = selectionBaseText ?: return
            selectionBaseText = null
            if (textField.text != base) {
                setTextProgrammatically(textField, base)
            }
        }

        private inline fun suppressSelection(block: () -> Unit) {
            if (suppressSelectionEvent) {
                block()
                return
            }
            suppressSelectionEvent = true
            try {
                block()
            } finally {
                suppressSelectionEvent = false
            }
        }
    }

    private inner class CommandCompletionSupport(
        private val textField: JBTextField,
        private val searchCompletions: List<SearchCompletionWord>,
    ) : CompletionSupport {
        private val model = CollectionListModel<CompletionEntry>()
        private val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(20)
            border = JBUI.Borders.empty(0, 6, 0, 6)
            isOpaque = false
            foreground = textField.foreground
            putClientProperty("JComponent.roundRect", java.lang.Boolean.TRUE)
            cellRenderer = object : ColoredListCellRenderer<CompletionEntry>() {
                override fun customizeCellRenderer(
                    list: JList<out CompletionEntry>,
                    value: CompletionEntry?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    if (value == null) {
                        return
                    }
                    val highlightAttrs = currentSearchHighlightAttributes()
                    val actionQueryText = this@CommandCompletionSupport.currentActionQuery?.query
                    val optionQueryText = this@CommandCompletionSupport.currentOptionQuery?.query
                    val exQueryText = this@CommandCompletionSupport.currentExQuery
                    when (value) {
                        is CompletionEntry.ExCommand -> {
                            val display = value.data.displayText
                            if (display.isNotBlank()) {
                                appendWithHighlights(
                                    text = display,
                                    highlightIndices = highlightIndicesForSubstring(display, exQueryText),
                                    normalAttrs = SimpleTextAttributes.REGULAR_ATTRIBUTES,
                                    highlightAttrs = highlightAttrs,
                                )
                            } else {
                                val execution = value.data.executionText
                                appendWithHighlights(
                                    text = execution,
                                    highlightIndices = highlightIndicesForSubstring(execution, exQueryText),
                                    normalAttrs = SimpleTextAttributes.REGULAR_ATTRIBUTES,
                                    highlightAttrs = highlightAttrs,
                                )
                            }
                        }

                        is CompletionEntry.SearchWord -> {
                            val baseAttributes = value.match.attributes
                            appendWithHighlights(
                                text = value.match.word,
                                highlightIndices = value.match.positions,
                                normalAttrs = baseAttributes,
                                highlightAttrs = currentSearchHighlightAttributes(baseAttributes),
                            )
                        }

                        is CompletionEntry.Action -> {
                            val highlightQuery = actionQueryText
                            val presentation = value.data.presentation
                            if (!presentation.isNullOrBlank()) {
                                appendWithHighlights(
                                    text = presentation,
                                    highlightIndices = highlightIndicesForSubstring(presentation, highlightQuery),
                                    normalAttrs = SimpleTextAttributes.REGULAR_ATTRIBUTES,
                                    highlightAttrs = highlightAttrs,
                                )
                                append("  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                                appendWithHighlights(
                                    text = value.data.actionId,
                                    highlightIndices = highlightIndicesForSubstring(value.data.actionId, highlightQuery),
                                    normalAttrs = SimpleTextAttributes.GRAY_ATTRIBUTES,
                                    highlightAttrs = highlightAttrs,
                                )
                            } else {
                                appendWithHighlights(
                                    text = value.data.actionId,
                                    highlightIndices = highlightIndicesForSubstring(value.data.actionId, highlightQuery),
                                    normalAttrs = SimpleTextAttributes.REGULAR_ATTRIBUTES,
                                    highlightAttrs = highlightAttrs,
                                )
                            }
                        }

                        is CompletionEntry.Option -> {
                            val highlightQuery = optionQueryText
                            appendWithHighlights(
                                text = value.data.name,
                                highlightIndices = highlightIndicesForSubstring(value.data.name, highlightQuery),
                                normalAttrs = SimpleTextAttributes.REGULAR_ATTRIBUTES,
                                highlightAttrs = highlightAttrs,
                            )
                            value.data.abbreviation?.let { abbrev ->
                                append("  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                                appendWithHighlights(
                                    text = abbrev,
                                    highlightIndices = highlightIndicesForSubstring(abbrev, highlightQuery),
                                    normalAttrs = SimpleTextAttributes.GRAY_ATTRIBUTES,
                                    highlightAttrs = highlightAttrs,
                                )
                            }
                        }
                    }
                }
            }
        }
        private var selectionBaseText: String? = null
        private var suppressSelectionEvent = false
        private var currentActionQuery: ActionQuery? = null
        private var currentOptionQuery: OptionQuery? = null
        private var currentExQuery: String = ""

        private val scrollPane = JBScrollPane(list).apply {
            isOpaque = false
            val lineColor = JBColor.namedColor("Popup.separatorColor", JBColor(0xD0D3D9, 0x4B4F55))
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(lineColor, 1, 0, 0, 0),
                JBUI.Borders.empty(1, 0, 3, 0),
            )
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(12)
        }

        private val maxVisibleRows = 8
        private val maxSearchCompletions = 50
        private var parentComponent: JComponent? = null
        private var currentHeight: Int = JBUI.scale(80)

        init {
            textField.addHierarchyListener(object : HierarchyListener {
                override fun hierarchyChanged(event: HierarchyEvent) {
                    if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L &&
                        !textField.isDisplayable
                    ) {
                        dispose()
                    }
                }
            })
            list.addListSelectionListener { event ->
                if (event.valueIsAdjusting || suppressSelectionEvent) {
                    return@addListSelectionListener
                }
                val index = list.selectedIndex
                if (index >= 0) {
                    if (selectionBaseText == null) {
                        selectionBaseText = textField.text
                    }
                    applySelection(index)
                } else {
                    restoreSelectionBase()
                }
            }
        }

        override fun install(parent: JComponent) {
            parentComponent = parent
        }

        override fun onUserInput(content: String) {
            selectionBaseText = null
            currentActionQuery = null
            currentOptionQuery = null
            currentExQuery = ""
            val substitutionQuery = parseSubstitutionSearchQuery(content)
            if (substitutionQuery != null && searchCompletions.isNotEmpty()) {
                val matches = matchSearchCompletions(searchCompletions, substitutionQuery.query, maxSearchCompletions)
                if (matches.isEmpty()) {
                    dispose()
                } else {
                    updateCompletions(matches.map { CompletionEntry.SearchWord(it, substitutionQuery) })
                }
                return
            }

            val actionQuery = parseActionQuery(content)
            if (actionQuery != null) {
                val completions = ActionCommandCompletion.suggest(actionQuery.query, maxVisibleRows)
                if (completions.isEmpty()) {
                    dispose()
                } else {
                    currentActionQuery = actionQuery
                    updateCompletions(completions.map { CompletionEntry.Action(it, actionQuery.prefix) })
                }
                return
            }

            val optionQuery = parseSetOptionQuery(content)
            if (optionQuery != null) {
                val completions = OptionCommandCompletion.suggest(optionQuery.query, maxVisibleRows)
                if (completions.isEmpty()) {
                    dispose()
                } else {
                    currentOptionQuery = optionQuery
                    updateCompletions(completions.map { CompletionEntry.Option(it, optionQuery.prefix) })
                }
                return
            }
            val completions = ExCommandCompletion.suggest(content, maxVisibleRows)
            if (completions.isEmpty()) {
                dispose()
            } else {
                currentExQuery = extractCommandHighlight(content)
                updateCompletions(completions.map { CompletionEntry.ExCommand(it) })
            }
        }

        override fun moveSelection(previous: Boolean): Boolean {
            if (!isActive()) {
                return false
            }
            if (model.isEmpty) {
                return false
            }
            val current = list.selectedIndex
            if (current == -1) {
                val target = if (previous) model.size - 1 else 0
                if (target < 0) {
                    return false
                }
                list.selectedIndex = target
                list.ensureIndexIsVisible(target)
                return true
            }
            if (previous) {
                if (current == 0) {
                    list.clearSelection()
                    return true
                }
                val target = current - 1
                list.selectedIndex = target
                list.ensureIndexIsVisible(target)
                return true
            } else {
                if (current == model.size - 1) {
                    list.clearSelection()
                    return true
                }
                val target = current + 1
                list.selectedIndex = target
                list.ensureIndexIsVisible(target)
                return true
            }
        }

        override fun acceptSelection(): Boolean {
            if (!isActive()) {
                return false
            }
            val index = list.selectedIndex
            if (index < 0 || index >= model.size) {
                return false
            }
            applySelection(index)
            dispose()
            return true
        }

        private fun updateCompletions(completions: List<CompletionEntry>) {
            suppressSelection {
                if (list.selectedIndex != -1) {
                    list.clearSelection()
                }
                model.replaceAll(completions)
                if (model.isEmpty) {
                    list.clearSelection()
                }
            }
            val parent = parentComponent
            if (model.isEmpty || parent == null) {
                removeFromParent()
                adjustCompletionsHeight(null)
                return
            }
            val visibleRows = min(model.size, maxVisibleRows)
            val rowHeight =
                list.fixedCellHeight.takeIf { it > 0 } ?: list.preferredSize.height.coerceAtLeast(JBUI.scale(20))
            val parentWidth = parent.width.takeIf { it > 0 } ?: parent.preferredSize.width
            val width = parentWidth.coerceAtLeast(JBUI.scale(200))
            val height = visibleRows * rowHeight + JBUI.scale(4)
            currentHeight = height
            val size = Dimension(width, height)
            scrollPane.preferredSize = size
            scrollPane.minimumSize = size
            scrollPane.maximumSize = size
            if (scrollPane.parent == null) {
                parent.add(scrollPane, java.awt.BorderLayout.SOUTH)
            }
            val selectedIndex = list.selectedIndex
            if (selectedIndex >= 0) {
                list.ensureIndexIsVisible(selectedIndex)
            }
            parent.revalidate()
            parent.repaint()
            adjustCompletionsHeight(height)
        }

        private fun isActive(): Boolean = (model.size > 0 && scrollPane.parent != null)

        // 移除独立 Popup 逻辑，改为嵌入主悬浮框

        override fun dispose() {
            suppressSelection {
                model.replaceAll(emptyList())
                list.clearSelection()
            }
            removeFromParent()
            selectionBaseText = null
            adjustCompletionsHeight(null)
        }

        private fun removeFromParent() {
            val parent = scrollPane.parent
            if (parent != null) {
                parent.remove(scrollPane)
            }
            parent?.revalidate()
            parent?.repaint()
        }


        private fun parseSubstitutionSearchQuery(content: String): SubstitutionQuery? {
            if (content.isEmpty()) {
                return null
            }
            val length = content.length
            var index = 0

            fun skipWhitespace() {
                while (index < length && content[index].isWhitespace()) {
                    index += 1
                }
            }

            skipWhitespace()
            if (index < length && content[index] == ':') {
                index += 1
            }
            skipWhitespace()

            while (index < length) {
                val before = index
                val ch = content[index]
                when {
                    ch == '\'' && index + 1 < length -> index += 2
                    ch.isWhitespace() || ch.isDigit() || ch == '%' || ch == '$' || ch == '.' || ch == ',' || ch == ';' || ch == '-' || ch == '+' -> index += 1
                }
                if (index == before) {
                    break
                }
            }

            skipWhitespace()
            if (index >= length) {
                return null
            }

            val remaining = content.substring(index)
            val commandLength = when {
                remaining.startsWith("substitute", ignoreCase = true) -> "substitute".length
                remaining.startsWith("s", ignoreCase = true) -> 1
                else -> return null
            }

            index += commandLength
            while (index < length && content[index].isWhitespace()) {
                index += 1
            }
            if (index >= length) {
                return null
            }

            val delimiter = content[index]
            if (delimiter != '/') {
                return null
            }

            val patternStart = index + 1
            var cursor = patternStart
            var escaping = false
            while (cursor < length) {
                val ch = content[cursor]
                if (!escaping && ch == delimiter) {
                    return null
                }
                if (!escaping && ch == '\\') {
                    escaping = true
                } else {
                    escaping = false
                }
                cursor += 1
            }

            val prefix = content.substring(0, patternStart)
            val query = content.substring(patternStart)
            return SubstitutionQuery(prefix, query, "")
        }

        private fun applySelection(index: Int) {
            when (val completion = model.getElementAt(index)) {
                is CompletionEntry.SearchWord -> {
                    val newValue = completion.context.prefix + completion.match.word + completion.context.suffix
                    setTextProgrammatically(textField, newValue)
                }

                is CompletionEntry.ExCommand -> {
                    setTextProgrammatically(textField, completion.data.executionText)
                }

                is CompletionEntry.Action -> {
                    setTextProgrammatically(textField, completion.prefix + completion.data.actionId)
                }

                is CompletionEntry.Option -> {
                    setTextProgrammatically(textField, completion.prefix + completion.data.name)
                }
            }
        }

        private fun restoreSelectionBase() {
            val base = selectionBaseText ?: return
            selectionBaseText = null
            if (textField.text != base) {
                setTextProgrammatically(textField, base)
            }
        }

        private inline fun suppressSelection(block: () -> Unit) {
            if (suppressSelectionEvent) {
                block()
                return
            }
            suppressSelectionEvent = true
            try {
                block()
            } finally {
                suppressSelectionEvent = false
            }
        }

        private fun parseSetOptionQuery(content: String): OptionQuery? {
            if (content.isEmpty()) {
                return null
            }

            var index = content.indexOfFirst { !it.isWhitespace() }
            if (index == -1) {
                return null
            }

            if (content[index] == ':') {
                index += 1
                while (index < content.length && content[index].isWhitespace()) {
                    index += 1
                }
                if (index >= content.length) {
                    return null
                }
            }

            if (!content.regionMatches(index, "set", 0, 3, ignoreCase = true)) {
                return null
            }
            val afterSet = index + 3
            if (afterSet >= content.length) {
                return null
            }

            var cursor = afterSet
            var sawWhitespace = false
            while (cursor < content.length && content[cursor].isWhitespace()) {
                cursor += 1
                sawWhitespace = true
            }
            if (!sawWhitespace) {
                return null
            }
            if (cursor >= content.length) {
                return null
            }

            val prefix = content.substring(0, cursor)
            val query = content.substring(cursor)
            return OptionQuery(prefix, query)
        }

        override fun updatePopupWidth(width: Int) {
            if (scrollPane.parent == null) return
            val size = Dimension(width, currentHeight)
            scrollPane.preferredSize = size
            scrollPane.minimumSize = size
            scrollPane.maximumSize = size
            scrollPane.parent.revalidate()
            scrollPane.parent.repaint()
        }

        private fun parseActionQuery(content: String): ActionQuery? {
            val firstNonSpace = content.indexOfFirst { !it.isWhitespace() }
            if (firstNonSpace == -1) {
                return null
            }
            val leading = content.substring(0, firstNonSpace)
            val remainder = content.substring(firstNonSpace)

            val patterns = listOf(":action ", "action ")
            for (pattern in patterns) {
                if (remainder.regionMatches(0, pattern, 0, pattern.length, ignoreCase = true)) {
                    val prefix = leading + remainder.substring(0, pattern.length)
                    val query = remainder.substring(pattern.length).trimStart()
                    if (query.isEmpty()) {
                        return null
                    }
                    return ActionQuery(prefix, query)
                }
            }
            return null
        }
    }

    companion object {
        private const val ACTION_CANCEL = "ideavim.cmdline.cancel"
        private const val ACTION_HISTORY_PREVIOUS = "ideavim.cmdline.history.previous"
        private const val ACTION_HISTORY_NEXT = "ideavim.cmdline.history.next"
        private const val ACTION_COMPLETION_PREVIOUS = "ideavim.cmdline.completion.previous"
        private const val ACTION_COMPLETION_NEXT = "ideavim.cmdline.completion.next"
    }

    private fun setTextProgrammatically(textField: JBTextField, value: String) {
        programmaticUpdate = true
        textField.text = value
        textField.caretPosition = value.length
        programmaticUpdate = false
        triggerSearchPreview(value)
        if (mode == OverlayMode.COMMAND) {
            updateCommandPatternPreview(value)
        }
        if (isSearchMode()) {
            updateSearchResultIndicator(value)
        }
    }

    private data class SearchResultStats(
        val currentIndex: Int,
        val total: Int,
    )

    private data class NormalizedPattern(
        val pattern: String,
        val overrideIgnoreCase: Boolean?,
        val hasUppercase: Boolean,
    )
}

private fun currentSearchHighlightAttributes(baseAttributes: SimpleTextAttributes? = null): SimpleTextAttributes {
    // 使用 SearchMatch 命名颜色，仅保留圆角“药丸”样式，不再加粗
    val bg = JBColor.namedColor("SearchMatch.startBackground", JBColor(0xFFF59D, 0x4D3B00))
    val defaultFg = JBColor.namedColor("SearchMatch.startForeground", JBColor(0x000000, 0x000000))
    val fg = baseAttributes?.fgColor ?: defaultFg
    val wave = baseAttributes?.waveColor
    val baseStyle = baseAttributes?.style ?: SimpleTextAttributes.STYLE_PLAIN
    return SimpleTextAttributes(bg, fg, wave, baseStyle or SimpleTextAttributes.STYLE_SEARCH_MATCH)
}

private val SEARCH_RESULT_NEUTRAL_COLOR = JBColor.namedColor("Label.infoForeground", JBColor(0x9397A1, 0x6D737D))
private val SEARCH_RESULT_ACTIVE_COLOR = JBColor.namedColor("Link.activeForeground", JBColor(0x0A84FF, 0x4C8DFF))
private val SEARCH_RESULT_EMPTY_COLOR = JBColor.namedColor("Label.errorForeground", JBColor(0xE5484D, 0xFF6A6A))
private const val NO_RESULTS_TEXT = "0 results"

private val searchMatchComparator = compareByDescending<SearchMatchCompletion> { it.maxConsecutive }
    .thenBy { it.firstIndex }
    .thenBy { it.span }
    .thenBy { it.sumIndices }
    .thenBy { it.word.length }
    .thenBy { it.word.lowercase(Locale.ROOT) }
    .thenBy { it.word }

private fun matchSearchCompletions(
    completions: List<SearchCompletionWord>,
    rawQuery: String,
    limit: Int,
): List<SearchMatchCompletion> {
    val normalized = rawQuery.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) {
        return emptyList()
    }
    return completions.asSequence()
        .mapNotNull { completion -> computeSearchMatch(normalized, completion) }
        .sortedWith(searchMatchComparator)
        .take(limit)
        .toList()
}

private fun computeSearchMatch(normalizedQuery: String, completion: SearchCompletionWord): SearchMatchCompletion? {
    val word = completion.word
    if (normalizedQuery.length > word.length) {
        return null
    }
    val completionLower = word.lowercase(Locale.ROOT)
    val positionsList = mutableListOf<Int>()
    var queryIndex = 0
    for (index in completionLower.indices) {
        if (completionLower[index] == normalizedQuery[queryIndex]) {
            positionsList.add(index)
            queryIndex += 1
            if (queryIndex == normalizedQuery.length) {
                break
            }
        }
    }
    if (queryIndex != normalizedQuery.length || positionsList.isEmpty()) {
        return null
    }
    val positions = positionsList.toIntArray()
    var maxStreak = 1
    var currentStreak = 1
    var sumIndices = positions.first()
    for (i in 1 until positions.size) {
        val prev = positions[i - 1]
        val current = positions[i]
        sumIndices += current
        if (current == prev + 1) {
            currentStreak += 1
            if (currentStreak > maxStreak) {
                maxStreak = currentStreak
            }
        } else {
            currentStreak = 1
        }
    }
    val span = positions.last() - positions.first()
    return SearchMatchCompletion(
        word = word,
        attributes = SimpleTextAttributes.fromTextAttributes(completion.attributes),
        maxConsecutive = maxStreak,
        firstIndex = positions.first(),
        span = span,
        sumIndices = sumIndices,
        positions = positions,
    )
}

private data class SubstitutionQuery(
    val prefix: String,
    val query: String,
    val suffix: String,
)

private data class SearchMatchCompletion(
    val word: String,
    val attributes: SimpleTextAttributes,
    val maxConsecutive: Int,
    val firstIndex: Int,
    val span: Int,
    val sumIndices: Int,
    val positions: IntArray,
)

private fun ColoredListCellRenderer<*>.appendWithHighlights(
    text: String,
    highlightIndices: IntArray,
    normalAttrs: SimpleTextAttributes,
    highlightAttrs: SimpleTextAttributes,
) {
    if (highlightIndices.isEmpty()) {
        append(text, normalAttrs)
        return
    }
    var cursor = 0
    var pointer = 0
    while (cursor < text.length) {
        if (pointer < highlightIndices.size && highlightIndices[pointer] == cursor) {
            var end = cursor
            while (pointer < highlightIndices.size && highlightIndices[pointer] == end) {
                end += 1
                pointer += 1
            }
            append(text.substring(cursor, end), highlightAttrs)
            cursor = end
        } else {
            val start = cursor
            while (cursor < text.length && (pointer >= highlightIndices.size || highlightIndices[pointer] != cursor)) {
                cursor += 1
            }
            if (cursor > start) {
                append(text.substring(start, cursor), normalAttrs)
            }
        }
    }
}

private fun highlightIndicesForSubstring(text: String, query: String?): IntArray {
    val trimmed = query?.trim() ?: return IntArray(0)
    if (trimmed.isEmpty()) {
        return IntArray(0)
    }
    val lowerText = text.lowercase(Locale.ROOT)
    val lowerQuery = trimmed.lowercase(Locale.ROOT)
    val index = lowerText.indexOf(lowerQuery)
    if (index == -1) {
        return IntArray(0)
    }
    return IntArray(lowerQuery.length) { offset -> index + offset }
}

private fun extractCommandHighlight(content: String): String {
    val trimmed = content.trimStart()
    val withoutColon = if (trimmed.startsWith(":")) trimmed.substring(1) else trimmed
    return withoutColon.trimStart()
}

private data class ActionQuery(val prefix: String, val query: String)
private data class OptionQuery(val prefix: String, val query: String)

private sealed interface CompletionEntry {
    data class SearchWord(val match: SearchMatchCompletion, val context: SubstitutionQuery) : CompletionEntry
    data class ExCommand(val data: ExCommandCompletion.Completion) : CompletionEntry
    data class Action(val data: ActionCommandCompletion.Completion, val prefix: String) : CompletionEntry
    data class Option(val data: OptionCommandCompletion.Completion, val prefix: String) : CompletionEntry
}
