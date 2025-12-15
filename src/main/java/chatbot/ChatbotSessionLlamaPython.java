package chatbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class ChatbotSessionLlamaPython extends ChatbotSession
{
    private String host;
    private String model;
    private JsonArray history;

    public ChatbotSessionLlamaPython(String host, String model){
        this.host = host;
        this.model = model;
        this.history = new JsonArray();
    }

    //TODO this is currently implemented as a generic class that can host any model, but ideally we should have different implementations for different models
    public ChatbotSessionLlamaPython(String host, String model, String systemPrompt){
        this.host = host;
        this.model = model;
        this.history = new JsonArray();
        addToHistory("system", systemPrompt);
    }

    public String submitPrompt(String prompt) throws Exception {
        // Create JSON request
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("function", "promptModel");
        JsonArray arguments = new JsonArray();
        arguments.add(this.host);
        arguments.add(this.model);
        arguments.add(prompt);
        arguments.add(this.history);
        requestJson.add("args", arguments);

        // Call Python function
        JsonObject responseJson = JavaSocketClient.callPython(requestJson);

        // Get response
        // String result = responseJson.get("result").toString();
        String result = "";
        if (responseJson.has("result") && !responseJson.get("result").isJsonNull()) {
            // Use getAsString() to avoid JSON quoting/escaped \n sequences
            result = responseJson.get("result").getAsString();
        }

        // Add to history
        addToHistory("user", prompt);
        addToHistory("assistant", result);

        // Return result
        return result;
    }

    //TODO ideally we don't need this, but the async nature of it is breaking things in some places
    public String submitPromptBlocking(String prompt) throws Exception {
        // Create JSON request
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("function", "promptModel");
        JsonArray arguments = new JsonArray();
        arguments.add(this.host);
        arguments.add(this.model);
        arguments.add(prompt);
        arguments.add(this.history);
        requestJson.add("args", arguments);

        // Call Python function
        JsonObject responseJson = JavaSocketClient.callPython(requestJson);

        // Get response
        // String result = responseJson.get("result").toString();
        String result = "";
        if (responseJson.has("result") && !responseJson.get("result").isJsonNull()) {
            // Use getAsString() to avoid JSON quoting/escaped \n sequences
            result = responseJson.get("result").getAsString();
        }

        // Add to history
        addToHistory("user", prompt);
        addToHistory("assistant", result);

        // Return result
        return result;
    }

    public void clearHistory(){
        this.history = new JsonArray();
    }

    public JsonArray getHistory(){
        return this.history;
    }

    public void addToHistory(String role, String content){
        JsonObject iteration = new JsonObject();
        iteration.addProperty("role", role);
        iteration.addProperty("content", content);
        this.history.add(iteration);
    }
}
