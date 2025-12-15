package com.example.my_plugin

import java.io.File

object CycloneDxMavenInvoker {
    private val LOG = LogInitializer.getLogger(CycloneDxMavenInvoker::class.java)

    fun generateSbom(mavenProjectDir: File, outputDir: String): File {
        require(File(mavenProjectDir, "pom.xml").exists()) { "pom.xml not found in ${mavenProjectDir.absolutePath}" }

        val mvnCmd = getMvnCmd(mavenProjectDir)

        val process = ProcessBuilder(
            mvnCmd,
            "org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom",
            "-DoutputFormat=xml",
            "-DoutputDirectory=" + outputDir,
            "-DoutputName=bom",
            "-DincludeBomSerialNumber=false",
        )
            .directory(mavenProjectDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader()
        var bomFilePath: String? = null

        val exitCode = process.waitFor()

        if (exitCode != 0) {
            LOG.error("Maven SBOM generation failed with exit code $exitCode")
            throw RuntimeException("Error during the generation of the SBOM:\n$output")
        }else {
            // wait for process to finish and capture output line by line
            output.useLines { lines ->
                lines.forEach { line ->
                    // intercept BOM path
                    if (line.contains("CycloneDX: Writing and validating BOM (XML):")) {
                        val detected = line.substringAfter("CycloneDX: Writing and validating BOM (XML):").trim()
                        bomFilePath = detected
                        // split by "/" and get last element to ensure it's "bom.xml"
                        if (bomFilePath.split("/").last() != "bom.xml") {
                            println("Unexpected BOM file name: $bomFilePath")
                            LOG.info("Unexpected BOM file name: $bomFilePath")
                            val newSbomFilePath = "$mavenProjectDir/$outputDir/bom.xml"
                            // rename to bom.xml
                            if(renameFile(bomFilePath, newSbomFilePath)) {
                                println("Renamed to bom.xml")
                                LOG.info("Renamed to $newSbomFilePath")
                            }else {
                                println("[ERROR] Failed to rename BOM file to bom.xml")
                                LOG.error("Failed to rename BOM file to bom.xml")
                            }
                        }
                    }
                }
            }
        }

        val bomFile = File(mavenProjectDir, outputDir + "/bom.xml")
        if (!bomFile.exists()) {
            LOG.warn("bom.xml not found after the generation: $bomFilePath")
            throw RuntimeException("SBOM not found after generation.")

        }

        println("✅ SBOM generated: ${bomFile.absolutePath}")
        LOG.info("✅ SBOM generated: ${bomFile.absolutePath}")
        return bomFile
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
            val oldFile = java.io.File(oldPath)
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