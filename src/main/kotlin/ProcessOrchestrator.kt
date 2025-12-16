package com.example.my_plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import org.slf4j.Logger
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

@Service(Service.Level.PROJECT)
class ProcessOrchestrator(private val project: Project) {
    private val LOG: Logger = LogInitializer.getLogger(ProcessOrchestrator::class.java)

    // Single-threaded executor to serialize heavy external work (avoids spawning many JVMs / pip installs)
    private val executor: ExecutorService = AppExecutorUtil.createBoundedApplicationPoolExecutor("Sbom-runner", 1)

    // Deduplicate concurrent SBOM requests per pomPath
    private val pendingSbom: ConcurrentHashMap<String, CompletableFuture<File>> = ConcurrentHashMap()

    /**
     * Ensure Python server is started. Returns a future that completes when startup is attempted.
     */
    fun ensurePythonRunning(): CompletableFuture<Unit> {
        val fut = CompletableFuture<Unit>()
        project.service<PythonServerService>().let { svc ->
            try {
                // Start off pooled thread to avoid EDT work
                executor.submit {
                    try {
                        svc.ensureStarted()
                        fut.complete(Unit)
                    } catch (t: Throwable) {
                        LOG.warn("Failed to start Python server", t)
                        fut.completeExceptionally(t)
                    }
                }
            } catch (t: Throwable) {
                fut.completeExceptionally(t)
            }
        }
        return fut
    }

    /**
     * Generate SBOM for the given pom path. This method deduplicates concurrent requests and serializes them.
     * Returns a CompletableFuture that completes with the generated BOM file or exceptionally on error.
     */
    fun generateSbomQueued(pomPath: String): CompletableFuture<File> {
        // use canonical path as key
        val key = File(pomPath).canonicalPath
        // If there's already a pending request for this key, return it
        pendingSbom[key]?.let { return it }

        val future = CompletableFuture<File>()
        val prev = pendingSbom.putIfAbsent(key, future)
        if (prev != null) return prev

        executor.submit {
            try {
                val base = project.basePath ?: run {
                    future.completeExceptionally(IllegalStateException("Project base path is null"))
                    pendingSbom.remove(key)
                    return@submit
                }
                val invoker = CycloneDxMavenInvoker
                val mavenDir = File(base)
                try {
                    // Use the async invoker and wait for completion off the EDT (we're already on executor)
                    val cf = invoker.generateSbomAsync(mavenDir, ".license-tool", File(pomPath))
                    val bomFile = cf.get()
                    future.complete(bomFile)
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            } finally {
                pendingSbom.remove(key)
            }
        }

        return future
    }
}
