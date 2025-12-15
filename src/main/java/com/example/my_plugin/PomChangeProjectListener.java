package com.example.my_plugin;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiTreeChangeAdapter;
import controller.LicensingController;
import org.jetbrains.annotations.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class PomChangeProjectListener extends PsiTreeChangeAdapter implements BulkFileListener {
    private final Project project;
    private final LicensingController licensingController;
    public PomChangeProjectListener(Project project) {
        this.project = project;
        this.licensingController = project.getService(LicensingController.class);
    }

    @Override
    public void after(@NotNull List<? extends VFileEvent> events) {
        DumbService.getInstance(project).runWhenSmart(() -> {
            String basePath = project.getBasePath();
            if (basePath == null) return;

            for (VFileEvent event : events) {
                // Check if the event is a file change and if the file is not null
                if (event.getFile() == null) continue;

                String path = event.getFile().getPath();
                if (path.endsWith("pom.xml") && path.startsWith(basePath)) {
                    LogInitializer.logState(PomChangeProjectListener.class, project, "pom_updated", java.util.Map.of("path", path));
                    System.out.println("📦 [pom.xml updated] project: " + project.getName() + ": " + path);
                    // Check if the event is a child addition (i.e., a new dependency)
                    // LicensingController controller = new LicensingController();
                    System.out.println("Start the pipeline for dependency addition");
                    LogInitializer.getLogger(PomChangeProjectListener.class).info("Start the pipeline for dependency addition for project: {}", project.getName());
                    licensingController.onDependencyChange(project);
                }
            }
        });
    }
}