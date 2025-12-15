package com.example.my_plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManagerListener
import controller.LicensingController

class ProjectListener : ProjectManagerListener {
    private val LOG = Logger.getInstance(ProjectListener::class.java)

    override fun projectClosing(project: com.intellij.openapi.project.Project) {

        project.getService(LicensingController::class.java).dispose()
        project.getService(PythonServerService::class.java).dispose()
    }
}