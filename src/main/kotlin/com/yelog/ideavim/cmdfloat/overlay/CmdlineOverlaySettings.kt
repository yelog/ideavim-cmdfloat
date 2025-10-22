package com.yelog.ideavim.cmdfloat.overlay

object CmdlineOverlaySettings {

    private const val HIGHLIGHT_VARIABLE = "cmdfloat_highlight_completions"
    private const val SEARCH_COMPLETION_LINE_LIMIT_VARIABLE = "cmdfloat_search_completion_line_limit"
    private const val DEFAULT_SEARCH_COMPLETION_LINE_LIMIT = 0

    fun highlightCompletionsEnabled(): Boolean {
        return IdeaVimFacade.readGlobalVariableBoolean(HIGHLIGHT_VARIABLE) ?: true
    }

    fun searchCompletionLineLimit(): Int {
        return IdeaVimFacade.readGlobalVariableInt(SEARCH_COMPLETION_LINE_LIMIT_VARIABLE)
            ?: DEFAULT_SEARCH_COMPLETION_LINE_LIMIT
    }
}
