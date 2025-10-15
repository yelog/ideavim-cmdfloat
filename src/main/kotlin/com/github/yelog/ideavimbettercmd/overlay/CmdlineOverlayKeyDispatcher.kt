package com.github.yelog.ideavimbettercmd.overlay

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent

class CmdlineOverlayKeyDispatcher(
    private val manager: CmdlineOverlayManager,
) : KeyEventDispatcher {

    private var suppressNextTyped = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.id) {
            KeyEvent.KEY_PRESSED -> handlePressed(event)
            KeyEvent.KEY_TYPED -> handleTyped(event)
            else -> false
        }
    }

    private fun handlePressed(event: KeyEvent): Boolean {
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
            return false
        }

        val source = event.component
        if (source != null) {
            if (manager.isOverlayComponent(source)) {
                return false
            }
            if (!manager.ownsComponent(source)) {
                return false
            }
        }

        val overlayMode = event.detectOverlayMode() ?: return false
        return manager.handleTrigger(overlayMode)
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
