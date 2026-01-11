package com.yelog.ideavim.cmdfloat.overlay

object CmdlineOverlaySettings {

    private const val HIGHLIGHT_VARIABLE = "cmdfloat_highlight_completions"
    private const val SEARCH_COMPLETION_LINE_LIMIT_VARIABLE = "cmdfloat_search_completion_line_limit"
    private const val DISABLE_DEFAULT_TRIGGER_VARIABLE = "cmdfloat_disable_default_trigger"
    private const val SINGLE_CHAR_ARGUMENT_KEYS_VARIABLE = "cmdfloat_single_char_argument_keys"
    private const val EXTENDED_SEARCH_KEYS_VARIABLE = "cmdfloat_extended_search_keys"
    private const val LEGACY_DISABLE_COMMAND_TRIGGER_VARIABLE = "cmdfloat_disable_default_command"
    private const val LEGACY_DISABLE_SEARCH_TRIGGER_VARIABLE = "cmdfloat_disable_default_search"
    private const val LEGACY_DISABLE_SEARCH_BACKWARD_TRIGGER_VARIABLE = "cmdfloat_disable_default_search_backward"
    private const val DEFAULT_SEARCH_COMPLETION_LINE_LIMIT = 0
    private val DEFAULT_SINGLE_CHAR_ARGUMENT_KEYS = setOf('f', 't', 'F', 'T', 'r', 'm', '\'', '`', '@', 'q', 'z', 'Z', 'g')
    private val DEFAULT_EXTENDED_SEARCH_KEYS = setOf('s', 'S')

    fun highlightCompletionsEnabled(): Boolean {
        return IdeaVimFacade.readGlobalVariableBoolean(HIGHLIGHT_VARIABLE) ?: true
    }

    fun searchCompletionLineLimit(): Int {
        return IdeaVimFacade.readGlobalVariableInt(SEARCH_COMPLETION_LINE_LIMIT_VARIABLE)
            ?: DEFAULT_SEARCH_COMPLETION_LINE_LIMIT
    }

    fun isDefaultTriggerEnabled(mode: OverlayMode): Boolean {
        if (mode == OverlayMode.EXPRESSION) {
            return true
        }

        val disableAll = IdeaVimFacade.readGlobalVariableBoolean(DISABLE_DEFAULT_TRIGGER_VARIABLE)
        if (disableAll != null) {
            return disableAll != true
        }

        val legacyDisableFlag = when (mode) {
            OverlayMode.COMMAND -> IdeaVimFacade.readGlobalVariableBoolean(LEGACY_DISABLE_COMMAND_TRIGGER_VARIABLE)
            OverlayMode.SEARCH_FORWARD -> IdeaVimFacade.readGlobalVariableBoolean(LEGACY_DISABLE_SEARCH_TRIGGER_VARIABLE)
            OverlayMode.SEARCH_BACKWARD -> IdeaVimFacade.readGlobalVariableBoolean(LEGACY_DISABLE_SEARCH_BACKWARD_TRIGGER_VARIABLE)
            OverlayMode.EXPRESSION -> null
        }
        return legacyDisableFlag != true
    }

    fun singleCharArgumentKeys(): Set<Char> {
        return readCharKeys(SINGLE_CHAR_ARGUMENT_KEYS_VARIABLE, DEFAULT_SINGLE_CHAR_ARGUMENT_KEYS)
    }

    fun extendedSearchKeys(): Set<Char> {
        return readCharKeys(EXTENDED_SEARCH_KEYS_VARIABLE, DEFAULT_EXTENDED_SEARCH_KEYS)
    }

    private fun readCharKeys(variableName: String, defaults: Set<Char>): Set<Char> {
        val rawValues = IdeaVimFacade.readGlobalVariableStrings(variableName) ?: return defaults
        if (rawValues.isEmpty()) {
            return defaults
        }
        val parsed = rawValues.mapNotNull { value ->
            val trimmed = value.trim()
            when {
                trimmed.length == 1 -> trimmed[0]
                trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length == 3 -> trimmed[1]
                else -> null
            }
        }
        return parsed.toSet().ifEmpty { defaults }
    }
}
