package com.example.my_plugin

import java.io.File

object CycloneDxMavenInvoker {
    private val LOG = LogInitializer.getLogger(CycloneDxMavenInvoker::class.java)

    /**
     * Synchronous helper retained for compatibility. Delegates to [generateSbomAsync] and blocks with a timeout.
     * Must not be called on EDT.
     */
    fun generateSbom(mavenProjectDir: File, outputDir: String, pomPath: File): File {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            throw IllegalStateException("generateSbom must not be called on the EDT. Run it from a background thread or Task.Backgroundable.")
        }

        // Delegate to async path but block with a reasonable timeout to avoid indefinite hangs.
        val future = generateSbomAsync(mavenProjectDir, outputDir, pomPath)
        return try {
            // 15 minutes should be more than enough for a single Maven invocation; callers can use async variant to avoid blocking.
            future.get(15, java.util.concurrent.TimeUnit.MINUTES)
        } catch (te: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            throw RuntimeException("Timed out while generating SBOM (15 minutes).", te)
        }
    }

    /**
     * Asynchronous, non-blocking SBOM generation using IntelliJ Execution API.
     * Returns a CompletableFuture that completes with the generated BOM File or completes exceptionally on failure.
     * Caller should call .cancel(true) to abort; cancellation will attempt to destroy the spawned process.
     */
    fun generateSbomAsync(mavenProjectDir: File, outputDir: String, pomPath: File): java.util.concurrent.CompletableFuture<File> {
        val mvnCmd = getMvnCmd(mavenProjectDir)
        val pomDir = File(pomPath.parent)

        val cmd = com.intellij.execution.configurations.GeneralCommandLine()
            .withExePath(mvnCmd)
            .withWorkDirectory(mavenProjectDir)
            .withParameters(
                "org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom",
                "-f", pomPath.absolutePath,
                "-DoutputFormat=xml",
                "-DoutputDirectory=${File(mavenProjectDir, outputDir).absolutePath}",
                "-DoutputName=bom",
                "-DincludeBomSerialNumber=false",
                "-B"
            )

        LOG.info("Starting async mvn command: ${cmd.commandLineString} in ${pomDir.absolutePath}")

        val processHandler = com.intellij.execution.process.OSProcessHandler(cmd)
        val future = java.util.concurrent.CompletableFuture<File>()
        val outputBuilder = StringBuilder()

        processHandler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
            override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                // Keep this lightweight: avoid heavy string operations per-line in high-throughput cases.
                outputBuilder.append(event.text)
            }

            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                try {
                    val exitCode = event.exitCode
                    val fullOutput = outputBuilder.toString()

                    if (exitCode != 0) {
                        LOG.error("Maven SBOM generation failed with exit code $exitCode")
                        LOG.debug(fullOutput)
                        future.completeExceptionally(RuntimeException("Error during SBOM generation. Exit code: $exitCode\n$fullOutput"))
                        return
                    }

                    // parse output for BOM path. The cyclonedx plugin prints the final path; if not found, fallback to expected location
                    var bomFilePath: String? = null
                    fullOutput.lineSequence().forEach { line ->
                        if (line.contains("CycloneDX: Writing and validating BOM (XML):")) {
                            val detected = line.substringAfter("CycloneDX: Writing and validating BOM (XML):").trim()
                            if (detected.isNotEmpty()) {
                                bomFilePath = detected
                            }
                        }
                    }

                    val bomFile = File(mavenProjectDir, outputDir + "/bom.xml")

                    if (bomFilePath != null && bomFilePath.trim().isNotEmpty()) {
                        val detectedFile = File(bomFilePath)
                        if (detectedFile.exists()) {
                            if (!detectedFile.absolutePath.equals(bomFile.absolutePath)) {
                                if (renameFile(detectedFile.absolutePath, bomFile.absolutePath)) {
                                    LOG.info("Renamed detected BOM to expected location: ${bomFile.absolutePath}")
                                } else {
                                    LOG.warn("Detected BOM exists at ${detectedFile.absolutePath} but failed to move to ${bomFile.absolutePath}")
                                }
                            }
                        }
                    }

                    if (!bomFile.exists()) {
                        LOG.warn("bom.xml not found after the generation. Output logged for debugging.")
                        LOG.debug(fullOutput)
                        future.completeExceptionally(RuntimeException("SBOM not found after generation. See logs for details."))
                        return
                    }

                    LOG.info("✅ SBOM generated: ${bomFile.absolutePath}")
                    future.complete(bomFile)
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }
        })

        // Wire cancellation: if caller cancels the CompletableFuture, try to destroy the process.
        future.whenComplete { _, _ ->
            if (future.isCancelled) {
                try {
                    if (!processHandler.process.isAlive) return@whenComplete
                } catch (_: Throwable) {}
                try {
                    LOG.info("Cancellation requested - destroying Maven process")
                    processHandler.destroyProcess()
                } catch (t: Throwable) {
                    LOG.warn("Failed to destroy process on cancel: ${t.message}")
                }
            }
        }

        // Start the process after listeners are attached
        processHandler.startNotify()
        return future
    }

    fun getMvnCmd(mavenProjectDir: File) : String
    {
        return getMvnLocal(mavenProjectDir) //First, try getting a local maven installation from the target repo
            ?: getMvnFromHome() //If it's not there, look for MAVEN_HOME or M2_HOME environment variables
            ?: getMvnByOs() //If all else fails, check the OS to see what the maven command should be
    }

    fun getMvnLocal(mavenProjectDir: File): String?
    {
        if (File(mavenProjectDir, "mvnw").exists())
        {
            return "./mvnw"
        }
        return null
    }

    fun getMvnFromHome() : String?
    {
        val mavenHome = System.getenv("MAVEN_HOME") ?: System.getenv("M2_HOME")
        if (mavenHome != null)
        {
            val mvnCmd = File(mavenHome, "bin/${if (isWindows()) "mvn.cmd" else "mvn"}")
            if (mvnCmd.exists() && mvnCmd.canExecute()) {
                return mvnCmd.absolutePath
            }
        }
        return null
    }

    fun getMvnByOs() : String
    {
        return if (isWindows()) "mvn.cmd" else "mvn"
    }

    fun isWindows(): Boolean
    {
        val os = System.getProperty("os.name").lowercase()
        return os.contains("win")
    }

    fun renameFile(oldPath: String, newPath: String): Boolean {
        return try {
            // if oldPath does not exist, return false
            val oldFile = File(oldPath)
            if (!oldFile.exists()) {
                println("renameFile error: source file does not exist: $oldPath")
                LOG.error("renameFile error: source file does not exist: $oldPath")
                return false
            }
            // if oldPath and newPath are the same, return true
            if (oldPath.trim() == newPath.trim()) {
                return true
            }
            val source = java.nio.file.Paths.get(oldPath.trim())
            val target = java.nio.file.Paths.get(newPath.trim())
            java.nio.file.Files.createDirectories(target.parent)
            java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (t: Throwable) {
            println("renameFile error: ${t.message}")
            LOG.error("Generic renaming file error: ${t.message}")
            false
        }
    }
}