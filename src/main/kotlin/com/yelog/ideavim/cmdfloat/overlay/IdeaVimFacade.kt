package com.yelog.ideavim.cmdfloat.overlay

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Optional
import javax.swing.KeyStroke

object IdeaVimFacade {

    private val logger = Logger.getInstance(IdeaVimFacade::class.java)

    private val vimPluginClass: Class<*>? = loadClass("com.maddyhome.idea.vim.VimPlugin")
    private val commandStateClass: Class<*>? = loadClass(
        "com.maddyhome.idea.vim.command.CommandState",
        "com.maddyhome.idea.vim.state.CommandState",
    )
    private val commandStateInstanceMethod = commandStateClass?.getMethod("getInstance", Editor::class.java)
    private val commandStateModeMethod = commandStateClass?.getMethod("getMode")
    private val commandStateCommandBuilderMethod = run {
        val clazz = commandStateClass ?: return@run null
        runCatching { clazz.getMethod("getCommandBuilder") }.getOrNull()
    }
    private val commandStateMappingStateMethod = run {
        val clazz = commandStateClass ?: return@run null
        runCatching { clazz.getMethod("getMappingState") }.getOrNull()
    }
    private val commandStateIsOperatorPendingMethod = run {
        val clazz = commandStateClass ?: return@run null
        runCatching { clazz.getMethod("isOperatorPending") }.getOrNull()
    }
    private val vimPluginInstanceMethod = vimPluginClass?.getMethod("getInstance")
    private val vimPluginKeyMethod = vimPluginClass?.getMethod("getKey")
    private val vimPluginSearchMethod = vimPluginClass?.getMethod("getSearch")
    private val vimPluginEditorMethod = vimPluginClass?.getMethod("getEditor")
    private val vimPluginOptionGroupMethod = run {
        val clazz = vimPluginClass ?: return@run null
        runCatching { clazz.getMethod("getOptionGroup") }.getOrNull()
    }
    private val vimPluginVariableServiceMethod = run {
        val clazz = vimPluginClass ?: return@run null
        runCatching { clazz.getMethod("getVariableService") }.getOrNull()
    }
    private val variableServiceClass = vimPluginVariableServiceMethod?.returnType
    private val variableServiceGlobalGetterMethod = run {
        val serviceClass = variableServiceClass ?: return@run null
        serviceClass.methods.firstOrNull { method ->
            method.parameterCount == 1 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.returnType != Void.TYPE &&
                    method.name.contains("Global", ignoreCase = true) &&
                    method.name.contains("Variable", ignoreCase = true)
        }
    }
    private val vimStringClass = loadClass(
        "com.maddyhome.idea.vim.vimscript.model.VimString",
        "com.maddyhome.idea.vim.vimscript.model.datatypes.VimString",
    )
    private val vimListClass = loadClass(
        "com.maddyhome.idea.vim.vimscript.model.VimList",
        "com.maddyhome.idea.vim.vimscript.model.datatypes.VimList",
    )
    private val vimNumberClass = loadClass(
        "com.maddyhome.idea.vim.vimscript.model.VimNumber",
        "com.maddyhome.idea.vim.vimscript.model.datatypes.VimNumber",
    )
    private val handleKeyMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val handleKeyFailureLogged = ConcurrentHashMap.newKeySet<String>()
    private val vimEditorClass = loadClass("com.maddyhome.idea.vim.api.VimEditor")
    private val executionContextClass = loadClass("com.maddyhome.idea.vim.api.ExecutionContext")
    private val vimEditorCreationCache = ConcurrentHashMap.newKeySet<Class<*>>()
    private val executionContextCreationCache = ConcurrentHashMap.newKeySet<Class<*>>()
    private val overlaySuppressionDeadline = AtomicLong(0)
    private val searchGroupClass = loadClass("com.maddyhome.idea.vim.group.SearchGroup")
    private val directionClass = loadClass("com.maddyhome.idea.vim.common.Direction")
    private val directionForward = runCatching { directionClass?.getField("FORWARDS")?.get(null) }.getOrNull()
    private val directionBackward = runCatching { directionClass?.getField("BACKWARDS")?.get(null) }.getOrNull()
    private val searchResetMethod = run {
        val group = searchGroupClass ?: return@run null
        runCatching { group.getMethod("resetIncsearchHighlights") }.getOrNull()
    }
    private val editorGroupClass = loadClass("com.maddyhome.idea.vim.group.EditorGroup")
    private val closeEditorSearchSessionMethod = run {
        val editorGroup = editorGroupClass ?: return@run null
        runCatching { editorGroup.getMethod("closeEditorSearchSession", Editor::class.java) }.getOrNull()
    }
    private val searchSetLastStateMethod = run {
        val group = searchGroupClass
        val direction = directionClass
        if (group != null && direction != null) {
            runCatching {
                group.getMethod(
                    "setLastSearchState",
                    Editor::class.java,
                    String::class.java,
                    String::class.java,
                    direction
                )
            }.getOrNull()
        } else {
            null
        }
    }
    private val searchGetLastPatternMethod = run {
        val group = searchGroupClass ?: return@run null
        runCatching { group.getMethod("getLastSearchPattern") }.getOrNull()
    }
    private val searchGetLastSubstituteMethod = run {
        val group = searchGroupClass ?: return@run null
        runCatching { group.getMethod("getLastSubstitutePattern") }.getOrNull()
    }
    private val searchGetLastDirMethod = run {
        val group = searchGroupClass ?: return@run null
        runCatching { group.getMethod("getLastDir") }.getOrNull()
    }
    private val searchHighlightsHelperClass = loadClass("com.maddyhome.idea.vim.helper.SearchHighlightsHelper")
    private val lineRangeClass = loadClass("com.maddyhome.idea.vim.ex.ranges.LineRange")
    private val updateIncsearchMethodInfo = run {
        val helper = searchHighlightsHelperClass ?: return@run null
        val method = helper.methods.firstOrNull { completion ->
            completion.name == "updateIncsearchHighlights" && completion.parameterTypes.size == 6
        } ?: return@run null
        val params = method.parameterTypes
        val countIndices = params.withIndex().filter { it.value == Integer.TYPE }.map { it.index }
        val booleanIndex = params.indexOfFirst { it == java.lang.Boolean.TYPE }
        val rangeIndex = params.withIndex().firstOrNull { (index, type) ->
            lineRangeClass?.let { clazz -> type == clazz || clazz.isAssignableFrom(type) } == true
        }?.index ?: -1
        if (countIndices.isEmpty() && booleanIndex == -1) {
            null
        } else {
            val countIndex = if (countIndices.size >= 2) countIndices.first() else null
            val caretIndex = countIndices.lastOrNull() ?: -1
            UpdateIncsearchMethod(method, countIndex, booleanIndex, caretIndex, rangeIndex)
        }
    }
    private val searchPreviewState = ConcurrentHashMap<Editor, SearchPreviewState>()
    private val commandBuilderClass = loadClass("com.maddyhome.idea.vim.command.CommandBuilder")
    private val commandBuilderAwaitingMethod = run {
        val clazz = commandBuilderClass ?: return@run null
        runCatching { clazz.getMethod("isAwaitingCharOrDigraphArgument") }.getOrNull()
    }
    private val commandBuilderIsEmptyMethod = run {
        val clazz = commandBuilderClass ?: return@run null
        runCatching { clazz.getMethod("isEmpty") }.getOrNull()
    }
    private val commandBuilderIsBuildingMultiKeyMethod = run {
        val clazz = commandBuilderClass ?: return@run null
        runCatching { clazz.getMethod("isBuildingMultiKeyCommand") }.getOrNull()
    }
    private val commandBuilderIsPuttingLiteralMethod = run {
        val clazz = commandBuilderClass ?: return@run null
        runCatching { clazz.getMethod("isPuttingLiteral") }.getOrNull()
    }
    private val mappingStateClass = loadClass("com.maddyhome.idea.vim.command.MappingState")
    private val mappingStateIsExecutingMethod = run {
        val clazz = mappingStateClass ?: return@run null
        runCatching { clazz.getMethod("isExecutingMap") }.getOrNull()
    }
    private val awaitingCharFailureLogged = AtomicBoolean(false)

    data class OptionInfo(
        val name: String,
        val abbreviation: String?,
    )

    fun isAvailable(): Boolean {
        return vimPluginClass != null && commandStateClass != null && vimPluginKeyMethod != null
    }

    fun isEditorInNormalMode(editor: Editor): Boolean {
        val modeName = editorModeName(editor) ?: return false
        return modeName == "COMMAND"
    }

    fun isEditorCommandOverlayAllowed(editor: Editor): Boolean {
        val modeName = editorModeName(editor) ?: return false
        if (modeName == "COMMAND") {
            return true
        }
        if (modeName.startsWith("VISUAL")) {
            return true
        }
        if (modeName == "SELECT") {
            return true
        }
        return false
    }

    fun hasVisualSelection(editor: Editor): Boolean {
        if (!isAvailable()) {
            return false
        }
        val modeName = editorModeName(editor) ?: return false
        return modeName.startsWith("VISUAL") || modeName == "SELECT"
    }

    fun isAwaitingCharArgument(editor: Editor): Boolean {
        if (!isAvailable()) {
            return false
        }
        return try {
            val commandState = commandStateInstanceMethod?.invoke(null, editor) ?: return false
            if (commandStateHasPendingCommand(commandState)) {
                return true
            }
            commandStateHasActiveMapping(commandState)
        } catch (throwable: Throwable) {
            if (awaitingCharFailureLogged.compareAndSet(false, true)) {
                logger.warn("Failed to query IdeaVim command builder state.", throwable)
            }
            false
        }
    }

    private fun commandStateHasPendingCommand(commandState: Any): Boolean {
        val builder = commandStateCommandBuilderMethod?.let { method ->
            runCatching { method.invoke(commandState) }.getOrNull()
        } ?: return invokeBoolean(commandStateIsOperatorPendingMethod, commandState) == true

        if (invokeBoolean(commandBuilderAwaitingMethod, builder) == true) {
            return true
        }

        if (invokeBoolean(commandBuilderIsBuildingMultiKeyMethod, builder) == true) {
            return true
        }

        if (invokeBoolean(commandBuilderIsPuttingLiteralMethod, builder) == true) {
            return true
        }

        val isEmpty = invokeBoolean(commandBuilderIsEmptyMethod, builder)
        if (isEmpty == false) {
            return true
        }

        return invokeBoolean(commandStateIsOperatorPendingMethod, commandState) == true
    }

    private fun commandStateHasActiveMapping(commandState: Any): Boolean {
        val mappingState = commandStateMappingStateMethod?.let { method ->
            runCatching { method.invoke(commandState) }.getOrNull()
        } ?: return false
        return invokeBoolean(mappingStateIsExecutingMethod, mappingState) == true
    }

    private fun invokeBoolean(method: Method?, target: Any): Boolean? {
        if (method == null) {
            return null
        }
        return runCatching { method.invoke(target) as? Boolean }.getOrNull()
    }

    private fun editorModeName(editor: Editor): String? {
        if (!isAvailable()) {
            return null
        }
        return try {
            val commandState = commandStateInstanceMethod?.invoke(null, editor) ?: return null
            val mode = commandStateModeMethod?.invoke(commandState)
            (mode as? Enum<*>)?.name ?: mode?.toString()
        } catch (throwable: Throwable) {
            logger.warn("Failed to query IdeaVim command state.", throwable)
            null
        }
    }

    fun collectOptions(): List<OptionInfo> {
        if (!isAvailable()) {
            return emptyList()
        }

        return try {
            val optionGroup = obtainOptionGroup() ?: return emptyList()
            val allOptions = readAllOptions(optionGroup)
            allOptions.mapNotNull { option ->
                val optionName = safeOptionName(option) ?: return@mapNotNull null
                val abbreviation = safeOptionAbbreviation(option)
                OptionInfo(name = optionName, abbreviation = abbreviation)
            }.sortedBy { it.name.lowercase() }
        } catch (throwable: Throwable) {
            logger.warn("Failed to collect IdeaVim option definitions.", throwable)
            emptyList()
        }
    }

    fun readGlobalVariableStrings(name: String): List<String>? {
        if (!isAvailable()) {
            return null
        }
        val raw = readGlobalVariableValue(name) ?: return null
        val normalized = normalizeGlobalValue(raw)
        return normalized.takeIf { it.isNotEmpty() }
    }

    fun readGlobalVariableBoolean(name: String): Boolean? {
        if (!isAvailable()) {
            return null
        }
        val raw = readGlobalVariableValue(name) ?: return null
        return convertToBoolean(raw)
    }

    fun readGlobalVariableInt(name: String): Int? {
        if (!isAvailable()) {
            return null
        }
        val raw = readGlobalVariableValue(name) ?: return null
        return convertToInt(raw)
    }

    private fun readGlobalVariableValue(name: String): Any? {
        val variableMethod = vimPluginVariableServiceMethod ?: return null
        val getter = variableServiceGlobalGetterMethod ?: return null
        return try {
            val service = obtainVariableService(variableMethod) ?: return null
            if (!getter.canAccess(service)) {
                getter.isAccessible = true
            }
            val value = getter.invoke(service, name)
            unwrapOptional(value)
        } catch (throwable: Throwable) {
            logger.debug("Failed to read IdeaVim global variable $name", throwable)
            null
        }
    }

    private fun obtainVariableService(method: Method): Any? {
        return try {
            if (Modifier.isStatic(method.modifiers)) {
                if (!method.canAccess(null)) {
                    method.isAccessible = true
                }
                method.invoke(null)
            } else {
                val plugin = vimPluginInstanceMethod?.invoke(null) ?: return null
                if (!method.canAccess(plugin)) {
                    method.isAccessible = true
                }
                method.invoke(plugin)
            }
        } catch (throwable: Throwable) {
            logger.debug("Failed to obtain IdeaVim variable service", throwable)
            null
        }
    }

    private fun normalizeGlobalValue(value: Any?): List<String> {
        val unwrapped = unwrapOptional(value) ?: return emptyList()
        val sequence = flattenValue(unwrapped)
        if (sequence != null) {
            return sequence.mapNotNull { convertToString(it)?.takeIf { token -> token.isNotBlank() } }.toList()
        }
        val single = convertToString(unwrapped)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val literal = parseListLiteral(single)
        return if (literal.isNotEmpty()) literal else listOf(single)
    }

    private fun flattenValue(value: Any?): Sequence<Any?>? {
        val target = unwrapOptional(value) ?: return null
        return when {
            vimListClass?.isInstance(target) == true -> extractVimListValues(target)
            target is Sequence<*> -> target
            target is Iterable<*> -> target.asSequence()
            target is Iterator<*> -> iteratorSequence(target)
            target != null && target.javaClass.isArray -> arrayElements(target)
            else -> null
        }
    }

    private fun extractVimListValues(value: Any): Sequence<Any?>? {
        val accessors = listOf("getValues", "values", "toList", "elements", "items")
        accessors.forEach { name ->
            val method = runCatching { value.javaClass.getMethod(name) }.getOrNull() ?: return@forEach
            val result = runCatching {
                if (!method.canAccess(value)) {
                    method.isAccessible = true
                }
                method.invoke(value)
            }.getOrNull() ?: return@forEach
            when (result) {
                is Sequence<*> -> return result
                is Iterable<*> -> return result.asSequence()
                is Iterator<*> -> return iteratorSequence(result)
            }
            if (result != value && result != null && result.javaClass.isArray) {
                return arrayElements(result)
            }
        }
        val field = runCatching { value.javaClass.getDeclaredField("values") }.getOrNull()
        if (field != null) {
            val result = runCatching {
                if (!field.canAccess(value)) {
                    field.isAccessible = true
                }
                field.get(value)
            }.getOrNull()
            when (result) {
                is Sequence<*> -> return result
                is Iterable<*> -> return result.asSequence()
                is Iterator<*> -> return iteratorSequence(result)
            }
            if (result != null && result.javaClass.isArray) {
                return arrayElements(result)
            }
        }
        return null
    }

    private fun iteratorSequence(iterator: Iterator<*>): Sequence<Any?> {
        return sequence {
            while (iterator.hasNext()) {
                yield(iterator.next())
            }
        }
    }

    private fun arrayElements(array: Any): Sequence<Any?> {
        val length = ReflectArray.getLength(array)
        return sequence {
            for (index in 0 until length) {
                yield(ReflectArray.get(array, index))
            }
        }
    }

    private fun convertToString(value: Any?): String? {
        val completion = unwrapOptional(value) ?: return null
        when (completion) {
            is CharSequence -> return completion.toString().trimMatchingQuotes()
            is Char -> return completion.toString()
            is Number -> return completion.toString()
        }
        if (vimStringClass?.isInstance(completion) == true) {
            readStringProperty(completion, "getValue", "value", "asString")?.let { return it.trimMatchingQuotes() }
        }
        if (vimNumberClass?.isInstance(completion) == true) {
            val asString = readStringProperty(completion, "asString", "getValue")
            if (!asString.isNullOrBlank()) {
                return asString.trimMatchingQuotes()
            }
        }
        val toStringValue = completion.toString()
        if (toStringValue.isNullOrBlank()) {
            return null
        }
        return toStringValue.trimMatchingQuotes()
    }

    private fun convertToBoolean(value: Any?): Boolean? {
        val completion = unwrapOptional(value) ?: return null
        return when (completion) {
            is Boolean -> completion
            is Number -> completion.toInt() != 0
            is CharSequence -> parseBooleanLiteral(completion.toString())
            else -> parseBooleanLiteral(completion.toString())
        }
    }

    private fun convertToInt(value: Any?): Int? {
        val completion = unwrapOptional(value) ?: return null
        return when (completion) {
            is Number -> completion.toInt()
            is Char -> completion.code
            else -> {
                val text = convertToString(completion) ?: return null
                text.toIntOrNull()
            }
        }
    }

    private fun parseBooleanLiteral(raw: String): Boolean? {
        val text = raw.trim().lowercase(Locale.ROOT)
        return when (text) {
            "1", "true", "yes", "on", "enable", "enabled" -> true
            "0", "false", "no", "off", "disable", "disabled" -> false
            else -> null
        }
    }

    private fun parseListLiteral(raw: String): List<String> {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) {
            return emptyList()
        }
        val bracketed = cleaned.startsWith("[") && cleaned.endsWith("]")
        val content = if (bracketed) cleaned.substring(1, cleaned.length - 1) else cleaned
        val commaSplit = content.split(',')
            .map { it.trim().trimMatchingQuotes() }
            .filter { it.isNotEmpty() }
        if (commaSplit.size > 1) {
            return commaSplit
        }
        if (!bracketed && commaSplit.isEmpty()) {
            val whitespaceSplit = content.split(Regex("\\s+")).map { it.trimMatchingQuotes() }.filter { it.isNotEmpty() }
            if (whitespaceSplit.size > 1) {
                return whitespaceSplit
            }
        }
        val single = content.trimMatchingQuotes()
        return if (single.isNotEmpty()) listOf(single) else emptyList()
    }

    private fun unwrapOptional(completion: Any?): Any? {
        if (completion == null) {
            return null
        }
        if (completion is Optional<*>) {
            return if (completion.isPresent) completion.get() else null
        }
        val className = completion.javaClass.name
        if (className.startsWith("java.util.Optional")) {
            val isPresent = runCatching {
                val method = completion.javaClass.getMethod("isPresent")
                if (!method.canAccess(completion)) {
                    method.isAccessible = true
                }
                method.invoke(completion) as? Boolean
            }.getOrNull()
            if (isPresent != true) {
                return null
            }
            val getter = runCatching { completion.javaClass.getMethod("get") }.getOrNull()
            if (getter != null) {
                if (!getter.canAccess(completion)) {
                    getter.isAccessible = true
                }
                return runCatching { getter.invoke(completion) }.getOrNull()
            }
        }
        return completion
    }

    private fun String.trimMatchingQuotes(): String {
        var result = this.trim()
        if (result.length >= 2) {
            val first = result.first()
            val last = result.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                result = result.substring(1, result.length - 1).trim()
            }
        }
        return result
    }

    fun isIgnoreCaseEnabled(): Boolean? = readBooleanOption("ignorecase")

    fun isSmartCaseEnabled(): Boolean? = readBooleanOption("smartcase")

    fun previewSearch(editor: Editor, mode: OverlayMode, query: String, initialOffset: Int) {
        if (!isAvailable()) {
            return
        }
        val caretOffset = if (initialOffset >= 0) initialOffset else editor.caretModel.primaryCaret.offset
        storeSearchState(editor)
        if (query.isEmpty()) {
            cancelSearchPreview(editor, caretOffset)
            return
        }

        try {
            closeEditorSearchSession(editor)
            val incsearch = updateIncsearchMethodInfo ?: return
            val forwards = mode != OverlayMode.SEARCH_BACKWARD
            val args = arrayOfNulls<Any?>(incsearch.method.parameterTypes.size)
            args[0] = editor
            args[1] = query
            val countIndex = incsearch.countIndex
            if (countIndex != null && countIndex in args.indices) {
                args[countIndex] = 1
            }
            if (incsearch.forwardsIndex in args.indices) {
                args[incsearch.forwardsIndex] = forwards
            }
            if (incsearch.caretIndex in args.indices) {
                args[incsearch.caretIndex] = caretOffset
            }
            if (incsearch.rangeIndex in args.indices && incsearch.rangeIndex >= 0) {
                args[incsearch.rangeIndex] = null
            }
            val result = runCatching {
                incsearch.method.invoke(null, *args)
            }.getOrNull()
            val matchOffset = when (result) {
                is Int -> result
                is java.lang.Integer -> result.toInt()
                else -> -1
            }
            if (matchOffset != null && matchOffset >= 0) {
                moveCaret(editor, matchOffset)
            } else {
                restoreCaret(editor, caretOffset)
            }
        } catch (throwable: Throwable) {
            logger.debug("Failed to process incremental search for query '$query'", throwable)
        }
    }

    fun resetSearchPreview() {
        if (!isAvailable()) {
            return
        }
        try {
            val searchGroup = obtainSearchGroup() ?: return
            searchResetMethod?.invoke(searchGroup)
        } catch (throwable: Throwable) {
            logger.debug("Failed to reset incremental search state", throwable)
        }
    }

    fun cancelSearchPreview(editor: Editor, initialOffset: Int) {
        if (!isAvailable()) {
            return
        }
        restoreSearchState(editor)
        clearSearchHighlights(editor)
        resetSearchPreview()
        closeEditorSearchSession(editor)
        restoreCaret(editor, initialOffset)
    }

    private fun readAllOptions(optionGroup: Any): Collection<Any?> {
        val method = optionGroup.javaClass.methods.firstOrNull { completion ->
            completion.parameterCount == 0 && completion.name == "getAllOptions"
        } ?: return emptyList()
        val raw = runCatching {
            if (!method.canAccess(optionGroup)) {
                method.isAccessible = true
            }
            method.invoke(optionGroup)
        }.getOrElse { throwable ->
            logger.warn("Failed to invoke getAllOptions on ${optionGroup.javaClass.name}", throwable)
            return emptyList()
        }
        return when (raw) {
            is Collection<*> -> raw as Collection<Any?>
            is Array<*> -> raw.toList()
            else -> {
                logger.warn("Unexpected getAllOptions result type: ${raw?.javaClass?.name}")
                emptyList()
            }
        }
    }

    private fun obtainOptionGroup(): Any? {
        val method = vimPluginOptionGroupMethod ?: return null
        return try {
            if (!method.canAccess(null)) {
                method.isAccessible = true
            }
            method.invoke(null)
        } catch (throwable: Throwable) {
            logger.debug("Failed to obtain IdeaVim option group.", throwable)
            null
        }
    }

    private fun safeOptionName(option: Any?): String? {
        option ?: return null
        return readStringProperty(option, "getName", "name")
    }

    private fun safeOptionAbbreviation(option: Any?): String? {
        option ?: return null
        return readStringProperty(option, "getAbbrev", "abbrev")?.takeIf { it.isNotBlank() }
    }

    private fun readStringProperty(target: Any, vararg accessors: String): String? {
        for (name in accessors) {
            val result = runCatching {
                val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                if (method != null) {
                    if (!method.canAccess(target)) {
                        method.isAccessible = true
                    }
                    method.invoke(target) as? String
                } else {
                    val field = runCatching { target.javaClass.getDeclaredField(name) }.getOrNull()
                    if (field != null) {
                        if (!field.canAccess(target)) {
                            field.isAccessible = true
                        }
                        field.get(target) as? String
                    } else {
                        null
                    }
                }
            }.getOrElse {
                logger.debug("Failed to read property $name from ${target.javaClass.name}", it)
                null
            }
            if (!result.isNullOrBlank()) {
                return result
            }
        }
        return null
    }

    private fun readBooleanOption(optionName: String): Boolean? {
        if (!isAvailable()) {
            return null
        }
        return try {
            val optionGroup = obtainOptionGroup() ?: return null
            val optionInstance = getOptionFromGroup(optionGroup, optionName) ?: return null
            val rawValue = extractOptionValue(optionInstance) ?: return null
            when (rawValue) {
                is Boolean -> rawValue
                is Number -> rawValue.toInt() != 0
                is String -> rawValue.equals("true", ignoreCase = true) || rawValue == "1"
                else -> null
            }
        } catch (throwable: Throwable) {
            logger.debug("Failed to read IdeaVim option $optionName", throwable)
            null
        }
    }

    private fun getOptionFromGroup(optionGroup: Any, optionName: String): Any? {
        val methods = optionGroup.javaClass.methods
        val completion = methods.firstOrNull { method ->
            method.parameterCount == 1 &&
                    method.parameterTypes[0] == String::class.java &&
                    (method.name == "getOption" || method.name == "getOptionValue")
        } ?: return null
        return try {
            if (!completion.canAccess(optionGroup)) {
                completion.isAccessible = true
            }
            completion.invoke(optionGroup, optionName)
        } catch (throwable: Throwable) {
            logger.debug("Failed to access option $optionName from IdeaVim option group.", throwable)
            null
        }
    }

    private fun extractOptionValue(option: Any): Any? {
        val optionClass = option.javaClass
        val methods = optionClass.methods.filter { it.parameterCount == 0 }
        val valueMethod = methods.firstOrNull { method -> method.name == "getValue" || method.name == "value" }
        if (valueMethod != null) {
            return try {
                if (!valueMethod.canAccess(option)) {
                    valueMethod.isAccessible = true
                }
                valueMethod.invoke(option)
            } catch (throwable: Throwable) {
                logger.debug("Failed to invoke IdeaVim option value accessor on ${optionClass.name}", throwable)
                null
            }
        }
        val isSetMethod = methods.firstOrNull { it.name == "isSet" }
        if (isSetMethod != null) {
            return try {
                if (!isSetMethod.canAccess(option)) {
                    isSetMethod.isAccessible = true
                }
                isSetMethod.invoke(option)
            } catch (throwable: Throwable) {
                logger.debug("Failed to invoke IdeaVim option isSet accessor on ${optionClass.name}", throwable)
                null
            }
        }
        return null
    }

    fun replay(editor: Editor, mode: OverlayMode, payload: String) {
        if (!isAvailable()) {
            return
        }

        val keys = sequenceOf(mode.prefix) + payload.asSequence() + sequenceOf('\n')
        dispatchKeys(editor, keys, "Failed to replay IdeaVim command sequence.")
    }

    fun typeKeys(editor: Editor, keys: CharSequence) {
        if (!isAvailable() || keys.isEmpty()) {
            return
        }
        dispatchKeys(editor, keys.asSequence(), "Failed to dispatch IdeaVim key sequence.")
    }

    fun reselectLastVisualSelection(editor: Editor) {
        typeKeys(editor, "gv")
    }

    private fun dispatchKeys(editor: Editor, keys: Sequence<Char>, failureMessage: String) {
        try {
            val plugin = vimPluginInstanceMethod?.invoke(null) ?: return
            val keyHandler = vimPluginKeyMethod?.invoke(plugin) ?: return
            val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
            var handleKeySupported = true
            var fallbackLogged = false
            keys.forEach { character ->
                val stroke = toKeyStroke(character)
                val handled = if (handleKeySupported) {
                    invokeHandleKey(keyHandler, editor, stroke, dataContext)
                } else {
                    false
                }
                if (!handled) {
                    handleKeySupported = false
                    if (!fallbackLogged) {
                        logger.info("Falling back to IDE event queue replay for IdeaVim command overlay.")
                        fallbackLogged = true
                    }
                    suppressOverlayFor(300)
                    dispatchThroughIdeQueue(editor, character)
                }
            }
        } catch (throwable: Throwable) {
            logger.warn(failureMessage, throwable)
        }
    }

    private fun loadClass(vararg names: String): Class<*>? {
        for (name in names) {
            try {
                return Class.forName(name)
            } catch (_: ClassNotFoundException) {
                continue
            } catch (throwable: Throwable) {
                logger.warn("Unable to load class $name", throwable)
            }
        }
        return null
    }

    private fun invokeHandleKey(
        keyHandler: Any,
        editor: Editor,
        stroke: KeyStroke,
        dataContext: DataContext,
    ): Boolean {
        val handlerClass = keyHandler.javaClass
        val methods = handleKeyMethodCache.computeIfAbsent(handlerClass) {
            handlerClass.methods.filter { method ->
                if (method.name != "handleKey") {
                    return@filter false
                }

                // 2025.2 起 IdeaVim 的 handleKey 改为使用 VimEditor/ExecutionContext 签名，不再必然包含 Editor。
                // 这里仅要求能够识别按键参数，具体是否支持由 buildArguments 再次判定。
                method.parameterTypes.any { KeyStroke::class.java.isAssignableFrom(it) }
            }.sortedBy { it.parameterCount }
        }

        if (methods.isEmpty()) {
            logHandleKeyFailure(handlerClass, null, null)
            return false
        }

        for (method in methods) {
            val args = buildArguments(method.parameterTypes, editor, stroke, dataContext) ?: continue
            try {
                if (!method.canAccess(keyHandler)) {
                    method.isAccessible = true
                }
                method.invoke(keyHandler, *args)
                return true
            } catch (throwable: Throwable) {
                logHandleKeyFailure(handlerClass, method, throwable)
            }
        }

        logHandleKeyFailure(handlerClass, null, null)
        return false
    }

    private fun buildArguments(
        parameterTypes: Array<Class<*>>,
        editor: Editor,
        stroke: KeyStroke,
        dataContext: DataContext,
    ): Array<Any?>? {
        val arguments = arrayOfNulls<Any>(parameterTypes.size)
        for (index in parameterTypes.indices) {
            val type = parameterTypes[index]
            arguments[index] = when {
                type.isAssignableFrom(editor.javaClass) -> editor
                type.isAssignableFrom(stroke.javaClass) -> stroke
                type.isAssignableFrom(dataContext.javaClass) -> dataContext
                vimEditorClass != null && type.isAssignableFrom(vimEditorClass) -> createVimEditorWrapper(editor)
                executionContextClass != null && type.isAssignableFrom(executionContextClass) -> createExecutionContextWrapper(
                    editor,
                    dataContext
                )

                type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> java.lang.Boolean.FALSE
                else -> return null
            }
        }
        return arguments
    }

    private fun logHandleKeyFailure(handlerClass: Class<*>, method: Method?, throwable: Throwable?) {
        val signature =
            method?.parameterTypes?.joinToString(prefix = "(", postfix = ")", separator = ",") { it.simpleName }
        val key = buildString {
            append(handlerClass.name)
            append('#')
            append(signature ?: "missing")
        }
        if (!handleKeyFailureLogged.add(key)) {
            return
        }

        if (method == null) {
            logger.warn("No compatible IdeaVim handleKey method found on ${handlerClass.name}.")
        } else if (throwable != null) {
            logger.warn(
                "Failed to invoke IdeaVim handleKey via signature ${handlerClass.name}.${method.name}$signature.",
                throwable
            )
        }
    }

    private fun toKeyStroke(character: Char): KeyStroke {
        return when (character) {
            '\n' -> KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
            else -> KeyStroke.getKeyStroke(character)
        }
    }

    private fun dispatchThroughIdeQueue(editor: Editor, character: Char) {
        val component = editor.contentComponent
        val queue = IdeEventQueue.getInstance()
        val timestamp = System.currentTimeMillis()
        val keyCode = when (character) {
            '\n' -> KeyEvent.VK_ENTER
            else -> KeyEvent.getExtendedKeyCodeForChar(character.code)
        }.takeIf { it != KeyEvent.VK_UNDEFINED } ?: KeyEvent.VK_UNDEFINED
        val modifiers = deriveModifiers(character)

        if (keyCode != KeyEvent.VK_UNDEFINED) {
            queue.postEvent(
                KeyEvent(
                    component,
                    KeyEvent.KEY_PRESSED,
                    timestamp,
                    modifiers,
                    keyCode,
                    KeyEvent.CHAR_UNDEFINED
                )
            )
        }
        val typed = KeyEvent(component, KeyEvent.KEY_TYPED, timestamp, modifiers, KeyEvent.VK_UNDEFINED, character)
        queue.postEvent(typed)
        if (keyCode != KeyEvent.VK_UNDEFINED) {
            queue.postEvent(
                KeyEvent(
                    component,
                    KeyEvent.KEY_RELEASED,
                    timestamp,
                    modifiers,
                    keyCode,
                    KeyEvent.CHAR_UNDEFINED
                )
            )
        }
    }

    private fun deriveModifiers(character: Char): Int {
        if (character.isUpperCase()) {
            return InputEvent.SHIFT_DOWN_MASK
        }
        return when (character) {
            ':', '?', '+', '*', '"', '<', '>', '{', '}', '|', '_',
            '~', '^', '&', '%', '$', '#', '@', ')', '(', '!', '=',
            '{', '}', '\u00A7', '\u00B0' -> InputEvent.SHIFT_DOWN_MASK

            else -> 0
        }
    }

    private fun suppressOverlayFor(durationMillis: Long) {
        val newDeadline = System.currentTimeMillis() + durationMillis
        while (true) {
            val current = overlaySuppressionDeadline.get()
            if (current >= newDeadline) {
                return
            }
            if (overlaySuppressionDeadline.compareAndSet(current, newDeadline)) {
                return
            }
        }
    }

    fun isOverlaySuppressed(): Boolean {
        return System.currentTimeMillis() < overlaySuppressionDeadline.get()
    }

    private fun createVimEditorWrapper(editor: Editor): Any? {
        val targetClass = vimEditorClass ?: return null
        if (targetClass.isInstance(editor)) {
            return editor
        }

        val result = tryCreateInstance(
            expectedType = targetClass,
            completions = listOf(
                "com.maddyhome.idea.vim.newapi.VimEditorKt",
                "com.maddyhome.idea.vim.newapi.IjVimEditorKt",
                "com.maddyhome.idea.vim.newapi.EditorKt",
                "com.maddyhome.idea.vim.helper.EditorHelperKt",
                "com.maddyhome.idea.vim.helper.EditorHelper",
                "com.maddyhome.idea.vim.newapi.IjVimEditor",
                "com.maddyhome.idea.vim.impl.VimEditorImpl",
            ),
            availableArgs = buildList {
                add(editor)
                editor.project?.let { add(it) }
                add(editor.contentComponent)
                add(editor.caretModel.primaryCaret)
            },
        )

        if (result == null && vimEditorCreationCache.add(targetClass)) {
            logger.warn("Unable to construct IdeaVim VimEditor wrapper; command overlay replay may not function.")
        }
        return result
    }

    private fun createExecutionContextWrapper(editor: Editor, dataContext: DataContext): Any? {
        val targetClass = executionContextClass ?: return null
        val result = tryCreateInstance(
            expectedType = targetClass,
            completions = listOf(
                "com.maddyhome.idea.vim.newapi.ExecutionContextKt",
                "com.maddyhome.idea.vim.newapi.IjExecutionContextKt",
                "com.maddyhome.idea.vim.newapi.ExecutionContextHelperKt",
                "com.maddyhome.idea.vim.helper.ExecutionContextHelperKt",
                "com.maddyhome.idea.vim.newapi.IjExecutionContext",
                "com.maddyhome.idea.vim.helper.ExecutionContextHelper",
            ),
            availableArgs = buildList {
                add(editor)
                editor.project?.let { add(it) }
                add(dataContext)
                add(editor.contentComponent)
                add(editor.caretModel.primaryCaret)
                add(java.lang.Boolean.FALSE)
            },
        )

        if (result == null && executionContextCreationCache.add(targetClass)) {
            logger.warn("Unable to construct IdeaVim ExecutionContext wrapper; command overlay replay may not function.")
        }
        return result
    }

    private fun tryCreateInstance(
        expectedType: Class<*>,
        completions: List<String>,
        availableArgs: List<Any>,
    ): Any? {
        completions.forEach { className ->
            val clazz = loadClass(className) ?: return@forEach

            if (expectedType.isAssignableFrom(clazz)) {
                instantiateWithConstructors(clazz, expectedType, availableArgs)?.let { return it }
            }

            clazz.methods.forEach { method ->
                if (!expectedType.isAssignableFrom(method.returnType)) {
                    return@forEach
                }
                val args = matchArguments(method.parameterTypes, availableArgs) ?: return@forEach
                try {
                    val target = if (Modifier.isStatic(method.modifiers)) null else kotlinObjectInstance(clazz)
                    val result = method.invoke(target, *args)
                    if (result != null && expectedType.isInstance(result)) {
                        return result
                    }
                } catch (throwable: Throwable) {
                    logger.debug("Failed to invoke factory method ${clazz.name}.${method.name}", throwable)
                }
            }
        }
        return null
    }

    private fun instantiateWithConstructors(
        clazz: Class<*>,
        expectedType: Class<*>,
        availableArgs: List<Any>,
    ): Any? {
        clazz.declaredConstructors.forEach { constructor ->
            val args = matchArguments(constructor.parameterTypes, availableArgs) ?: return@forEach
            try {
                if (!constructor.canAccess(null)) {
                    constructor.isAccessible = true
                }
                val instance = constructor.newInstance(*args)
                if (instance != null && expectedType.isInstance(instance)) {
                    return instance
                }
            } catch (throwable: Throwable) {
                logger.debug(
                    "Failed to invoke constructor ${clazz.name}(${constructor.parameterTypes.joinToString { it.simpleName }})",
                    throwable
                )
            }
        }
        return null
    }

    private fun matchArguments(
        parameterTypes: Array<Class<*>>,
        availableArgs: List<Any>,
    ): Array<Any?>? {
        val resolved = arrayOfNulls<Any>(parameterTypes.size)
        parameterTypes.forEachIndexed { index, parameterType ->
            val match = availableArgs.firstOrNull { completion ->
                parameterType.isInstance(completion) || (parameterType.isPrimitive && wrapPrimitive(parameterType).isInstance(
                    completion
                ))
            } ?: return null
            resolved[index] = if (parameterType.isPrimitive) {
                unwrapPrimitive(parameterType, match)
            } else {
                match
            }
        }
        return resolved
    }

    private fun wrapPrimitive(primitive: Class<*>): Class<*> = when (primitive) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> primitive
    }

    private fun unwrapPrimitive(primitive: Class<*>, value: Any): Any = when (primitive) {
        java.lang.Boolean.TYPE -> (value as? Boolean) ?: java.lang.Boolean.FALSE
        java.lang.Integer.TYPE -> (value as? Number)?.toInt() ?: 0
        java.lang.Long.TYPE -> (value as? Number)?.toLong() ?: 0L
        java.lang.Short.TYPE -> (value as? Number)?.toShort() ?: 0
        java.lang.Byte.TYPE -> (value as? Number)?.toByte() ?: 0
        java.lang.Character.TYPE -> when (value) {
            is Char -> value
            is Number -> value.toInt().toChar()
            else -> 0.toChar()
        }

        java.lang.Float.TYPE -> (value as? Number)?.toFloat() ?: 0f
        java.lang.Double.TYPE -> (value as? Number)?.toDouble() ?: 0.0
        else -> value
    }

    private fun kotlinObjectInstance(clazz: Class<*>): Any? {
        return runCatching { clazz.getField("INSTANCE").get(null) }.getOrNull()
    }

    private fun obtainSearchGroup(): Any? {
        return try {
            vimPluginSearchMethod?.invoke(null)
        } catch (throwable: Throwable) {
            logger.debug("Failed to obtain IdeaVim search group", throwable)
            null
        }
    }

    private fun closeEditorSearchSession(editor: Editor) {
        try {
            val editorGroup = vimPluginEditorMethod?.invoke(null) ?: return
            closeEditorSearchSessionMethod?.invoke(editorGroup, editor)
        } catch (throwable: Throwable) {
            logger.debug("Failed to close editor search session", throwable)
        }
    }

    private fun clearSearchHighlights(editor: Editor) {
        try {
            searchHighlightsHelperClass?.getMethod(
                "updateSearchHighlights",
                String::class.java,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE
            )
        } catch (_: Throwable) {
            // ignore, not the overload we need
        }
        try {
            val removeMethod =
                searchHighlightsHelperClass?.getDeclaredMethod("removeSearchHighlights", Editor::class.java)
            if (removeMethod != null) {
                if (!removeMethod.canAccess(null)) {
                    removeMethod.isAccessible = true
                }
                removeMethod.invoke(null, editor)
            }
        } catch (throwable: Throwable) {
            logger.debug("Failed to clear search highlights", throwable)
        }
    }

    private fun restoreVisibleArea(editor: Editor, area: Rectangle) {
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) {
                return@invokeLater
            }
            val scrollingModel = editor.scrollingModel
            scrollingModel.scrollHorizontally(area.x)
            scrollingModel.scrollVertically(area.y)
        }
    }

    private fun moveCaret(editor: Editor, offset: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) {
                return@invokeLater
            }
            val target = offset.coerceIn(0, editor.document.textLength)
            val scrollingModel = editor.scrollingModel
            val visibleArea = scrollingModel.visibleArea
            val targetVisualPos = editor.offsetToVisualPosition(target)
            val targetPoint = editor.visualPositionToXY(targetVisualPos)
            val needsScroll = !visibleArea.contains(targetPoint)
            editor.caretModel.primaryCaret.moveToOffset(target)
            if (needsScroll) {
                val logicalPosition = editor.offsetToLogicalPosition(target)
                scrollingModel.scrollTo(logicalPosition, ScrollType.CENTER)
            }
        }
    }

    fun restoreCaret(editor: Editor, offset: Int) {
        if (offset < 0) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) {
                return@invokeLater
            }
            val target = offset.coerceIn(0, editor.document.textLength)
            editor.caretModel.primaryCaret.moveToOffset(target)
        }
    }

    fun commitSearchPreview(editor: Editor) {
        searchPreviewState.remove(editor)
    }

    private fun storeSearchState(editor: Editor) {
        if (searchPreviewState.containsKey(editor)) {
            return
        }
        val searchGroup = obtainSearchGroup() ?: return
        val pattern = (searchGetLastPatternMethod?.invoke(searchGroup) as? String)
        val substitute = (searchGetLastSubstituteMethod?.invoke(searchGroup) as? String)
        val directionInt = (searchGetLastDirMethod?.invoke(searchGroup) as? Int) ?: 1
        val direction = when {
            directionInt < 0 -> directionBackward
            else -> directionForward
        }
        val visibleArea = Rectangle(editor.scrollingModel.visibleArea)
        searchPreviewState.putIfAbsent(editor, SearchPreviewState(pattern, substitute, direction, visibleArea))
    }

    private fun restoreSearchState(editor: Editor) {
        val state = searchPreviewState.remove(editor) ?: return
        val searchGroup = obtainSearchGroup() ?: return
        val direction = state.direction ?: directionForward ?: return
        val pattern = state.pattern ?: ""
        val substitute = state.substitute ?: ""
        searchSetLastStateMethod?.invoke(searchGroup, editor, pattern, substitute, direction)
        state.visibleArea?.let { restoreVisibleArea(editor, it) }
    }

    private data class UpdateIncsearchMethod(
        val method: Method,
        val countIndex: Int?,
        val forwardsIndex: Int,
        val caretIndex: Int,
        val rangeIndex: Int,
    )

    private data class SearchPreviewState(
        val pattern: String?,
        val substitute: String?,
        val direction: Any?,
        val visibleArea: Rectangle?,
    )
}
