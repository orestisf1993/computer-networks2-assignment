import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static javax.xml.bind.DatatypeConverter.printHexBinary;

public class userApplication {
    final static Level loggerLevel = Level.ALL;
    private final static Logger logger = Logger.getLogger(userApplication.class.getName());

    static {
        // http://stackoverflow.com/questions/6315699/why-are-the-level-fine-logging-messages-not-showing
        final Handler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(loggerLevel);
        logger.setUseParentHandlers(false);
        logger.addHandler(consoleHandler);
        logger.setLevel(loggerLevel);
    }


    public static void main(final String[] args) throws IOException {
        final MainInstance app = new MainInstance();
        app.run(args);
    }

    private static class MainInstance {
        final String JSON_FILE_NAME = "codes.json";
        final String SERVER_ADDRESS = "155.207.18.208";  // ithaki's address.
        final DatagramSocket server;
        final DatagramSocket client;
        String clientPublicAddress;
        int clientListeningPort;
        int serverListeningPort;
        String echoRequestCode;
        String imageRequestCode;
        String soundRequestCode;

        /**
         * Initialize the connection with the server at ithaki.
         *
         * @throws SocketException
         * @throws FileNotFoundException
         * @throws UnknownHostException
         */
        MainInstance() throws SocketException, FileNotFoundException, UnknownHostException {
            initVariables();
            printInitMessage();

            final InetAddress address = InetAddress.getByName(SERVER_ADDRESS);
            client = new DatagramSocket(clientListeningPort);
            server = new DatagramSocket();
            server.setSoTimeout(1000);
            server.connect(address, serverListeningPort);
        }

        void printInitMessage() {
            logger.info("Using configuration:\n" +
                    "Client address: " + clientPublicAddress + " at port: " + clientListeningPort + "\n" +
                    "Server address: " + SERVER_ADDRESS + " at port: " + serverListeningPort + "\n" +
                    "Codes:" + "\n" +
                    "echo: " + echoRequestCode + " image: " + imageRequestCode + " sound: " + soundRequestCode);
        }

        void run(final String[] args) throws IOException {
            logger.info("Starting execution.");
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
