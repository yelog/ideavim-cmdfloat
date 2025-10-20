package com.yelog.ideavim.cmdfloat.overlay

object CmdlineOverlaySettings {

    private const val HIGHLIGHT_VARIABLE = "cmdfloat_highlight_completions"

    fun highlightCompletionsEnabled(): Boolean {
        return IdeaVimFacade.readGlobalVariableBoolean(HIGHLIGHT_VARIABLE) ?: true
    }
}
