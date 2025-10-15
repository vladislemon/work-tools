package worktools;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Router {
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
                                List<String> serverNames = SNIReader.readServerNames(lanInputStream);
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
}
