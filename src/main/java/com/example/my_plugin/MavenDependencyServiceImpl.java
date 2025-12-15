package com.example.my_plugin;

import chatbot.ChatbotSession;
import chatbot.ChatbotSessionLlamaPython;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import com.intellij.openapi.ui.Messages;
import java.nio.charset.StandardCharsets;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;

import controller.LicensingController;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.util.*;

import com.google.gson.JsonArray;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.slf4j.Logger;


// Creates a project-level service for the pom.xml listener.
// The service will be automatically created when the project opens and registers the listener.
public final class MavenDependencyServiceImpl implements MavenDependencyService, Disposable
{
    private final Project project;
    private static final Logger LOG = com.example.my_plugin.LogInitializer.getLogger(MavenDependencyServiceImpl.class);
    // private final MavenDependencyListener listener;

    public MavenDependencyServiceImpl(Project project) {
        this.project = project;
    }

    @Override
    public void dispose() {
        //See parent and https://plugins.jetbrains.com/docs/intellij/disposers.html?from=jetbrains.org#automatically-disposed-objects
    }

    @Override
    public void flagNewDependency(String pomPath) {
        // This method is called when a new dependency is added to the pom.xml file.
        LOG.info("New dependency detected, starting analysis pipeline.");
        // return the depJson object to the controller
        JsonObject depJson = getChanges(pomPath);
        if(!depJson.isEmpty()) {
            System.out.println("flagNewDependency - licenseChange called");
            // write the depJson object to file for debugging purposes
            try {
                writeJsonToFile(depJson, project.getBasePath() + "/.license-tool/dependency-diff.json");
            } catch (IOException e) {
                System.out.println("Error writing JSON to file: " + e.getMessage());
            }

            MyToolWindowFactory.ChatUi toolWindow = MyToolWindowBridge.Companion.getInstance(project).getUi();
            if (toolWindow != null) {
                // toolWindow.appendToChatHistory("A change in your project dependencies is found!\n\n");
                // Open the tool window if it is not already open
                if (!toolWindow.isToolWindowVisible(project)) {
                    toolWindow.toggleToolWindowVisibility(project);
                }

                // Get the changes that are potentially problematic/cause conflicts

                //Get the license of our software

                License myLicense = this.project.getService(LicensingController.class).getTargetLicense(); //gives "unknown" if config not found

                ArrayList<Map<License, String>> conflicts = getConflicts(myLicense, depJson);

                // Submit the information to the chatbot
                //toolWindow.submitMessage("In my project these dependencies has been removed or added :\n"
                //        + conflicts.toString() + "\n It would be great if you could analyze it and provide me with the information about the licenses of these dependencies and based on this give me a suggestion on how i can redistribute my project.\n");
                if (!conflicts.get(0).isEmpty() || !conflicts.get(1).isEmpty() || !conflicts.get(2).isEmpty()) //only submit the message if a conflict is detected
                {
                    //Read in the prompt template to be used for supplying the model with information about the change
                    String inputPromptTemplate;
                    try {
                        InputStream is = LicensingController.class.getClassLoader().getResourceAsStream("prompts/change-report-template.txt");
                        inputPromptTemplate = IOUtils.toString(is, "UTF-8");
                    }
                    catch (Exception e)
                    {
                        //Unable to load input prompt template
                        inputPromptTemplate = "My software project is licensed under {myLicense}" +
                                ". The following libraries have been added or removed: {libraries}" +
                                "I have determined that the following software licenses may cause conflicts in my project, and identified reasons for these conflicts, which you should be able to resolve: {addressableIssues}" +
                                "; These conflicts require analysis by a lawyer: {lawyerIssues}; And these are unknown: {unknownIssues}. Please present this information back to me and provide me with a summary of any licensing " +
                                "conflicts caused by this change, and provide suggestions on how to remedy them where appropriate. Use this licensing information about my repository as context: {licensingQuestionnaire}";
                    }

                    String inputPrompt = inputPromptTemplate.replace("{myLicense}", myLicense.getType())
                            .replace("{libraries}", depJson.toString())
                            .replace("{addressableIssues}", conflicts.get(0).toString())
                            .replace("{lawyerIssues}", conflicts.get(1).toString())
                            .replace("{unknownIssues}", conflicts.get(2).toString())
                            .replace("{licensingQuestionnaire}", this.project.getService(LicensingController.class).getUserLicenseConfig().toString());

                    toolWindow.submitMessage(inputPrompt);
                }
            }

            System.out.println("flagNewDependency - engageChatbot called");
        }

    }

    public static void writeJsonToFile(JsonObject jsonObject, String filePath) throws IOException {
        try (FileWriter file = new FileWriter(filePath)) {
            file.write(jsonObject.toString());
        }
    }

    public JsonObject getChanges(String pomPath) {
        // This method is called when a pom.xml file is added, modified, or removed.
        // It should analyze the dependencies and generate a new SBOM.
        System.out.println("getChanges - diffSbom called");
        LOG.info("Analyzing dependency changes via SBOM diff.");
        File[] sbomFiles = genSbom(pomPath);
        File prevSbom = sbomFiles[0];
        File currSbom = sbomFiles[1];
        if (currSbom == null) {
            // If the current SBOM generation fails, show an error message on the EDT and return null.
            final String errMsg = "Failed to generate SBOM files.";
            //ApplicationManager.getApplication().invokeLater(() -> {
                //if (!project.isDisposed()) Messages.showErrorDialog(project, errMsg, "Dependency Analysis Error");
            //});
            LOG.error("Failed to generate SBOM files. Current SBOM is null.");
            return new JsonObject(); // Return an empty JSON object if no changes are detected.
        } else if (prevSbom != null) {
            // If a previous SBOM exists, compare it with the current SBOM.
            LOG.info("Previous SBOM found, performing diff with current SBOM.");
            try {
                SbomDiffResult results = diffSbomXml(prevSbom, currSbom);
                Set<String> addedComponents = results.added;
                Set<String> removedComponents = results.removed;
                // parse the sets to a list of Dependency objects
                List<Dependency> addedDependencies = parseSetToList(addedComponents);
                List<Dependency> removedDependencies = parseSetToList(removedComponents);
                // Create a JSON object to hold the results
                JsonObject diffResults = new JsonObject();
                JsonArray addedArray = new JsonArray();
                if (!addedDependencies.isEmpty() || !removedDependencies.isEmpty()) {
                    for (Dependency dep : addedDependencies) {
                        addedArray.add(dep.toJson());
                    }
                    JsonArray removedArray = new JsonArray();
                    for (Dependency dep : removedDependencies) {
                        removedArray.add(dep.toJson());
                    }
                    diffResults.add("addedComponents", addedArray);
                    diffResults.add("removedComponents", removedArray);
                    return diffResults;
                }
            } catch (Exception e) {
                final String err = "Error analyzing dependencies: " + e.getMessage();
                System.out.println(err);
                LOG.error(err);
/*                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) Messages.showErrorDialog(project, err, "Dependency Analysis Error");
                });*/
                return new JsonObject(); // Return an empty JSON object if analysis fails.
            }
        } else {
            // If no previous SBOM exists, only analyze the current SBOM (e.g. new pom.xml).
            System.out.println("No previous SBOM found, only the current SBOM will be analyzed.");
            LOG.info("No previous SBOM found, analyzing current SBOM only.");
            try {
                List<Dependency> currDependencies = parseSetToList(extractComponentKeys(currSbom));
                // Create a JSON object to hold the results
                JsonObject diffResults = new JsonObject();
                JsonArray addedArray = new JsonArray();
                if (!currDependencies.isEmpty()) {
                    for (Dependency dep : currDependencies) {
                        addedArray.add(dep.toJson());
                    }
                    diffResults.add("addedComponents", addedArray);
                    diffResults.add("removedComponents", new JsonArray());
                    return diffResults;
                }
            } catch (Exception e) {
                System.out.println("Error analyzing current SBOM: " + e.getMessage());
                LOG.error("Error analyzing current SBOM: " + e.getMessage());
                return new JsonObject(); // Return an empty JSON object if no changes are detected.
            }
        }
        // If no changes are detected, return an empty JSON object.
        System.out.println("No changes detected in dependencies.");
        LOG.info("No changes detected in dependencies.");
        return new JsonObject(); // Return an empty JSON object if no changes are detected.
    }

    /**
     * Given a set of changes to project dependencies, get the set of licenses which may conflict with each other or
     * the target project's own license, paired with explanations for the conflicts
     * @param changes Json breakdown of the changes to the project (should be formatted as from getChanges())
     * @return Three mappings of licenses to descriptions of how they conflict with your repo: 1) that the tool thinks
     * it can address, 2) that it thinks need a legal expert, and 3) that are unknown/uncategorized
     */
    public ArrayList<Map<License, String>> getConflicts(License myLicense, JsonObject changes)
    {

        Map<License, String> conflicts = new HashMap<>();

        //Extract licenses from added components
        List<License> allLicenses = new ArrayList<>();
        JsonArray addedComponents = changes.getAsJsonArray("addedComponents");

        for (JsonElement depElem : addedComponents)
        {
            JsonObject dependency = depElem.getAsJsonObject();
            JsonArray licenses = dependency.getAsJsonArray("licenses");

            for (JsonElement licElem : licenses)
            {
                JsonObject license = licElem.getAsJsonObject();
                String licenseName = license.get("type").getAsString();
                String licenseUrl = license.get("url").getAsString();

                License licObj = new License(licenseName, licenseUrl);
                allLicenses.add(licObj);
            }
        }

        //Compare licenses against compatibility matrix to identify if any conflicts exist

        String basePath = project.getBasePath();
        // Path matrixPath = Paths.get(basePath, ".license-tool", "matrix.csv");
        // String py_serverPath = System.getenv("PYTHONPATH");
        String py_serverPath = System.getProperty("py_serverPath");
        //System.out.println("py_serverPath: " + py_serverPath);
        Path matrixPath = Paths.get(py_serverPath, "/matrix.csv");

        List<String[]> matrix = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(matrixPath.toFile()))) //Read the compatibility matrix in
        {
            String line = reader.readLine();
            while (line != null)
            {
                String[] licenseArr = line.split(",");
                matrix.add(licenseArr);
                line = reader.readLine();
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        //Find the row corresponding with my license, then find all conflicts
        for (int i = 1; i < matrix.size(); i++)
        {
            String[] licenseArr = matrix.get(i);
            if (licenseArr[0].equals(myLicense.getType()))
            {
                for (License potentialConflict : allLicenses)
                {
                    String pcType = potentialConflict.getType();
                    for (int j = 0; j < matrix.get(0).length; j++)
                    {
                        String matLicense = matrix.get(0)[j];
                        if (matLicense.equals(pcType))
                        {
                            conflicts.put(potentialConflict, matrix.get(i)[j]);
                        }
                    }
                }
                break;
            }
        }

        //Via the chatbot, verify conflicts and derive reasons for them
        Map<License, String> descriptiveConflicts = deriveConflictReasons(myLicense, conflicts);
        //Categorize the conflicts into what we can fix vs what we can't
        ArrayList<Map<License, String>> categorizedConflicts = categorizeConflicts(myLicense, descriptiveConflicts);
        //Return the conflicts paired with their descriptors
        return categorizedConflicts;
    }

    /**
     * Given a dictionary of potential conflicts mapped to string indicators of whether there is a conflict, prune all
     * items that are presumed to not be conflicts, and replace basic indicators with detailed descriptions for the
     * rest.
     * @param ownLicense target project's license
     * @param potentialConflicts dictionary of potentially conflict-inducing licenses mapped to yes/no/dep./? indicators
     *                           (from intermediate in getConflicts)
     * @return dictionary of confirmed conflicting licenses mapped to descriptions of the conflicts
     */
    public Map<License, String> deriveConflictReasons(License ownLicense, Map<License, String> potentialConflicts)
    {
        Set<String> checkedLicenses = new HashSet<>();
        Map<License, String> conflicts = new HashMap<>();

        //Read in the system prompt for the helper instance
        String systemPrompt;
        try {
            InputStream is = LicensingController.class.getClassLoader().getResourceAsStream("prompts/system-reasons.txt");
            systemPrompt = IOUtils.toString(is, "UTF-8");
        }
        catch (Exception e)
        {
            //Unable to load system prompt
            systemPrompt = "You are a component in an IDE designed to analyze software license conflicts, determining whether given licenses conflict, and, if so, why.";
        }

        //Read in the template to be used when prompting the helper instance
        String inputPromptTemplate;
        try {
            InputStream is = LicensingController.class.getClassLoader().getResourceAsStream("prompts/reasons-input-template.txt");
            inputPromptTemplate = IOUtils.toString(is, "UTF-8");
        }
        catch (Exception e)
        {
            //Unable to load input prompt template
            inputPromptTemplate = "My software license, {myLicense}, " +
            "may conflict with another license, {otherLicense}" +
                    ". If they conflict, please give me a concise, one-sentence description of why these two " +
                    "licenses may conflict with each other, including any conditions upon that conflict." +
                    "Otherwise, say \"NO CONFLICT\" in capital letters and nothing else. If you do not" +
                    "know the answer, say \"UNSURE\" in capital letters and nothing else.";
        }

        ChatbotSessionLlamaPython conflictChatbot = new ChatbotSessionLlamaPython( //TODO split up classes for different models
                "localhost",
                "gpt-4o", //TODO ideally we can configure this around the user's settings
                systemPrompt);

        for (Map.Entry<License, String> entry : potentialConflicts.entrySet())
        {
            if (!(checkedLicenses.contains(entry.getKey().getType())))
            {
                checkedLicenses.add(entry.getKey().getType());
                String reason;
                try
                {
                    switch (entry.getValue())
                    {
                        case "Yes":
                        case "Same":
                            break; //Don't add confirmed compatible licenses to our list of conflicts
                        case "No":
                        case "Dep.":
                        case "Check dependency":
                        case "?":
                            String inputPrompt = inputPromptTemplate.replace("{myLicense}", ownLicense.getType()).replace("{otherLicense}", entry.getKey().getType());
                            reason = conflictChatbot.submitPrompt(inputPrompt);
                            conflicts.put(entry.getKey(), reason);
                            break;
                        default:
                            reason = "Unknown license relationship.";
                            conflicts.put(entry.getKey(), reason);
                            break;
                    }
                }
                catch (Exception e)
                {
                    reason = "Chatbot failed to analyze license conflicts.";
                    conflicts.put(entry.getKey(), reason);
                }
            }
        }
        return conflicts;
    }

    public ArrayList<Map<License, String>> categorizeConflicts(License ownLicense, Map<License, String> allConflicts)
    {

        //Read in the system prompt for the helper instance
        String systemPrompt;
        try {
            InputStream is = LicensingController.class.getClassLoader().getResourceAsStream("prompts/system-categorization.txt");
            systemPrompt = IOUtils.toString(is, "UTF-8");
        }
        catch (Exception e)
        {
            //Unable to load system prompt
            systemPrompt = "You are a component in an IDE designed to analyze software license conflicts and determine whether yourself, an LLM, can safely and reasonably make recommendations to address the conflict, or if the conflict should not be " +
                    "answered by an LLM and instead requires the counsel of a legal expert.";
        }

        //Read in the template to be used when prompting the helper instance
        String inputPromptTemplate;
        try {
            InputStream is = LicensingController.class.getClassLoader().getResourceAsStream("prompts/categorization-input-template.txt");
            inputPromptTemplate = IOUtils.toString(is, "UTF-8");
        }
        catch (Exception e)
        {
            //Unable to load input prompt template
            inputPromptTemplate = "My software is licensed under {myLicense} " +
                    " and conflicts with the license {otherLicense}" +
                    " for the following reason: {reason}. " +
                    " If this is a conflict that you can safely address with what you know now, respond \"A\" and nothing else. " +
                    " If this is a conflict that would require analysis from a legal expert, respond \"B\" and nothing else.";
        }

        ChatbotSessionLlamaPython categorizationChatbot = new ChatbotSessionLlamaPython( //TODO split up classes for different models
                "localhost",
                "gpt-4o", //TODO ideally we can configure this around the user's settings
                systemPrompt);

        ArrayList<Map<License, String>> categorizedConflicts = new ArrayList<>();

        Map<License, String> fixableConflicts = new HashMap<>(); //The set of conflicts that the model thinks that it can address/make recommendations for
        Map<License, String> nonfixableConflicts = new HashMap<>(); //The set of conflicts that the model thinks it's best to send to a legal expert
        Map<License, String> unknownConflicts = new HashMap<>();

        for (Map.Entry<License, String> entry : allConflicts.entrySet())
        {
            try {
                String inputPrompt = inputPromptTemplate.replace("{myLicense}", ownLicense.getType()).replace("{otherLicense}", entry.getKey().getType()).replace("{reason}", entry.getValue());
                String cat = categorizationChatbot.submitPrompt(inputPrompt);
                if (cat.equals("A")) {
                    fixableConflicts.put(entry.getKey(), entry.getValue());
                } else if (cat.equals("B")) {
                    nonfixableConflicts.put(entry.getKey(), entry.getValue());
                }
                else {
                    throw new RuntimeException("unknown conflict category: " + cat);
                }
            }
            catch (Exception e) //The chatbot either failed or gave some invalid output
            {
                unknownConflicts.put(entry.getKey(), entry.getValue());
            }
        }

        categorizedConflicts.add(fixableConflicts);
        categorizedConflicts.add(nonfixableConflicts);
        categorizedConflicts.add(unknownConflicts);

        return categorizedConflicts;
    }

    public File[] genSbom(String pomPath) {
        // This method is called to analyze a dependency and return its details.
        // It should be called when a new dependency is added to the pom.xml file.
        String basePath = project.getBasePath();
        String outputDir = ".license-tool";
        if (basePath == null) {
            final String msg = "Project base path is not set.";
            /*ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) Messages.showErrorDialog(project, msg, "Dependency Analysis Error");
            });*/
            LOG.error("Project base path is not set.");
            return new File[] {null, null};
        }
        Path newSbomPath = Paths.get(basePath, "/.license-tool/bom.xml");
        File newSbomFile = newSbomPath.toFile();
        Path prevSbomPath = Paths.get(basePath, "/.license-tool/bom-prev.xml");
        File prevSbomFile = prevSbomPath.toFile();
        try{
            System.out.println("sbomFile: " + newSbomFile.getAbsolutePath());
            LOG.info("Generating SBOM for project at path: {}", basePath);
            System.out.println("prevSbomFile: " + prevSbomFile.getAbsolutePath());
            LOG.info("Previous SBOM path: {}", prevSbomFile.getAbsolutePath());
            System.out.println("sbomFile.exists(): " + newSbomFile.exists());
            LOG.info("Previous SBOM exists: {}", newSbomFile.exists());
            // Backup current SBOM before regenerating
            if (newSbomFile.exists()) {
                Files.copy(newSbomFile.toPath(), prevSbomFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Previous SBOM backed up to: " + prevSbomFile.getAbsolutePath());
                LOG.info("Previous SBOM backed up to: {}", prevSbomFile.getAbsolutePath());
            } else {
                System.out.println("No previous SBOM found.");
                LOG.info("No previous SBOM found.");
                prevSbomFile = null; // No previous SBOM to compare against
            }
            // Generate new SBOM
            newSbomFile = CycloneDxMavenInvoker.INSTANCE.generateSbom(new File(basePath), outputDir, new File(pomPath));
            System.out.println("New SBOM generated to: " + newSbomFile.getAbsolutePath());
            LOG.info("New SBOM generated to: {}", newSbomFile.getAbsolutePath());

            // Refresh the VFS to ensure listeners receive the change events
            try {
                LocalFileSystem localFs = LocalFileSystem.getInstance();
                VirtualFile vf = localFs.refreshAndFindFileByIoFile(newSbomFile);
                if (vf == null) vf = localFs.refreshAndFindFileByIoFile(newSbomFile.getParentFile());
                if (vf != null) {
                    VfsUtil.markDirtyAndRefresh(false, true, true, vf);
                    System.out.println("VFS refreshed for: " + vf.getPath());
                } else {
                    VirtualFileManager.getInstance().asyncRefresh(null);
                    System.out.println("VFS async refresh triggered");
                }
            } catch (Exception e) {
                System.out.println("Error refreshing VFS: " + e.getMessage());
                LOG.error("Error refreshing VFS: {}", e.getMessage());
            }
        }catch (Exception e){
            System.out.println( "Error analyzing dependencies: " + e.getMessage() + " Dependency Analysis Error");
            System.out.println("Stack trace: " + Arrays.toString(e.getStackTrace()));
            LOG.error("Error analyzing dependencies: {}", e.getMessage());
            return new File[] {null, null};
        }
        return new File[] {prevSbomFile, newSbomFile};
    }

    public SbomDiffResult diffSbomXml(File prevSbom, File currSbom) throws Exception {
        Set<String> prevComponents = extractComponentKeys(prevSbom);
        Set<String> currComponents = extractComponentKeys(currSbom);

        Set<String> added = new HashSet<>(currComponents);
        added.removeAll(prevComponents);

        Set<String> removed = new HashSet<>(prevComponents);
        removed.removeAll(currComponents);

        // Print the added and removed components for debugging purposes
        if (!added.isEmpty()) {
            System.out.println("Added components:");
            LOG.info("Added components: {}", String.join(", ", added));
            for (String comp : added) System.out.println(comp);
        }
        if (!removed.isEmpty()) {
            System.out.println("Removed components:");
            LOG.info("Removed components: {}", String.join(", ", removed));
            for (String comp : removed) System.out.println(comp);
        }
        if (added.isEmpty() && removed.isEmpty()) {
            LOG.info("Nothing to do. No component changes detected.");
            System.out.println("No component changes detected.");
        }

        // Return the sets of added and removed components
        return new SbomDiffResult(added, removed);
    }

    private Set<String> extractComponentKeys(File sbomFile) throws Exception {
        Set<String> keys = new HashSet<>();
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(sbomFile);
        NodeList components = doc.getElementsByTagName("component");
        for (int i = 0; i < components.getLength(); i++) {
            Element comp = (Element) components.item(i);
            String group = getTagValue(comp, "group");
            String name = getTagValue(comp, "name");
            String version = getTagValue(comp, "version");
            String license = getTagValue(comp, "license");
            System.out.println("group: " + group);
            System.out.println("name: " + name);
            System.out.println("version: " + version);
            System.out.println("License: " + license.trim());
            LOG.info("group: {}", group);
            LOG.info("name: {}", name);
            LOG.info("version: {}", version);
            LOG.info("License: {}", license.trim());
            keys.add(group + ":" + name + ":" + version + (license.isEmpty() ? "" : ":" + license));
        }
        return keys;
    }

    private String getTagValue(Element element, String tag) {
        // This method retrieves the text content of a specific tag from an XML element.
        // If the tag is "license", it retrieves all license nodes and concatenates their text content.
        if ("license".equals(tag)) {
            NodeList licenseNodes = element.getElementsByTagName("license");
            StringBuilder licenses = new StringBuilder();
            for (int i = 0; i < licenseNodes.getLength(); i++) {
                String license = licenseNodes.item(i).getTextContent();
                if (license != null && !license.trim().isEmpty()) {
                    if (!licenses.isEmpty()) licenses.append(",");
                    licenses.append(license.trim());
                }
            }
            return licenses.toString();
        } else {
            NodeList nl = element.getElementsByTagName(tag);
            if (nl.getLength() > 0 && nl.item(0).getTextContent() != null) {
                return nl.item(0).getTextContent();
            }
            return "";
        }
    }

    // This class represents the result of the SBOM diff operation.
        public record SbomDiffResult(Set<String> added, Set<String> removed) {
    }

    public static List<Dependency> parseSetToList(Set<String> depSet) {
        // This method converts a set of dependency strings to a list of Dependency objects.
        List<Dependency> dependencies = new ArrayList<>();
        for (String dep : depSet) {
            String[] parts = dep.split(":", 4); // Split by ':' and limit to 4 parts
            if (parts.length >= 3) {
                String group = parts[0];
                String name = parts[1];
                String version = parts[2];
                List<License> licenses = new ArrayList<>();
                if (parts.length > 3) {
                    String[] licenseParts = parts[3].split(",");
                    for (String license : licenseParts) {
                        // Trim whitespace and create a License object for each license
                        // split the license string to extract the license type and URL if available
                        if (license.contains("\n")) {
                            String[] licenseInfo = license.split("\n", 2);
                            licenses.add(new License(licenseInfo[0].trim(), licenseInfo.length > 1 ? licenseInfo[1].trim() : ""));
                        } else {
                            // If no URL is provided, just use the license type
                            licenses.add(new License(license.trim(), ""));
                        }
                    }
                }
                dependencies.add(new Dependency(group, name, version, licenses));
            }
        }
        return dependencies;
    }
}
