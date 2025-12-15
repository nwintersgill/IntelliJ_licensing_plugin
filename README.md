# IntelliJ IDEA Plugin – Quick Start

This plugin adds a **Licensing Tool** panel and a chatbot interface to IntelliJ IDEA.

---

## Prerequisites
- **Java:** 21 or newer  
  Install via Homebrew (macOS):
  ```sh
  brew install openjdk@21
    ```
  
- **IntelliJ IDEA:** Ultimate or Community Edition installed
- **Gradle:** Optional, IntelliJ's bundled Gradle works fine
- **Model Access:** Set up access for an LLM to be used by the tool. See the "Set up Models" section for more details. 
- **Python 3:** For the backend server, install Python 3.13 or newer
- **Apache Maven:** Ensure Maven is installed and on your PATH, you can get it from [maven.apache.org/install](https://maven.apache.org/install.html).

## Clone the Repository
- **To get started,** clone the plugin repository:
  ```sh
  git clone https://github.com/nwintersgill/licensing_tool.git
  cd licensing_tool
  ```

## Install Dependencies
- Ensure that the requisite Python packages are installed from the requirements.txt file for the Python server:
```
pip install -r src/main/resources/py_server/requirements.txt
```

## Set up Models
- If you want to use OpenAI models, in the src/main/resources/py_server folder, create or modify the [openai_key.txt](src/main/resources/py_server/openai_key.txt) file, which should contain only your OpenAI API key.
- To use a free local model for the chatbot, install Ollama from [ollama.com](https://ollama.com/).
- Verify that the Apache Maven command ``mvn`` works on your machine. In a terminal, type ``mvn -v``. If the command is not recognized, follow the instructions at [maven.apache.org/install](https://maven.apache.org/install.html) to download and install the tool, ensuring that it is added to your PATH.

## Open in IntelliJ IDEA
1. Launch IntelliJ IDEA
2. Open Project → select the cloned plugin folder
3. Let IntelliJ sync Gradle dependencies

## Run the Plugin in a Sandbox
1. If you want to use local models, ensure that Ollama is running (in a terminal, `ollama run llama3.2`).
2. In IntelliJ, open Run → Edit Configurations…
3. Add a new Gradle run configuration:\
    **Gradle task: runIde**
4. Click Run ▶️\
    IntelliJ will start a sandboxed IDE with your plugin installed.
5. Open a Maven project in the sandboxed IDE to see the plugin in action. The chat window should open automatically, and can be opened or closed with a button on the right side of the screen.
6. Use the dropdown at the bottom of the chat window to select a model. Note that the chatbot will not function if you select a model which is not configured.
7. At the top right corner of the chat window, click the "Survey" button to enter licensing information about your repository. This will be used as context for the model. 
8. Once the tool has been run on a target repository, it should create a ".license-tool" folder in that repository. 

## Verify Installation
In the sandboxed IntelliJ:
- Licensing Tool should appear in the right-hand tool window 
- Feel free to explore the chatbot functionality!
- The tool looks for changes to dependencies in Maven projects. Open or create a Maven project in the sandboxed IDE and make changes to the pom.xml file: the tool should engage with the user regarding these changes.

# License Information

The software in this repository is licensed under the [GNU General Public License v3.0 (GPL-3.0)](LICENSE).

The [license checklist](src/main/resources/py_server/matrix.csv), provided by the [Open Source Automation Development Lab (OSADL) eG](https://www.osadl.org/?id=3115), is licensed under the [Creative Commons Attribution 4.0 International license (CC-BY-4.0)](https://creativecommons.org/licenses/by/4.0/). © 2017 - 2024 Open Source Automation Development Lab (OSADL) eG and contributors, infoªosadl.org. Attribution information: "A project by the Open Source Automation Development Lab (OSADL) eG. For further information about the project see the description at www.osadl.org/checklists." Disclaimer notice: "The checklists and particularly the license compatibility data have been assembled with maximum diligence and care; however, the authors do not warrant nor can be held liable in any way for its correctness, usefulness, merchantibility or fitness for a particular purpose as far as permissible by applicable law. Anyone who uses the information does this on his or her sole responsibility. For any individual legal advice, it is recommended to contact a lawyer."
