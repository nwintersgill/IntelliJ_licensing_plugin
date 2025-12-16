package com.example.my_plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import controller.LicensingController
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.SwingUtilities
import com.example.my_plugin.license.LicenseQuestionnaireDialog
import javax.swing.Timer
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem

import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter



import com.intellij.openapi.Disposable
import org.slf4j.Logger

class MyToolWindowFactory : ToolWindowFactory {

    // Encapsulate per-project UI and state
    class ChatUi(private val project: Project) : Disposable {
        var chatView: JEditorPane? = null
        var inputArea: JBTextArea? = null
        var loaderLabel: JBLabel? = null
        var submitButton: JButton? = null
        private var loadingTimer: Timer? = null
        private val propertyGraph = PropertyGraph()
        val selectedModelProp = propertyGraph.property("gpt-4o")

        // Add a reference to the Java listener so we can unregister it on dispose
        private var surveyListener: LicenseQuestionnaireListener? = null

        private data class ChatMessage(val role: String, val text: String, val model: String? = null, val ts: Long = System.currentTimeMillis())
        private val messages = mutableListOf<ChatMessage>()

        private val tsFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

        private val LOG: Logger = LogInitializer.getLogger(MyToolWindowFactory::class.java)


        private fun escapeHtml(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")

        private fun markdownToHtmlBasic(src: String): String {
            val codeBlocks = mutableListOf<String>()
            var text = src
            val fenceRegex = Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL)
            text = fenceRegex.replace(text) { m ->
                val idx = codeBlocks.size
                codeBlocks += m.groupValues[1]
                "@@CODEBLOCK_$idx@@"
            }
            text = escapeHtml(text)
            text = text.replace(Regex("`([^`]+)`")) { m -> "<span class='code'>" + m.groupValues[1] + "</span>" }
            text = text.replace(Regex("\\*\\*([^*]+)\\*\\*")) { m -> "<b>" + m.groupValues[1] + "</b>" }
            text = text.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")) { m -> "<i>" + m.groupValues[1] + "</i>" }
            text = text.lines().joinToString("\n") { line ->
                when {
                    line.startsWith("### ") -> "<b>" + line.removePrefix("### ") + "</b>"
                    line.startsWith("## ")  -> "<b>" + line.removePrefix("## ") + "</b>"
                    line.startsWith("# ")   -> "<b>" + line.removePrefix("# ") + "</b>"
                    else -> line
                }
            }
            val lines = text.split("\n")
            val out = StringBuilder()
            var inList = false
            for (ln in lines) {
                val trimmed = ln.trim()
                val isBullet = trimmed.startsWith("- ") || trimmed.startsWith("* ")
                if (isBullet && !inList) { out.append("<ul>"); inList = true }
                if (!isBullet && inList) { out.append("</ul>"); inList = false }
                if (isBullet) out.append("<li>").append(trimmed.substring(2)).append("</li>")
                else out.append(ln).append("\n")
            }
            if (inList) out.append("</ul>")
            text = out.toString()

            // Replace placeholders with escaped code blocks (use local vars so static analysis sees usage)
            codeBlocks.forEachIndexed { idx, raw ->
                val replaced = "<pre>" + escapeHtml(raw.trim()) + "</pre>"
                text = text.replace("@@CODEBLOCK_${'$'}idx@@", replaced)
            }
            return text
        }

        private fun renderChatHtml(): String {
            val sb = StringBuilder()
            sb.append(
                """
                <html>
                <head>
                  <style>
                    body { font-family: sans-serif; font-size: 12px; margin: 0; }
                    .wrap { padding: 8px; }
                    .msg { padding: 8px 10px; margin: 6px 0; border: 1px solid #e1e3e6; background: #ffffff; }
                    .user  { background: #dfefff; }
                    .bot   { background: #f5f6f7; }
                    .meta { font-size: 10px; color: #6b6f76; margin-top: 4px; }
                    .name { font-size: 11px; font-weight: bold; margin-bottom: 4px; }
                    .code { font-family: Monospaced; background:#ffffff; border:1px solid #e1e3e6; padding:2px 4px; }
                    pre { font-family: Monospaced; background:#ffffff; border:1px solid #e1e3e6; padding:8px; }
                    a { color:#0b6eff; text-decoration:none; }
                  </style>
                </head>
                <body><div class='wrap'>
                """.trimIndent()
            )
            for (m in messages) {
                val roleClass = if (m.role == "user") "user" else "bot"
                val time = tsFmt.format(Instant.ofEpochMilli(m.ts))
                val modelStr = m.model?.let { " — <span class='meta'>" + escapeHtml(it) + "</span>" } ?: ""
                val htmlText = markdownToHtmlBasic(m.text)
                val name = if (m.role == "user") "You" else "Bot"
                val nameColor = if (m.role == "user") "#0b3d62" else "#2b2d30"
                val avatarLetter = if (m.role == "user") "U" else "B"
                val avatarBg = if (m.role == "user") "#0b6eff" else "#6b6f76"
                val rowHtml = if (m.role == "user") {
                    // User on the right: message cell first, avatar cell on the far right
                    """
                    <table width='100%' cellspacing='4' cellpadding='0'>
                      <tr>
                        <td width='*' align='right'>
                          <div class='msg $roleClass'>
                            <div class='name' style='color:$nameColor;'>$name</div>
                            $htmlText
                            <div class='meta'>$time$modelStr</div>
                          </div>
                        </td>
                        <td width='28' align='center' valign='top' bgcolor='$avatarBg'>
                          <font color='#ffffff'><b>$avatarLetter</b></font>
                        </td>
                      </tr>
                    </table>
                    """.trimIndent()
                } else {
                    // Bot on the left: avatar first, message next
                    """
                    <table width='100%' cellspacing='4' cellpadding='0'>
                      <tr>
                        <td width='28' align='center' valign='top' bgcolor='$avatarBg'>
                          <font color='#ffffff'><b>$avatarLetter</b></font>
                        </td>
                        <td width='*'>
                          <div class='msg $roleClass'>
                            <div class='name' style='color:$nameColor;'>$name</div>
                            $htmlText
                            <div class='meta'>$time$modelStr</div>
                          </div>
                        </td>
                      </tr>
                    </table>
                    """.trimIndent()
                }
                sb.append(rowHtml)
            }
            sb.append("</div></body></html>")
            return sb.toString()
        }

        private fun addMessage(role: String, text: String, model: String? = null) {
            LOG.info("Adding message to UI $role: $text")
            messages += ChatMessage(role, text, model)
            chatView?.text = renderChatHtml()
            chatView?.caretPosition = chatView?.document?.length ?: 0
        }

        fun appendToChatHistory(text: String) {
            addMessage("bot", text)
        }

        fun startSbomAnimation() {
            startLoadingAnimation(listOf("Analyzing dependencies", "Generating SBOM", "Querying model"))
        }

        fun startSubmitAnimation() {
            startLoadingAnimation(listOf("Collecting license context", "Querying model"))
        }

        fun startLoadingAnimation(messages: List<String>) {
            SwingUtilities.invokeLater {
                loaderLabel?.isVisible = true
                //val messages = listOf("Analyzing dependencies", "Generating SBOM", "Querying model")
                var currentIndex = 0
                var dotTick = 0

                loaderLabel?.text = messages[currentIndex]

                loadingTimer?.stop()
                loadingTimer = Timer(500) {
                    dotTick = (dotTick + 1) % 4
                    val dots = ".".repeat(dotTick)

                    if (currentIndex < messages.size - 1) {
                        // Step through the first two messages once
                        loaderLabel?.text = "${messages[currentIndex]}$dots"

                        if (dotTick == 0) {
                            currentIndex++
                            loaderLabel?.text = messages[currentIndex]
                        }
                    } else {
                        // Stay on the last message and keep cycling dots forever
                        loaderLabel?.text = "${messages.last()}$dots"
                    }
                }

                loadingTimer?.start()
            }
        }


        fun stopAnimation() {
            SwingUtilities.invokeLater {
                loadingTimer?.stop()
                loaderLabel?.isVisible = false
                loaderLabel?.text = "Loading"
            }
        }

        fun submitMessage(inputText: String) {
            //startLoadingAnimation()
            LOG.info("Submit message to the LLM: $inputText")
            startSubmitAnimation()
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val selectedModel = selectedModelProp.get()
                    val chatbotSession = LicensingController.getChatbotSession(selectedModel)
                    val response = chatbotSession.submitPrompt(inputText)
                    SwingUtilities.invokeLater { addMessage("bot", response) }
                } catch (t: Throwable) {
                    SwingUtilities.invokeLater { addMessage("bot", "(error) ${'$'}{t.message}") }
                    LOG.error("Error during message submission: {}", t.message)
                } finally {
                    stopAnimation()
                }
            }
        }

        fun buildPanel(): DialogPanel {
            val myPanel: DialogPanel = panel {
                indent {
                    row {
                        button("Survey") {
                            val dialog = LicenseQuestionnaireDialog(project)
                            dialog.show()
                        }.align(AlignX.RIGHT)
                    }
                    row {
                        chatView = JEditorPane("text/html", "").apply {
                            isEditable = false
                            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                            addHyperlinkListener { ev ->
                                if (ev.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                                    java.awt.Desktop.getDesktop().browse(ev.url.toURI())
                                }
                            }
                            text = ""
                        }

                        val chatScrollPane = JBScrollPane(chatView).apply { border = JBUI.Borders.empty() }
                        cell(chatScrollPane).resizableColumn().align(Align.FILL)
                    }.resizableRow()
                    row {
                        loaderLabel = JBLabel("Loading...").apply { isVisible = false }
                        cell(loaderLabel!!)
                    }
                    separator()
                    row {
                        inputArea = JBTextArea().apply {
                            lineWrap = true
                            wrapStyleWord = true
                            rows = 4
                            addKeyListener(object : KeyAdapter() {
                                override fun keyPressed(e: KeyEvent) {
                                    if (e.keyCode == KeyEvent.VK_ENTER) {
                                        if (e.isShiftDown) {
                                            this@apply.append("\n")
                                        } else {
                                            e.consume()
                                            val inputText = text.trim()
                                            if (inputText.isNotEmpty()) {
                                                text = ""
                                                addMessage("user", inputText, selectedModelProp.get())
                                                submitMessage(inputText)
                                            }
                                        }
                                    }
                                }
                            })
                        }
                        val scrollPane = JBScrollPane(inputArea)
                        cell(scrollPane).resizableColumn().align(Align.FILL)
                        submitButton = button("Submit") {
                            val inputText = inputArea?.text ?: ""
                            if (inputText.isNotBlank()) {
                                inputArea?.text = ""
                                addMessage("user", inputText, selectedModelProp.get())
                                submitMessage(inputText)
                            }
                        }.component
                        submitButton?.isEnabled = false
                    }
                    row {
                        val modelItems = listOf("gpt-4o")
                        comboBox(modelItems).bindItem(selectedModelProp).align(AlignX.RIGHT)
                        button("API Key") {
                            val apiDialog = com.example.my_plugin.ApiKeysDialog(project)
                            apiDialog.show()
                        }
                    }
                }
            }

            myPanel.border = JBUI.Borders.empty(8, 0, 8, 16)
            return myPanel
        }

        fun enableSubmitButton() { submitButton?.isEnabled = true }
        fun disableSubmitButton() { submitButton?.isEnabled = false }

        fun disableInputArea() { inputArea?.isEnabled = false }
        fun enableInputArea() { inputArea?.isEnabled = true }

        // Function to check if the tool window is visible
        fun isToolWindowVisible(project: Project): Boolean {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Licensing Tool")
            return toolWindow?.isVisible ?: false
        }

        fun toggleToolWindowVisibility(project: Project) {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Licensing Tool")
            if (toolWindow != null) {
                if (toolWindow.isVisible) {
                    toolWindow.hide(null)
                } else {
                    toolWindow.show(null)
                }
            }
        }

        fun getSurveyJson(): String {
            // read the license-survey.json
            val surveyFile = java.io.File(project.basePath, ".license-tool/license-survey.json")
            val surveyJson = surveyFile.readText(Charsets.UTF_8)
            return surveyJson
        }

        // Register the Java-based listener and wire callbacks to UI updates
        fun registerSurveyListener() {
            // create a callback implementing the SurveyChangeListener Java interface
            val callback = object : SurveyChangeListener {
                override fun onSurveyCreated() {
                    ApplicationManager.getApplication().invokeLater {
                        enableSubmitButton()
                        enableInputArea()
                        appendToChatHistory("Survey saved — thank you. You can now submit queries.")
                        //use the surveyJson to inform the bot about the project licensing context
                        submitMessage(getSurveyJson())
                        LOG.info("Survey created event handled, UI updated.")
                    }
                }

                override fun onSurveyChanged() {
                    ApplicationManager.getApplication().invokeLater {
                        appendToChatHistory("Survey updated.")
                        LOG.info("Survey updated event handled, UI updated.")
                        //use the surveyJson to inform the bot about the project licensing context changes
                        submitMessage(getSurveyJson())
                    }
                }

                override fun onSurveyDeleted() {
                    ApplicationManager.getApplication().invokeLater {
                        disableSubmitButton()
                        disableInputArea()
                        appendToChatHistory("Survey removed. Please re-fill the project questionnaire to enable the chat.")
                        LOG.info("Survey deleted event handled, UI updated.")
                        //TODO: inform the bot about the project licensing context deletion
                        submitMessage(getSurveyJson())
                    }
                }
            }

            surveyListener = LicenseQuestionnaireListener(project, callback)
            surveyListener?.register()
        }

        override fun dispose() {
            try { loadingTimer?.stop() } catch (_: Throwable) {}
            loadingTimer = null
            // make sure to unregister listener if still registered
            try { surveyListener?.unregister() } catch (_: Throwable) {}
            surveyListener = null
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val ui = ChatUi(project)
        val panel = ui.buildPanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        MyToolWindowBridge.getInstance(project).ui = ui
        ui.appendToChatHistory("Hi, I'm a Licensing Bot. I'm here to help you with licensing issues in your project.")

        // Ensure we have a user identifier; prompt on first open and do not allow skipping
        var userId = UsageLogger.loadUserId(project)
        if (userId == null) {
            val app = ApplicationManager.getApplication()
            if (app.isDispatchThread) {
                // We're on EDT: show dialog directly in a loop
                while (UsageLogger.loadUserId(project) == null) {
                    val dlg = UserIdDialog(project)
                    dlg.show()
                }
            } else {
                // Off-EDT: use invokeAndWait to block until dialog is dismissed
                app.invokeAndWait {
                    while (UsageLogger.loadUserId(project) == null) {
                        val dlg = UserIdDialog(project)
                        dlg.show()
                    }
                }
            }
            userId = UsageLogger.loadUserId(project)
        }

        // Log that the tool was opened (include user id)
        UsageLogger.logEvent(project, userId, "tool_opened", mapOf("toolWindow" to "Licensing Tool"))

        // Post-init greeting and gating submit button by survey presence
        val surveyJson = java.io.File(project.basePath, ".license-tool/license-survey.json")
        if (!surveyJson.exists()) {
            ui.appendToChatHistory("Before we start, please fill out the project license questionnaire.\nClick the 'Survey' button above to open it.")
            ui.disableSubmitButton()
            ui.disableInputArea()
        } else {
            ui.enableSubmitButton()
            ui.enableInputArea()
        }

        // Register the listener so UI reacts to create/change/delete events
        ui.registerSurveyListener()

        // Dispose UI when project is disposed to ensure a clean env on project switch
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable, ui)
        // Initial greeting
    }

    /*companion object {
        private val instances = mutableMapOf<Project, ChatUi>()
        fun getInstance(project: Project): ChatUi? = instances[project]
    }*/

}