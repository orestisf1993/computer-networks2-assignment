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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static javax.xml.bind.DatatypeConverter.printHexBinary;

class userApplication {
    private final static Level loggerLevel = Level.ALL;
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
        final Decoder dcpmDecoder = new Decoder() {
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
        final Decoder aqdcpmDecoder = new Decoder() {
            /**
             * Old value of last delta2.
             */
            int oldDelta2;

            /**
             * Get an integer from low and high bytes using little endian format.
             * @param first The first byte.
             * @param second The second byte.
             * @return The integer.
             */
            int getInt(final byte first, final byte second) {
                final ByteBuffer byteBuffer = ByteBuffer.wrap(new byte[]{first, second});
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
                return byteBuffer.getShort();
            }

            /**
             * Get low byte of 16-bit integer.
             * @param x The integer.
             * @return The low byte.
             */
            byte getLowByte(final int x) {
                return (byte) (x & 0xff);
            }

            /**
             * Get high byte of 16-bit integer.
             * @param x The integer.
             * @return The high byte.
             */
            byte getHighByte(final int x) {
                return (byte) ((x >> 8) & 0xff);
            }

            @Override
            public void decode(final byte[] buffer, final byte[] decoded, int decodedIndex) {
                if (decodedIndex == 0) {
                    // When we start decoding a new audio file, initialize last byte to 0.
                    oldDelta2 = 0;
                }

                // Grab mean and step from header.
                final int mean = getInt(buffer[0], buffer[1]);
                final int step = getInt(buffer[2], buffer[3]);
                for (int i = 4; i < AUDIO_PACKAGE_LENGTH + 4; ++i) {
                    final byte lsByte = (byte) (buffer[i] & 0x0f);
                    final byte msByte = (byte) ((buffer[i] >> 4) & 0x0f);
                    final int delta1 = (msByte - 8) * step;
                    final int delta2 = (lsByte - 8) * step;

                    final int X1 = delta1 + oldDelta2;
                    final int X2 = delta2 + delta1;
                    oldDelta2 = delta2;

                    decoded[decodedIndex++] = getLowByte(X1);
                    decoded[decodedIndex++] = getHighByte(X1);
                    decoded[decodedIndex++] = getLowByte(X2);
                    decoded[decodedIndex++] = getHighByte(X2);

                }
            }
        };
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
            final byte[] audio = downloadSound(50, 1, false);
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

        byte[] downloadSound(final int totalPackages, final int trackId) throws IOException, LineUnavailableException {
            return downloadSound(totalPackages, trackId, false);
        }

        byte[] downloadSound(final int totalPackages, final int trackId, final boolean useAQ) throws IOException, LineUnavailableException {
            if (0 >= trackId || trackId > 99) {
                final String message = "Invalid track number: " + trackId;
                logger.severe(message);
                throw new IllegalArgumentException(message);
            }
            return downloadSound(totalPackages, "L" + String.format("%02d", trackId), useAQ);
        }

        byte[] downloadSound(final int totalPackages) throws IOException, LineUnavailableException {
            return downloadSound(totalPackages, "");
        }

        byte[] downloadSound(final int totalPackages, final boolean useAQ) throws IOException, LineUnavailableException {
            return downloadSound(totalPackages, "", useAQ);
        }

        private byte[] downloadSound(final int totalPackages, final String trackCode) throws IOException, LineUnavailableException {
            return downloadSound(totalPackages, trackCode, false);
        }

        private byte[] downloadSound(final int totalPackages, final String trackCode, final boolean useAQ) throws IOException {
            if (0 > totalPackages || totalPackages > 999) {
                final String message = "Invalid number of packages asked: " + totalPackages;
                logger.severe(message);
                throw new IllegalArgumentException(message);
            }

            final String command = soundRequestCode + trackCode + (useAQ ? "AQ" : "") + "F" + String.format("%03d", totalPackages);
            simpleSend(command);

            final Decoder decoder = useAQ ? aqdcpmDecoder : dcpmDecoder;

            // Received packets for DCPM are 128 bytes long and 132 bytes long for AQ-DCPM.
            final int audioStepPerBufferByte = (useAQ ? 4 : 2);
            final byte[] buffer = new byte[AUDIO_PACKAGE_LENGTH + (useAQ ? 4 : 0)];
            final byte[] decoded = new byte[audioStepPerBufferByte * AUDIO_PACKAGE_LENGTH * totalPackages];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            logger.fine("Starting receiving packages.");
            for (int packageId = 0; packageId < totalPackages; packageId++) {
                client.receive(packet);
                logger.finest(": Received sound packet " + packageId + "  of length:" + packet.getLength());
                decoder.decode(buffer, decoded, audioStepPerBufferByte * AUDIO_PACKAGE_LENGTH * packageId);
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
