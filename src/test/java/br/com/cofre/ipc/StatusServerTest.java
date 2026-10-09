package br.com.cofre.ipc;

import static org.junit.jupiter.api.Assertions.*;

import br.com.cofre.application.VaultService;
import br.com.cofre.nativehost.NativeMessageCodec;
import br.com.cofre.nativehost.StatusProtocol;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.Socket;
import java.nio.file.*;
import java.util.Map;

class StatusServerTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    private byte[] request() throws Exception {
        return json.writeValueAsBytes(
                Map.of("version", 1, "command", "GET_STATUS", "requestId", "test"));
    }

    @Test
    void consultSameSessionAndRemoveEndpointWhenClosed() throws Exception {
        Path endpoint = directory.resolve("ipc/endpoint.json");
        var client = new StatusClient(endpoint);
        assertEquals("APP_CLOSED", json.readTree(client.query(request())).get("status").asText());
        try (var vault = new VaultService(directory.resolve("vault.db"));
                var server =
                        new StatusServer(
                                endpoint, new StatusProtocol(vault::status, vault::listMatches))) {
            assertEquals(
                    "NOT_CREATED", json.readTree(client.query(request())).get("status").asText());
            vault.create("senha de teste longa".toCharArray());
            vault.save(
                    null,
                    new br.com.cofre.domain.Credential(
                            "Conta",
                            "usuário",
                            "senha privada",
                            java.util.List.of("https://exemplo.com"),
                            false,
                            ""));
            byte[] matchesRequest =
                    json.writeValueAsBytes(
                            Map.of(
                                    "version",
                                    1,
                                    "command",
                                    "LIST_MATCHES",
                                    "requestId",
                                    "matches",
                                    "origin",
                                    "https://exemplo.com"));
            var matches = json.readTree(client.query(matchesRequest));
            assertEquals("Conta", matches.get("accounts").get(0).get("title").asText());
            assertFalse(matches.toString().contains("senha privada"));
            assertEquals("UNLOCKED", json.readTree(client.query(request())).get("status").asText());
            vault.lock();
            assertEquals(
                    "VAULT_LOCKED",
                    json.readTree(client.query(matchesRequest)).get("error").asText());
            assertEquals("LOCKED", json.readTree(client.query(request())).get("status").asText());
            assertThrows(
                    Exception.class,
                    () -> new StatusServer(endpoint, new StatusProtocol(vault::status)));
            var settings = json.readTree(Files.readAllBytes(endpoint));
            try (var socket = new Socket("127.0.0.1", settings.get("port").asInt())) {
                socket.setSoTimeout(3000);
                var frames = new NativeMessageCodec();
                frames.write(
                        socket.getOutputStream(),
                        json.writeValueAsBytes(
                                Map.of("token", "wrong", "request", json.readTree(request()))));
                assertNull(frames.read(socket.getInputStream()));
            }
            assertEquals("LOCKED", json.readTree(client.query(request())).get("status").asText());
        }
        assertFalse(Files.exists(endpoint));
        assertEquals("APP_CLOSED", json.readTree(client.query(request())).get("status").asText());
    }
}
