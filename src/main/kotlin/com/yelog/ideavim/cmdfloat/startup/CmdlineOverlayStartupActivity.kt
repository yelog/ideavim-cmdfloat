package com.yelog.ideavim.cmdfloat.startup

import com.yelog.ideavim.cmdfloat.services.CmdlineOverlayService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CmdlineOverlayStartupActivity : ProjectActivity {

    private val logger = Logger.getInstance(CmdlineOverlayStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) {
            logger.debug("Skip overlay initialization in headless environment.")
            return
        }

        project.getService(CmdlineOverlayService::class.java).initialize()
    }
}
