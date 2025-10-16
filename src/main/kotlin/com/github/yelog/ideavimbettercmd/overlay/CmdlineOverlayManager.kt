package com.github.yelog.ideavimbettercmd.overlay

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.util.LinkedHashMap
import java.util.Locale
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

    private fun showOverlay(editor: Editor, mode: OverlayMode, history: CommandHistory) {
        val searchCandidates = when (mode) {
            OverlayMode.SEARCH_FORWARD, OverlayMode.SEARCH_BACKWARD, OverlayMode.COMMAND -> collectSearchWords(editor)
        }
        val panel = CmdlineOverlayPanel(mode, history, editor, searchCandidates)
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

    private fun collectSearchWords(editor: Editor): List<String> {
        val application = ApplicationManager.getApplication()
        val extractor = {
            val document = editor.document
            val text = document.charsSequence
            val unique = LinkedHashMap<String, String>()
            val buffer = StringBuilder()

            fun flush() {
                if (buffer.isEmpty()) {
                    return
                }
                if (unique.size >= MAX_SEARCH_WORDS) {
                    buffer.setLength(0)
                    return
                }
                val word = buffer.toString()
                buffer.setLength(0)
                if (word.any { it.isLetterOrDigit() }) {
                    val key = word.lowercase(Locale.ROOT)
                    unique.putIfAbsent(key, word)
                }
            }

            for (char in text) {
                if (char.isLetterOrDigit() || char == '-' || char == '_') {
                    buffer.append(char)
                } else {
                    flush()
                }
                if (unique.size >= MAX_SEARCH_WORDS) {
                    break
                }
            }
            flush()
            unique.values.toList()
        }
        return if (application.isReadAccessAllowed) {
            extractor()
        } else {
            application.runReadAction<List<String>> { extractor() }
        }
    }

    companion object {
        private const val MAX_SEARCH_WORDS = 5000
    }
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
