import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class userApplication {

    public static void main(final String[] args) throws IOException {
        MainInstance app = new MainInstance();
        app.run(args);
    }

    private static class MainInstance {
        final String jsonFileName = "codes.json";

        String clientPublicAddress;
        int clientListeningPort;
        int serverListeningPort;
        String echoRequestCode;
        String imageRequestCode;
        String soundRequestCode;

        MainInstance() {}

        void printInitMessage() {
            System.out.println("Using configuration:");
            System.out.println("client address: " + clientPublicAddress + " at port: " + clientListeningPort);
            System.out.println("Server port: " + serverListeningPort);
            System.out.println("Codes:");
            System.out.println("echo: " + echoRequestCode + " image: " + imageRequestCode + " sound: " + soundRequestCode);
        }

        public void run(final String[] args) throws IOException {
            initVariables();
            printInitMessage();
        }

        void initVariables() throws FileNotFoundException {
            JsonReader reader = new JsonReader(new FileReader(jsonFileName));
            JsonObject json = new Gson().fromJson(reader, JsonObject.class);

            clientPublicAddress = json.get("clientPublicAddress").getAsString();
            clientListeningPort = json.get("clientListeningPort").getAsInt();
            serverListeningPort = json.get("serverListeningPort").getAsInt();
            echoRequestCode = json.get("echoRequestCode").getAsString();
            imageRequestCode = json.get("imageRequestCode").getAsString();
            soundRequestCode = json.get("soundRequestCode").getAsString();
        }
    }
}
