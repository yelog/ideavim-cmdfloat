package com.yelog.ideavim.cmdfloat.cache

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.yelog.ideavim.cmdfloat.overlay.SearchCompletionWord
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cache for document words used in search completions.
 * Provides incremental updates and TTL-based invalidation for large files.
 */
class DocumentWordCache(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(DocumentWordCache::class.java)

    // Main cache: Document -> WordIndex
    private val cache = ConcurrentHashMap<Document, WordIndexEntry>()

    // Global modification counter for cache versioning
    private val globalVersion = AtomicLong(0)

    // Cache configuration
    companion object {
        // Maximum document size (in lines) to cache (100k lines)
        private const val MAX_CACHEABLE_LINES = 100_000

        // Maximum words per document
        private const val MAX_WORDS_PER_DOCUMENT = 10_000

        // Minimum word length
        private const val MIN_WORD_LENGTH = 2

        // TTL for cache entries (30 seconds)
        private const val CACHE_TTL_MS = 30_000L
    }

    data class WordIndexEntry(
        val words: List<SearchCompletionWord>,
        val version: Long,
        val timestamp: Long,
        val documentRef: WeakReference<Document>,
    )

    /**
     * Get cached words for the given editor's document.
     * Returns cached result if valid, otherwise rebuilds the index.
     */
    fun getWords(editor: Editor, limitRange: TextRange? = null): List<SearchCompletionWord> {
        if (!shouldCacheDocument(editor)) {
            return collectWordsUncached(editor, limitRange)
        }

        val document = editor.document
        val currentStamp = document.modificationStamp
        val cached = cache[document]

        // Check if cache is still valid
        if (cached != null && cached.version == currentStamp) {
            logger.debug("Cache hit for document with stamp $currentStamp")
            return cached.words
        }

        // Build or rebuild cache
        return buildCache(editor, currentStamp, limitRange)
    }

    /**
     * Invalidate cache for a specific document.
     */
    fun invalidate(document: Document) {
        cache.remove(document)
        logger.debug("Cache invalidated for document")
    }

    /**
     * Clear all cached entries.
     */
    fun clear() {
        cache.clear()
        globalVersion.set(0)
        logger.debug("All cache entries cleared")
    }

    /**
     * Clean up expired cache entries.
     */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = cache.entries.filter { (_, entry) ->
            entry.documentRef.get() == null || (now - entry.timestamp) > CACHE_TTL_MS
        }
        expired.forEach { (doc, _) ->
            cache.remove(doc)
        }
        if (expired.isNotEmpty()) {
            logger.debug("Cleaned up ${expired.size} expired cache entries")
        }
    }

    private fun shouldCacheDocument(editor: Editor): Boolean {
        val document = editor.document

        // Don't cache if document is too large
        if (document.lineCount > MAX_CACHEABLE_LINES) {
            logger.debug("Document too large (${document.lineCount} lines), skipping cache")
            return false
        }

        // Don't cache if document is in a transient state
        if (document.textLength == 0) {
            return false
        }

        return true
    }

    private fun buildCache(
        editor: Editor,
        version: Long,
        limitRange: TextRange?,
    ): List<SearchCompletionWord> {
        val startTime = System.currentTimeMillis()
        val words = collectWordsUncached(editor, limitRange)
        val elapsed = System.currentTimeMillis() - startTime

        // Only cache if collection was fast enough (< 100ms)
        if (elapsed < 100 && limitRange == null) {
            val entry = WordIndexEntry(
                words = words,
                version = version,
                timestamp = System.currentTimeMillis(),
                documentRef = WeakReference(editor.document),
            )
            cache[editor.document] = entry
            logger.debug("Cache built for document in ${elapsed}ms with ${words.size} words")
        }

        return words
    }

    private fun collectWordsUncached(
        editor: Editor,
        limitRange: TextRange?,
    ): List<SearchCompletionWord> {
        val document = editor.document
        val textRange = limitRange?.intersection(TextRange(0, document.textLength))
            ?.takeIf { !it.isEmpty }
            ?: TextRange(0, document.textLength)

        if (textRange.isEmpty) {
            return emptyList()
        }

        val text = document.charsSequence.subSequence(textRange.startOffset, textRange.endOffset)
        val words = mutableSetOf<String>()
        val result = mutableListOf<SearchCompletionWord>()

        var index = 0
        var wordStart = -1
        val length = text.length

        fun flushWord(endExclusive: Int) {
            if (wordStart == -1) return
            if (endExclusive <= wordStart) {
                wordStart = -1
                return
            }

            val word = text.subSequence(wordStart, endExclusive).toString()
            if (word.length >= MIN_WORD_LENGTH &&
                word.any { it.isLetterOrDigit() } &&
                words.add(word)
            ) {
                // Use default attributes for uncached collection
                result.add(SearchCompletionWord(word, defaultAttributes))
            }
            wordStart = -1
        }

        while (index < length && words.size < MAX_WORDS_PER_DOCUMENT) {
            val ch = text[index]
            if (isWordChar(ch)) {
                if (wordStart == -1) {
                    wordStart = index
                }
            } else {
                flushWord(index)
            }
            index++
        }

        if (words.size < MAX_WORDS_PER_DOCUMENT) {
            flushWord(length)
        }

        return result
    }

    private fun isWordChar(ch: Char): Boolean {
        return ch.isLetterOrDigit() || ch == '_' || ch == '-'
    }

    private val defaultAttributes = com.intellij.openapi.editor.markup.TextAttributes()

    override fun dispose() {
        clear()
    }
}

/**
 * Service to manage document word cache lifecycle.
 */
class DocumentWordCacheService(private val project: Project) : Disposable {

    private val cache = DocumentWordCache(project)

    fun getCache(): DocumentWordCache = cache

    override fun dispose() {
        cache.dispose()
    }
}
