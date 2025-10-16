package com.yelog.ideavim.cmdfloat.overlay

import com.intellij.openapi.diagnostic.Logger
import com.maddyhome.idea.vim.vimscript.model.commands.EngineExCommandProvider
import com.maddyhome.idea.vim.vimscript.model.commands.ExCommandProvider
import com.maddyhome.idea.vim.vimscript.model.commands.IntellijExCommandProvider

object ExCommandCompletion {

    private val logger = Logger.getInstance(ExCommandCompletion::class.java)

    private val suggestions: List<Suggestion> by lazy(LazyThreadSafetyMode.PUBLICATION) { loadSuggestions() }

    data class Suggestion(
        val displayText: String,
        val matchText: String,
        val executionText: String,
    )

    fun suggest(query: String, limit: Int): List<Suggestion> {
        if (suggestions.isEmpty()) {
            return emptyList()
        }
        val trimmed = query.trimStart()
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
                    .thenBy { it.second.matchText.length }
                    .thenBy { it.second.executionText },
            )
            .asSequence()
            .map { it.second }
            .take(limit)
            .toList()
    }

    private fun loadSuggestions(): List<Suggestion> {
        return try {
            val unique = LinkedHashMap<String, Suggestion>()
            val providers = listOf<ExCommandProvider>(
                EngineExCommandProvider,
                IntellijExCommandProvider,
            )

            for (provider in providers) {
                val commands = runCatching { provider.getCommands() }
                    .getOrElse { throwable ->
                        logger.warn("Failed to load ex commands from ${provider.javaClass.name}", throwable)
                        emptyMap<String, Any?>()
                    }
                for (rawKey in commands.keys) {
                    val match = normalizeForMatch(rawKey)
                    if (match.isEmpty()) {
                        continue
                    }
                    val execution = normalizeForExecution(rawKey)
                    val key = match.lowercase()
                    unique.putIfAbsent(
                        key,
                        Suggestion(
                            displayText = rawKey,
                            matchText = match,
                            executionText = execution,
                        ),
                    )
                }
            }

            unique.values.sortedWith(
                compareBy<Suggestion> { it.matchText.lowercase() }
                    .thenBy { it.executionText.lowercase() },
            )
        } catch (throwable: Throwable) {
            logger.warn("Unable to initialize ex command suggestions.", throwable)
            emptyList()
        }
    }

    private fun normalizeForMatch(raw: String): String {
        // Example: bd[elete] -> bdelete
        return raw.replace("[", "").replace("]", "")
    }

    private fun normalizeForExecution(raw: String): String {
        // Example: bd[elete] -> bd
        val builder = StringBuilder(raw.length)
        var insideOptional = false
        raw.forEach { char ->
            when (char) {
                '[' -> insideOptional = true
                ']' -> insideOptional = false
                else -> if (!insideOptional) {
                    builder.append(char)
                }
            }
        }
        return builder.toString()
    }
}
