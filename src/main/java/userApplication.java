/**
 * Εργασία: Δίκτυα Υπολογιστών ΙΙ, Ορέστης Φλώρος-Μαλιβίτσης 7796.
 */

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

            logger.info("Starting image downloads.");
            downloadImage("test2.jpg", 512, false, "PTZ");
            downloadImage("test3.jpg", 1024, true);
        }

        void simpleSend(final String message) throws IOException {
            final byte[] buffer = message.getBytes();
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            server.send(packet);
        }

        void downloadImage(final String filename) throws IOException {
            // 128 is default (L=128). Supported are: 128,256,512,1024.
            downloadImage(filename, 128);
        }

        void downloadImage(final String filename, final int maxLength) throws IOException {
            downloadImage(filename, maxLength, false);
        }

        void downloadImage(final String filename, final int maxLength, final boolean flow) throws IOException {
            downloadImage(filename, maxLength, flow, "FIX");
        }

        void downloadImage(final String filename, final int maxLength, final boolean flow, final String camera) throws IOException {
            final byte[] imageBuffer = new byte[maxLength];
            final DatagramPacket imagePacket = new DatagramPacket(imageBuffer, imageBuffer.length);
            final String imageCommand = "image_request_code" + imageRequestCode + (flow ? "FLOW=ON" : "") + "UDP=" + maxLength + "CAM=" + camera;
            simpleSend(imageCommand);
            final ByteArrayOutputStream stream = new ByteArrayOutputStream();
            while (true) {
                try {
                    client.receive(imagePacket);
                } catch (final SocketTimeoutException exception) {
                    // Since we got a timeout, we have to check if the termination sequence is that of an image.
                    final byte[] finalImageBytes = stream.toByteArray();
                    final byte[] terminatingSequence = Arrays.copyOfRange(finalImageBytes, finalImageBytes.length - 2, finalImageBytes.length);
                    final byte[] expectedTerminatingSequence = new byte[]{(byte) 0xff, (byte) 0xd9};
                    final String baseLogMessage = "Image download stopped by timeout.";
                    if (Arrays.equals(terminatingSequence, expectedTerminatingSequence)) {
                        logger.info(baseLogMessage);
                        break;
                    } else {
                        logger.warning(baseLogMessage
                                + " Last bytes aren't those that terminate a .jpg image. No image will be saved.\n"
                                + "Expected: " + printHexBinary(expectedTerminatingSequence) + "\n"
                                + "Got: " + printHexBinary(terminatingSequence));
                        stream.close();
                        return;
                    }
                }
                final int packetLength = imagePacket.getLength();
                logger.finest(filename + ": Received image packet of length:" + packetLength + ".");
                stream.write(imageBuffer, 0, packetLength);
                if (packetLength < maxLength) {
                    break;
                }
                if (flow) {
                    simpleSend("NEXT");
                }
            }
            final FileOutputStream out = new FileOutputStream(filename);
            logger.info("Image download finished, saving to " + filename + ".");
            out.write(stream.toByteArray());
            out.close();
            stream.close();
        }

        void initVariables() throws FileNotFoundException {
            final JsonReader reader = new JsonReader(new FileReader(JSON_FILE_NAME));
            final JsonObject json = new Gson().fromJson(reader, JsonObject.class);

            clientPublicAddress = json.get("clientPublicAddress").getAsString();
            clientListeningPort = json.get("clientListeningPort").getAsInt();
            serverListeningPort = json.get("serverListeningPort").getAsInt();
            echoRequestCode = json.get("echoRequestCode").getAsString();
            imageRequestCode = json.get("imageRequestCode").getAsString();
            soundRequestCode = json.get("soundRequestCode").getAsString();
        }
    }
}
