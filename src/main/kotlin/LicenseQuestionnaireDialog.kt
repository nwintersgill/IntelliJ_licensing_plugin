package com.example.my_plugin.license

import com.example.my_plugin.MyToolWindowBridge
import com.example.my_plugin.ApiKeysDialog
import com.intellij.ide.util.PropertiesComponent
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.*
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.FormBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*
import java.awt.Color
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

class LicenseQuestionnaireDialog(private val project: Project) : DialogWrapper(project, true) {

    /*
    private val spdxLicenses = arrayOf(
        "Unlicense", "Apache-2.0", "MIT", "GPL-3.0-or-later", "GPL-3.0-only", "GPL-2.0-or-later", "GPL-2.0-only",
        "GPL-1.0-or-later", "GPL-1.0-only","BSD-3-Clause", "BSD-2-Clause", "LGPL-3.0-or-later", "MPL-2.0", "EPL-2.0"
    )
    */

    private val licenseChoices = arrayOf(
        "--Choose a License--", "Unlicense", "Apache-2.0", "MIT", "GPL-3.0-or-later", "GPL-3.0-only", "GPL-2.0-or-later", "GPL-2.0-only",
        "GPL-1.0-or-later", "GPL-1.0-only","BSD-3-Clause", "BSD-2-Clause", "LGPL-3.0-or-later", "MPL-2.0", "EPL-2.0"
    )
    // --- UI fields ---
    // project
    private val tfProjectName = JBTextField(project.name)
    private val taProjectDescription = JBTextArea(3, 40).apply { lineWrap = true; wrapStyleWord = true }

    // author
    // private val tfAuthorName = JBTextField()
    // private val tfAuthorEmail = JBTextField()
    // private val tfAuthorOrg = JBTextField()

    // intent
    private val cbCommercialUse = JBCheckBox("Commercial use")
    private val cbDistribution = JBCheckBox("Allow re-distribution", true)
    private val cbModificationAllowed = JBCheckBox("Allow modification", true)
    private val cbPatentGrantRequired = JBCheckBox("Include patent grant")
    private val cbUseWithClosedSource = JBCheckBox("Allow closed‑source re-distribution", true)

    // constraints
    private val cbCopyleftRequired = JBCheckBox("Copyleft required")
    private val cbMustDiscloseSource = JBCheckBox("Must disclose source")
    private val cbMustDocumentChanges = JBCheckBox("Must document changes")
    private val cbIncludeLicenseInBinary = JBCheckBox("Include license in binaries", true)

    // lists (use IntelliJ ComboBox)
    private val cbExistingLicenses = ComboBox(licenseChoices)
    private val cbPreferredLicenses = ComboBox(licenseChoices)

    // notes
    private val taNotes = JBTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = true }

    init {
        title = "Project License Questionnaire"
        init()
        loadFromJsonIfPresent() // ← load values if file exists
        // Reread from disk when dialog is shown (case: JSON modified in editor)
        SwingUtilities.invokeLater {
            this.window?.addWindowListener(object : WindowAdapter() {
                override fun windowOpened(e: WindowEvent) {
                    loadFromJsonIfPresent()
                }
            })
        }
        cbUseWithClosedSource.addChangeListener {
            if (cbUseWithClosedSource.isSelected) {
                cbMustDiscloseSource.isSelected = false
            }
        }
        cbMustDiscloseSource.addChangeListener {
            if (cbMustDiscloseSource.isSelected) {
                cbUseWithClosedSource.isSelected = false
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val projectPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", tfProjectName)
            .addLabeledComponent("Description:", JBScrollPane(taProjectDescription))
            .panel

        val intentPanel = FormBuilder.createFormBuilder()
            .addComponent(cbCommercialUse)
            .addComponent(cbDistribution)
            .addComponent(cbModificationAllowed)
            .addComponent(cbPatentGrantRequired)
            .addComponent(cbUseWithClosedSource)
            .panel

        val constraintsPanel = FormBuilder.createFormBuilder()
            .addComponent(cbCopyleftRequired)
            .addComponent(cbMustDiscloseSource)
            .addComponent(cbMustDocumentChanges)
            .addComponent(cbIncludeLicenseInBinary)
            .panel


        val repositoryLicenseLabel = JBLabel("Repository license:").apply {
            toolTipText = "The software license assigned to this repository."
            icon = AllIcons.General.ContextHelp
        }

        //val preferredLicenseLabel = JBLabel("Preferred license:").apply {
        //    toolTipText = "A license you would like to make your repository available under, but which is not currently assigned to your repository."
        //    icon = AllIcons.General.ContextHelp
        //}

        val listsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(repositoryLicenseLabel, cbExistingLicenses)
            //.addLabeledComponent(preferredLicenseLabel, cbPreferredLicenses)
            .panel

        val notesPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Notes:", JBScrollPane(taNotes))
            .panel

        // helper to create bold section labels
        fun sectionLabel(title: String): JComponent = JBLabel("<html><b>$title</b></html>").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        // helper to create a bordered section that contains the label and content
        fun sectionPanel(title: String, content: JComponent): JComponent {
            return JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(0xDDDDDD)),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)
                )
                add(sectionLabel(title))
                add(Box.createVerticalStrut(6))
                add(content)
            }
        }

        // Single page: stack the sections vertically and make the whole thing scrollable
        val main = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(sectionPanel("Project", wrap(projectPanel)))
            add(Box.createVerticalStrut(8))

            add(sectionPanel("Intent", wrap(intentPanel)))
            add(Box.createVerticalStrut(8))

            add(sectionPanel("Constraints", wrap(constraintsPanel)))
            add(Box.createVerticalStrut(8))

            add(sectionPanel("Licenses", wrap(listsPanel)))
            add(Box.createVerticalStrut(8))

            add(sectionPanel("Notes", wrap(notesPanel)))
        }

        return JBScrollPane(main)
    }

    private fun wrap(c: JComponent): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(c)
    }

    // =========================
    // JSON I/O
    // =========================

    private fun projectDir(): Path =
        project.basePath?.let { Path.of(it) } ?: Path.of(".")

    private fun surveyDir(): Path = projectDir().resolve(".license-tool")

    private fun surveyFile(): Path = surveyDir().resolve("license-survey.json")

    private fun ensureSurveyDir() {
        if (!Files.exists(surveyDir())) {
            Files.createDirectories(surveyDir())
        }
    }

    private fun loadFromJsonIfPresent() {
        val file = surveyFile()
        if (!Files.exists(file)) return
        try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val root = JSONObject(text)

            // 1. project
            root.optJSONObject("project")?.let { pj ->
                tfProjectName.text = pj.optString("name", "")
                taProjectDescription.text = pj.optString("description", "")
                // tfRepository.text = pj.optString("repository", "")
            }

            // 2. author
           /* root.optJSONObject("author")?.let { au ->
                tfAuthorName.text = au.optString("name", "")
                tfAuthorEmail.text = au.optString("email", "")
                tfAuthorOrg.text = au.optString("organization", "")
            }*/

            // 3. intent
            root.optJSONObject("intent")?.let { itj ->
                cbCommercialUse.isSelected = itj.optBoolean("commercialUse", false)
                cbDistribution.isSelected = itj.optBoolean("distribution", true)
                cbModificationAllowed.isSelected = itj.optBoolean("modificationAllowed", true)
                cbPatentGrantRequired.isSelected = itj.optBoolean("patentGrantRequired", false)
                cbUseWithClosedSource.isSelected = itj.optBoolean("useWithClosedSource", true)
            }

            // 4. constraints
            root.optJSONObject("constraints")?.let { cj ->
                cbCopyleftRequired.isSelected = cj.optBoolean("copyleftRequired", false)
                cbMustDiscloseSource.isSelected = cj.optBoolean("mustDiscloseSource", false)
                cbMustDocumentChanges.isSelected = cj.optBoolean("mustDocumentChanges", false)
                cbIncludeLicenseInBinary.isSelected = cj.optBoolean("includeLicenseInBinary", true)
            }

            // lists
            cbExistingLicenses.selectedItem = root.optJSONArray("existingLicensesUsed")?.optString(0, "") ?: ""
            //cbPreferredLicenses.selectedItem = root.optJSONArray("preferredLicenses")?.optString(0, "") ?: ""

            // 9. notes
            taNotes.text = root.optString("notes", "")
        } catch (t: Throwable) {
            t.printStackTrace()
            // Se fallisce il parsing, lascio i default nei campi
        }
    }

    private fun saveToJson() {
        try {
            ensureSurveyDir()
            val root = JSONObject()

            // 1. project
            val projectObj = JSONObject()
                .put("name", tfProjectName.text.trim())
                .put("description", taProjectDescription.text.trim())
                // .put("repository", tfRepository.text.trim())
            root.put("project", projectObj)

            // 2. author
            /*val authorObj = JSONObject()
                .put("name", tfAuthorName.text.trim())
                .put("email", tfAuthorEmail.text.trim())
                .put("organization", tfAuthorOrg.text.trim())
            root.put("author", authorObj)*/

            // 3. intent
            val intentObj = JSONObject()
                .put("commercialUse", cbCommercialUse.isSelected)
                .put("distribution", cbDistribution.isSelected)
                .put("modificationAllowed", cbModificationAllowed.isSelected)
                .put("patentGrantRequired", cbPatentGrantRequired.isSelected)
                .put("useWithClosedSource", cbUseWithClosedSource.isSelected)
            root.put("intent", intentObj)

            // 4. constraints
            val constraintsObj = JSONObject()
                .put("copyleftRequired", cbCopyleftRequired.isSelected)
                .put("mustDiscloseSource", cbMustDiscloseSource.isSelected)
                .put("mustDocumentChanges", cbMustDocumentChanges.isSelected)
                .put("includeLicenseInBinary", cbIncludeLicenseInBinary.isSelected)
            root.put("constraints", constraintsObj)

            // lists
            root.put("existingLicensesUsed", JSONArray().put(cbExistingLicenses.selectedItem ?: ""))
            //root.put("preferredLicenses", JSONArray().put(cbPreferredLicenses.selectedItem ?: ""))

            // 9. notes
            root.put("notes", taNotes.text.trim())

            // write (pretty)
            val jsonText = root.toString(2)
            Files.writeString(surveyFile(), jsonText + System.lineSeparator(), StandardCharsets.UTF_8)

            // Refresh IntelliJ VFS so listeners detect the new/updated file immediately
            try {
                val ioFile = surveyFile().toFile()
                val localFs = LocalFileSystem.getInstance()
                var vf = localFs.refreshAndFindFileByIoFile(ioFile)
                if (vf == null) vf = localFs.refreshAndFindFileByIoFile(ioFile.parentFile)
                if (vf != null) {
                    VfsUtil.markDirtyAndRefresh(false, true, true, vf)
                } else {
                    // Fallback to async full refresh if the specific file wasn't found
                    VirtualFileManager.getInstance().asyncRefresh(null)
                }
            } catch (vfEx: Throwable) {
                // Don't fail saving if refresh fails; just print for diagnostics
                vfEx.printStackTrace()
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    // =========================
    // Dialog lifecycle
    // =========================

    override fun doOKAction() {
        if (tfProjectName.text.trim().isEmpty()) {
            setErrorText("Project name is required.")
            return
        }
        // if the model selcted is gpt-4, ensure api key is set before saving
        try {
            val selectedModel = MyToolWindowBridge.getInstance(project).ui?.selectedModelProp?.get()
            if (selectedModel != null && selectedModel.contains("gpt")) {
                // Check stored OpenAI API key in project properties or on-disk file
                val props = PropertiesComponent.getInstance(project)
                //val openAiKey = props.getValue("license_tool.openai_api_key", "")
                val keyFile = project.basePath?.let { java.io.File(it, ".license-tool/openai_key.txt") }
                val hasKeyOnDisk = keyFile?.exists() == true && keyFile.readText(Charsets.UTF_8).isNotBlank()

                if (!hasKeyOnDisk) {
                    // Prompt user to enter API key via the API Keys dialog
                    val apiDlg = ApiKeysDialog(project)
                    apiDlg.show()

                    // Re-check after dialog
                    val newKey = props.getValue("license_tool.openai_api_key", "")
                    val newHasKeyOnDisk = keyFile?.exists() == true && keyFile.readText(Charsets.UTF_8).isNotBlank()
                    if (newKey.isBlank() && !newHasKeyOnDisk) {
                        setErrorText("OpenAI API key is required for the selected model ($selectedModel). Please configure it in the 'API Key' dialog.")
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            // Non-fatal: allow save but log error to console for diagnostics
            t.printStackTrace()
        }

        saveToJson()
        super.doOKAction()
    }
}