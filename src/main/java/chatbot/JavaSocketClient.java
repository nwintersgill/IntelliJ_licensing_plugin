package chatbot;

import java.io.*;
import java.net.*;
import com.google.gson.*;

public class JavaSocketClient {

    final static String host = "localhost";
    final static int port = 9999;
    // Timeouts in milliseconds
    private static final int CONNECT_TIMEOUT = 2000; // 2s
    private static final int READ_TIMEOUT = 30_000; // 30s

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

        // Open socket with timeouts so we don't block indefinitely
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);

            // Send JSON
            OutputStream output = socket.getOutputStream();
            PrintWriter writer = new PrintWriter(output, true);
            writer.println(requestJson.toString()); // Gson auto-converts to JSON string

            // Read response
            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            String responseLine = reader.readLine();  // Reads until newline or times out
            if (responseLine == null) {
                return new JsonObject();
            }
            JsonObject responseJson = JsonParser.parseString(responseLine).getAsJsonObject();

            // Return
            return responseJson;

        } catch (SocketTimeoutException e) {
            System.out.println("Socket timeout when contacting python server: " + e.getMessage());
        } catch (ConnectException e) {
            System.out.println("Unable to connect to python server: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("I/O Error: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Unexpected error in JavaSocketClient: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
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

        // Return
        if (responseJson.has("result") && !responseJson.get("result").isJsonNull()) {
            try { return responseJson.get("result").getAsInt(); } catch (Exception ignored) {}
        }
        return 0;
    }

    public static String promptModel(String prompt, JsonObject conversationHistory, String model) {

        // Create JSON request
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("function", "promptModel");
        JsonArray arguments = new JsonArray();
        arguments.add(model);
        arguments.add(prompt);
        // Optionally send history
        if (conversationHistory != null) requestJson.add("history", conversationHistory);
        requestJson.add("args", arguments);

        // Call Python function
        JsonObject responseJson = callPython(requestJson);

        // Return response
        if (responseJson.has("result") && !responseJson.get("result").isJsonNull()) {
            try { return responseJson.get("result").getAsString(); } catch (Exception ignored) {}
        }
        return "";
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
        if (responseJson.has("result") && !responseJson.get("result").isJsonNull()) {
            try { return responseJson.get("result").getAsInt(); } catch (Exception ignored) {}
        }
        return 0;
    }
}