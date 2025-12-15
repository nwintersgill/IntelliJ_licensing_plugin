package com.example.my_plugin;

import java.io.File;

// Creates a project-level service for the pom.xml listener.
// The service will be automatically created when the project opens and registers the listener.
public interface MavenDependencyService
{
    void flagNewDependency();

    // Generate SBOM for the project and return prev/current SBOM files (prev may be null).
    File[] genSbom();

}
