package worktools;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class ServerSocketList implements AutoCloseable, Iterable<ServerSocket> {
    private final List<ServerSocket> list;

    public ServerSocketList(Collection<Integer> ports) {
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
