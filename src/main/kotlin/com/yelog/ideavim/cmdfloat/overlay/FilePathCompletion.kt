package com.yelog.ideavim.cmdfloat.overlay

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.IconUtil
import javax.swing.Icon

/**
 * Provides file path completions for commands like :e, :w, :source, :r, etc.
 * Supports relative path navigation with .. for parent directories.
 */
class FilePathCompletion(private val project: Project) {

    private val logger = Logger.getInstance(FilePathCompletion::class.java)

    data class Completion(
        val name: String,
        val path: String,
        val displayPath: String,
        val fileType: String?,
        val isDirectory: Boolean,
        val matchText: String,
        val icon: Icon,
    )

    private val fileTypeManager: FileTypeManager by lazy { FileTypeManager.getInstance() }

    /**
     * Suggest file completions based on current directory context.
     * @param query The search query (may contain path prefix like "../subdir/file")
     * @param editor The current editor to determine the base directory
     * @param limit Maximum number of results to return
     * @return List of matching file completions
     */
    fun suggest(query: String, editor: Editor?, limit: Int): List<Completion> {
        val baseDir = getBaseDirectory(editor)
        return suggestFromDirectory(query, baseDir, limit)
    }

    /**
     * Get the base directory for relative path resolution.
     * Uses the directory of the current file, or project root if no file is open.
     */
    private fun getBaseDirectory(editor: Editor?): VirtualFile? {
        // Try to get the directory of the current file
        val currentFile = editor?.document?.let { doc ->
            com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(doc)?.virtualFile
        }

        return when {
            currentFile != null && !currentFile.isDirectory -> currentFile.parent
            currentFile != null && currentFile.isDirectory -> currentFile
            else -> project.basePath?.let { path ->
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
            }
        }
    }

    /**
     * Resolve a relative path query to a target directory and search pattern.
     * @param query The query string (e.g., "../src/Main", "subdir/file", "test")
     * @param baseDir The starting directory
     * @return Pair of (target directory, search pattern)
     */
    private fun resolvePath(query: String, baseDir: VirtualFile?): Pair<VirtualFile?, String> {
        if (baseDir == null) return null to query

        var currentDir = baseDir
        var remaining = query

        // Handle leading ./ or just start from current directory
        if (remaining.startsWith("./")) {
            remaining = remaining.substring(2)
        }

        // Handle absolute path from project root
        if (remaining.startsWith("/")) {
            val projectRoot = project.basePath?.let { path ->
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
            }
            if (projectRoot != null) {
                currentDir = projectRoot
                remaining = remaining.substring(1)
            }
        }

        // Process path segments
        val segments = remaining.split("/")
        if (segments.isEmpty()) return currentDir to ""

        // All but the last segment are directory navigation
        for (i in 0 until segments.size - 1) {
            val segment = segments[i]
            currentDir = when (segment) {
                ".." -> currentDir?.parent ?: return null to query
                "." -> currentDir
                "" -> currentDir
                else -> currentDir?.findChild(segment) ?: return null to query
            }
            if (currentDir == null) return null to query
        }

        val searchPattern = segments.last()
        return currentDir to searchPattern
    }

    /**
     * Suggest completions from a specific directory.
     */
    private fun suggestFromDirectory(query: String, baseDir: VirtualFile?, limit: Int): List<Completion> {
        val (targetDir, searchPattern) = resolvePath(query, baseDir)

        if (targetDir == null) return emptyList()

        val children = try {
            targetDir.children?.toList() ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to list directory: ${targetDir.path}", e)
            return emptyList()
        }

        // Filter and score children
        val matches = children
            .filter { file ->
                !fileTypeManager.isFileIgnored(file.name) && !isBinaryFile(file)
            }
            .mapNotNull { file ->
                val score = calculateMatchScore(searchPattern, file.name)
                if (score > 0) {
                    score to file
                } else if (searchPattern.isEmpty()) {
                    // Show all files when pattern is empty
                    100 to file
                } else {
                    null
                }
            }
            .sortedWith(
                compareByDescending<Pair<Int, VirtualFile>> { it.first }
                    .thenBy { if (it.second.isDirectory) 0 else 1 }
                    .thenBy { it.second.name.lowercase() }
            )

        // Build path prefix for display (e.g., "../", "src/")
        val pathPrefix = buildPathPrefix(query)

        // Get project base path for constructing project-relative paths
        val projectBasePath = project.basePath

        return matches
            .take(limit)
            .map { (_, file) ->
                val displayPath = if (pathPrefix.isNotEmpty()) {
                    "$pathPrefix${file.name}"
                } else {
                    file.name
                }
                // Determine the best path format for execution:
                // 1. If user typed relative path (../ or ./), preserve that format
                // 2. Otherwise, use project-relative path from project root
                val executionPath = buildExecutionPath(query, file, pathPrefix, projectBasePath)
                val icon = getFileIcon(file)
                Completion(
                    name = file.name,
                    path = executionPath,
                    displayPath = displayPath,
                    fileType = file.extension?.lowercase(),
                    isDirectory = file.isDirectory,
                    matchText = file.name,
                    icon = icon,
                )
            }
    }

    /**
     * Build the execution path for a file completion.
     * Preserves user's relative path input when possible, falls back to project-relative path.
     */
    private fun buildExecutionPath(
        query: String,
        file: VirtualFile,
        pathPrefix: String,
        projectBasePath: String?
    ): String {
        // If user typed a relative path (../ or ./), preserve that format
        if (pathPrefix.startsWith("../") || pathPrefix.startsWith("./")) {
            return "$pathPrefix${file.name}"
        }

        // Use project-relative path for execution to ensure IdeaVim can resolve it correctly
        // IdeaVim resolves paths from the project root, not from the current file's directory
        return if (projectBasePath != null && file.path.startsWith(projectBasePath)) {
            file.path.substring(projectBasePath.length + 1)
        } else {
            file.path
        }
    }

    /**
     * Get the icon for a file, matching the icon shown in the project view.
     */
    private fun getFileIcon(file: VirtualFile): Icon {
        return IconUtil.getIcon(file, 0, project)
    }

    /**
     * Build the path prefix for display (e.g., "../", "src/")
     */
    private fun buildPathPrefix(query: String): String {
        val lastSlash = query.lastIndexOf('/')
        return if (lastSlash >= 0) {
            query.substring(0, lastSlash + 1)
        } else {
            ""
        }
    }

    /**
     * Build relative base path from original base to target directory.
     */
    private fun buildRelativeBase(baseDir: VirtualFile?, targetDir: VirtualFile?): String {
        if (baseDir == null || targetDir == null) return ""
        if (baseDir == targetDir) return ""

        // Count how many levels we went up
        val basePath = baseDir.path
        val targetPath = targetDir.path

        return if (targetPath.length < basePath.length && basePath.startsWith(targetPath)) {
            // We went up - construct ../ path
            val diff = basePath.substring(targetPath.length)
            val levels = diff.count { it == '/' }
            "../".repeat(levels)
        } else if (targetPath.length > basePath.length && targetPath.startsWith(basePath)) {
            // We went down - construct subpath
            targetPath.substring(basePath.length + 1) + "/"
        } else {
            // Different branch - return full relative path
            targetPath + "/"
        }
    }

    private fun calculateMatchScore(pattern: String, name: String): Int {
        if (pattern.isEmpty()) return 100

        val nameLower = name.lowercase()
        val patternLower = pattern.lowercase()

        // Exact match
        if (nameLower == patternLower) return 1000

        // Starts with pattern
        if (nameLower.startsWith(patternLower)) return 800

        // Contains pattern as word boundary
        if (nameLower.contains(patternLower)) return 600

        // Fuzzy match
        val fuzzyScore = FuzzyMatcher.score(patternLower, nameLower)
        if (fuzzyScore != null) return 400 + fuzzyScore

        return 0
    }

    private fun isBinaryFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false

        val binaryExtensions = setOf(
            "exe", "dll", "so", "dylib", "bin", "o", "obj",
            "class", "jar", "war", "ear",
            "zip", "tar", "gz", "bz2", "7z", "rar",
            "jpg", "jpeg", "png", "gif", "bmp", "ico",
            "mp3", "mp4", "avi", "mov", "wmv", "flv",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "db", "sqlite", "lock",
        )
        return file.extension?.lowercase() in binaryExtensions
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

        val prefix = content.substring(0, index)
        val query = if (index < content.length) content.substring(index) else ""

        return FilePathQuery(prefix, query)
    }
}