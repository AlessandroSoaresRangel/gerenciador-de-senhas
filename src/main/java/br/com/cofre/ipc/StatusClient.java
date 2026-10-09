package br.com.cofre.ipc;

import br.com.cofre.nativehost.NativeMessageCodec;
import br.com.cofre.nativehost.StatusProtocol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.*;
import java.nio.file.*;
import java.util.Map;

public final class StatusClient {
    private static final int MAX_ENDPOINT_BYTES = 4096;
    private static final int CONNECT_TIMEOUT_MILLIS = 1000;
    private static final int RESPONSE_TIMEOUT_MILLIS = 3000;
    private static final int UNLOCK_RESPONSE_TIMEOUT_MILLIS = 15000;
    private final Path endpoint;
    private final ObjectMapper json = new ObjectMapper();

    public StatusClient(Path endpoint) {
        this.endpoint = endpoint;
    }

    /** Autentica a consulta no loopback; falhas da ponte retornam uma resposta correlacionada. */
    public byte[] query(byte[] request) throws Exception {
        try {
            if (Files.size(endpoint) > MAX_ENDPOINT_BYTES) {
                throw new java.io.IOException("Endpoint inválido.");
            }
            var settings = json.readTree(Files.readAllBytes(endpoint));
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress("127.0.0.1", settings.path("port").asInt()), CONNECT_TIMEOUT_MILLIS);
                boolean unlocking = "UNLOCK_VAULT".equals(json.readTree(request).path("command").asText());
                // Argon2 e a atualização da interface desktop podem levar mais que uma consulta.
                socket.setSoTimeout(unlocking ? UNLOCK_RESPONSE_TIMEOUT_MILLIS : RESPONSE_TIMEOUT_MILLIS);
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
                if (response == null) {
                    throw new java.io.IOException("Resposta ausente.");
                }
                return response;
            }
        } catch (Exception failure) {
            String id = json.readTree(request).path("requestId").asText();
            if (!"GET_STATUS".equals(json.readTree(request).path("command").asText())) {
                return json.writeValueAsBytes(
                        Map.of("version", StatusProtocol.PROTOCOL_VERSION, "requestId", id,
                                "ok", false, "error", "APP_CLOSED"));
            }
            return json.writeValueAsBytes(
                    Map.of("version", StatusProtocol.PROTOCOL_VERSION, "requestId", id,
                            "ok", true, "status", "APP_CLOSED"));
        }
    }
}
