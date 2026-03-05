package worktools.router;

import worktools.SNIReader;
import worktools.ServerSocketList;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Router implements Runnable, AutoCloseable {
    private final ExecutorService executor;
    private final int gatewayServerSockerPort;
    private final Map<Integer, String> portToHostMap;
    private final BlockingQueue<GatewayDataSocket> waitingForGateway = new SynchronousQueue<>();
    private final BlockingQueue<Socket> incomingGateway = new SynchronousQueue<>();

    public Router(ExecutorService executor, int gatewayServerSockerPort, Map<Integer, String> portToHostMap) {
        this.executor = executor;
        this.gatewayServerSockerPort = gatewayServerSockerPort;
        this.portToHostMap = portToHostMap;
    }

    @Override
    public void run() {
        try (ServerSocket gatewayServerSocket = new ServerSocket(gatewayServerSockerPort);
             ServerSocketList lanServerSocketList = new ServerSocketList(portToHostMap.keySet())
        ) {
            handleGatewayServerSocket(gatewayServerSocket);
            handleLanServerSockets(lanServerSocketList);
            //noinspection ResultOfMethodCallIgnored
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            executor.close();
        }
    }

    private void handleGatewayServerSocket(ServerSocket gatewayServerSocket) {
        try {
            listenForCommandConnection(gatewayServerSocket);
            executor.submit(() -> listenForDataConnections(gatewayServerSocket));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleLanServerSockets(ServerSocketList lanServerSocketList) {
        for (ServerSocket lanServerSocket : lanServerSocketList) {
            executor.submit(() -> {
                try {
                    Socket socket = lanServerSocket.accept();
                    int localPort = lanServerSocket.getLocalPort();
                    //noinspection ResultOfMethodCallIgnored
                    waitingForGateway.offer(new GatewayDataSocket(socket, localPort));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void listenForCommandConnection(ServerSocket gatewayServerSocket) throws IOException {
        Socket commandSocket = gatewayServerSocket.accept();
        executor.submit(() -> handleCommandConnection(commandSocket));
    }

    private void listenForDataConnections(ServerSocket gatewayServerSocket) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Socket socket = gatewayServerSocket.accept();
                //noinspection ResultOfMethodCallIgnored
                incomingGateway.offer(socket);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void handleCommandConnection(Socket commandSocket) {
        //in
        executor.submit(() -> {
            try {
                InputStream input = new BufferedInputStream(commandSocket.getInputStream());
                //todo
                //noinspection StatementWithEmptyBody
                while (input.read() != -1) ;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        //out
        executor.submit(() -> {
            try {
                OutputStream output = new DataOutputStream(commandSocket.getOutputStream());
                while (!Thread.currentThread().isInterrupted()) {
                    GatewayDataSocket lan = waitingForGateway.take();
                    output.write(1); //TODO command structure
                    output.flush();
                    Socket gateway = incomingGateway.take();
                    handleDataConnection(lan.socket(), gateway, lan.localPort());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void handleDataConnection(Socket lan, Socket gateway, int lanLocalPort) {
        executor.submit(() -> forward(lan, gateway, lanLocalPort));
        executor.submit(() -> forward(gateway, lan, null));
    }

    private void forward(Socket in, Socket out, Integer lanLocalPort) {
        try {
            BufferedInputStream input = new BufferedInputStream(in.getInputStream());
            OutputStream output = out.getOutputStream();
            if (lanLocalPort != null) {
                InetSocketAddress socketAddress = getSocketAddress(lanLocalPort, input);
                if (socketAddress == null) {
                    return;
                }
                System.out.println("New connection to " + socketAddress);
                DataOutputStream dataOutputStream = new DataOutputStream(output);
                dataOutputStream.writeUTF(socketAddress.getHostName());
                dataOutputStream.writeShort(socketAddress.getPort());
                dataOutputStream.flush();
            }
            forward(input, output);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void forward(InputStream input, OutputStream output) throws IOException {
        int b;
        while ((b = input.read()) != -1) {
            output.write(b);
        }
    }

    private InetSocketAddress getSocketAddress(int localPort, BufferedInputStream lanInputStream) throws IOException {
        String serverName;
        int port;
        String host = portToHostMap.get(localPort);
        String[] hostParts = host.split(":");
        if (hostParts.length < 1) {
            return null;
        }
        if ("SNI".equals(hostParts[0])) {
            List<String> serverNames = SNIReader.readServerNamesAndReset(lanInputStream);
            if (serverNames.isEmpty()) {
                return null;
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
        return InetSocketAddress.createUnresolved(serverName, port);
    }

    @Override
    public void close() {
        executor.close();
    }

    private record GatewayDataSocket(
            Socket socket,
            int localPort
    ) {
    }

    public static void main(String[] args) {
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "1");
        Map<Integer, String> portToHostMap = System.getenv().entrySet().stream()
                .filter(entry -> isPort(entry.getKey()))
                .collect(Collectors.toMap(
                        entry -> Integer.parseInt(entry.getKey().substring(1)),
                        Map.Entry::getValue
                ));
        new Router(Executors.newVirtualThreadPerTaskExecutor(), 9999, portToHostMap).run();
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
}
