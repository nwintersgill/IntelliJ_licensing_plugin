package chatbot;

import java.io.*;
import java.net.*;
import com.google.gson.*;

public class JavaSocketClient {

    final static String host = "localhost";
    final static int port = 9999;
    public static void main(String[] args) {
        int c = add(1,2);
        System.out.println(c);
        int d = add(3,4);
        System.out.println(d);
        int e = subtract(6,2);
        System.out.println(e);
        // System.out.println(promptModel());
    }

    public static JsonObject callPython(JsonObject requestJson) {

        // Open socket
        try (Socket socket = new Socket(host, port)){

            // Send JSON
            OutputStream output = socket.getOutputStream();
            PrintWriter writer = new PrintWriter(output, true);
            writer.println(requestJson.toString()); // Gson auto-converts to JSON string

            // Read response
            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            String responseLine = reader.readLine();  // Reads until newline
            JsonObject responseJson = JsonParser.parseString(responseLine).getAsJsonObject();

            // Return
            return responseJson;

        } catch (IOException e) {
            System.out.println("I/O Error: " + e.getMessage());
        }
        return new JsonObject();
    }

    public static int add(int a, int b) {

        // Create JSON request
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("function", "add");
        JsonArray arguments = new JsonArray();
        arguments.add(a);
        arguments.add(b);
        requestJson.add("args", arguments);

        // Call Python function
        JsonObject responseJson = callPython(requestJson);

        // Return response
        return responseJson.get("result").getAsInt();
    }

    public static String promptModel(String prompt, JsonObject conversationHistory, String model) {

        // Create JSON request
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("function", "promptModel");
        // JsonArray arguments = new JsonArray();
        // requestJson.add("args", arguments);

        // Call Python function
        JsonObject responseJson = callPython(requestJson);

        // Return response
        return responseJson.get("result").toString();
    }

    public static int subtract(int a, int b) {

        // Create JSON request
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("function", "subtract");
        JsonArray arguments = new JsonArray();
        arguments.add(a);
        arguments.add(b);
        requestJson.add("args", arguments);

        // Call Python function
        JsonObject responseJson = callPython(requestJson);

        // Return response
        return responseJson.get("result").getAsInt();
    }
}