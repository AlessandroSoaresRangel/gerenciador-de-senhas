package br.com.cofre.ipc;

import br.com.cofre.nativehost.NativeMessageCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StatusClientTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void corruptEndpointIsUnavailableRatherThanClosed() throws Exception {
        Path endpoint = directory.resolve("endpoint.json");
        Files.writeString(endpoint, "broken JSON");
        var response = json.readTree(new StatusClient(endpoint).query(request()));
        assertEquals("UNAVAILABLE", response.path("status").asText());
    }

    @Test
    void invalidResponseIsUnavailableAndSilentServerTimesOut() throws Exception {
        for (boolean silent : new boolean[] {false, true}) {
            try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Path endpoint = directory.resolve("endpoint.json");
                Files.write(endpoint, json.writeValueAsBytes(Map.of("port", server.getLocalPort(), "token", "test")));
                var worker = Executors.newSingleThreadExecutor();
                try {
                    var serving = worker.submit(() -> {
                        try (var socket = server.accept()) {
                            var frames = new NativeMessageCodec();
                            frames.read(socket.getInputStream());
                            if (silent) {
                                socket.getInputStream().read();
                            } else {
                                frames.write(socket.getOutputStream(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        } catch (Exception failure) { throw new RuntimeException(failure); }
                    });
                    var result = json.readTree(new StatusClient(endpoint).query(request()));
                    if (silent) {
                        assertFalse(result.path("ok").asBoolean());
                        assertEquals("TIMEOUT", result.path("error").asText());
                    } else {
                        assertEquals("UNAVAILABLE", result.path("status").asText());
                    }
                    serving.get(5, TimeUnit.SECONDS);
                } finally { worker.shutdownNow(); }
            }
        }
    }

    private byte[] request() throws Exception {
        return json.writeValueAsBytes(Map.of("version", 1, "command", "GET_STATUS", "requestId", "test"));
    }
}
