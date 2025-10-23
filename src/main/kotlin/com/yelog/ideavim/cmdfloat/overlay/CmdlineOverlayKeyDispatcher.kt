package com.yelog.ideavim.cmdfloat.overlay

import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent

class CmdlineOverlayKeyDispatcher(
    private val manager: CmdlineOverlayManager,
) : KeyEventDispatcher {

    private var suppressNextTyped = false
    private var awaitingExpressionTrigger = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.id) {
            KeyEvent.KEY_PRESSED -> handlePressed(event)
            KeyEvent.KEY_TYPED -> handleTyped(event)
            else -> false
        }
    }

    private fun handlePressed(event: KeyEvent): Boolean {
        updateExpressionTriggerState(event)
        val handled = handleEvent(event)
        if (handled) {
            suppressNextTyped = true
            event.consume()
        }
        return handled
    }

    private fun handleTyped(event: KeyEvent): Boolean {
        if (suppressNextTyped) {
            suppressNextTyped = false
            event.consume()
            return true
        }
        val handled = handleEvent(event)
        if (handled) {
            event.consume()
        }
        return handled
    }

    private fun handleEvent(event: KeyEvent): Boolean {
        if (event.isConsumed) {
            return false
        }

        if (IdeaVimFacade.isOverlaySuppressed()) {
            awaitingExpressionTrigger = false
            return false
        }

        val source = event.component
        val editor = if (source != null) {
            if (manager.isOverlayComponent(source)) {
                return false
            }
            if (!manager.ownsComponent(source)) {
                return false
            }
            if (!manager.isEditorComponent(source)) {
                return false
            }
            manager.findEditor(source)
        } else {
            manager.findEditor(null)
        }

        if (awaitingExpressionTrigger) {
            if (event.isExpressionTrigger()) {
                awaitingExpressionTrigger = false
                if (editor != null && IdeaVimFacade.isOverlayAllowed(editor, OverlayMode.EXPRESSION)) {
                    val opened = manager.handleTrigger(OverlayMode.EXPRESSION)
                    if (opened) {
                        manager.prepareExpressionReplay(needsCtrlR = false)
                        IdeaVimFacade.beginExpressionInput(editor)
                    }
                    return opened
                }
                return false
            }
            if (event.id == KeyEvent.KEY_TYPED && !event.keyChar.isISOControl()) {
                awaitingExpressionTrigger = false
            } else if (event.id == KeyEvent.KEY_PRESSED) {
                val code = event.keyCode
                if (code != KeyEvent.VK_R &&
                    code != KeyEvent.VK_SHIFT &&
                    code != KeyEvent.VK_CONTROL &&
                    code != KeyEvent.VK_ALT &&
                    code != KeyEvent.VK_META &&
                    code != KeyEvent.VK_UNDEFINED
                ) {
                    awaitingExpressionTrigger = false
                }
            }
        }

        if (editor != null && IdeaVimFacade.isAwaitingCharArgument(editor)) {
            return false
        }

        val overlayMode = event.detectOverlayMode() ?: return false
        return manager.handleTrigger(overlayMode)
    }

    private fun updateExpressionTriggerState(event: KeyEvent) {
        if (event.keyCode == KeyEvent.VK_R && event.isControlDown && !event.isAltDown && !event.isMetaDown) {
            awaitingExpressionTrigger = true
            return
        }
        if (event.keyCode == KeyEvent.VK_EQUALS || event.keyCode == KeyEvent.VK_UNDEFINED) {
            return
        }
        if (event.keyCode == KeyEvent.VK_SHIFT ||
            event.keyCode == KeyEvent.VK_CONTROL ||
            event.keyCode == KeyEvent.VK_ALT ||
            event.keyCode == KeyEvent.VK_META
        ) {
            return
        }
        awaitingExpressionTrigger = false
    }

    private fun KeyEvent.isExpressionTrigger(): Boolean {
        return when (id) {
            KeyEvent.KEY_TYPED -> keyChar == '='
            KeyEvent.KEY_PRESSED -> keyCode == KeyEvent.VK_EQUALS
            else -> false
        }
    }

    private fun KeyEvent.detectOverlayMode(): OverlayMode? {
        return when (keyChar) {
            ':' -> OverlayMode.COMMAND
            '/' -> OverlayMode.SEARCH_FORWARD
            '?' -> OverlayMode.SEARCH_BACKWARD
            else -> when (keyCode) {
                KeyEvent.VK_COLON -> OverlayMode.COMMAND
                KeyEvent.VK_SEMICOLON -> if (isShiftDown) OverlayMode.COMMAND else null
                KeyEvent.VK_SLASH -> if (isShiftDown) OverlayMode.SEARCH_BACKWARD else OverlayMode.SEARCH_FORWARD
                KeyEvent.VK_DIVIDE -> if (isShiftDown) OverlayMode.SEARCH_BACKWARD else OverlayMode.SEARCH_FORWARD
                else -> null
            }
        }
    }
}
