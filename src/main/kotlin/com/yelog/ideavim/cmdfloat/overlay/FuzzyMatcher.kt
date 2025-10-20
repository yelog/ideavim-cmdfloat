package com.yelog.ideavim.cmdfloat.overlay

import kotlin.math.min

object FuzzyMatcher {

    fun score(query: String, completion: String): Int? {
        if (query.isEmpty() || completion.isEmpty()) {
            return null
        }
        val queryLower = query.lowercase()
        val completionLower = completion.lowercase()
        var searchIndex = 0
        var score = 0
        var lastMatch = -1

        for (char in queryLower) {
            val found = completionLower.indexOf(char, searchIndex)
            if (found == -1) {
                return null
            }
            if (found == searchIndex) {
                score += 4
            } else if (found == searchIndex + 1) {
                score += 3
            } else {
                score += 1
            }
            if (found == 0) {
                score += 5
            }
            if (lastMatch != -1) {
                if (found == lastMatch + 1) {
                    score += 2
                } else {
                    val gap = found - lastMatch
                    score -= min(gap - 1, 3)
                }
            }
            lastMatch = found
            searchIndex = found + 1
        }
        score -= (completion.length - query.length).coerceAtLeast(0)
        return score
    }
}
