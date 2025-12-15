package com.example.my_plugin

import com.example.my_plugin.MyToolWindowBridge.Companion.getInstance
import com.example.my_plugin.license.LicenseQuestionnaireDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.example.my_plugin.MavenDependencyService
import com.example.my_plugin.LogInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

class ProjectStartupActivity : ProjectActivity {

    companion object {
        private val LOG: Logger = LogInitializer.getLogger(ProjectStartupActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        // Initialize per-project logging (creates .license-tool/<project>.log)
        try {
            LogInitializer.setupLoggingForProject(project)
        } catch (t: Throwable) {
            // If logging initialization fails, continue but warn
            LoggerFactory.getLogger(ProjectStartupActivity::class.java).warn("Failed to initialize project logging", t)
        }

        LOG.info("Starting project activity for: {}", project.name)
        // start the python server
        println("Starting project activity for: ${project.name}")
        project.getService(PythonServerService::class.java).ensureStarted()

        // Access the bridge service; the UI may not be created yet by the ToolWindowFactory,
        // so avoid forcing a non-null and handle the null case gracefully.
        val bridge = project.getService(MyToolWindowBridge::class.java)
        val toolWindow = bridge?.ui
        if (toolWindow == null) {
            LOG.warn("Tool window UI not yet initialized for project {}", project.name)
        }

        LOG.info("Project opened: {}", project.name)
         // Check if the license survey file exists
         val licenseFile = surveyFile(project)
         if (licenseFile.exists()) {
            LOG.info("License survey file exists at: {}", licenseFile.absolutePath)
            toolWindow?.enableSubmitButton()
            toolWindow?.enableInputArea()
         } else {
            LOG.info("License survey file does not exist.")
             // Create the .license-tool folder if it does not exists
             ensureSurveyDir(project)
             // Wait for indexing to finish, then show toolwindow and dialog on the EDT
             DumbService.getInstance(project).runWhenSmart {
                 ApplicationManager.getApplication().invokeLater {
                     // All UI must be on EDT
                     LOG.debug("Project opened (invokeLater): {}", project.name)
                     LOG.debug("toolWindow (bridge.ui): {}", bridge?.ui)
                     bridge?.ui?.let { ui ->
                         if (!ui.isToolWindowVisible(project)) {
                             ui.toggleToolWindowVisibility(project)
                         }
                     }
                     // showQuestionnaireEdt(project, toolWindow)
                     showQuestionnaireEdt(project)
                 }
             }
         }

        // If the project contains one or more pom.xml files and no SBOM exists yet, generate SBOM on background thread.
        try {
            val base = project.basePath
            if (base != null) {
                val baseDir = File(base)
                val hasPom = baseDir.walkTopDown().any { it.name == "pom.xml" }
                if (hasPom) {
                    val sbomFile = File(base, ".license-tool/bom.xml")
                    if (!sbomFile.exists()) {
                        // run SBOM generation off the EDT
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                val svc = project.getService(MavenDependencyService::class.java)
                                if (svc != null) {
                                    LOG.info("Triggering SBOM generation on project open for: {}", project.name)
                                    svc.genSbom()
                                } else {
                                    LOG.warn("MavenDependencyService not available on project: {}", project.name)
                                }
                            } catch (t: Throwable) {
                                LOG.error("Error during SBOM generation", t)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            LOG.error("Unexpected error in ProjectStartupActivity", t)
        }

    }

    private fun surveyDir(project: Project): java.io.File {
        val base = project.basePath ?: return java.io.File(".")
        return java.io.File(base, ".license-tool")
    }

    private fun surveyFile(project: Project): java.io.File =
        java.io.File(surveyDir(project), "license-survey.json")

    private fun ensureSurveyDir(project: Project) {
        val dir = surveyDir(project)
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    /**
     * Persist the filled questionnaire JSON to .license-tool/license-survey.json
     * Returns true on success, false otherwise.
     */
    private fun saveSurveyJson(project: Project, json: String): Boolean {
        return try {
            ensureSurveyDir(project)
            val file = surveyFile(project)
            file.writeText(json.trim() + System.lineSeparator(), Charsets.UTF_8)
            LOG.info("License survey saved to: {}", file.absolutePath)
            true
        } catch (t: Throwable) {
            LOG.error("Failed to save license survey: {}", t.message)
            false
        }
    }


    @RequiresEdt
    //private fun showQuestionnaireEdt(project: Project, toolWindow: MyToolWindowFactory?) {
    private fun showQuestionnaireEdt(project: Project) {
        val dialog = LicenseQuestionnaireDialog(project)
        dialog.show() // modal: returns when closed

        // After closing, check if the JSON has been created using the safe helper
        val created = surveyFile(project).exists()
        if (created) {
            LOG.info("License survey created. Submit button enabled in the tool window.")
            // toolWindow?.enableSubmitButton()
        } else {
            LOG.info("The questionnaire has not been saved by the user.")
        }
    }
}