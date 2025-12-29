package dev.braintrust;

import java.io.IOException;
import java.net.ServerSocket;

public class TestUtils {
    public static int getRandomOpenPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find an available port", e);
        }
    }
}
