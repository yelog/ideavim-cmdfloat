package com.yelog.ideavim.cmdfloat.overlay

import com.intellij.openapi.diagnostic.Logger

object OptionCommandCompletion {

    private val logger = Logger.getInstance(OptionCommandCompletion::class.java)

    data class Completion(
        val name: String,
        val abbreviation: String?,
        val matchText: String,
    )

    private val completions: List<Completion> by lazy(LazyThreadSafetyMode.PUBLICATION) { loadCompletions() }

    fun suggest(query: String, limit: Int): List<Completion> {
        if (completions.isEmpty()) {
            return emptyList()
        }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }

        val matches = buildList {
            for (completion in completions) {
                val score = FuzzyMatcher.score(trimmed, completion.matchText) ?: continue
                add(score to completion)
            }
        }
        if (matches.isEmpty()) {
            return emptyList()
        }
        return matches
            .sortedWith(
                compareByDescending<Pair<Int, Completion>> { it.first }
                    .thenBy { it.second.name.length }
                    .thenBy { it.second.matchText.length },
            )
            .asSequence()
            .map { it.second }
            .take(limit)
            .toList()
    }

    private fun loadCompletions(): List<Completion> {
        return try {
            IdeaVimFacade.collectOptions().map { option ->
                val matchText = buildString {
                    append(option.name)
                    option.abbreviation?.takeIf { it.isNotBlank() }?.let { abbrev ->
                        append(' ')
                        append(abbrev)
                    }
                }
                Completion(
                    name = option.name,
                    abbreviation = option.abbreviation?.takeIf { it.isNotBlank() },
                    matchText = matchText,
                )
            }.sortedBy { it.name.lowercase() }
        } catch (throwable: Throwable) {
            logger.warn("Unable to initialize option command completions.", throwable)
            emptyList()
        }
    }
}
