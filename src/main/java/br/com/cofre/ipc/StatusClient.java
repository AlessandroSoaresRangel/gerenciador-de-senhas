package br.com.cofre.ipc;

import br.com.cofre.nativehost.NativeMessageCodec;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.*;
import java.nio.file.*;
import java.util.Map;

public final class StatusClient {
    private final Path endpoint;
    private final ObjectMapper json = new ObjectMapper();

    public StatusClient(Path endpoint) {
        this.endpoint = endpoint;
    }

    public byte[] query(byte[] request) throws Exception {
        try {
            if (Files.size(endpoint) > 4096) throw new java.io.IOException("Endpoint inválido.");
            var settings = json.readTree(Files.readAllBytes(endpoint));
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress("127.0.0.1", settings.path("port").asInt()), 1000);
                socket.setSoTimeout(3000);
                var codec = new NativeMessageCodec();
                codec.write(
                        socket.getOutputStream(),
                        json.writeValueAsBytes(
                                Map.of(
                                        "token",
                                        settings.path("token").asText(),
                                        "request",
                                        json.readTree(request))));
                byte[] response = codec.read(socket.getInputStream());
                if (response == null) throw new java.io.IOException("Resposta ausente.");
                return response;
            }
        } catch (Exception failure) {
            String id = json.readTree(request).path("requestId").asText();
            if ("LIST_MATCHES".equals(json.readTree(request).path("command").asText())) {
                return json.writeValueAsBytes(
                        Map.of("version", 1, "requestId", id, "ok", false, "error", "APP_CLOSED"));
            }
            return json.writeValueAsBytes(
                    Map.of("version", 1, "requestId", id, "ok", true, "status", "APP_CLOSED"));
        }
    }
}
