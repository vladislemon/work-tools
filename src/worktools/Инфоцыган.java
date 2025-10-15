package worktools;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Инфоцыган {
    public static void main(String[] args) throws IOException, InterruptedException {
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "1");
        try (ServerSocket lanServerSocket = new ServerSocket(443);
             ServerSocket gatewayServerSocket = new ServerSocket(9999);
             Socket gatewaySocket = gatewayServerSocket.accept();
             ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        ) {
            executorService.submit(() -> {

            });
            DataInputStream input = new DataInputStream(gatewaySocket.getInputStream());
            DataOutputStream output = new DataOutputStream(gatewaySocket.getOutputStream());
            executorService.submit(() -> {

            });
            Map<Long, Socket> lanSocketMap = new ConcurrentHashMap<>();
            AtomicLong nextId = new AtomicLong();
            executorService.submit(() -> {
                try {
                    while (true) {
                        Socket lanSocket = lanServerSocket.accept();
                        long id = nextId.getAndIncrement();
                        lanSocketMap.put(id, lanSocket);
                        // lan -> gateway
                        executorService.submit(() -> {
                            try {
                                BufferedInputStream lanInputStream = new BufferedInputStream(lanSocket.getInputStream());
                                lanInputStream.mark(4096);
                                List<String> serverNames = readServerNames(lanInputStream);
                                lanInputStream.reset();
                                if (serverNames.isEmpty()) {
                                    return;
                                }
                                System.out.println("New connection to " + serverNames.getFirst());
                                byte[] buffer = new byte[65535];
                                int messageLength;
                                while ((messageLength = lanInputStream.read(buffer)) > -1) {
                                    if (messageLength == 0) {
                                        continue;
                                    }
                                    System.out.println("To gateway: " + messageLength);
                                    output.writeLong(id);
                                    output.writeUTF(serverNames.getFirst());
                                    output.writeInt(443); //todo support different ports?
                                    output.writeShort(messageLength);
                                    output.write(buffer, 0, messageLength);
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            } finally {
                                try {
                                    lanSocketMap.remove(id).close();
                                } catch (IOException ignored) {
                                }
                            }
                        });
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            });
            // gateway -> lan
            executorService.submit(() -> {
                try {
                    while (true) {
                        long id = input.readLong();
                        int length = input.readUnsignedShort();
                        byte[] message = input.readNBytes(length);
                        Socket lanSocket = lanSocketMap.get(id);
                        if (lanSocket == null) {
                            continue;
                        }
                        System.out.println("To lan: " + length);
                        lanSocket.getOutputStream().write(message);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            });

            //noinspection ResultOfMethodCallIgnored
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
    }

    static List<String> readServerNames(InputStream inputStream) throws IOException {
        DataInputStream dataInputStream = new DataInputStream(inputStream);
        int tlsContentType = dataInputStream.readUnsignedByte();
        if (tlsContentType != 22) { //22 = handshake
            return Collections.emptyList();
        }
        int tlsVersion = dataInputStream.readUnsignedShort();
        int tlsRecordLength = dataInputStream.readUnsignedShort();
        byte[] tlsRecord = dataInputStream.readNBytes(tlsRecordLength);
        return readServerNames(tlsRecord);
    }

    static List<String> readServerNames(byte[] tlsHandshakeData) throws IOException {
        List<String> serverNames = new ArrayList<>();
        DataInputStream handshake = new DataInputStream(new ByteArrayInputStream(tlsHandshakeData));
        int handshakeType = handshake.readUnsignedByte();
        handshake.readUnsignedByte();
        int handshakeLength = handshake.readUnsignedShort();
        int handshakeVersion = handshake.readUnsignedShort();
        byte[] random = handshake.readNBytes(32);
        int sessionIdLength = handshake.readUnsignedByte();
        byte[] sessionId = handshake.readNBytes(sessionIdLength);
        int cipherSuitesLength = handshake.readUnsignedShort();
        byte[] cipherSuites = handshake.readNBytes(cipherSuitesLength);
        int compressionMethodsLength = handshake.readUnsignedByte();
        byte[] compressionMethods = handshake.readNBytes(compressionMethodsLength);
        int extensionsLength = handshake.readUnsignedShort();
        DataInputStream extensions = new DataInputStream(new ByteArrayInputStream(handshake.readNBytes(extensionsLength)));
        while (extensions.available() > 0) {
            int extensionType = extensions.readUnsignedShort();
            int extensionLength = extensions.readUnsignedShort();
            byte[] extensionDataArray = extensions.readNBytes(extensionLength);
            if (extensionType == 0) {
                DataInputStream extensionData = new DataInputStream(new ByteArrayInputStream(extensionDataArray));
                int serverNameListLength = extensionData.readUnsignedShort();
                DataInputStream serverNameList = new DataInputStream(new ByteArrayInputStream(extensionData.readNBytes(serverNameListLength)));
                while (serverNameList.available() > 0) {
                    int serverNameType = serverNameList.readUnsignedByte();
                    int serverNameLength = serverNameList.readUnsignedShort();
                    byte[] serverName = serverNameList.readNBytes(serverNameLength);
                    serverNames.add(new String(serverName));
                }
            }
        }
        return serverNames;
    }
}
