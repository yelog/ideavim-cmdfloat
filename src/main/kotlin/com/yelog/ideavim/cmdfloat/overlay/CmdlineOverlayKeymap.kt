package com.yelog.ideavim.cmdfloat.overlay

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.ArrayList
import java.util.HashSet
import javax.swing.KeyStroke

object CmdlineOverlayKeymap {

    private const val PREVIOUS_VARIABLE = "cmdfloat_completion_prev_keys"
    private const val NEXT_VARIABLE = "cmdfloat_completion_next_keys"

    data class NavigationBindings(
        val previous: List<KeyStroke>,
        val next: List<KeyStroke>,
    )

    fun completionNavigationBindings(): NavigationBindings {
        val previous = readKeyStrokes(PREVIOUS_VARIABLE)
        val next = readKeyStrokes(NEXT_VARIABLE)
        val defaults = defaultBindings()
        return NavigationBindings(
            previous = previous.ifEmpty { defaults.previous },
            next = next.ifEmpty { defaults.next },
        )
    }

    private fun defaultBindings(): NavigationBindings {
        return NavigationBindings(
            previous = listOf(
                KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
                KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK),
            ),
            next = listOf(
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
                KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0),
            ),
        )
    }

    private fun readKeyStrokes(variableName: String): List<KeyStroke> {
        val rawValues = IdeaVimFacade.readGlobalVariableStrings(variableName) ?: return emptyList()
        if (rawValues.isEmpty()) {
            return emptyList()
        }
        val seen = HashSet<String>()
        val parsed = ArrayList<KeyStroke>()
        for (value in rawValues) {
            val stroke = KeyStrokeParser.parse(value) ?: continue
            val key = buildKey(stroke)
            if (seen.add(key)) {
                parsed.add(stroke)
            }
        }
        return parsed
    }

    private fun buildKey(keyStroke: KeyStroke): String {
        val code = keyStroke.keyCode
        val modifiers = keyStroke.modifiers
        val keyChar = if (keyStroke.keyCode == KeyEvent.VK_UNDEFINED) keyStroke.keyChar.code else -1
        return "$code:$modifiers:$keyChar"
    }

    private object KeyStrokeParser {

        fun parse(spec: String): KeyStroke? {
            val trimmed = spec.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            val unquoted = trimmed.removeSurroundingQuotes()
            if (unquoted.isEmpty()) {
                return null
            }
            if (unquoted.startsWith("<") && unquoted.endsWith(">") && unquoted.length > 2) {
                val inner = unquoted.substring(1, unquoted.length - 1)
                parseComposite(inner)?.let { return it }
            }
            parseComposite(unquoted)?.let { return it }
            val normalized = normalizeDescriptor(unquoted)
            return KeyStroke.getKeyStroke(normalized)
        }

        private fun parseComposite(descriptor: String): KeyStroke? {
            val tokens = descriptor.split(Regex("[-+\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) {
                return null
            }
            var modifiers = 0
            var keyToken: String? = null
            for (token in tokens) {
                when (token.lowercase()) {
                    "c", "ctrl", "control" -> modifiers = modifiers or InputEvent.CTRL_DOWN_MASK
                    "s", "shift" -> modifiers = modifiers or InputEvent.SHIFT_DOWN_MASK
                    "a", "alt", "option" -> modifiers = modifiers or InputEvent.ALT_DOWN_MASK
                    "m", "meta", "cmd", "command" -> modifiers = modifiers or InputEvent.META_DOWN_MASK
                    else -> keyToken = token
                }
            }
            val key = keyToken ?: return null
            val keyCode = lookupKeyCode(key)
            if (keyCode != null) {
                return KeyStroke.getKeyStroke(keyCode, modifiers)
            }
            if (key.length == 1) {
                val code = KeyEvent.getExtendedKeyCodeForChar(key[0].code)
                if (code != KeyEvent.VK_UNDEFINED) {
                    return KeyStroke.getKeyStroke(code, modifiers)
                }
            }
            return null
        }

        private fun normalizeDescriptor(descriptor: String): String {
            val tokens = descriptor.split(Regex("[-+\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) {
                return descriptor
            }
            val modifierTokens = tokens.dropLast(1).joinToString(" ") { token ->
                when (token.lowercase()) {
                    "c", "ctrl", "control" -> "ctrl"
                    "s", "shift" -> "shift"
                    "a", "alt", "option" -> "alt"
                    "m", "meta", "cmd", "command" -> "meta"
                    else -> token.lowercase()
                }
            }.trim()
            val keyToken = tokens.last()
            val keyPart = if (keyToken.length == 1) keyToken.uppercase() else keyToken.uppercase()
            return buildString {
                if (modifierTokens.isNotEmpty()) {
                    append(modifierTokens)
                    append(' ')
                }
                append(keyPart)
            }
        }

        private fun lookupKeyCode(token: String): Int? {
            val lower = token.lowercase()
            KEY_NAMES[lower]?.let { return it }
            if (lower.startsWith("f") && lower.length in 2..3) {
                val number = lower.substring(1).toIntOrNull()
                if (number != null && number in 1..24) {
                    return KeyEvent.VK_F1 + (number - 1)
                }
            }
            return null
        }

        private fun String.removeSurroundingQuotes(): String {
            if (length >= 2) {
                val first = first()
                val last = last()
                if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                    return substring(1, length - 1)
                }
            }
            return this
        }

        private val KEY_NAMES = mapOf(
            "up" to KeyEvent.VK_UP,
            "down" to KeyEvent.VK_DOWN,
            "left" to KeyEvent.VK_LEFT,
            "right" to KeyEvent.VK_RIGHT,
            "tab" to KeyEvent.VK_TAB,
            "enter" to KeyEvent.VK_ENTER,
            "return" to KeyEvent.VK_ENTER,
            "cr" to KeyEvent.VK_ENTER,
            "space" to KeyEvent.VK_SPACE,
            "esc" to KeyEvent.VK_ESCAPE,
            "escape" to KeyEvent.VK_ESCAPE,
            "bs" to KeyEvent.VK_BACK_SPACE,
            "backspace" to KeyEvent.VK_BACK_SPACE,
            "delete" to KeyEvent.VK_DELETE,
            "del" to KeyEvent.VK_DELETE,
            "home" to KeyEvent.VK_HOME,
            "end" to KeyEvent.VK_END,
            "pageup" to KeyEvent.VK_PAGE_UP,
            "pagedown" to KeyEvent.VK_PAGE_DOWN,
            "pgup" to KeyEvent.VK_PAGE_UP,
            "pgdn" to KeyEvent.VK_PAGE_DOWN,
            "insert" to KeyEvent.VK_INSERT,
            "minus" to KeyEvent.VK_MINUS,
            "plus" to KeyEvent.VK_PLUS
        )
    }
}
