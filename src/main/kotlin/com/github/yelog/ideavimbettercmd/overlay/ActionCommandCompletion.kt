package com.github.yelog.ideavimbettercmd.overlay

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.diagnostic.Logger

object ActionCommandCompletion {

    private val logger = Logger.getInstance(ActionCommandCompletion::class.java)

    data class Suggestion(
        val actionId: String,
        val presentation: String?,
        val matchText: String,
    )

    private val suggestions: List<Suggestion> by lazy(LazyThreadSafetyMode.PUBLICATION) { loadSuggestions() }

    fun suggest(query: String, limit: Int): List<Suggestion> {
        if (query.isBlank() || suggestions.isEmpty()) {
            return emptyList()
        }

        val matches = buildList {
            for (candidate in suggestions) {
                val score = FuzzyMatcher.score(query, candidate.matchText) ?: continue
                add(score to candidate)
            }
        }
        if (matches.isEmpty()) {
            return emptyList()
        }
        return matches
            .sortedWith(
                compareByDescending<Pair<Int, Suggestion>> { it.first }
                    .thenBy { it.second.presentation?.length ?: Int.MAX_VALUE }
                    .thenBy { it.second.actionId.length },
            )
            .asSequence()
            .map { it.second }
            .take(limit)
            .toList()
    }

    private fun loadSuggestions(): List<Suggestion> {
        return try {
            val manager = ActionManagerEx.getInstanceEx()
            val ids = manager.getActionIds("")
            ids.mapNotNull { actionId ->
                val action = runCatching { manager.getActionOrStub(actionId) ?: manager.getAction(actionId) }
                    .getOrElse { throwable ->
                        logger.debug("Failed to resolve action $actionId", throwable)
                        null
                    }
                buildSuggestion(actionId, action)
            }
        } catch (throwable: Throwable) {
            logger.warn("Unable to initialize action command suggestions.", throwable)
            emptyList()
        }
    }

    private fun buildSuggestion(actionId: String, action: AnAction?): Suggestion? {
        val presentation = action?.templatePresentation?.text?.takeIf { it.isNotBlank() }
        val matchText = buildString {
            append(actionId)
            if (!presentation.isNullOrBlank()) {
                append(' ')
                append(presentation)
            }
        }
        if (matchText.isBlank()) {
            return null
        }
        return Suggestion(
            actionId = actionId,
            presentation = presentation,
            matchText = matchText,
        )
    }
}
