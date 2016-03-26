/**
 * Εργασία: Δίκτυα Υπολογιστών ΙΙ, Ορέστης Φλώρος-Μαλιβίτσης 7796.
 */

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
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

    public static void main(final String[] args) throws IOException, LineUnavailableException {
        final MainInstance app = new MainInstance();
        app.run(args);
    }

    private static class MainInstance {
        static final int AUDIO_PACKAGE_LENGTH = 128;
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
        Decoder dcpmDecoder = new Decoder() {
            @Override
            public void decode(final byte[] buffer, final byte[] decoded, int decodedIndex) {
                byte X2 = decoded[decodedIndex];
                for (int i = 0; i < AUDIO_PACKAGE_LENGTH; i++) {
                    final byte lsByte = (byte) (buffer[i] & 0x0f);
                    final byte msByte = (byte) ((buffer[i] >> 4) & 0x0f);
                    final byte X1 = (byte) (msByte - 8 + X2);
                    X2 = (byte) (lsByte - 8 + X1);

                    decoded[decodedIndex++] = X1;
                    decoded[decodedIndex++] = X2;
                }
            }
        };
        Decoder aqdcpmDecoder = new Decoder() {
            @Override
            public void decode(final byte[] buffer, final byte[] decoded, final int decodedIndex) {
                //TODO.
            }
        };

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
            client.setSoTimeout(2000);
            server = new DatagramSocket();
            server.setSoTimeout(2000);
            server.connect(address, serverListeningPort);
        }

        void downloadImage(final String filename) throws IOException {
            // 128 is default (L=128). Supported are: 128,256,512,1024.
            downloadImage(filename, 128);
        }

        void downloadImage(final String filename, final int maxLength) throws IOException {
            downloadImage(filename, maxLength, false);
        }

        void printInitMessage() {
            logger.info("Using configuration:\n" +
                    "Client address: " + clientPublicAddress + " at port: " + clientListeningPort + "\n" +
                    "Server address: " + SERVER_ADDRESS + " at port: " + serverListeningPort + "\n" +
                    "Codes:" + "\n" +
                    "echo: " + echoRequestCode + " image: " + imageRequestCode + " sound: " + soundRequestCode);
        }

        void run(final String[] args) throws IOException, LineUnavailableException {
            logger.info("Starting execution.");

            logger.info("Starting image downloads.");
            downloadImage("test2.jpg", 512, false, "PTZ");
            downloadImage("test3.jpg", 1024, true);

            logger.info("Starting downloadSound().");
            final byte[] audio = downloadSound("test.wav", 50, 1, false);
            playMusic(audio, 8);
        }

        void simpleSend(final String message) throws IOException {
            logger.fine("Sending command:" + message);
            final byte[] buffer = message.getBytes();
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            server.send(packet);
        }

        void playMusic(final byte[] audio, final int Q) throws LineUnavailableException {
            final AudioFormat linearPCM = new AudioFormat(8000, Q, 1, true, false);
            final SourceDataLine lineOut = AudioSystem.getSourceDataLine(linearPCM);
            lineOut.open(linearPCM, 32000);
            lineOut.start();
            lineOut.write(audio, 0, audio.length);
            lineOut.stop();
            lineOut.close();
        }

        byte[] downloadSound(final String filename, final int totalPackages, final int trackId) throws IOException, LineUnavailableException {
            return downloadSound(filename, totalPackages, trackId, false);
        }

        byte[] downloadSound(final String filename, final int totalPackages, final int trackId, final boolean useAQ) throws IOException, LineUnavailableException {
            if (0 >= trackId || trackId > 99) {
                final String message = "Invalid track number: " + trackId;
                logger.severe(message);
                throw new IllegalArgumentException(message);
            }
            return downloadSound(filename, totalPackages, "L" + String.format("%02d", trackId), useAQ);
        }

        byte[] downloadSound(final String filename, final int totalPackages) throws IOException, LineUnavailableException {
            return downloadSound(filename, totalPackages, "");
        }

        byte[] downloadSound(final String filename, final int totalPackages, final boolean useAQ) throws IOException, LineUnavailableException {
            return downloadSound(filename, totalPackages, "", useAQ);
        }

        private byte[] downloadSound(final String filename, final int totalPackages, final String trackCode) throws IOException, LineUnavailableException {
            return downloadSound(filename, totalPackages, trackCode, false);
        }

        private byte[] downloadSound(final String filename, final int totalPackages, final String trackCode, final boolean useAQ) throws IOException, LineUnavailableException {
            if (0 > totalPackages || totalPackages > 999) {
                final String message = "Invalid number of packages asked: " + totalPackages;
                logger.severe(message);
                throw new IllegalArgumentException(message);
            }

            final String command = soundRequestCode + trackCode + (useAQ ? "AQ" : "") + "F" + String.format("%03d", totalPackages);
            simpleSend(command);

            final Decoder decoder;
            if (useAQ) {
                decoder = aqdcpmDecoder;
            } else {
                decoder = dcpmDecoder;
            }

            // Received packets are 128 bytes long.
            final byte[] buffer = new byte[AUDIO_PACKAGE_LENGTH];
            final byte[] decoded = new byte[2 * AUDIO_PACKAGE_LENGTH * totalPackages];
            final DatagramPacket packet = new DatagramPacket(buffer, AUDIO_PACKAGE_LENGTH);
            logger.fine("Starting receiving packages.");
            for (int packageId = 0; packageId < totalPackages; packageId++) {
                client.receive(packet);
                logger.finest(filename + ": Received sound packet " + packageId + "  of length:" + packet.getLength());
                decoder.decode(buffer, decoded, 2 * AUDIO_PACKAGE_LENGTH * packageId);
            }
            return decoded;
        }

        void downloadImage(final String filename, final int maxLength, final boolean flow) throws IOException {
            downloadImage(filename, maxLength, flow, "FIX");
        }

        void downloadImage(final String filename, final int maxLength, final boolean flow, final String camera) throws IOException {
            final byte[] imageBuffer = new byte[maxLength];
            final DatagramPacket imagePacket = new DatagramPacket(imageBuffer, imageBuffer.length);
            final String imageCommand = imageRequestCode + (flow ? "FLOW=ON" : "") + "UDP=" + maxLength + "CAM=" + camera;
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
            saveStreamToFile(stream, filename);
        }

        void saveStreamToFile(final ByteArrayOutputStream stream, final String filename) throws IOException {
            final FileOutputStream out = new FileOutputStream(filename);
            logger.info("Download finished, saving stream to " + filename + ".");
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

        interface Decoder {
            void decode(final byte[] buffer, byte[] decoded, int decodedIndex);
        }
    }
}
