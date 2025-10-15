package worktools;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Gateway {

    public static void main(String[] args) throws InterruptedException, IOException {
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "1");
        try (Socket routerSocket = new Socket("192.168.1.2", 9999);
             ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        ) {
            Map<Long, Socket> wanSocketMap = new ConcurrentHashMap<>();
            executorService.submit(() -> {
                try {
                    DataInputStream input = new DataInputStream(routerSocket.getInputStream());
                    DataOutputStream output = new DataOutputStream(routerSocket.getOutputStream());
                    while (true) {
                        long id = input.readLong();
                        String host = input.readUTF();
                        int port = input.readInt();
                        int length = input.readUnsignedShort();
                        System.out.println("From gateway: " + length);
                        byte[] message = input.readNBytes(length);
                        if (message.length < length) {
                            break;
                        }
//                        Socket wanSocket = wanSocketMap.compute(id, (i, s) -> {
//                            if (s == null || s.isClosed()) {
//                                return socket(host, port);
//                            }
//                            return s;
//                        });
                        executorService.submit(() -> {
                            try {
                                @SuppressWarnings("resource")
                                Socket wanSocket = wanSocketMap.computeIfAbsent(id, s -> socket(host, port));
                                System.out.println("To wan: " + message.length);
                                wanSocket.getOutputStream().write(message);
                            } catch (IOException e) {
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            }
                        });
                        executorService.submit(() -> {
                            try {
                                @SuppressWarnings("resource")
                                Socket wanSocket = wanSocketMap.computeIfAbsent(id, s -> socket(host, port));
                                byte[] buffer = new byte[65535];
                                int responseLength;
                                while ((responseLength = wanSocket.getInputStream().read(buffer)) > -1) {
                                    if (responseLength == 0) {
                                        continue;
                                    }
                                    System.out.println("From wan " + responseLength);
                                    output.writeLong(id);
                                    output.writeShort(responseLength);
                                    output.write(buffer, 0, responseLength);
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            } finally {
                                try {
                                    wanSocketMap.remove(id).close();
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

            //noinspection ResultOfMethodCallIgnored
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
    }

    static Socket socket(String host, int port) {
        System.out.println("New connection to " + host + ":" + port);
        try {
            return new Socket(host, port);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
