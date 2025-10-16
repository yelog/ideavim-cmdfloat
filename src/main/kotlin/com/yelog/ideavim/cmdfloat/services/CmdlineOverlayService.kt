package com.yelog.ideavim.cmdfloat.services

import com.yelog.ideavim.cmdfloat.overlay.CmdlineOverlayKeyDispatcher
import com.yelog.ideavim.cmdfloat.overlay.CmdlineOverlayManager
import com.yelog.ideavim.cmdfloat.overlay.IdeaVimFacade
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.Service
import java.awt.KeyboardFocusManager
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class CmdlineOverlayService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(CmdlineOverlayService::class.java)
    private val manager = CmdlineOverlayManager(project)
    private val dispatcher = CmdlineOverlayKeyDispatcher(manager)
    private val registered = AtomicBoolean(false)

    fun initialize() {
        if (!IdeaVimFacade.isAvailable()) {
            logger.warn("IdeaVim is not available; command overlay will stay disabled.")
            return
        }
        if (isLightEditProject()) {
            logger.debug("LightEdit project detected; skip command overlay initialization.")
            return
        }
        if (!registered.compareAndSet(false, true)) {
            return
        }

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) {
                registered.set(false)
                return@invokeLater
            }
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
            logger.info("IdeaVim command overlay dispatcher registered.")
        })
    }

    override fun dispose() {
        if (registered.compareAndSet(true, false)) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
            logger.info("IdeaVim command overlay dispatcher removed.")
        }
    }

    private fun isLightEditProject(): Boolean {
        return try {
            val clazz = Class.forName("com.intellij.ide.lightEdit.LightEditUtil")
            val method = clazz.getMethod("isLightEditProject", Project::class.java)
            val target = when {
                Modifier.isStatic(method.modifiers) -> null
                else -> runCatching { clazz.getField("INSTANCE").get(null) }.getOrNull()
            }
            (method.invoke(target, project) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }
}
