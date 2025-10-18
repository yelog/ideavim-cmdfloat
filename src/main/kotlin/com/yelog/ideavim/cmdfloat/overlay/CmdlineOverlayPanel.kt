package com.yelog.ideavim.cmdfloat.overlay

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.yelog.ideavim.cmdfloat.overlay.OptionCommandCompletion
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.ActionMap
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.InputMap
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.min

class CmdlineOverlayPanel(
    private val mode: OverlayMode,
    history: CommandHistory,
    private val editor: Editor,
    private val searchCandidates: List<String> = emptyList(),
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
    private var suggestionSupport: SuggestionSupport? = null
    private var preferredWidth = JBUI.scale(400)
    private val basePreferredHeight: Int
    private var searchCommitted = false
    private var suggestionsHeight: Int? = null
    private var searchCancelled = false
    private var searchInitialCaretOffset: Int = -1
    private var commandPreviewActive = false
    private val searchResultLabel: JBLabel?

    init {
        val scheme = EditorColorsManager.getInstance().globalScheme
        focusComponent = createTextField(scheme)
        searchResultLabel = if (isSearchMode()) createSearchResultLabel() else null
        suggestionSupport = when (mode) {
            OverlayMode.COMMAND -> CommandSuggestionSupport(focusComponent, scheme, searchCandidates)
            OverlayMode.SEARCH_FORWARD, OverlayMode.SEARCH_BACKWARD -> SearchSuggestionSupport(
                focusComponent,
                scheme,
                searchCandidates,
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

            add(inputPanel, java.awt.BorderLayout.CENTER)
            suggestionSupport?.install(this)
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

        if (mode == OverlayMode.COMMAND && historySnapshot.isNotEmpty() && historySnapshot.firstOrNull()?.startsWith("<,'>") == true) {
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
        suggestionSupport?.updatePopupWidth(width)
    }

    private fun applyPreferredSize() {
        val totalHeight = basePreferredHeight + (suggestionsHeight ?: 0)
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

    private fun adjustSuggestionsHeight(suggestionsHeight: Int?) {
        this.suggestionsHeight = suggestionsHeight
        applyPreferredSize()
    }

    // 已通过 applyPreferredSize 调整并 pack popup，无需单独 resize 方法

    private fun handleDocumentChange(textField: JBTextField) {
        if (programmaticUpdate) {
            return
        }
        historyIndex = -1
        suggestionSupport?.onUserInput(textField.text)
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
            margin = JBUI.insets(0, 1, 0, 6)
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
            border = JBUI.Borders.empty(0, 6, 0, 0)
            foreground = focusComponent.foreground
            font = JBFont.label().deriveFont(Font.BOLD, scaledFontSize(16f))
            preferredSize = Dimension(JBUI.scale(if (isSearchMode) 26 else 24), inputHeight)
            isOpaque = false
        }
        if (isSearchMode) {
            label.icon = AllIcons.Actions.Search
        } else {
            label.text = ">"
        }
        return label
    }

    private fun installActions(textField: JBTextField) {
        textField.addActionListener {
            suggestionSupport?.acceptSelection()
            onSubmit?.invoke(textField.text)
            markSearchCommitted()
            suggestionSupport?.dispose()
        }

        val inputMap: InputMap = textField.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap: ActionMap = textField.actionMap

        inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), ACTION_CANCEL)
        actionMap.put(ACTION_CANCEL, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                suggestionSupport?.dispose()
                cancelSearchPreview()
                cancelCommandPreview()
                onCancel?.invoke()
            }
        })

        inputMap.put(KeyStroke.getKeyStroke("UP"), ACTION_HISTORY_PREVIOUS)
        actionMap.put(ACTION_HISTORY_PREVIOUS, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (suggestionSupport?.moveSelection(previous = true) != true) {
                    navigateHistory(previous = true, textField)
                }
            }
        })

        inputMap.put(KeyStroke.getKeyStroke("DOWN"), ACTION_HISTORY_NEXT)
        actionMap.put(ACTION_HISTORY_NEXT, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (suggestionSupport?.moveSelection(previous = false) != true) {
                    navigateHistory(previous = false, textField)
                }
            }
        })

        val suggestionPreviousAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                suggestionSupport?.moveSelection(previous = true)
            }
        }
        val suggestionNextAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                suggestionSupport?.moveSelection(previous = false)
            }
        }

        inputMap.put(KeyStroke.getKeyStroke("shift TAB"), ACTION_SUGGESTION_PREVIOUS)
        actionMap.put(ACTION_SUGGESTION_PREVIOUS, suggestionPreviousAction)
        inputMap.put(KeyStroke.getKeyStroke("TAB"), ACTION_SUGGESTION_NEXT)
        actionMap.put(ACTION_SUGGESTION_NEXT, suggestionNextAction)
        inputMap.put(KeyStroke.getKeyStroke("ctrl P"), ACTION_SUGGESTION_PREVIOUS)
        actionMap.put(ACTION_SUGGESTION_PREVIOUS, suggestionPreviousAction)
        inputMap.put(KeyStroke.getKeyStroke("ctrl N"), ACTION_SUGGESTION_NEXT)
        actionMap.put(ACTION_SUGGESTION_NEXT, suggestionNextAction)
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
        suggestionSupport?.dispose()
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

    private interface SuggestionSupport {
        fun install(parent: JComponent)
        fun onUserInput(content: String)
        fun moveSelection(previous: Boolean): Boolean
        fun acceptSelection(): Boolean
        fun updatePopupWidth(width: Int)
        fun dispose()
    }

    private inner class SearchSuggestionSupport(
        private val textField: JBTextField,
        private val scheme: EditorColorsScheme,
        private val candidates: List<String>,
    ) : SuggestionSupport {
        private val model = CollectionListModel<SearchMatchCandidate>()
        private val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(20)
            border = JBUI.Borders.empty(0, 6, 0, 6)
            isOpaque = false
            foreground = textField.foreground
            putClientProperty("JComponent.roundRect", java.lang.Boolean.TRUE)
            cellRenderer = object : ColoredListCellRenderer<SearchMatchCandidate>() {
                override fun customizeCellRenderer(
                    list: JList<out SearchMatchCandidate>,
                    value: SearchMatchCandidate?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    if (value == null) {
                        return
                    }
                    appendWithHighlights(
                        text = value.word,
                        highlightIndices = value.positions,
                        normalAttrs = SimpleTextAttributes.REGULAR_ATTRIBUTES,
                        highlightAttrs = SEARCH_HIGHLIGHT_ATTRIBUTES,
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
            border = JBUI.Borders.empty(2, 0, 4, 0)
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(12)
        }

        private val maxVisibleRows = 8
        private val maxSuggestions = 50
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
            if (candidates.isEmpty()) {
                dispose()
                return
            }
            val query = content.trim()
            if (query.isEmpty()) {
                dispose()
                return
            }
            val matches = matchSearchCandidates(candidates, query, maxSuggestions)
            if (matches.isEmpty()) {
                dispose()
            } else {
                updateSuggestions(matches)
            }
        }

        private fun updateSuggestions(matches: List<SearchMatchCandidate>) {
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
            adjustSuggestionsHeight(height)
            adjustSuggestionsHeight(height)
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
            adjustSuggestionsHeight(null)
        }

        private fun removeFromParent() {
            val parent = scrollPane.parent ?: return
            parent.remove(scrollPane)
            parent.revalidate()
            parent.repaint()
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

    private inner class CommandSuggestionSupport(
        private val textField: JBTextField,
        private val scheme: EditorColorsScheme,
        private val searchCandidates: List<String>,
    ) : SuggestionSupport {
        private val model = CollectionListModel<SuggestionEntry>()
        private val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(20)
            border = JBUI.Borders.empty(0, 6, 0, 6)
            isOpaque = false
            foreground = textField.foreground
            putClientProperty("JComponent.roundRect", java.lang.Boolean.TRUE)
            cellRenderer = object : ColoredListCellRenderer<SuggestionEntry>() {
                override fun customizeCellRenderer(
                    list: JList<out SuggestionEntry>,
                    value: SuggestionEntry?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    if (value == null) {
                        return
                    }
                    val highlightAttrs = SEARCH_HIGHLIGHT_ATTRIBUTES
                    val actionQueryText = this@CommandSuggestionSupport.currentActionQuery?.query
                    val optionQueryText = this@CommandSuggestionSupport.currentOptionQuery?.query
                    val exQueryText = this@CommandSuggestionSupport.currentExQuery
                    when (value) {
                        is SuggestionEntry.ExCommand -> {
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
                        is SuggestionEntry.SearchWord -> {
                            appendWithHighlights(
                                text = value.match.word,
                                highlightIndices = value.match.positions,
                                normalAttrs = SimpleTextAttributes.REGULAR_ATTRIBUTES,
                                highlightAttrs = highlightAttrs,
                            )
                        }
                        is SuggestionEntry.Action -> {
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
                        is SuggestionEntry.Option -> {
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
            border = JBUI.Borders.empty(2, 0, 4, 0)
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(12)
        }

        private val maxVisibleRows = 8
        private val maxSearchSuggestions = 50
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
            if (substitutionQuery != null && searchCandidates.isNotEmpty()) {
                val matches = matchSearchCandidates(searchCandidates, substitutionQuery.query, maxSearchSuggestions)
                if (matches.isEmpty()) {
                    dispose()
                } else {
                    updateSuggestions(matches.map { SuggestionEntry.SearchWord(it, substitutionQuery) })
                }
                return
            }

            val actionQuery = parseActionQuery(content)
            if (actionQuery != null) {
                val suggestions = ActionCommandCompletion.suggest(actionQuery.query, maxVisibleRows)
                if (suggestions.isEmpty()) {
                    dispose()
                } else {
                    currentActionQuery = actionQuery
                    updateSuggestions(suggestions.map { SuggestionEntry.Action(it, actionQuery.prefix) })
                }
                return
            }

            val optionQuery = parseSetOptionQuery(content)
            if (optionQuery != null) {
                val suggestions = OptionCommandCompletion.suggest(optionQuery.query, maxVisibleRows)
                if (suggestions.isEmpty()) {
                    dispose()
                } else {
                    currentOptionQuery = optionQuery
                    updateSuggestions(suggestions.map { SuggestionEntry.Option(it, optionQuery.prefix) })
                }
                return
            }
            val suggestions = ExCommandCompletion.suggest(content, maxVisibleRows)
            if (suggestions.isEmpty()) {
                dispose()
            } else {
                currentExQuery = extractCommandHighlight(content)
                updateSuggestions(suggestions.map { SuggestionEntry.ExCommand(it) })
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

        private fun updateSuggestions(suggestions: List<SuggestionEntry>) {
            suppressSelection {
                if (list.selectedIndex != -1) {
                    list.clearSelection()
                }
                model.replaceAll(suggestions)
                if (model.isEmpty) {
                    list.clearSelection()
                }
            }
            val parent = parentComponent
            if (model.isEmpty || parent == null) {
                removeFromParent()
                adjustSuggestionsHeight(null)
                return
            }
            val visibleRows = min(model.size, maxVisibleRows)
            val rowHeight = list.fixedCellHeight.takeIf { it > 0 } ?: list.preferredSize.height.coerceAtLeast(JBUI.scale(20))
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
            adjustSuggestionsHeight(height)
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
            adjustSuggestionsHeight(null)
        }

        private fun removeFromParent() {
            val parent = scrollPane.parent ?: return
            parent.remove(scrollPane)
            parent.revalidate()
            parent.repaint()
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
            when (val suggestion = model.getElementAt(index)) {
                is SuggestionEntry.SearchWord -> {
                    val newValue = suggestion.context.prefix + suggestion.match.word + suggestion.context.suffix
                    setTextProgrammatically(textField, newValue)
                }
                is SuggestionEntry.ExCommand -> {
                    setTextProgrammatically(textField, suggestion.data.executionText)
                }
                is SuggestionEntry.Action -> {
                    setTextProgrammatically(textField, suggestion.prefix + suggestion.data.actionId)
                }
                is SuggestionEntry.Option -> {
                    setTextProgrammatically(textField, suggestion.prefix + suggestion.data.name)
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
        private const val ACTION_SUGGESTION_PREVIOUS = "ideavim.cmdline.suggestion.previous"
        private const val ACTION_SUGGESTION_NEXT = "ideavim.cmdline.suggestion.next"
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

private val SEARCH_HIGHLIGHT_ATTRIBUTES = SimpleTextAttributes(
    SimpleTextAttributes.STYLE_BOLD,
    // 使用与 Search Everywhere / Goto 类似的主题前景色（可随主题变化），提供更原生的匹配高亮体验
    JBColor.namedColor("SearchEverywhere.matchesForeground", JBColor(0x0F7AF5, 0x62AFFF)),
)

private val SEARCH_RESULT_NEUTRAL_COLOR = JBColor.namedColor("Label.infoForeground", JBColor(0x9397A1, 0x6D737D))
private val SEARCH_RESULT_ACTIVE_COLOR = JBColor.namedColor("Link.activeForeground", JBColor(0x0A84FF, 0x4C8DFF))
private val SEARCH_RESULT_EMPTY_COLOR = JBColor.namedColor("Label.errorForeground", JBColor(0xE5484D, 0xFF6A6A))
private const val NO_RESULTS_TEXT = "0 results"

private val searchMatchComparator = compareByDescending<SearchMatchCandidate> { it.maxConsecutive }
    .thenBy { it.firstIndex }
    .thenBy { it.span }
    .thenBy { it.sumIndices }
    .thenBy { it.word.length }
    .thenBy { it.word.lowercase(Locale.ROOT) }
    .thenBy { it.word }

private fun matchSearchCandidates(
    candidates: List<String>,
    rawQuery: String,
    limit: Int,
): List<SearchMatchCandidate> {
    val normalized = rawQuery.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) {
        return emptyList()
    }
    return candidates.asSequence()
        .mapNotNull { candidate -> computeSearchMatch(normalized, candidate) }
        .sortedWith(searchMatchComparator)
        .take(limit)
        .toList()
}

private fun computeSearchMatch(normalizedQuery: String, candidate: String): SearchMatchCandidate? {
    if (normalizedQuery.length > candidate.length) {
        return null
    }
    val candidateLower = candidate.lowercase(Locale.ROOT)
    val positionsList = mutableListOf<Int>()
    var queryIndex = 0
    for (index in candidateLower.indices) {
        if (candidateLower[index] == normalizedQuery[queryIndex]) {
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
    return SearchMatchCandidate(candidate, maxStreak, positions.first(), span, sumIndices, positions)
}

private data class SubstitutionQuery(
    val prefix: String,
    val query: String,
    val suffix: String,
)

private data class SearchMatchCandidate(
    val word: String,
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

private sealed interface SuggestionEntry {
    data class SearchWord(val match: SearchMatchCandidate, val context: SubstitutionQuery) : SuggestionEntry
    data class ExCommand(val data: ExCommandCompletion.Suggestion) : SuggestionEntry
    data class Action(val data: ActionCommandCompletion.Suggestion, val prefix: String) : SuggestionEntry
    data class Option(val data: OptionCommandCompletion.Suggestion, val prefix: String) : SuggestionEntry
}
