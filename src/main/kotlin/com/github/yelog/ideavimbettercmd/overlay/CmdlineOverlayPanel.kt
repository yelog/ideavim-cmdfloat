package com.github.yelog.ideavimbettercmd.overlay

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.github.yelog.ideavimbettercmd.overlay.OptionCommandCompletion
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
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
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.border.TitledBorder
import javax.swing.BorderFactory
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
    private var searchCancelled = false
    private var searchInitialCaretOffset: Int = -1
    private var commandPreviewActive = false

    init {
        val scheme = EditorColorsManager.getInstance().globalScheme
        focusComponent = createTextField(scheme)
        suggestionSupport = when (mode) {
            OverlayMode.COMMAND -> CommandSuggestionSupport(focusComponent, scheme)
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
            add(focusComponent, java.awt.BorderLayout.CENTER)
        }

        val contentPanel = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
            isOpaque = true
            background = scheme.toOverlayInputBackground()
            putClientProperty("JComponent.roundRect", java.lang.Boolean.TRUE)

            val titleBorder = BorderFactory.createTitledBorder(
                JBUI.Borders.customLine(scheme.toOverlayBorder(), 1),
                mode.header,
                TitledBorder.CENTER,
                TitledBorder.TOP,
                JBFont.label().deriveFont(Font.BOLD),
                focusComponent.foreground
            )

            border = JBUI.Borders.compound(
                titleBorder,
                JBUI.Borders.empty(2, 0, 2, 0)
            )

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
        component.preferredSize = Dimension(preferredWidth, basePreferredHeight)
        component.revalidate()
        component.repaint()
        component.parent?.revalidate()
        component.parent?.repaint()
    }

    private fun handleDocumentChange(textField: JBTextField) {
        if (programmaticUpdate) {
            return
        }
        historyIndex = -1
        suggestionSupport?.onUserInput(textField.text)
        triggerSearchPreview(textField.text)
        if (mode == OverlayMode.COMMAND) {
            updateCommandPatternPreview(textField.text)
        }
    }

    private fun createTextField(scheme: EditorColorsScheme): JBTextField {
        val inputHeight = JBUI.scale(28)
        return JBTextField().apply {
            border = JBUI.Borders.empty()
            background = scheme.toOverlayInputBackground()
            foreground = scheme.defaultForeground ?: JBColor.foreground()
            caretColor = foreground
            font = JBFont.regular().deriveFont(Font.PLAIN, JBUI.scale(14f))
            preferredSize = Dimension(JBUI.scale(200), inputHeight)
            minimumSize = Dimension(JBUI.scale(200), inputHeight)
            margin = JBUI.insets(0, 1, 0, 6)
            putClientProperty("JComponent.roundRect", java.lang.Boolean.TRUE)
            focusTraversalKeysEnabled = false
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = handleDocumentChange(this@apply)
                override fun removeUpdate(event: DocumentEvent) = handleDocumentChange(this@apply)
                override fun changedUpdate(event: DocumentEvent) = handleDocumentChange(this@apply)
            })
        }
    }

    private fun createPrefixLabel(): JBLabel {
        val isSearchMode = mode == OverlayMode.SEARCH_FORWARD || mode == OverlayMode.SEARCH_BACKWARD
        val inputHeight = JBUI.scale(28)
        val label = JBLabel().apply {
            border = JBUI.Borders.empty(0, 6, 0, 0)
            foreground = focusComponent.foreground
            font = JBFont.label().deriveFont(Font.BOLD, JBUI.scale(16f))
            preferredSize = Dimension(JBUI.scale(if (isSearchMode) 26 else 24), inputHeight)
            isOpaque = true
            background = focusComponent.background
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

    private fun EditorColorsScheme.toOverlayInputBackground(): JBColor {
        return JBColor.namedColor("TextField.background", JBColor(0xFFFFFF, 0x3B3F45))
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
        private val model = CollectionListModel<String>()
        private val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(20)
            border = JBUI.Borders.empty(0, 6, 0, 6)
            background = textField.background
            foreground = textField.foreground
            putClientProperty("JComponent.roundRect", java.lang.Boolean.TRUE)
        }

        private val scrollPane = JBScrollPane(list).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 4, 0)
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(12)
            background = scheme.toOverlayInputBackground()
        }

        private val maxVisibleRows = 8
        private val maxSuggestions = 50
        private var popup: JBPopup? = null
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
        }

        override fun install(parent: JComponent) {
            parentComponent = parent
        }

        override fun onUserInput(content: String) {
            if (candidates.isEmpty()) {
                dispose()
                return
            }
            val query = content.trim()
            if (query.isEmpty()) {
                dispose()
                return
            }
            val matches = collectMatches(query)
            if (matches.isEmpty()) {
                dispose()
            } else {
                updateSuggestions(matches.map { it.word })
            }
        }

        private fun collectMatches(query: String): List<SearchMatchCandidate> {
            val normalized = query.lowercase(Locale.ROOT)
            if (normalized.isEmpty()) {
                return emptyList()
            }
            return candidates.asSequence()
                .mapNotNull { candidate -> matchCandidate(normalized, candidate) }
                .sortedWith(matchComparator)
                .take(maxSuggestions)
                .toList()
        }

        private fun matchCandidate(normalizedQuery: String, candidate: String): SearchMatchCandidate? {
            if (normalizedQuery.length > candidate.length) {
                return null
            }
            val candidateLower = candidate.lowercase(Locale.ROOT)
            val positions = mutableListOf<Int>()
            var queryIndex = 0
            for (index in candidateLower.indices) {
                if (candidateLower[index] == normalizedQuery[queryIndex]) {
                    positions.add(index)
                    queryIndex += 1
                    if (queryIndex == normalizedQuery.length) {
                        break
                    }
                }
            }
            if (queryIndex != normalizedQuery.length || positions.isEmpty()) {
                return null
            }
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
            return SearchMatchCandidate(candidate, maxStreak, positions.first(), span, sumIndices)
        }

        private val matchComparator = compareByDescending<SearchMatchCandidate> { it.maxConsecutive }
            .thenBy { it.firstIndex }
            .thenBy { it.span }
            .thenBy { it.sumIndices }
            .thenBy { it.word.length }
            .thenBy { it.word.lowercase(Locale.ROOT) }
            .thenBy { it.word }

        private fun updateSuggestions(words: List<String>) {
            model.replaceAll(words)
            if (model.isEmpty) {
                dispose()
                return
            }
            list.clearSelection()
            val visibleRows = min(model.size, maxVisibleRows)
            val rowHeight = list.fixedCellHeight.takeIf { it > 0 }
                ?: list.preferredSize.height.coerceAtLeast(JBUI.scale(20))
            val parent = parentComponent ?: return dispose()

            val parentWidth = parent.width.takeIf { it > 0 } ?: parent.preferredSize.width
            val width = parentWidth.coerceAtLeast(JBUI.scale(200))
            val height = visibleRows * rowHeight + JBUI.scale(4)
            currentHeight = height
            val size = Dimension(width, height)
            scrollPane.preferredSize = size
            scrollPane.minimumSize = size
            scrollPane.maximumSize = size

            val popup = ensurePopup()
            popup.setSize(size)
            popup.setLocation(RelativePoint(parent, Point(0, parent.height)).screenPoint)
            val selectedIndex = list.selectedIndex
            if (selectedIndex >= 0) {
                list.ensureIndexIsVisible(selectedIndex)
            }
            scrollPane.revalidate()
            scrollPane.repaint()
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
            setTextProgrammatically(textField, value)
            dispose()
            return true
        }

        override fun updatePopupWidth(width: Int) {
            val popup = popup ?: return
            if (popup.isDisposed) {
                return
            }
            val size = Dimension(width, currentHeight)
            popup.setSize(size)
            scrollPane.preferredSize = size
            scrollPane.minimumSize = size
            scrollPane.maximumSize = size
            parentComponent?.let {
                popup.setLocation(RelativePoint(it, Point(0, it.height)).screenPoint)
            }
        }

        override fun dispose() {
            popup?.cancel()
            popup = null
            model.replaceAll(emptyList())
            list.clearSelection()
        }

        private fun ensurePopup(): JBPopup {
            var current = popup
            if (current == null || current.isDisposed) {
                current = JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(scrollPane, list)
                    .setFocusable(false)
                    .setRequestFocus(false)
                    .setCancelKeyEnabled(false)
                    .setCancelOnClickOutside(false)
                    .setCancelOnOtherWindowOpen(true)
                    .setCancelOnWindowDeactivation(true)
                    .setMovable(false)
                    .setResizable(false)
                    .createPopup()
                popup = current
                parentComponent?.let {
                    current.show(RelativePoint(it, Point(0, it.height)))
                }
            }
            return current
        }

        private fun isActive(): Boolean = popup?.let { !it.isDisposed && model.size > 0 } == true
    }

    private inner class CommandSuggestionSupport(
        private val textField: JBTextField,
        private val scheme: EditorColorsScheme,
    ) : SuggestionSupport {
        private val model = CollectionListModel<SuggestionEntry>()
        private val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(20)
            border = JBUI.Borders.empty(0, 6, 0, 6)
            background = textField.background
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
                    when (value) {
                        is SuggestionEntry.ExCommand -> {
                            append(value.data.displayText, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        }
                        is SuggestionEntry.Action -> {
                            val presentation = value.data.presentation
                            if (!presentation.isNullOrBlank()) {
                                append(presentation, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                                append("  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                                append(value.data.actionId, SimpleTextAttributes.GRAY_ATTRIBUTES)
                            } else {
                                append(value.data.actionId, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                            }
                        }
                        is SuggestionEntry.Option -> {
                            append(value.data.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                            value.data.abbreviation?.let { abbrev ->
                                append("  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                                append(abbrev, SimpleTextAttributes.GRAY_ATTRIBUTES)
                            }
                        }
                    }
                }
            }
        }

        private val scrollPane = JBScrollPane(list).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 4, 0)
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(12)
            background = scheme.toOverlayInputBackground()
        }

        private val maxVisibleRows = 8
        private var popup: JBPopup? = null
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
        }

        override fun install(parent: JComponent) {
            parentComponent = parent
        }

        override fun onUserInput(content: String) {
            val actionQuery = parseActionQuery(content)
            if (actionQuery != null) {
                val suggestions = ActionCommandCompletion.suggest(actionQuery.query, maxVisibleRows)
                if (suggestions.isEmpty()) {
                    dispose()
                } else {
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
                    updateSuggestions(suggestions.map { SuggestionEntry.Option(it, optionQuery.prefix) })
                }
                return
            }
            val suggestions = ExCommandCompletion.suggest(content, maxVisibleRows)
            if (suggestions.isEmpty()) {
                dispose()
            } else {
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
            when (val suggestion = model.getElementAt(index)) {
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
            dispose()
            return true
        }

        private fun updateSuggestions(suggestions: List<SuggestionEntry>) {
            model.replaceAll(suggestions)
            if (model.isEmpty) {
                dispose()
                return
            }
            if (list.selectedIndex >= model.size) {
                list.clearSelection()
            }
            val visibleRows = min(model.size, maxVisibleRows)
            val rowHeight = list.fixedCellHeight.takeIf { it > 0 } ?: list.preferredSize.height.coerceAtLeast(JBUI.scale(20))
            val parent = parentComponent ?: return dispose()

            val parentWidth = parent.width.takeIf { it > 0 } ?: parent.preferredSize.width
            val width = parentWidth.coerceAtLeast(JBUI.scale(200))
            val height = visibleRows * rowHeight + JBUI.scale(4)
            currentHeight = height
            val size = Dimension(width, height)
            scrollPane.preferredSize = size
            scrollPane.minimumSize = size
            scrollPane.maximumSize = size

            val popup = ensurePopup()
            popup.setSize(size)
            popup.setLocation(
                RelativePoint(parent, Point(0, parent.height)).screenPoint
            )
            val selectedIndex = list.selectedIndex
            if (selectedIndex >= 0) {
                list.ensureIndexIsVisible(selectedIndex)
            }
            scrollPane.revalidate()
            scrollPane.repaint()
        }

        private fun isActive(): Boolean = popup?.let { !it.isDisposed && model.size > 0 } == true

        private fun ensurePopup(): JBPopup {
            var current = popup
            if (current == null || current.isDisposed) {
                current = JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(scrollPane, list)
                    .setFocusable(false)
                    .setRequestFocus(false)
                    .setCancelKeyEnabled(false)
                    .setCancelOnClickOutside(false)
                    .setCancelOnOtherWindowOpen(true)
                    .setCancelOnWindowDeactivation(true)
                    .setMovable(false)
                    .setResizable(false)
                    .createPopup()
                popup = current
                parentComponent?.let {
                    current.show(RelativePoint(it, Point(0, it.height)))
                }
            }
            return current
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
            val popup = popup ?: return
            if (popup.isDisposed) {
                return
            }
            val size = Dimension(width, currentHeight)
            popup.setSize(size)
            scrollPane.preferredSize = size
            scrollPane.minimumSize = size
            scrollPane.maximumSize = size
            parentComponent?.let {
                popup.setLocation(RelativePoint(it, Point(0, it.height)).screenPoint)
            }
        }

        override fun dispose() {
            popup?.cancel()
            popup = null
            model.replaceAll(emptyList())
            list.clearSelection()
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
    }
}

private data class SearchMatchCandidate(
    val word: String,
    val maxConsecutive: Int,
    val firstIndex: Int,
    val span: Int,
    val sumIndices: Int,
)

private data class ActionQuery(val prefix: String, val query: String)
private data class OptionQuery(val prefix: String, val query: String)

private sealed interface SuggestionEntry {
    data class ExCommand(val data: ExCommandCompletion.Suggestion) : SuggestionEntry
    data class Action(val data: ActionCommandCompletion.Suggestion, val prefix: String) : SuggestionEntry
    data class Option(val data: OptionCommandCompletion.Suggestion, val prefix: String) : SuggestionEntry
}
