package worktools;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SNIReader {
    public static void main(String[] args) throws IOException {
        Socket socket = new ServerSocket(7777).accept();
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int tlsContentType = input.readUnsignedByte(); //22 = handshake
        int tlsVersion = input.readUnsignedShort();
        int tlsRecordLength = input.readUnsignedShort();
        DataInputStream handshake = new DataInputStream(new ByteArrayInputStream(input.readNBytes(tlsRecordLength)));
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
                    System.out.println("ServerName=" + new String(serverName));
                }
            }
        }
    }

    public static List<String> readServerNamesTransparently(InputStream inputStream, OutputStream outputStream) throws IOException {
        DataInputStream dataInputStream = new DataInputStream(inputStream);
        DataOutputStream dataOutputStream = new DataOutputStream(outputStream);

        int tlsContentType = dataInputStream.readUnsignedByte();
        dataOutputStream.writeByte(tlsContentType);
        if (tlsContentType != 22) { //22 = handshake
            return Collections.emptyList();
        }

        int tlsVersion = dataInputStream.readUnsignedShort();
        dataOutputStream.writeShort(tlsVersion);

        int tlsRecordLength = dataInputStream.readUnsignedShort();
        dataOutputStream.writeShort(tlsRecordLength);

        byte[] tlsRecord = dataInputStream.readNBytes(tlsRecordLength);
        dataOutputStream.write(tlsRecord);

        return SNIReader.readServerNames(tlsRecord);
    }

    public static List<String> readServerNames(InputStream inputStream) throws IOException {
        DataInputStream dataInputStream = new DataInputStream(inputStream);
        int tlsContentType = dataInputStream.readUnsignedByte();
        if (tlsContentType != 22) { //22 = handshake
            return Collections.emptyList();
        }
        int tlsVersion = dataInputStream.readUnsignedShort();
        int tlsRecordLength = dataInputStream.readUnsignedShort();
        byte[] tlsRecord = dataInputStream.readNBytes(tlsRecordLength);
        return SNIReader.readServerNames(tlsRecord);
    }

    public static List<String> readServerNames(byte[] tlsHandshakeData) throws IOException {
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
