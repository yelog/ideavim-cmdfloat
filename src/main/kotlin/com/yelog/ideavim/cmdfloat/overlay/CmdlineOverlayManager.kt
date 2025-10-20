package com.yelog.ideavim.cmdfloat.overlay

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Font
import java.awt.Point
import java.awt.Color
import java.util.*
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.roundToInt

class CmdlineOverlayManager(private val project: Project) {

    private val logger = Logger.getInstance(CmdlineOverlayManager::class.java)
    private val commandHistory = CommandHistory()
    private val searchHistory = CommandHistory()

    private var popup: JBPopup? = null
    private var overlayComponent: CmdlineOverlayPanel? = null
    private var activeMode: OverlayMode? = null
    private var activeEditor: Editor? = null

    fun handleTrigger(mode: OverlayMode): Boolean {
        if (popup != null) {
            logger.debug("Overlay already visible; ignore new trigger.")
            return true
        }

        val editor = currentEditor() ?: return false
        if (!IdeaVimFacade.isEditorCommandOverlayAllowed(editor)) {
            logger.debug("Editor not in a compatible IdeaVim mode; skip overlay display.")
            return false
        }

        val history = when (mode.historyKey) {
            HistoryKey.COMMAND -> commandHistory
            HistoryKey.SEARCH -> searchHistory
        }

        showOverlay(editor, mode, history)
        return true
    }

    fun isOverlayComponent(component: Component): Boolean {
        val overlayRoot = overlayComponent?.component ?: return false
        return component == overlayRoot || overlayRoot.isAncestorOf(component)
    }

    fun ownsComponent(component: Component): Boolean {
        val dataContext = DataManager.getInstance().getDataContext(component)
        val componentProject = CommonDataKeys.PROJECT.getData(dataContext)
        if (componentProject != null) {
            return componentProject == project
        }

        val projectFrame = WindowManager.getInstance().getFrame(project) ?: return false
        val window = SwingUtilities.getWindowAncestor(component) ?: return false
        if (projectFrame == window) {
            return true
        }
        return projectFrame.isAncestorOf(window)
    }

    fun isEditorComponent(component: Component): Boolean {
        val editor = currentEditor() ?: return false
        val editorComponent = (editor as? EditorEx)?.contentComponent ?: editor.contentComponent
        return component == editorComponent || editorComponent.isAncestorOf(component)
    }

    fun findEditor(component: Component?): Editor? {
        if (component != null) {
            val dataContext = DataManager.getInstance().getDataContext(component)
            CommonDataKeys.EDITOR.getData(dataContext)?.let { return it }
        }
        return currentEditor()
    }

    private fun showOverlay(editor: Editor, mode: OverlayMode, history: CommandHistory) {
        val searchCompletions =
            when (mode) {
                OverlayMode.SEARCH_FORWARD, OverlayMode.SEARCH_BACKWARD, OverlayMode.COMMAND -> collectSearchWords(editor)
            }
        val panel = CmdlineOverlayPanel(mode, history, editor, searchCompletions)
        panel.setSearchInitialCaretOffset(editor.caretModel.primaryCaret.offset)
        panel.onSubmit = { text ->
            if (text.isNotEmpty()) {
                history.add(text)
            }
            closePopup()
            refocusEditor(editor)
            ApplicationManager.getApplication().invokeLater {
                IdeaVimFacade.replay(editor, mode, text)
                IdeaVimFacade.commitSearchPreview(editor)
            }
        }
        panel.onCancel = {
            closePopup()
            refocusEditor(editor)
        }
        if (mode == OverlayMode.SEARCH_FORWARD || mode == OverlayMode.SEARCH_BACKWARD) {
            IdeaVimFacade.resetSearchPreview()
            panel.onSearchPreview = { text, initialOffset ->
                IdeaVimFacade.previewSearch(editor, mode, text, initialOffset)
            }
            panel.onSearchPreviewCancel = { initialOffset ->
                IdeaVimFacade.cancelSearchPreview(editor, initialOffset)
            }
        }
        if (mode == OverlayMode.COMMAND) {
            panel.onCommandPatternPreview = { pattern, initialOffset ->
                IdeaVimFacade.previewSearch(editor, OverlayMode.SEARCH_FORWARD, pattern, initialOffset)
            }
            panel.onCommandPatternCancel = { initialOffset ->
                IdeaVimFacade.cancelSearchPreview(editor, initialOffset)
            }
        }

        val editorComponent = (editor as? EditorEx)?.contentComponent ?: editor.contentComponent
        val visibleArea = (editor as? EditorEx)?.scrollingModel?.visibleArea ?: editorComponent.visibleRect

        val targetWidth = max((visibleArea.width * 0.5).roundToInt(), JBUI.scale(320))
        panel.setPreferredWidth(targetWidth)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel.component, panel.focusComponent)
            .setResizable(false)
            .setMovable(false)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelKeyEnabled(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelOnWindowDeactivation(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    closePopup(requestCancel = false)
                }
            })
            .createPopup()

        val popupSize = panel.preferredSize
        val x = visibleArea.x + ((visibleArea.width - popupSize.width) / 2)
        val y = visibleArea.y + JBUI.scale(32)
        val displayPoint = Point(max(x, visibleArea.x + JBUI.scale(16)), y)

        this.popup = popup
        this.overlayComponent = panel
        this.activeMode = mode
        this.activeEditor = editor

        popup.show(RelativePoint(editorComponent, displayPoint))
        panel.requestFocus()
    }

    private fun closePopup(requestCancel: Boolean = true) {
        val panel = overlayComponent
        val currentPopup = popup
        popup = null
        overlayComponent = null
        activeMode = null
        activeEditor = null
        panel?.notifyClosed()
        if (requestCancel) {
            currentPopup?.cancel()
        }
    }

    private fun refocusEditor(editor: Editor) {
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }

    private fun Component.isAncestorOf(child: Component): Boolean {
        var current: Component? = child
        while (current != null) {
            if (current == this) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun currentEditor(): Editor? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val editorEx = editor as? EditorEx ?: return null
        if (editorEx.isDisposed || editorEx.isViewer || editorEx.editorKind != EditorKind.MAIN_EDITOR) {
            return null
        }
        return editor
    }

    private fun collectSearchWords(editor: Editor): List<SearchCompletionWord> {
        val application = ApplicationManager.getApplication()
        val highlightEnabled = CmdlineOverlaySettings.highlightCompletionsEnabled()
        val extractor = {
            val document = editor.document
            val text = document.charsSequence
            val unique = LinkedHashMap<String, SearchCompletionWord>()
            val defaultForeground =
                editor.colorsScheme.defaultForeground
                    ?: EditorColorsManager.getInstance().globalScheme.defaultForeground
                    ?: JBColor.foreground()
            val defaultAttributes = TextAttributes().apply {
                foregroundColor = defaultForeground
                fontType = Font.PLAIN
            }
            val maxWords = MAX_SEARCH_WORDS
            var index = 0
            var wordStart = -1
            val length = text.length

            fun flushWord(endExclusive: Int) {
                if (wordStart == -1 || unique.size >= maxWords) {
                    wordStart = -1
                    return
                }
                if (endExclusive <= wordStart) {
                    wordStart = -1
                    return
                }
                val word = text.subSequence(wordStart, endExclusive).toString()
                if (word.any { it.isLetterOrDigit() }) {
                    val lowerKey = word.lowercase(Locale.ROOT)
                    val key = buildString {
                        append(lowerKey)
                        append('\u0000')
                        append(word)
                    }
                    val attributes = if (highlightEnabled) {
                        resolveWordAttributes(editor, wordStart, defaultAttributes)
                    } else {
                        copyTextAttributes(defaultAttributes)
                    }
                    val completion = SearchCompletionWord(word, attributes)
                    if (!highlightEnabled) {
                        unique.putIfAbsent(key, completion)
                    } else {
                        val existing = unique[key]
                        if (existing == null) {
                            unique[key] = completion
                        } else if (!isMeaningfulAttributes(existing.attributes, defaultAttributes) &&
                            isMeaningfulAttributes(attributes, defaultAttributes)
                        ) {
                            unique[key] = completion
                        }
                    }
                }
                wordStart = -1
            }

            while (index < length && unique.size < maxWords) {
                val ch = text[index]
                if (isWordChar(ch)) {
                    if (wordStart == -1) {
                        wordStart = index
                    }
                } else {
                    flushWord(index)
                }
                index += 1
            }
            if (unique.size < maxWords) {
                flushWord(length)
            }
            unique.values.toList()
        }
        return if (application.isReadAccessAllowed) {
            extractor()
        } else {
            application.runReadAction<List<SearchCompletionWord>> { extractor() }
        }
    }

    companion object {
        private const val MAX_SEARCH_WORDS = 5000
    }
}

data class SearchCompletionWord(
    val word: String,
    val attributes: TextAttributes,
)

private fun isMeaningfulAttributes(attributes: TextAttributes, defaultAttributes: TextAttributes): Boolean {
    val foreground = attributes.foregroundColor
    val defaultForeground = defaultAttributes.foregroundColor
    if (!colorsEqual(foreground, defaultForeground)) {
        return true
    }
    if (attributes.fontType != defaultAttributes.fontType) {
        return true
    }
    if (!colorsEqual(attributes.backgroundColor, defaultAttributes.backgroundColor)) {
        return true
    }
    if (!colorsEqual(attributes.effectColor, defaultAttributes.effectColor)) {
        return true
    }
    if (attributes.effectType != defaultAttributes.effectType) {
        return true
    }
    return false
}

private fun copyTextAttributes(source: TextAttributes): TextAttributes {
    return (source.clone() as TextAttributes)
}

private fun colorsEqual(first: Color?, second: Color?): Boolean {
    if (first === second) {
        return true
    }
    if (first == null || second == null) {
        return false
    }
    return first.rgb == second.rgb
}

private fun resolveWordAttributes(
    editor: Editor,
    offset: Int,
    defaultAttributes: TextAttributes,
): TextAttributes {
    val document = editor.document
    if (offset < 0 || offset >= document.textLength) {
        return copyTextAttributes(defaultAttributes)
    }
    val scheme = editor.colorsScheme
    val result = copyTextAttributes(defaultAttributes)

    (editor as? EditorEx)?.let { editorEx ->
        val highlighter = editorEx.highlighter
        val iterator = highlighter.createIterator(offset)
        if (!iterator.atEnd()) {
            iterator.textAttributes?.let { applyAttributes(result, it) }
        }
        collectMarkupAttributes(editorEx, offset, scheme, result)
    }

    if (result.foregroundColor == null) {
        result.foregroundColor = defaultAttributes.foregroundColor
    }
    return result
}

private fun collectMarkupAttributes(
    editor: EditorEx,
    offset: Int,
    scheme: EditorColorsScheme,
    target: TextAttributes,
) {
    processMarkup(editor.markupModel, offset, scheme, target)
    processMarkup(editor.filteredDocumentMarkupModel, offset, scheme, target)
}

private fun processMarkup(
    markup: MarkupModel,
    offset: Int,
    scheme: EditorColorsScheme,
    target: TextAttributes,
) {
    when (markup) {
        is MarkupModelEx -> {
            markup.processRangeHighlightersOverlappingWith(offset, offset + 1) { highlighter ->
                applyRangeHighlighterAttributes(highlighter, scheme, target)
                true
            }
        }

        else -> {
            for (highlighter in markup.allHighlighters) {
                if (highlighter.startOffset <= offset && offset < highlighter.endOffset) {
                    applyRangeHighlighterAttributes(highlighter, scheme, target)
                }
            }
        }
    }
}

private fun applyRangeHighlighterAttributes(
    highlighter: RangeHighlighter,
    scheme: EditorColorsScheme,
    target: TextAttributes,
) {
    val attrs = when (highlighter) {
        is RangeHighlighterEx -> highlighter.getTextAttributes(scheme)
        else -> highlighter.textAttributes
    } ?: return
    applyAttributes(target, attrs)
}

private fun applyAttributes(target: TextAttributes, source: TextAttributes) {
    source.foregroundColor?.let { target.foregroundColor = it }
    source.backgroundColor?.let { target.backgroundColor = it }
    source.effectColor?.let { target.effectColor = it }
    source.effectType?.let { target.effectType = it }
    if (source.fontType != Font.PLAIN || target.fontType == Font.PLAIN) {
        target.fontType = source.fontType
    }
}

private fun isWordChar(ch: Char): Boolean {
    return ch.isLetterOrDigit() || ch == '-' || ch == '_'
}

enum class OverlayMode(val header: String, val prefix: Char, val historyKey: HistoryKey) {
    COMMAND("CmdLine", ':', HistoryKey.COMMAND),
    SEARCH_FORWARD("Search", '/', HistoryKey.SEARCH),
    SEARCH_BACKWARD("Search", '?', HistoryKey.SEARCH),
}

enum class HistoryKey {
    COMMAND,
    SEARCH,
}
