package com.yelog.ideavim.cmdfloat.overlay

import java.util.concurrent.ConcurrentHashMap

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

    // Cache TTL in milliseconds (5 seconds)
    private const val CACHE_TTL_MS = 5000L

    private data class CachedValue<T>(val value: T, val timestamp: Long)

    private val booleanCache = ConcurrentHashMap<String, CachedValue<Boolean>>()
    private val intCache = ConcurrentHashMap<String, CachedValue<Int>>()
    private val stringListCache = ConcurrentHashMap<String, CachedValue<List<String>?>>()
    private val charSetCache = ConcurrentHashMap<String, CachedValue<Set<Char>>>()

    private fun <T> getCached(cache: MutableMap<String, CachedValue<T>>, key: String, fetcher: () -> T): T {
        val now = System.currentTimeMillis()
        val cached = cache[key]
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.value
        }
        val value = fetcher()
        cache[key] = CachedValue(value, now)
        return value
    }

    fun highlightCompletionsEnabled(): Boolean {
        return getCached(booleanCache, HIGHLIGHT_VARIABLE) {
            IdeaVimFacade.readGlobalVariableBoolean(HIGHLIGHT_VARIABLE) ?: true
        }
    }

    fun searchCompletionLineLimit(): Int {
        return getCached(intCache, SEARCH_COMPLETION_LINE_LIMIT_VARIABLE) {
            IdeaVimFacade.readGlobalVariableInt(SEARCH_COMPLETION_LINE_LIMIT_VARIABLE)
                ?: DEFAULT_SEARCH_COMPLETION_LINE_LIMIT
        }
    }

    fun isDefaultTriggerEnabled(mode: OverlayMode): Boolean {
        val cacheKey = "trigger_${mode.name}"
        return getCached(booleanCache, cacheKey) {
            computeIsDefaultTriggerEnabled(mode)
        }
    }

    private fun computeIsDefaultTriggerEnabled(mode: OverlayMode): Boolean {
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
        return getCached(charSetCache, SINGLE_CHAR_ARGUMENT_KEYS_VARIABLE) {
            readCharKeys(SINGLE_CHAR_ARGUMENT_KEYS_VARIABLE, DEFAULT_SINGLE_CHAR_ARGUMENT_KEYS)
        }
    }

    fun extendedSearchKeys(): Set<Char> {
        return getCached(charSetCache, EXTENDED_SEARCH_KEYS_VARIABLE) {
            readCharKeys(EXTENDED_SEARCH_KEYS_VARIABLE, DEFAULT_EXTENDED_SEARCH_KEYS)
        }
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

    /**
     * Clears all cached settings. Call this when settings might have changed
     * (e.g., after .ideavimrc reload).
     */
    fun clearCache() {
        booleanCache.clear()
        intCache.clear()
        stringListCache.clear()
        charSetCache.clear()
    }
}
