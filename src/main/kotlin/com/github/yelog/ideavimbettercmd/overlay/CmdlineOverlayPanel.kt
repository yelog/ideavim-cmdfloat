package com.github.yelog.ideavimbettercmd.overlay

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.ActionMap
import javax.swing.JComponent
import javax.swing.InputMap
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.border.TitledBorder
import javax.swing.BorderFactory
import javax.swing.SwingConstants

class CmdlineOverlayPanel(
    private val mode: OverlayMode,
    history: CommandHistory,
) {

    val component: JComponent
    val focusComponent: JBTextField
    val preferredSize: Dimension
        get() = component.preferredSize

    var onSubmit: ((String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val historySnapshot = history.snapshot()
    private var historyIndex = -1
    private var draftText: String = ""
    private var updatingFromHistory = false

    init {
        val scheme = EditorColorsManager.getInstance().globalScheme
        focusComponent = createTextField(scheme)

        val inputPanel = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 10, 10, 10)
            add(createPrefixLabel(), java.awt.BorderLayout.WEST)
            add(focusComponent, java.awt.BorderLayout.CENTER)
        }

        val contentPanel = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
            isOpaque = true
            background = scheme.toOverlayBackground()
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
                JBUI.Borders.empty(18, 14, 14, 14)
            )

            add(inputPanel, java.awt.BorderLayout.CENTER)
        }

        component = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4)
            add(contentPanel, java.awt.BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(400), JBUI.scale(136))
        }

        installActions(focusComponent)
    }

    fun requestFocus() {
        focusComponent.selectAll()
        focusComponent.requestFocusInWindow()
    }

    fun setPreferredWidth(width: Int) {
        component.preferredSize = Dimension(width, JBUI.scale(136))
    }

    private fun createTextField(scheme: EditorColorsScheme): JBTextField {
        return JBTextField().apply {
            border = JBUI.Borders.empty(6, 10, 6, 10)
            background = scheme.toOverlayInputBackground()
            foreground = scheme.defaultForeground ?: JBColor.foreground()
            caretColor = foreground
            font = JBFont.regular().deriveFont(Font.PLAIN, JBUI.scale(14f))
            preferredSize = Dimension(JBUI.scale(200), JBUI.scale(38))
            minimumSize = Dimension(JBUI.scale(200), JBUI.scale(38))
            putClientProperty("JComponent.roundRect", java.lang.Boolean.TRUE)
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = resetHistoryIfNeeded()
                override fun removeUpdate(event: DocumentEvent) = resetHistoryIfNeeded()
                override fun changedUpdate(event: DocumentEvent) = resetHistoryIfNeeded()

                private fun resetHistoryIfNeeded() {
                    if (!updatingFromHistory) {
                        historyIndex = -1
                    }
                }
            })
        }
    }

    private fun createPrefixLabel(): JBLabel {
        return JBLabel(mode.prefix.toString(), SwingConstants.CENTER).apply {
            border = JBUI.Borders.empty(0, 0, 0, 6)
            foreground = focusComponent.foreground
            font = JBFont.label().deriveFont(Font.BOLD, JBUI.scale(16f))
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(38))
        }
    }

    private fun installActions(textField: JBTextField) {
        textField.addActionListener {
            onSubmit?.invoke(textField.text)
        }

        val inputMap: InputMap = textField.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap: ActionMap = textField.actionMap

        inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), ACTION_CANCEL)
        actionMap.put(ACTION_CANCEL, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                onCancel?.invoke()
            }
        })

        inputMap.put(KeyStroke.getKeyStroke("UP"), ACTION_HISTORY_PREVIOUS)
        actionMap.put(ACTION_HISTORY_PREVIOUS, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                navigateHistory(previous = true, textField)
            }
        })

        inputMap.put(KeyStroke.getKeyStroke("DOWN"), ACTION_HISTORY_NEXT)
        actionMap.put(ACTION_HISTORY_NEXT, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                navigateHistory(previous = false, textField)
            }
        })
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
        updatingFromHistory = true
        textField.text = value
        textField.caretPosition = value.length
        updatingFromHistory = false
    }

    private fun EditorColorsScheme.toOverlayBackground(): JBColor {
        val base = this.defaultBackground
        if (base != null) {
            val awt = java.awt.Color(base.red, base.green, base.blue, 235)
            return JBColor(awt, awt)
        }
        return if (UIUtil.isUnderDarcula()) JBColor(0x2F3136, 0x2F3136) else JBColor(0xF6F7FB, 0xF6F7FB)
    }

    private fun EditorColorsScheme.toOverlayInputBackground(): JBColor {
        return JBColor.namedColor("TextField.background", JBColor(0xFFFFFF, 0x3B3F45))
    }

    private fun EditorColorsScheme.toOverlayBorder(): JBColor {
        return JBColor.namedColor("Component.borderColor", JBColor(0xC9CDD8, 0x4C5057))
    }

    companion object {
        private const val ACTION_CANCEL = "ideavim.cmdline.cancel"
        private const val ACTION_HISTORY_PREVIOUS = "ideavim.cmdline.history.previous"
        private const val ACTION_HISTORY_NEXT = "ideavim.cmdline.history.next"
    }
}
