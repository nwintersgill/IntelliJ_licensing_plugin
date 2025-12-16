// src/main/kotlin/PythonServerService.kt
package com.example.my_plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.Key
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions


@Service(Service.Level.PROJECT)
class PythonServerService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PythonServerService::class.java)
    // get instance of WindowFactory to access selected model
    // val toolWindow = MyToolWindowFactory.getInstance(project)
    val toolWindow = MyToolWindowBridge.getInstance(project).ui

    @Volatile private var handler: OSProcessHandler? = null
    @Volatile private var tempRuntimeDir: Path? = null

    @Synchronized
    fun ensureStarted() {
        if (handler?.isProcessTerminated == false) {
            log.info("Python server already running")
            return
        }

        val script = resolveScriptPath()
        makeExecutableIfUnix(script)

        val tempDir = tempRuntimeDir ?: script.parent
        installDependencies(tempDir)

        // Set up PYTHONPATH to import modules installed with --target and local modules
        val existingPyPath = System.getenv("PYTHONPATH") ?: ""
        val newPyPath = if (existingPyPath.isBlank()) tempDir.toString() else existingPyPath + java.io.File.pathSeparator + tempDir
        val projectDir = project.basePath ?: ""
        val selectedModel = toolWindow?.selectedModelProp?.get() ?: "gpt-4o"
        println("Selected model: $selectedModel")
        val cmd = GeneralCommandLine(pythonExe(), script.toString())
            .withWorkDirectory(tempDir.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(mapOf(
                "PYTHONUNBUFFERED" to "1",
                "PYTHONPATH" to newPyPath,
                "LICENSE_TOOL_PROJECT" to projectDir,
                "LICENSE_TOOL_MODEL" to selectedModel
            ))
        System.setProperty("py_serverPath", newPyPath)
        println("Python path: $newPyPath")

        val h = OSProcessHandler(cmd)
        h.addProcessListener(object : ProcessAdapter() {
            override fun startNotified(event: ProcessEvent) {
                println("Python server started: ${cmd.commandLineString}")
                log.info("Python server started: ${cmd.commandLineString}")
            }
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // Read/print output
                val line = event.text?.trimEnd() ?: ""
                log.info("[py][$outputType] $line")
                println("[py][$outputType] $line")
            }
            override fun processTerminated(event: ProcessEvent) {
                log.info("Python server terminated with exit code ${event.exitCode}")
                println("Python server terminated with exit code ${event.exitCode}")
            }
        })
        h.startNotify()

        handler = h
        Disposer.register(project, this) // turns off the server at project closure
    }

    override fun dispose() {
        try {
            handler?.let { if (!it.isProcessTerminated) it.process.destroy() }
        } catch (t: Throwable) {
            log.warn("Error while stopping python server", t)
        } finally {
            handler = null
        }
    }

    private fun pythonExe(): String {
        // Select the interpreter: "python3" on macOS/Linux, "python" on Windows
        return if (SystemInfo.isWindows) "python" else "python3"
    }

    private fun resolveScriptPath(): Path {
        // Extracts the necessary files from src/main/resources/py_server to a temporary folder.
        val tempDir = extractResourceDir("py_server", listOf(
            "python_server.py",
            "requirements.txt",
            "api_functions.py",
            "config.py",
            "function_calling.py",
            "model_functions.py",
            "utils.py",
            "model_list.py",
            "matrix.csv"
        ))
        tempRuntimeDir = tempDir //
        return tempDir.resolve("python_server.py")
    }


    private fun extractResourceDir(resourceDir: String, files: List<String>): Path {
        val tempDir = Files.createTempDirectory("py_server_")
        tempDir.toFile().deleteOnExit()

        val cl = this::class.java.classLoader
        for (name in files) {
            val inStream = cl.getResourceAsStream("$resourceDir/$name")
                ?: error("Resource $resourceDir/$name not found on classpath")
            val target = tempDir.resolve(name)
            inStream.use { input ->
                Files.createDirectories(target.parent)
                Files.copy(input, target)
            }
            target.toFile().deleteOnExit()
        }
        return tempDir
    }

    private fun makeExecutableIfUnix(path: Path) {
        if (!SystemInfo.isWindows) {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
            } catch (_: Exception) { /* best effort */ }
        }
    }

    private fun installDependencies(tempDir: Path) {
        // Install Python dependencies locally in the temp directory using pip --target
        val req = tempDir.resolve("requirements.txt")
        if (!Files.exists(req)) return

        println("Installing Python dependencies from ${req.toAbsolutePath()}")
        val cmd = GeneralCommandLine(listOf(
            pythonExe(), "-m", "pip", "install",
            "--disable-pip-version-check",
            "--no-warn-script-location",
            "--target", tempDir.toString(),
            "-r", req.toString()
        ))
            .withWorkDirectory(tempDir.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(mapOf("PYTHONUNBUFFERED" to "1"))

        val proc = OSProcessHandler(cmd)
        proc.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val line = event.text?.trimEnd() ?: ""
                println("[pip][$outputType] $line")
                log.info("[pip][$outputType] $line")
            }
        })
        proc.startNotify()
        // best-effort: wait for pip to complete (max ~60s)
        val waited = proc.waitFor(60_000)
        if (!waited || proc.exitCode != 0) {
            log.warn("pip install failed or timed out (exit=${proc.exitCode}). Continuing; runtime may miss packages.")
        }
    }

}