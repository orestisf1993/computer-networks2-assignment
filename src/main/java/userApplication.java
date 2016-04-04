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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * Main application for the assignment.
 */
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
        /**
         * The length in bytes for a UDP audio package.
         */
        static final int AUDIO_PACKAGE_LENGTH = 128;
        /**
         * Code used to use the "echo" functionality without the artificial delay.
         */
        final String ECHO_WITHOUT_DELAY_CODE = "E0000";
        /**
         * JSON file with needed codes for communication with ithaki.
         */
        final String JSON_FILE_NAME = "codes.json";
        /**
         * Ithaki's address.
         */
        final String SERVER_ADDRESS = "155.207.18.208";
        /**
         * {@link DatagramSocket} that sends commands to ithaki server.
         */
        final DatagramSocket server;
        /**
         * {@link DatagramSocket} that receives data from ithaki server.
         */
        final DatagramSocket client;
        /**
         * {@link Decoder} used for DPCM decoding of audio bytes.
         */
        final Decoder dpcmDecoder = new Decoder() {
            @Override
            public void decode(final byte[] buffer, final byte[] decoded, int decodedIndex) {
                byte X2 = decodedIndex > 0 ? decoded[decodedIndex - 1] : 0;
                for (int i = 0; i < AUDIO_PACKAGE_LENGTH; i++) {
                    final byte lsByte = (byte) (buffer[i] & 0x0f);
                    final byte msByte = (byte) ((buffer[i] >> 4) & 0x0f);
                    final byte X1 = (byte) (msByte - 8 + X2);
                    X2 = (byte) (lsByte - 8 + X1);

                    decoded[decodedIndex++] = X1;
                    decoded[decodedIndex++] = X2;
                }
            }

            @Override
            public void saveHistory(final File filename) throws FileNotFoundException {}
        };
        /**
         * {@link Decoder} used for AQ-DPCM decoding of audio bytes.
         */
        final Decoder aqdpcmDecoder = new Decoder() {
            /**
             * Old value of last delta2.
             */
            int oldDelta2;
            /**
             * Save history of values of mean m.
             */
            ArrayList<Integer> meanHistory = new ArrayList<>();
            /**
             * Save history of values of step b.
             */
            ArrayList<Integer> stepHistory = new ArrayList<>();

            @Override
            public void saveHistory(final File filename) throws FileNotFoundException {
                final PrintWriter out = new PrintWriter(filename);
                out.println("{");
                out.println("  \"mean\":" + meanHistory.toString() + ",");
                out.println("  \"step\":" + stepHistory.toString());
                out.println("}");
                out.close();
            }

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
                    meanHistory.clear();
                    stepHistory.clear();
                }

                // Grab mean and step from header.
                final int mean = getInt(buffer[0], buffer[1]);
                logger.finest("mean: " + mean);
                meanHistory.add(mean);
                final int step = getInt(buffer[2], buffer[3]);
                logger.finest("step: " + step);
                stepHistory.add(step);
                for (int i = 4; i < AUDIO_PACKAGE_LENGTH + 4; ++i) {
                    final byte lsByte = (byte) (buffer[i] & 0x0f);
                    final byte msByte = (byte) ((buffer[i] >> 4) & 0x0f);
                    final int delta1 = (msByte - 8) * step;
                    final int delta2 = (lsByte - 8) * step;

                    final int X1 = delta1 + oldDelta2 + mean;
                    final int X2 = delta2 + delta1 + mean;
                    oldDelta2 = delta2;

                    decoded[decodedIndex++] = getLowByte(X1);
                    decoded[decodedIndex++] = getHighByte(X1);
                    decoded[decodedIndex++] = getLowByte(X2);
                    decoded[decodedIndex++] = getHighByte(X2);

                }
            }
        };
        /**
         * Address of the client that runs the userApplication.
         */
        String clientPublicAddress;
        /**
         * The port used by the client to receive data.
         */
        int clientListeningPort;
        /**
         * The port used by the server to receive data.
         */
        int serverListeningPort;
        /**
         * Code for echo requests.
         */
        String echoRequestCode;
        /**
         * Code for image requests.
         */
        String imageRequestCode;
        /**
         * Code for sound requests.
         */
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
            client.setSoTimeout(10000);
            server = new DatagramSocket();
            server.setSoTimeout(10000);
            server.connect(address, serverListeningPort);
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
            final byte[] audio = downloadSound(50, 1, true);
            playMusic(audio, 16);
            playMusic(downloadRandomSound(100, false), 8);
            testThroughput(1000 * 60 / 10, false);
            testThroughput(1000 * 60 / 10, true);
        }

        /**
         * Send a message to the server.
         *
         * @param message The message.
         * @throws IOException
         */
        void simpleSend(final String message) throws IOException {
            logger.fine("Sending command:" + message);
            final byte[] buffer = message.getBytes();
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            server.send(packet);
        }

        void testThroughput(final long duration, final boolean enableServerDelay) throws IOException {
            if (duration < 4 * 60 * 1000) {
                logger.warning("Throughput duration smaller than minimum expected for assignment.");
            }
            final String code = enableServerDelay ? echoRequestCode : ECHO_WITHOUT_DELAY_CODE;
            final byte[] commandBuffer = code.getBytes();
            final byte[] receiveBuffer = new byte[128];
            final DatagramPacket packetSend = new DatagramPacket(commandBuffer, commandBuffer.length);
            final DatagramPacket packetReceive = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            final long timeStart = System.currentTimeMillis();
            final StringBuilder history = new StringBuilder("" + timeStart + "\n");
            long timeEnd = timeStart;
            int counter = 0;
            logger.info(String.format("Starting downloading echo packages with code %s for next %d ms.", code,
                    duration));
            while (timeEnd - timeStart < duration) {
                server.send(packetSend);
                boolean timeout = false;
                try {
                    client.receive(packetReceive);
                } catch (final SocketTimeoutException exception) {
                    logger.severe(exception.toString());
                    timeout = true;
                }
                timeEnd = System.currentTimeMillis();
                counter++;
                history.append(timeEnd).append(":")
                        .append(packetReceive.getLength() + packetSend.getLength()).append(":")
                        .append(timeout).append("\n");
            }
            logger.info(String.format("Received %d packets in %d ms.", counter, duration));
            final PrintWriter out = new PrintWriter(code + ".txt");
            out.print(history.toString());
            out.close();
        }

        /**
         * Play music from a byte array, using Q bits for the quantizer.
         *
         * @param audio The byte array containing the audio data.
         * @param Q     The bits for the quantizer.
         * @throws LineUnavailableException
         */
        void playMusic(final byte[] audio, final int Q) throws LineUnavailableException {
            final AudioFormat linearPCM = new AudioFormat(8000, Q, 1, true, false);
            final SourceDataLine lineOut = AudioSystem.getSourceDataLine(linearPCM);
            lineOut.open(linearPCM, 32000);
            lineOut.start();
            lineOut.write(audio, 0, audio.length);
            lineOut.stop();
            lineOut.close();
        }

        /**
         * Download a random sound by using the {@code "T"} code.
         *
         * @param totalPackages
         * @param useAQ
         * @return
         * @throws IOException
         * @throws LineUnavailableException
         * @see MainInstance#downloadSound(int, String, boolean, boolean)
         */
        byte[] downloadRandomSound(final int totalPackages, final boolean useAQ) throws IOException,
                LineUnavailableException {
            return downloadSound(totalPackages, "", useAQ, true);
        }

        /**
         * {@code trackId} is converted to a properly formatted {@link String} for use in
         * {@link MainInstance#downloadSound(int, String, boolean, boolean)}.
         *
         * @param totalPackages
         * @param trackId
         * @param useAQ
         * @return
         * @throws IOException
         * @throws LineUnavailableException
         * @see MainInstance#downloadSound(int, String, boolean, boolean)
         */
        byte[] downloadSound(final int totalPackages, final int trackId, final boolean useAQ) throws IOException,
                LineUnavailableException {
            if (0 >= trackId || trackId > 99) {
                final String message = "Invalid track number: " + trackId;
                logger.severe(message);
                throw new IllegalArgumentException(message);
            }
            return downloadSound(totalPackages, "L" + String.format("%02d", trackId), useAQ, false);
        }

        /**
         * Download & encode audio file.
         *
         * @param totalPackages Length of the audio file in {@link MainInstance#AUDIO_PACKAGE_LENGTH}-byte packages.
         * @param trackCode     The code string used for the track code eg {@code "L01"}.
         * @param useAQ         {@code true} if adaptive quantiser is to be used.
         * @param randomTrack   If {@code true} {@code "T"} code will be used.
         * @return The decoded audio file.
         * @throws IOException
         */
        private byte[] downloadSound(
                final int totalPackages,
                final String trackCode,
                final boolean useAQ,
                final boolean randomTrack
        ) throws IOException {
            if (0 > totalPackages || totalPackages > 999) {
                final String message = "Invalid number of packages asked: " + totalPackages;
                logger.severe(message);
                throw new IllegalArgumentException(message);
            }
            if (randomTrack && !Objects.equals(trackCode, "")) {
                final String message = "randomTrack can't be enabled when a trackCode is specified";
                logger.severe(message);
                throw new IllegalArgumentException(message);
            }

            final String command = soundRequestCode + trackCode + (useAQ ? "AQ" : "") + (randomTrack ? "T" : "F")
                    + String.format("%03d", totalPackages);
            simpleSend(command);

            final Decoder decoder = useAQ ? aqdpcmDecoder : dpcmDecoder;

            // Received packets for DPCM are 128 bytes long and 132 bytes long for AQ-DPCM.
            final int audioStepPerBufferByte = (useAQ ? 4 : 2);
            final byte[] buffer = new byte[AUDIO_PACKAGE_LENGTH + (useAQ ? 4 : 0)];
            final byte[] decoded = new byte[audioStepPerBufferByte * AUDIO_PACKAGE_LENGTH * totalPackages];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            logger.fine("Starting receiving packages.");
            FileOutputStream streamOut = new FileOutputStream(getUniqueFile(command + "buffer", "data"));
            for (int packageId = 0; packageId < totalPackages; packageId++) {
                client.receive(packet);
                logger.finest(": Received sound packet " + packageId + "  of length:" + packet.getLength());
                decoder.decode(buffer, decoded, audioStepPerBufferByte * AUDIO_PACKAGE_LENGTH * packageId);
                try {
                    streamOut.write(buffer);
                } catch (final Exception exception) {
                    streamOut.close();
                }
            }
            decoder.saveHistory(getUniqueFile(command, "txt"));
            streamOut.close();
            streamOut = new FileOutputStream(getUniqueFile(command + "decoded", "data"));
            try {
                streamOut.write(decoded);
            } finally {
                streamOut.close();
            }
            return decoded;
        }

        File getUniqueFile(final String baseFilename, final String extension) {
            int i = 1;
            String filename = baseFilename + "." + extension;
            File file = new File(filename);
            while (file.exists()) {
                filename = baseFilename + "-" + i++ + "." + extension;
                file = new File(filename);
            }
            return file;
        }

        /**
         * {@code camera} defaults to {@code "FIX"}
         *
         * @param maxLength
         * @param flow
         * @throws IOException
         * @see MainInstance#downloadImage(int, boolean, String)
         */
        void downloadImage(final int maxLength, final boolean flow) throws IOException {
            downloadImage(maxLength, flow, "FIX");
        }

        /**
         * Downloads an image and saves it at specified file.
         *
         * @param maxLength The length of each UDP packet.
         * @param useFlow   {@code true} if ithaki's "FLOW" feature is to be used.
         * @param camera    Specifies which camera is to be used for the picture.
         * @throws IOException
         */
        void downloadImage(final int maxLength, final boolean useFlow, final String camera)
                throws IOException {
            final byte[] imageBuffer = new byte[maxLength];
            final DatagramPacket imagePacket = new DatagramPacket(imageBuffer, imageBuffer.length);
            final String imageCommand = imageRequestCode + (useFlow ? "FLOW=ON" : "") + "UDP=" + maxLength + "CAM=" +
                    camera;
            simpleSend(imageCommand);
            final ByteArrayOutputStream stream = new ByteArrayOutputStream();
            while (true) {
                try {
                    client.receive(imagePacket);
                } catch (final SocketTimeoutException exception) {
                    // Since we got a timeout, we have to check if the termination sequence is that of an image.
                    final byte[] finalImageBytes = stream.toByteArray();
                    final byte[] terminatingSequence = Arrays.copyOfRange(finalImageBytes, finalImageBytes.length -
                            2, finalImageBytes.length);
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
                logger.finest(imageCommand + ": Received image packet of length:" + packetLength + ".");
                stream.write(imageBuffer, 0, packetLength);
                if (packetLength < maxLength) {
                    break;
                }
                if (useFlow) {
                    simpleSend("NEXT");
                }
            }
            saveStreamToFile(stream, imageCommand + ".jpg");
        }

        void saveStreamToFile(final ByteArrayOutputStream stream, final String filename) throws IOException {
            final FileOutputStream out = new FileOutputStream(filename);
            logger.info("Download finished, saving stream to " + filename + ".");
            out.write(stream.toByteArray());
            out.close();
            stream.close();
        }

        /**
         * Read {@link MainInstance#JSON_FILE_NAME} and initialize parameters to be used.
         * <p>
         * Initializes:
         * <ul>
         * <li>{@link MainInstance#clientPublicAddress}</li>
         * <li>{@link MainInstance#clientListeningPort}</li>
         * <li>{@link MainInstance#serverListeningPort}</li>
         * <li>{@link MainInstance#echoRequestCode}</li>
         * <li>{@link MainInstance#imageRequestCode}</li>
         * <li>{@link MainInstance#soundRequestCode}</li>
         * </ul>
         * <p>Uses {@link Gson} library.</p>
         *
         * @throws FileNotFoundException
         */
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

        /**
         * Interface that hold {@link Decoder#decode(byte[], byte[], int)} function for decoding of received audio
         * files in @{code byte[]} format.
         */
        interface Decoder {
            /**
             * Decode an encoded buffer.
             *
             * @param buffer       The buffer.
             * @param decoded      The decoded result.
             * @param decodedIndex The place to start decoding in the buffer.
             */
            void decode(final byte[] buffer, byte[] decoded, int decodedIndex);

            void saveHistory(File filename) throws FileNotFoundException;
        }
    }
}
