package com.github.yelog.ideavimbettercmd.overlay

import com.intellij.openapi.diagnostic.Logger

object OptionCommandCompletion {

    private val logger = Logger.getInstance(OptionCommandCompletion::class.java)

    data class Suggestion(
        val name: String,
        val abbreviation: String?,
        val matchText: String,
    )

    private val suggestions: List<Suggestion> by lazy(LazyThreadSafetyMode.PUBLICATION) { loadSuggestions() }

    fun suggest(query: String, limit: Int): List<Suggestion> {
        if (suggestions.isEmpty()) {
            return emptyList()
        }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }

        val matches = buildList {
            for (candidate in suggestions) {
                val score = FuzzyMatcher.score(trimmed, candidate.matchText) ?: continue
                add(score to candidate)
            }
        }
        if (matches.isEmpty()) {
            return emptyList()
        }
        return matches
            .sortedWith(
                compareByDescending<Pair<Int, Suggestion>> { it.first }
                    .thenBy { it.second.name.length }
                    .thenBy { it.second.matchText.length },
            )
            .asSequence()
            .map { it.second }
            .take(limit)
            .toList()
    }

    private fun loadSuggestions(): List<Suggestion> {
        return try {
            IdeaVimFacade.collectOptions().map { option ->
                val matchText = buildString {
                    append(option.name)
                    option.abbreviation?.takeIf { it.isNotBlank() }?.let { abbrev ->
                        append(' ')
                        append(abbrev)
                    }
                }
                Suggestion(
                    name = option.name,
                    abbreviation = option.abbreviation?.takeIf { it.isNotBlank() },
                    matchText = matchText,
                )
            }.sortedBy { it.name.lowercase() }
        } catch (throwable: Throwable) {
            logger.warn("Unable to initialize option command suggestions.", throwable)
            emptyList()
        }
    }
}
