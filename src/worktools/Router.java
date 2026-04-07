package worktools;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class Router {
    public static void main(String[] args) throws IOException, InterruptedException {
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "1");
        Map<Integer, String> portToHostMap = System.getenv().entrySet().stream()
                .filter(entry -> isPort(entry.getKey()))
                .collect(Collectors.toMap(
                        entry -> Integer.parseInt(entry.getKey().substring(1)),
                        Map.Entry::getValue
                ));
        System.out.println("Listening on ports: " + portToHostMap);
        try (ServerSocketList lanServerSocketList = new ServerSocketList(portToHostMap.keySet());
             ServerSocket gatewayServerSocket = new ServerSocket(9999);
             Socket gatewaySocket = gatewayServerSocket.accept();
             ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        ) {
            System.out.println("Gateway connected");
            DataInputStream input = new DataInputStream(gatewaySocket.getInputStream());
            DataOutputStream output = new DataOutputStream(gatewaySocket.getOutputStream());
            Map<Long, Socket> lanSocketMap = new ConcurrentHashMap<>();
            AtomicLong nextId = new AtomicLong();
            for (ServerSocket lanServerSocket : lanServerSocketList) {
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
                                    String serverName;
                                    int port;
                                    String host = portToHostMap.get(lanServerSocket.getLocalPort());
                                    String[] hostParts = host.split(":");
                                    if (hostParts.length < 1) {
                                        return;
                                    }
                                    if ("SNI".equals(hostParts[0])) {
                                        lanInputStream.mark(4096);
                                        List<String> serverNames = SNIReader.readServerNames(lanInputStream);
                                        lanInputStream.reset();
                                        if (serverNames.isEmpty()) {
                                            return;
                                        }
                                        serverName = serverNames.getFirst();
                                    } else {
                                        serverName = hostParts[0];
                                    }
                                    if (hostParts.length > 1) {
                                        port = Integer.parseInt(hostParts[1]);
                                    } else {
                                        port = 443;
                                    }
                                    System.out.println("New connection to " + serverName + ":" + port);
                                    byte[] buffer = new byte[65535];
                                    int messageLength;
                                    while ((messageLength = lanInputStream.read(buffer)) > -1) {
                                        if (messageLength == 0) {
                                            continue;
                                        }
//                                        System.out.println("To gateway: " + messageLength);
                                        synchronized (output) {
                                            output.writeLong(id);
                                            output.writeUTF(serverName);
                                            output.writeInt(port);
                                            output.writeShort(messageLength);
                                            output.write(buffer, 0, messageLength);
                                            output.flush();
                                        }
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
            }
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
//                        System.out.println("To lan: " + length);
                        synchronized (lanSocket) {
                            lanSocket.getOutputStream().write(message);
                            lanSocket.getOutputStream().flush();
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            });
            //keyboard
            Thread keyboardThread = new Thread(() -> {
                try {
                    Scanner scanner = new Scanner(System.in);
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        if ("reset".equals(line)) {
                            System.out.println("Resetting " + lanSocketMap.size() + " sockets");
                            synchronized (output) {
                                for (Socket socket : lanSocketMap.values()) {
                                    try {
                                        socket.close();
                                    } catch (Exception ignored) {
                                    }
                                }
                                lanSocketMap.clear();
                                output.writeLong(-1L);
                            }
                        } else if ("list".equals(line)) {
                            for (Socket socket : lanSocketMap.values()) {
                                System.out.println(socket);
                            }
                        } else {
                            System.out.println("Unknown command: " + line);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            keyboardThread.setDaemon(true);
            keyboardThread.start();

            //noinspection ResultOfMethodCallIgnored
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
    }

    private static boolean isPort(String s) {
        try {
            if (s.charAt(0) != 'P') {
                return false;
            }
            int i = Integer.parseInt(s.substring(1));
            return i > 0 && i <= 65535;
        } catch (Exception e) {
            return false;
        }
    }

    private static class ServerSocketList implements AutoCloseable, Iterable<ServerSocket> {
        private final List<ServerSocket> list;

        private ServerSocketList(Collection<Integer> ports) {
            list = ports.stream()
                    .map(port -> {
                        try {
                            return new ServerSocket(port);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        }

        @Override
        public void close() {
            for (ServerSocket serverSocket : list) {
                try {
                    serverSocket.close();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public Iterator<ServerSocket> iterator() {
            return list.iterator();
        }
    }
}
