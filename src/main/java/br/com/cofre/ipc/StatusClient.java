package br.com.cofre.ipc;

import br.com.cofre.nativehost.NativeMessageCodec;
import br.com.cofre.nativehost.StatusProtocol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.*;
import java.nio.file.*;
import java.util.Map;

public final class StatusClient {
    private static final int MAX_TCP_PORT = 65_535;
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
        var envelope = json.readTree(request);
        String id = envelope.path("requestId").asText();
        boolean statusRequest = "GET_STATUS".equals(envelope.path("command").asText());
        try {
            if (Files.size(endpoint) > MAX_ENDPOINT_BYTES) {
                throw new java.io.IOException("Endpoint inválido.");
            }
            var settings = json.readTree(Files.readAllBytes(endpoint));
            int port = settings.path("port").asInt();
            if (!settings.path("port").isInt() || port < 1 || port > MAX_TCP_PORT
                    || !settings.path("token").isTextual() || settings.path("token").asText().isBlank()) {
                throw new java.io.IOException("Endpoint invalido.");
            }
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS);
                boolean unlocking = "UNLOCK_VAULT".equals(envelope.path("command").asText());
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
                                        envelope)));
                byte[] response = codec.read(socket.getInputStream());
                if (response == null) {
                    throw new java.io.IOException("Resposta ausente.");
                }
                var result = json.readTree(response);
                if (result == null || !result.isObject()
                        || !result.path("version").isInt()
                        || result.path("version").asInt() != StatusProtocol.PROTOCOL_VERSION
                        || !result.path("requestId").isTextual()
                        || !id.equals(result.path("requestId").asText())
                        || !result.path("ok").isBoolean()) {
                    throw new java.io.IOException("Resposta invalida.");
                }
                return response;
            }
        } catch (NoSuchFileException | ConnectException closed) {
            return failure(id, statusRequest, "APP_CLOSED");
        } catch (SocketTimeoutException timeout) {
            return failure(id, false, "TIMEOUT");
        } catch (Exception unavailable) {
            return failure(id, statusRequest, "UNAVAILABLE");
        }
    }

    private byte[] failure(String id, boolean statusRequest, String reason) throws Exception {
        if (statusRequest) {
            return json.writeValueAsBytes(Map.of("version", StatusProtocol.PROTOCOL_VERSION,
                    "requestId", id, "ok", true, "status", reason));
        }
        return json.writeValueAsBytes(Map.of("version", StatusProtocol.PROTOCOL_VERSION,
                "requestId", id, "ok", false, "error", reason));
    }
}
