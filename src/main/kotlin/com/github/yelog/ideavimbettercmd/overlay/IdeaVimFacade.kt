package com.github.yelog.ideavimbettercmd.overlay

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
    private val vimPluginInstanceMethod = vimPluginClass?.getMethod("getInstance")
    private val vimPluginKeyMethod = vimPluginClass?.getMethod("getKey")
    private val handleKeyMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val handleKeyFailureLogged = ConcurrentHashMap.newKeySet<String>()
    private val vimEditorClass = loadClass("com.maddyhome.idea.vim.api.VimEditor")
    private val executionContextClass = loadClass("com.maddyhome.idea.vim.api.ExecutionContext")
    private val vimEditorCreationCache = ConcurrentHashMap.newKeySet<Class<*>>()
    private val executionContextCreationCache = ConcurrentHashMap.newKeySet<Class<*>>()
    private val overlaySuppressionDeadline = AtomicLong(0)

    fun isAvailable(): Boolean {
        return vimPluginClass != null && commandStateClass != null && vimPluginKeyMethod != null
    }

    fun isEditorInNormalMode(editor: Editor): Boolean {
        if (!isAvailable()) {
            return false
        }
        return try {
            val commandState = commandStateInstanceMethod?.invoke(null, editor) ?: return false
            val mode = commandStateModeMethod?.invoke(commandState)
            (mode as? Enum<*>)?.name == "COMMAND"
        } catch (throwable: Throwable) {
            logger.warn("Failed to query IdeaVim command state.", throwable)
            false
        }
    }

    fun replay(editor: Editor, mode: OverlayMode, payload: String) {
        if (!isAvailable()) {
            return
        }

        try {
            val plugin = vimPluginInstanceMethod?.invoke(null) ?: return
            val keyHandler = vimPluginKeyMethod?.invoke(plugin) ?: return
            val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
            val keys = sequenceOf(mode.prefix) + payload.asSequence() + sequenceOf('\n')
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
            logger.warn("Failed to replay IdeaVim command sequence.", throwable)
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
                method.name == "handleKey" &&
                    method.parameterTypes.any { Editor::class.java.isAssignableFrom(it) } &&
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
                executionContextClass != null && type.isAssignableFrom(executionContextClass) -> createExecutionContextWrapper(editor, dataContext)
                type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> java.lang.Boolean.FALSE
                else -> return null
            }
        }
        return arguments
    }

    private fun logHandleKeyFailure(handlerClass: Class<*>, method: Method?, throwable: Throwable?) {
        val signature = method?.parameterTypes?.joinToString(prefix = "(", postfix = ")", separator = ",") { it.simpleName }
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
            logger.warn("Failed to invoke IdeaVim handleKey via signature ${handlerClass.name}.${method.name}$signature.", throwable)
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
            queue.postEvent(KeyEvent(component, KeyEvent.KEY_PRESSED, timestamp, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED))
        }
        val typed = KeyEvent(component, KeyEvent.KEY_TYPED, timestamp, modifiers, KeyEvent.VK_UNDEFINED, character)
        queue.postEvent(typed)
        if (keyCode != KeyEvent.VK_UNDEFINED) {
            queue.postEvent(KeyEvent(component, KeyEvent.KEY_RELEASED, timestamp, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED))
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
            candidates = listOf(
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
            candidates = listOf(
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
        candidates: List<String>,
        availableArgs: List<Any>,
    ): Any? {
        candidates.forEach { className ->
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
                logger.debug("Failed to invoke constructor ${clazz.name}(${constructor.parameterTypes.joinToString { it.simpleName }})", throwable)
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
            val match = availableArgs.firstOrNull { candidate ->
                parameterType.isInstance(candidate) || (parameterType.isPrimitive && wrapPrimitive(parameterType).isInstance(candidate))
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
}
