package com.yelog.ideavim.cmdfloat.overlay

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.diagnostic.Logger

object ActionCommandCompletion {

    private val logger = Logger.getInstance(ActionCommandCompletion::class.java)

    data class Completion(
        val actionId: String,
        val presentation: String?,
        val matchText: String,
    )

    private val completions: List<Completion> by lazy(LazyThreadSafetyMode.PUBLICATION) { loadCompletions() }

    fun suggest(query: String, limit: Int): List<Completion> {
        if (query.isBlank() || completions.isEmpty()) {
            return emptyList()
        }

        val matches = buildList {
            for (completion in completions) {
                val score = FuzzyMatcher.score(query, completion.matchText) ?: continue
                add(score to completion)
            }
        }
        if (matches.isEmpty()) {
            return emptyList()
        }
        return matches
            .sortedWith(
                compareByDescending<Pair<Int, Completion>> { it.first }
                    .thenBy { it.second.presentation?.length ?: Int.MAX_VALUE }
                    .thenBy { it.second.actionId.length },
            )
            .asSequence()
            .map { it.second }
            .take(limit)
            .toList()
    }

    private fun loadCompletions(): List<Completion> {
        return try {
            val manager = ActionManagerEx.getInstanceEx()
            fetchActionIds(manager, "").mapNotNull { actionId ->
                val action = runCatching { manager.getActionOrStub(actionId) ?: manager.getAction(actionId) }
                    .getOrElse { throwable ->
                        logger.debug("Failed to resolve action $actionId", throwable)
                        null
                    }
                buildCompletion(actionId, action)
            }.toList()
        } catch (throwable: Throwable) {
            logger.warn("Unable to initialize action command completions.", throwable)
            emptyList()
        }
    }

    private fun fetchActionIds(manager: ActionManagerEx, prefix: String): Sequence<String> {
        val sequence = readActionIdSequence(manager, prefix)
        if (sequence != null) {
            return sequence
        }
        val legacy = readLegacyActionIds(manager, prefix)
        if (legacy != null) {
            return legacy
        }
        logger.warn("Unable to enumerate action identifiers; falling back to empty sequence.")
        return emptySequence()
    }

    private fun readActionIdSequence(manager: ActionManagerEx, prefix: String): Sequence<String>? {
        val method = manager.javaClass.methods.firstOrNull { completion ->
            completion.name == "getActionIdSequence" &&
                    completion.parameterCount == 1 &&
                    completion.parameterTypes[0] == String::class.java
        } ?: return null
        val result = runCatching {
            if (!method.canAccess(manager)) {
                method.isAccessible = true
            }
            method.invoke(manager, prefix)
        }.getOrElse { throwable ->
            logger.debug("Failed to invoke getActionIdSequence on ${manager.javaClass.name}", throwable)
            return null
        }
        return when (result) {
            is Sequence<*> -> result.filterIsInstance<String>()
            is Iterable<*> -> result.asSequence().filterIsInstance<String>()
            is Array<*> -> result.asSequence().filterIsInstance<String>()
            else -> {
                logger.debug(
                    "getActionIdSequence returned unsupported type ${result?.javaClass?.name}; falling back to legacy API.",
                )
                null
            }
        }
    }

    private fun readLegacyActionIds(manager: ActionManagerEx, prefix: String): Sequence<String>? {
        val method = manager.javaClass.methods.firstOrNull { completion ->
            completion.name == "getActionIds" &&
                    completion.parameterCount == 1 &&
                    completion.parameterTypes[0] == String::class.java
        } ?: return null
        val result = runCatching {
            if (!method.canAccess(manager)) {
                method.isAccessible = true
            }
            method.invoke(manager, prefix)
        }.getOrElse { throwable ->
            logger.debug("Failed to invoke getActionIds on ${manager.javaClass.name}", throwable)
            return null
        }
        return when (result) {
            is Array<*> -> result.asSequence().filterIsInstance<String>()
            is Collection<*> -> result.asSequence().filterIsInstance<String>()
            else -> {
                logger.debug("Legacy getActionIds returned unsupported type ${result?.javaClass?.name}")
                null
            }
        }
    }

    private fun buildCompletion(actionId: String, action: AnAction?): Completion? {
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
        return Completion(
            actionId = actionId,
            presentation = presentation,
            matchText = matchText,
        )
    }
}
