package com.github.yelog.ideavimbettercmd.overlay

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
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
            val handleMethod = keyHandler.javaClass.getMethod(
                "handleKey",
                Editor::class.java,
                KeyStroke::class.java,
                DataContext::class.java,
            )

            val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
            val keys = sequenceOf(mode.prefix) + payload.asSequence() + sequenceOf('\n')
            keys.forEach { character ->
                val stroke = KeyStroke.getKeyStroke(character)
                handleMethod.invoke(keyHandler, editor, stroke, dataContext)
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
}
