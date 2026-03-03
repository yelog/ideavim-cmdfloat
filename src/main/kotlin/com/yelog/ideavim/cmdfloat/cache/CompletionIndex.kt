package com.yelog.ideavim.cmdfloat.cache

import com.yelog.ideavim.cmdfloat.overlay.SearchCompletionWord

/**
 * Indexed completion system using prefix trie and trigram indexing.
 * Provides O(1) prefix lookups and fast fuzzy matching for large completion sets.
 */
class CompletionIndex {

    // Prefix trie for fast prefix-based completion
    private val prefixTrie = PrefixTrieNode()

    // Trigram index for fuzzy matching fallback
    private val trigramIndex = mutableMapOf<String, MutableSet<String>>()

    // All completions indexed by their word
    private val completionMap = mutableMapOf<String, SearchCompletionWord>()

    // Maximum number of results to return
    private val maxResults = 50

    companion object {
        // Minimum query length to use trigram index
        private const val TRIGRAM_MIN_LENGTH = 3
        // Minimum word length to index trigrams
        private const val MIN_WORD_LENGTH_FOR_TRIGRAM = 4
    }

    /**
     * Adds a completion to the index.
     */
    fun add(completion: SearchCompletionWord) {
        val word = completion.word
        if (completionMap.containsKey(word)) {
            return
        }
        completionMap[word] = completion

        // Add to prefix trie
        var node = prefixTrie
        for (ch in word.lowercase()) {
            node = node.children.computeIfAbsent(ch) { PrefixTrieNode() }
            node.completions.add(word)
        }

        // Add to trigram index for words long enough
        if (word.length >= MIN_WORD_LENGTH_FOR_TRIGRAM) {
            val lowerWord = word.lowercase()
            for (i in 0..lowerWord.length - 3) {
                val trigram = lowerWord.substring(i, i + 3)
                trigramIndex.computeIfAbsent(trigram) { mutableSetOf() }.add(word)
            }
        }
    }

    /**
     * Adds multiple completions to the index.
     */
    fun addAll(completions: List<SearchCompletionWord>) {
        completions.forEach { add(it) }
    }

    /**
     * Searches for completions matching the given query.
     * First tries prefix matching, then falls back to fuzzy/trigram matching.
     */
    fun search(query: String): List<SearchCompletionWord> {
        if (query.isEmpty()) {
            return completionMap.values.take(maxResults)
        }

        val lowerQuery = query.lowercase()

        // For short queries (1-2 chars), use prefix matching
        if (lowerQuery.length <= 2) {
            return searchByPrefix(lowerQuery)
                .mapNotNull { completionMap[it] }
                .take(maxResults)
        }

        // For longer queries, try exact prefix first, then trigram matching
        val prefixWordResults = searchByPrefix(lowerQuery)
        val prefixResults = prefixWordResults.mapNotNull { completionMap[it] }
        if (prefixResults.size >= maxResults / 2) {
            return prefixResults.take(maxResults)
        }

        // Combine with trigram results for better coverage
        val trigramWordResults = searchByTrigrams(lowerQuery)
        val combinedWords = (prefixWordResults + trigramWordResults).distinct()

        // Score and sort results
        return combinedWords
            .mapNotNull { completionMap[it] }
            .map { ScoreResult(it, scoreMatch(lowerQuery, it.word.lowercase())) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(maxResults)
            .map { it.completion }
    }

    /**
     * Clears all indexed completions.
     */
    fun clear() {
        prefixTrie.children.clear()
        prefixTrie.completions.clear()
        trigramIndex.clear()
        completionMap.clear()
    }

    /**
     * Returns the number of indexed completions.
     */
    fun size(): Int = completionMap.size

    private fun searchByPrefix(prefix: String): List<String> {
        var node = prefixTrie
        for (ch in prefix) {
            node = node.children[ch] ?: return emptyList()
        }
        return node.completions.toList()
    }

    private fun searchByTrigrams(query: String): List<String> {
        if (query.length < TRIGRAM_MIN_LENGTH) {
            return emptyList()
        }

        // Collect candidates from trigram index
        val candidates = mutableSetOf<String>()
        for (i in 0..query.length - 3) {
            val trigram = query.substring(i, i + 3)
            val matches = trigramIndex[trigram]
            if (matches != null) {
                if (candidates.isEmpty()) {
                    candidates.addAll(matches)
                } else {
                    // Intersect with existing candidates
                    candidates.retainAll(matches)
                }
                if (candidates.isEmpty()) {
                    break
                }
            }
        }

        return candidates.toList()
    }

    private fun scoreMatch(query: String, word: String): Int {
        // Exact match gets highest score
        if (word == query) return 1000

        // Starts with query gets high score
        if (word.startsWith(query)) return 500 + query.length * 10

        // Contains query as substring
        val index = word.indexOf(query)
        if (index >= 0) {
            // Bonus for being at word boundary
            val boundaryBonus = if (index == 0 || word[index - 1] == '_' || word[index - 1] == '-') 100 else 0
            return 200 + query.length * 5 + boundaryBonus
        }

        // Fuzzy match scoring
        return fuzzyScore(query, word)
    }

    private fun fuzzyScore(query: String, word: String): Int {
        var score = 0
        var queryIndex = 0
        var lastMatchIndex = -1
        var consecutiveMatches = 0

        for (i in word.indices) {
            if (queryIndex >= query.length) break

            if (word[i] == query[queryIndex]) {
                score += 10

                // Bonus for consecutive matches
                if (lastMatchIndex == i - 1) {
                    consecutiveMatches++
                    score += consecutiveMatches * 5
                } else {
                    consecutiveMatches = 0
                }

                // Bonus for matching at word start or after separator
                if (i == 0 || word[i - 1] == '_' || word[i - 1] == '-') {
                    score += 15
                }

                // Penalty for distance from start
                score -= i / 5

                lastMatchIndex = i
                queryIndex++
            }
        }

        // If not all characters matched, return 0
        if (queryIndex < query.length) return 0

        return score
    }

    private data class ScoreResult(
        val completion: SearchCompletionWord,
        val score: Int,
    )

    private class PrefixTrieNode {
        val children = mutableMapOf<Char, PrefixTrieNode>()
        val completions = mutableSetOf<String>()
    }
}

/**
 * Cached completion index that can be reused across overlay invocations.
 */
class CachedCompletionIndex {
    private val index = CompletionIndex()
    private var cachedCompletions: List<SearchCompletionWord> = emptyList()
    private var cacheValid = false

    fun buildIndex(completions: List<SearchCompletionWord>) {
        if (cacheValid && completions == cachedCompletions) {
            return
        }
        index.clear()
        index.addAll(completions)
        cachedCompletions = completions
        cacheValid = true
    }

    fun search(query: String): List<SearchCompletionWord> {
        if (!cacheValid) {
            return emptyList()
        }
        return index.search(query)
    }

    fun invalidate() {
        cacheValid = false
        cachedCompletions = emptyList()
        index.clear()
    }
}
