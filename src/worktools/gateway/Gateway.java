package worktools.gateway;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Gateway implements Runnable, AutoCloseable {
    private final ExecutorService executor;
    private final InetSocketAddress routerAddress;

    public Gateway(ExecutorService executor, InetSocketAddress routerAddress) {
        this.executor = executor;
        this.routerAddress = routerAddress;
    }

    @Override
    public void run() {
        try (Socket routerSocket = new Socket(routerAddress.getAddress(), routerAddress.getPort())) {
            handleCommandConnection(routerSocket);
            //noinspection ResultOfMethodCallIgnored
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            executor.close();
        }
    }

    private void handleCommandConnection(Socket routerSocket) {
        try {
            InputStream input = routerSocket.getInputStream();
            while (input.read() != -1) {
                executor.submit(this::handleDataConnection);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleDataConnection() {
        try (Socket routerSocket = new Socket(routerAddress.getAddress(), routerAddress.getPort())) {
            DataInputStream dataInputStream = new DataInputStream(routerSocket.getInputStream());
            String host = dataInputStream.readUTF();
            int port = dataInputStream.readUnsignedShort();
            System.out.println("New connection to " + host + ":" + port);
            //noinspection resource
            Socket wanSocket = new Socket(host, port);
            executor.submit(() -> forward(routerSocket, wanSocket));
            executor.submit(() -> forward(wanSocket, routerSocket));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void forward(Socket in, Socket out) {
        try {
            InputStream input = new BufferedInputStream(in.getInputStream());
            OutputStream output = out.getOutputStream();
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

    @Override
    public void close() {
        executor.close();
    }

    public static void main(String[] args) {
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "1");
        new Gateway(Executors.newVirtualThreadPerTaskExecutor(), new InetSocketAddress("192.168.1.2", 9999)).run();
    }
}
