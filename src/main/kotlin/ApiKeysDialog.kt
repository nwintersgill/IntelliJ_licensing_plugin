package com.example.my_plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.icons.AllIcons
import javax.swing.JToggleButton
import javax.swing.JComponent

// Simple dialog to configure API keys / endpoints
class ApiKeysDialog(private val project: Project) : DialogWrapper(project, true) {
    private val props = PropertiesComponent.getInstance(project)

    private val openAiKeyField = JBPasswordField()
    private val defaultEchoChar: Char = openAiKeyField.echoChar

    companion object {
        // Use the shared initializer so logs go to the per-project file configured by LogInitializer
        private val LOG = LogInitializer.getLogger(ApiKeysDialog::class.java)
        private const val KEY_OPENAI = "license_tool.openai_api_key"
    }

    // Utility to mask API keys for logging (never log full secret)
    private fun maskKey(key: String): String {
        if (key.length <= 8) return "****"
        return key.take(4) + "..." + key.takeLast(4)
    }

    init {
        title = "API Keys"
        // Prefill from saved values
        openAiKeyField.text = props.getValue(KEY_OPENAI, "")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = panel {
            group("OpenAI") {
                row("API Key") {
                    cell(openAiKeyField).resizableColumn().align(Align.FILL)
                    val eyeToggle = JToggleButton(AllIcons.Actions.Preview).apply {
                        toolTipText = "Show/Hide"
                        isContentAreaFilled = false
                        isBorderPainted = false
                        addActionListener {
                            if (isSelected) {
                                openAiKeyField.echoChar = 0.toChar()
                                toolTipText = "Hide"
                            } else {
                                openAiKeyField.echoChar = defaultEchoChar
                                toolTipText = "Show"
                            }
                        }
                    }
                    cell(eyeToggle)
                }
            }
        }
        root.preferredSize = com.intellij.util.ui.JBUI.size(560, 120)
        root.minimumSize = com.intellij.util.ui.JBUI.size(520, 120)
        return root
    }

    override fun doValidate(): ValidationInfo? {
        val allEmpty = openAiKeyField.password.isEmpty()
        return if (allEmpty) ValidationInfo("Set the API key to use the OpenAI-based assistant.") else null
    }

    override fun doOKAction() {
        val openAiKey = String(openAiKeyField.password)

        // Save to IntelliJ persistent storage
        props.setValue(KEY_OPENAI, openAiKey)

        // Also write OpenAI key to .license-tool/openai_key.txt in project directory
        project.basePath?.let { basePath ->
            try {
                val file = java.io.File(basePath, ".license-tool/openai_key.txt")
                file.parentFile.mkdirs() // ensure dir exists

                // Write the key and ensure it's flushed to disk using the same stream
                file.outputStream().use { os ->
                    val bytes = openAiKey.toByteArray(Charsets.UTF_8)
                    os.write(bytes)
                    os.flush()
                    try {
                        os.fd.sync()
                    } catch (syncEx: Throwable) {
                        // best-effort; do not fail saving because of sync issues
                        LOG.warn("Could not fsync openai_key.txt", syncEx)
                    }
                }

                // Log masked key and emit structured state (do not include full key)
                LOG.info("Saved OpenAI key for project {} — masked={}", project.name, maskKey(openAiKey))
                LogInitializer.logState(this::class.java, project, "openai_key_saved", mapOf("masked" to maskKey(openAiKey), "path" to file.absolutePath))

                // Refresh VFS so listeners detect the change immediately
                try {
                    val localFs = LocalFileSystem.getInstance()
                    var vf = localFs.refreshAndFindFileByIoFile(file)
                    if (vf == null) vf = localFs.refreshAndFindFileByIoFile(file.parentFile)
                    if (vf != null) {
                        VfsUtil.markDirtyAndRefresh(false, true, true, vf)
                    } else {
                        VirtualFileManager.getInstance().asyncRefresh(null)
                    }
                } catch (vfEx: Throwable) {
                    LOG.warn("VFS refresh failed after saving OpenAI key", vfEx)
                }

                // enable UI immediately
                MyToolWindowBridge.getInstance(project).ui?.apply {
                    enableSubmitButton()
                    enableInputArea()
                }
            } catch (e: Exception) {
                LOG.error("Failed to save OpenAI key to .license-tool/openai_key.txt", e)
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Failed to save OpenAI key to .license-tool/openai_key.txt: ${'$'}{e.message}",
                    "Error"
                )
            }
        }

        super.doOKAction()
    }

/*
    override fun doCancelAction() {
        props.setValue(KEY_OPENAI, "")
        // delete the openai_key.txt file if it exists
        project.basePath?.let { basePath ->
            try {
                val file = java.io.File(basePath, ".license-tool/openai_key.txt")
                if (file.exists()) {
                    val deleted = file.delete()
                    if (deleted) {
                        LOG.info("Deleted OpenAI key file for project {} at {}", project.name, file.absolutePath)
                        LogInitializer.logState(this::class.java, project, "openai_key_deleted", mapOf("path" to file.absolutePath))
                    } else {
                        LOG.warn("Failed to delete OpenAI key file at {}", file.absolutePath)
                    }

                    // Refresh VFS so listeners detect the deletion immediately
                    try {
                        val localFs = LocalFileSystem.getInstance()
                        var vf = localFs.refreshAndFindFileByIoFile(file)
                        if (vf == null) vf = localFs.refreshAndFindFileByIoFile(file.parentFile)
                        if (vf != null) {
                            VfsUtil.markDirtyAndRefresh(false, true, true, vf)
                        } else {
                            VirtualFileManager.getInstance().asyncRefresh(null)
                        }
                    } catch (vfEx: Throwable) {
                        LOG.warn("VFS refresh failed after deleting OpenAI key", vfEx)
                    }

                    // disable UI immediately
                    MyToolWindowBridge.getInstance(project).ui?.apply {
                        disableSubmitButton()
                        disableInputArea()
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to delete OpenAI key file", e)
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Failed to delete OpenAI key to py_server/openai_key.txt: ${'$'}{e.message}",
                    "Error"
                )
            }
        }

        super.doCancelAction()
    }
*/
}
