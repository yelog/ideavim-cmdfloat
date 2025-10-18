package com.yelog.ideavim.cmdfloat.overlay

import com.intellij.openapi.diagnostic.Logger

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
            val providers = listOfNotNull(
                loadProviderInstance(
                    "com.maddyhome.idea.vim.vimscript.model.commands.EngineExCommandProvider",
                    "com.maddyhome.idea.vim.vimscript.model.commands.engine.EngineExCommandProvider",
                ),
                loadProviderInstance(
                    "com.maddyhome.idea.vim.vimscript.model.commands.IntellijExCommandProvider",
                    "com.maddyhome.idea.vim.vimscript.model.commands.intellij.IntellijExCommandProvider",
                ),
            )

            for (provider in providers) {
                val commands = readCommands(provider)
                if (commands.isEmpty()) {
                    continue
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

    private fun loadProviderInstance(vararg classNames: String): Any? {
        for (name in classNames) {
            if (name.isBlank()) continue
            val clazz = runCatching { Class.forName(name) }.getOrNull() ?: continue
            runCatching {
                val instanceField = clazz.getDeclaredField("INSTANCE")
                instanceField.isAccessible = true
                instanceField.get(null)
            }.onSuccess { return it }
            runCatching {
                val getter =
                    clazz.methods.firstOrNull { it.parameterCount == 0 && it.returnType == clazz && it.name == "getInstance" }
                if (getter != null) {
                    return getter.invoke(null)
                }
            }
            runCatching {
                val constructor = clazz.getDeclaredConstructor()
                constructor.isAccessible = true
                constructor.newInstance()
            }.onSuccess { return it }
        }
        return null
    }

    private fun readCommands(provider: Any): Map<String, Any?> {
        val method = runCatching {
            provider::class.java.methods.firstOrNull { it.name == "getCommands" && it.parameterCount == 0 }
        }.getOrNull() ?: return emptyMap()
        val result = runCatching { method.invoke(provider) }.getOrElse { throwable ->
            logger.warn("Failed to invoke getCommands on ${provider.javaClass.name}", throwable)
            return emptyMap()
        }
        return when (result) {
            is Map<*, *> -> result.filterStringKeys()
            else -> {
                logger.warn("Unexpected commands container from ${provider.javaClass.name}: ${result?.javaClass?.name}")
                emptyMap()
            }
        }
    }

    private fun Map<*, *>.filterStringKeys(): Map<String, Any?> {
        val output = LinkedHashMap<String, Any?>()
        for ((key, value) in this) {
            if (key is String) {
                output[key] = value
            }
        }
        return output
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
