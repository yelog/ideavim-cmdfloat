package com.yelog.ideavim.cmdfloat.overlay

object CmdlineOverlaySettings {

    private const val HIGHLIGHT_VARIABLE = "cmdfloat_highlight_completions"
    private const val SEARCH_COMPLETION_LINE_LIMIT_VARIABLE = "cmdfloat_search_completion_line_limit"
    private const val DISABLE_DEFAULT_TRIGGER_VARIABLE = "cmdfloat_disable_default_trigger"
    // 兼容旧版配置，后续可以移除
    private const val LEGACY_DISABLE_COMMAND_TRIGGER_VARIABLE = "cmdfloat_disable_default_command"
    private const val LEGACY_DISABLE_SEARCH_TRIGGER_VARIABLE = "cmdfloat_disable_default_search"
    private const val LEGACY_DISABLE_SEARCH_BACKWARD_TRIGGER_VARIABLE = "cmdfloat_disable_default_search_backward"
    private const val DEFAULT_SEARCH_COMPLETION_LINE_LIMIT = 0

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
}
