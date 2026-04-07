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
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<Long, Socket> wanSocketMap = new ConcurrentHashMap<>();
            executorService.submit(() -> {
                Socket routerSocket = null;
                DataInputStream input = null;
                DataOutputStream output = null;
                while (true) {
                    try {
                        if (routerSocket == null) {
                            routerSocket = new Socket("192.168.1.2", 9999);
                            input = new DataInputStream(routerSocket.getInputStream());
                            output = new DataOutputStream(routerSocket.getOutputStream());
                        }
                        long id = input.readLong();
                        // handle reset command
                        if (id == -1L) {
                            System.out.println("Resetting " + wanSocketMap.size() + " sockets");
                            for (Socket socket : wanSocketMap.values()) {
                                try {
                                    socket.close();
                                } catch (Exception ignored) {
                                }
                            }
                            wanSocketMap.clear();
                            continue;
                        }
                        String host = input.readUTF();
                        int port = input.readInt();
                        int length = input.readUnsignedShort();
//                        System.out.println("From gateway: " + length);
                        byte[] message = input.readNBytes(length);
                        if (message.length < length) {
                            break;
                        }
                        Socket wanSocket = wanSocketMap.computeIfAbsent(id, s -> socket(host, port));
                        executorService.submit(() -> {
                            try {
//                                System.out.println("To wan: " + message.length);
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
                        DataOutputStream finalOutput = output;
                        executorService.submit(() -> {
                            try {
                                byte[] buffer = new byte[65535];
                                int responseLength;
                                while ((responseLength = wanSocket.getInputStream().read(buffer)) > -1) {
                                    if (responseLength == 0) {
                                        continue;
                                    }
//                                    System.out.println("From wan " + responseLength);
                                    synchronized (finalOutput) {
                                        finalOutput.writeLong(id);
                                        finalOutput.writeShort(responseLength);
                                        finalOutput.write(buffer, 0, responseLength);
                                        finalOutput.flush();
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
                    } catch (Exception e) {
                        e.printStackTrace();
                        for (Socket socket : wanSocketMap.values()) {
                            try {
                                socket.close();
                            } catch (Exception ignored) {
                            }
                        }
                        wanSocketMap.clear();
                        try {
                            if (routerSocket != null) {
                                routerSocket.close();
                            }
                        } catch (Exception ignored) {
                        } finally {
                            routerSocket = null;
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ignored) {
                            }
                        }
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
