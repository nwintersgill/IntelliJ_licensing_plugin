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
import com.intellij.ide.util.PropertiesComponent
import java.util.concurrent.CompletableFuture

import com.intellij.util.io.BaseOutputReader


@Service(Service.Level.PROJECT)
class PythonServerService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PythonServerService::class.java)

    @Volatile private var handler: OSProcessHandler? = null
    @Volatile private var tempRuntimeDir: Path? = null
    @Volatile private var starting: Boolean = false

    @Synchronized
    fun ensureStarted() {
        if (handler?.isProcessTerminated == false) {
            log.info("Python server already running")
            return
        }
        if (starting) {
            log.info("Python server start already in progress")
            return
        }
        starting = true

        val script = resolveScriptPath()
        makeExecutableIfUnix(script)

        val tempDir = tempRuntimeDir ?: script.parent

        // Get selected model safely from persistent storage; avoid touching UI components from background threads
        val selectedModel = PropertiesComponent.getInstance(project).getValue("license.tool.selectedModel", "gpt-4o")

        // Set up PYTHONPATH to import modules installed with --target and local modules
        val existingPyPath = System.getenv("PYTHONPATH") ?: ""
        val newPyPath = if (existingPyPath.isBlank()) tempDir.toString() else existingPyPath + java.io.File.pathSeparator + tempDir
        val projectDir = project.basePath ?: ""
        System.setProperty("py_serverPath", newPyPath)

        // Install dependencies asynchronously; only start the server if install succeeds
        val installFuture: CompletableFuture<Boolean> = installDependencies(tempDir)

        installFuture.whenComplete { success, err ->
            try {
                if (err != null) {
                    log.warn("pip install threw an exception, aborting python server start", err)
                    return@whenComplete
                }

                if (success != true) {
                    log.warn("pip install failed; not starting python server. See logs for pip output.")
                    return@whenComplete
                }

                // Start the Python server after dependencies install completes successfully
                val cmd = GeneralCommandLine(pythonExe(), script.toString())
                    .withWorkDirectory(tempDir.toFile())
                    .withCharset(StandardCharsets.UTF_8)
                    .withEnvironment(mapOf(
                        "PYTHONUNBUFFERED" to "1",
                        "PYTHONPATH" to newPyPath,
                        "LICENSE_TOOL_PROJECT" to projectDir,
                        "LICENSE_TOOL_MODEL" to selectedModel
                    ))

                log.info("Starting Python server: ${cmd.commandLineString} in ${tempDir}")

                // Use a specialized handler for mostly-silent daemon processes to reduce CPU usage
                val h = object : OSProcessHandler(cmd) {
                    override fun readerOptions(): BaseOutputReader.Options {
                        return BaseOutputReader.Options.forMostlySilentProcess()
                    }
                }
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
                // register disposal with project-level disposer; alternative parents are possible but project is acceptable here
                Disposer.register(project, this)
            } catch (t: Throwable) {
                log.error("Failed to start python server after install: ${t.message}", t)
            } finally {
                starting = false
            }
        }
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

    /**
     * Install Python dependencies and complete the returned future with true on success or false on failure.
     * The process is started asynchronously and the future completes when the pip process terminates.
     */
    private fun installDependencies(tempDir: Path): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val req = tempDir.resolve("requirements.txt")
        if (!Files.exists(req)) {
            // nothing to install -> success
            future.complete(true)
            return future
        }

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

            override fun processTerminated(event: ProcessEvent) {
                try {
                    if (event.exitCode != 0) {
                        log.warn("pip install failed (exit=${event.exitCode}). Runtime may miss packages.")
                        future.complete(false)
                    } else {
                        log.info("pip install completed successfully")
                        future.complete(true)
                    }
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }
        })
        // Start asynchronously and do not block the calling thread
        proc.startNotify()
        return future
    }

}