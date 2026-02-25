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

    public static void main(String[] args) throws InterruptedException {
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "1");
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            executorService.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket routerSocket = new Socket("192.168.1.2", 9999)) {
                        DataInputStream input = new DataInputStream(routerSocket.getInputStream());
                        DataOutputStream output = new DataOutputStream(routerSocket.getOutputStream());
                        Map<Long, Socket> wanSocketMap = new ConcurrentHashMap<>();
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
                            @SuppressWarnings("resource")
                            Socket wanSocket = wanSocketMap.computeIfAbsent(id, s -> socket(host, port));
                            executorService.submit(() -> {
                                try {
                                    System.out.println("To wan: " + message.length);
                                    synchronized (wanSocket) {
                                        wanSocket.getOutputStream().write(message);
                                        wanSocket.getOutputStream().flush();
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    try {
                                        wanSocketMap.remove(id).close();
                                    } catch (IOException ignored) {
                                    }
                                    throw new RuntimeException(e);
                                }
                            });
                            executorService.submit(() -> {
                                try {
                                    byte[] buffer = new byte[65535];
                                    int responseLength;
                                    while ((responseLength = wanSocket.getInputStream().read(buffer)) > -1) {
                                        if (responseLength == 0) {
                                            continue;
                                        }
                                        System.out.println("From wan " + responseLength);
                                        synchronized (output) {
                                            output.writeLong(id);
                                            output.writeShort(responseLength);
                                            output.write(buffer, 0, responseLength);
                                            output.flush();
                                        }
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
                        System.out.println("Reconnecting");
                    }
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
