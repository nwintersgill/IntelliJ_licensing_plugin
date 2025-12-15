package com.example.my_plugin;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.FileAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LogInitializer {

    // Keep track of which project paths we already initialized logging for.
    private static final Set<String> INITIALIZED = ConcurrentHashMap.newKeySet();

    public static void setupLoggingForProject(com.intellij.openapi.project.Project project) {
        // Use project base path as unique key; fall back to project name if basePath is null
        String projectKey = project.getBasePath() != null ? project.getBasePath() : project.getName();
        if (projectKey == null) {
            // Nothing to configure
            return;
        }

        // If we've already initialized logging for this project, do nothing.
        if (!INITIALIZED.add(projectKey)) {
            return; // already initialized
        }

        // Use project name to create a per-project log under .license-tool
        String projectName = project.getName() != null ? project.getName() : "project";
        String logFilePath = Paths.get(project.getBasePath() != null ? project.getBasePath() : ".", ".license-tool", projectName + ".log").toString();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
        encoder.start();

        // Create an appender with a unique name per project so we can detect duplicates
        String appenderName = "LICENSING_TOOL_FILE_APPENDER_" + projectName;

        // If an appender with the same name already exists on the logger, skip adding.
        String pluginPackage = "com.example.my_plugin";
        ch.qos.logback.classic.Logger packageLogger = context.getLogger(pluginPackage);
        // If an appender with the same name already exists on the logger, skip adding.
        if (packageLogger.getAppender(appenderName) != null) {
            return;
        }

        // Defensive: remove any existing FileAppender that writes to the same file path
        // This prevents duplicate output when a previous initialization created an appender
        // that wasn't cleaned up (for example across plugin reloads).
        java.util.Iterator<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it = packageLogger.iteratorForAppenders();
        while (it.hasNext()) {
            ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent> a = it.next();
            if (a instanceof FileAppender) {
                FileAppender<?> fa = (FileAppender<?>) a;
                try {
                    String existingFile = fa.getFile();
                    if (existingFile != null && existingFile.equals(logFilePath)) {
                        // detach the old one to prevent duplicate writes
                        packageLogger.detachAppender(a);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setName(appenderName);
        fileAppender.setContext(context);
        fileAppender.setFile(logFilePath);
        fileAppender.setEncoder(encoder);
        fileAppender.setAppend(true);
        fileAppender.start();

        // Attach the appender to the package logger. Turn off additivity to avoid repeating
        // messages to the root logger as well.
        packageLogger.addAppender(fileAppender);
        packageLogger.setAdditive(false); // avoid duplicate logs to root
    }

    /**
     * Convenience accessor so other classes can consistently obtain the plugin logger.
     * Use LogInitializer.getLogger(YourClass.class) in your classes.
     */
    public static Logger getLogger(Class<?> cls) {
        // Return the logger for the class; because we attached an appender to the
        // package logger, class-level loggers (same package) will write to the file.
        return LoggerFactory.getLogger(cls);
    }

    // Lightweight Gson instance for structured state logging
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Emit a structured JSON state record to the plugin logger.
     * Example: LogInitializer.logState(getClass(), project, "pom_changed", Map.of("path", path));
     */
    public static void logState(Class<?> cls, com.intellij.openapi.project.Project project, String event, Map<String, Object> state) {
        try {
            Map<String,Object> payload = new HashMap<>();
            payload.put("ts", Instant.now().toEpochMilli());
            payload.put("project", project != null ? project.getName() : null);
            payload.put("event", event);
            payload.put("state", state != null ? state : new HashMap<>());
            String json = GSON.toJson(payload);
            Logger log = getLogger(cls);
            log.info("PLUGIN_STATE {}", json);
        } catch (Throwable t) {
            // don't let logging blow up the host flow
            getLogger(LogInitializer.class).warn("Failed to emit structured state", t);
        }
    }

}
