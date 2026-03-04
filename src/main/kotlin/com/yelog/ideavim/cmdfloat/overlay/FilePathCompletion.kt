package com.yelog.ideavim.cmdfloat.overlay

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Provides file path completions for commands like :e, :w, :source, :r, etc.
 * Uses IntelliJ's FilenameIndex for efficient file searching.
 */
class FilePathCompletion(private val project: Project) {

    private val logger = Logger.getInstance(FilePathCompletion::class.java)

    data class Completion(
        val name: String,
        val path: String,
        val fileType: String?,
        val isDirectory: Boolean,
        val matchText: String,
    )

    private data class FileEntry(
        val name: String,
        val path: String,
        val fileType: String?,
        val isDirectory: Boolean,
        val sortKey: String,
    )

    private var allFiles: List<FileEntry> = emptyList()
    private var initialized = false

    init {
        // Initialize file index in background
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread {
                initializeFileIndex()
            }
    }

    private fun initializeFileIndex() {
        if (initialized) return
        try {
            val application = com.intellij.openapi.application.ApplicationManager.getApplication()
            val fileTypeManager = FileTypeManager.getInstance()

            val files = if (application.isReadAccessAllowed) {
                doInitializeFileIndex(fileTypeManager)
            } else {
                com.intellij.openapi.application.ReadAction.nonBlocking<List<FileEntry>> {
                    doInitializeFileIndex(fileTypeManager)
                }.executeSynchronously()
            }

            allFiles = files
            initialized = true
            logger.debug("FilePathCompletion initialized with ${files.size} files")
        } catch (e: Exception) {
            logger.warn("Failed to initialize file index", e)
        }
    }

    private fun doInitializeFileIndex(fileTypeManager: FileTypeManager): List<FileEntry> {
        return try {
            FilenameIndex.getAllFiles(project)
                .filter { file ->
                    // Filter out ignored files and binary files
                    !fileTypeManager.isFileIgnored(file.name) &&
                    !isBinaryFile(file)
                }
                .map { file ->
                    FileEntry(
                        name = file.name,
                        path = getRelativePath(file) ?: file.path,
                        fileType = file.extension?.lowercase(),
                        isDirectory = file.isDirectory,
                        sortKey = buildSortKey(file),
                    )
                }
                .sortedBy { it.sortKey }
        } catch (e: Exception) {
            logger.warn("Failed to build file index", e)
            emptyList()
        }
    }

    /**
     * Suggest file completions matching the query.
     * @param query The search query (file name or partial path)
     * @param limit Maximum number of results to return
     * @return List of matching file completions
     */
    fun suggest(query: String, limit: Int): List<Completion> {
        if (query.isBlank()) {
            return emptyList()
        }

        // Ensure index is initialized
        if (!initialized) {
            initializeFileIndex()
        }

        val queryLower = query.lowercase()
        val queryParts = queryLower.split('/')

        val matches = buildList {
            for (entry in allFiles) {
                val score = calculateMatchScore(queryLower, queryParts, entry)
                if (score > 0) {
                    add(score to entry)
                }
            }
        }

        return matches
            .sortedWith(
                compareByDescending<Pair<Int, FileEntry>> { it.first }
                    .thenBy { it.second.sortKey.length }
                    .thenBy { it.second.sortKey },
            )
            .asSequence()
            .map { (_, entry) ->
                Completion(
                    name = entry.name,
                    path = entry.path,
                    fileType = entry.fileType,
                    isDirectory = entry.isDirectory,
                    matchText = entry.name,
                )
            }
            .take(limit)
            .toList()
    }

    private fun calculateMatchScore(query: String, queryParts: List<String>, entry: FileEntry): Int {
        val nameLower = entry.name.lowercase()
        val pathLower = entry.path.lowercase()

        // Exact name match gets highest score
        if (nameLower == query) return 1000

        // Name starts with query
        if (nameLower.startsWith(query)) return 800

        // Name contains query as word boundary
        if (nameLower.contains("$query")) return 700

        // Name fuzzy match
        val nameScore = FuzzyMatcher.score(query, nameLower)
        if (nameScore != null) return 600 + nameScore

        // Path ends with query
        if (pathLower.endsWith(query)) return 500

        // Path contains all query parts in order
        var pathIndex = 0
        var allPartsFound = true
        for (part in queryParts) {
            if (part.isEmpty()) continue
            val found = pathLower.indexOf(part, pathIndex)
            if (found == -1) {
                allPartsFound = false
                break
            }
            pathIndex = found + part.length
        }
        if (allPartsFound) return 400

        // Path fuzzy match (lower priority)
        val pathScore = FuzzyMatcher.score(query, pathLower)
        if (pathScore != null) return 200 + pathScore

        return 0
    }

    private fun getRelativePath(file: VirtualFile): String? {
        val basePath = project.basePath ?: return null
        val filePath = file.path
        return if (filePath.startsWith(basePath)) {
            filePath.substring(basePath.length).removePrefix("/")
        } else {
            filePath
        }
    }

    private fun buildSortKey(file: VirtualFile): String {
        // Prioritize files in src directory, then by name
        val path = file.path
        val priority = when {
            "/src/" in path -> "0"
            "/test/" in path -> "1"
            file.isDirectory -> "2"
            else -> "3"
        }
        return "$priority${file.name.lowercase()}"
    }

    private fun isBinaryFile(file: VirtualFile): Boolean {
        val binaryExtensions = setOf(
            "exe", "dll", "so", "dylib", "bin", "o", "obj",
            "class", "jar", "war", "ear",
            "zip", "tar", "gz", "bz2", "7z", "rar",
            "jpg", "jpeg", "png", "gif", "bmp", "ico", "svg",
            "mp3", "mp4", "avi", "mov", "wmv", "flv",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "db", "sqlite", "lock", "log",
        )
        return file.extension?.lowercase() in binaryExtensions
    }

    /**
     * Refresh the file index. Call this when files change significantly.
     */
    fun refresh() {
        initialized = false
        initializeFileIndex()
    }
}

/**
 * Data classes for file path completion queries
 */
data class FilePathQuery(
    val prefix: String,
    val query: String,
)

/**
 * Parser for detecting file path completion context in command line
 */
object FilePathQueryParser {

    // Commands that expect file path arguments
    private val FILE_COMMANDS = setOf(
        "e", "edit", "ed",
        "w", "write",
        "wq", "x", "xit",
        "r", "read",
        "source", "so",
        "saveas", "sav",
        "split", "sp",
        "vsplit", "vs", "vsp",
        "tabedit", "tabe", "tabnew",
        "diffsplit", "diffs",
        "args", "ar",
    )

    /**
     * Check if the current command context expects a file path.
     * @param content The full command line content
     * @return FilePathQuery if in file path context, null otherwise
     */
    fun parse(content: String): FilePathQuery? {
        if (content.isEmpty()) {
            return null
        }

        var index = content.indexOfFirst { !it.isWhitespace() }
        if (index == -1) {
            return null
        }

        // Skip leading colon if present
        if (content[index] == ':') {
            index += 1
            while (index < content.length && content[index].isWhitespace()) {
                index += 1
            }
        }

        if (index >= content.length) {
            return null
        }

        // Find the end of the command word
        val commandStart = index
        while (index < content.length && content[index].isLetter()) {
            index++
        }

        if (commandStart == index) {
            return null
        }

        val command = content.substring(commandStart, index).lowercase()

        // Check if this is a file-related command
        if (command !in FILE_COMMANDS) {
            return null
        }

        // Skip whitespace after command
        while (index < content.length && content[index].isWhitespace()) {
            index++
        }

        if (index >= content.length) {
            return null
        }

        val prefix = content.substring(0, index)
        val query = content.substring(index)

        return FilePathQuery(prefix, query)
    }
}
