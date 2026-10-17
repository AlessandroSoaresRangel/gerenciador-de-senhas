package br.com.cofre.nativehost;

import br.com.cofre.application.VaultService;
import br.com.cofre.ipc.StatusClient;
import br.com.cofre.ipc.StatusServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnlockProtocolTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    private byte[] request(String password) throws Exception {
        return json.writeValueAsBytes(Map.of("version", 1, "command", "UNLOCK_VAULT",
                "requestId", "unlock-test", "password", password));
    }

    @Test
    void removesPasswordFromEnvelopeOnEveryExit() throws Exception {
        for (boolean fail : new boolean[] {false, true}) {
            var protocol = new UnlockProtocol(secret -> {
                if (fail) throw new IllegalStateException("synthetic failure");
            });
            var envelope = json.readTree(request("synthetic password"));
            protocol.handle(envelope, "test");
            assertEquals("", envelope.path("password").asText());
        }
        var invalid = json.readTree(request(""));
        invalid = ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put("extra", true).put("password", "synthetic");
        new UnlockProtocol(secret -> {}).handle(invalid, "test");
        assertEquals("", invalid.path("password").asText());
    }

    @Test
    void validatesHostEnvelopeAndWipesPasswordEvenWhenActionFails() throws Exception {
        var host = new StatusProtocol(() -> "APP_CLOSED", origin -> java.util.List.of(),
                FillProtocol.validationOnly(), UnlockProtocol.validationOnly());
        assertTrue(json.readTree(host.handle(request("senha fictícia"))).path("ok").asBoolean());
        assertFalse(json.readTree(host.handle(request(""))).path("ok").asBoolean());
        assertFalse(json.readTree(host.handle(request("x".repeat(4097)))).path("ok").asBoolean());
        var malformed = json.createObjectNode().put("version", 1).put("command", "UNLOCK_VAULT")
                .put("requestId", "test").put("password", 123);
        assertFalse(json.readTree(host.handle(json.writeValueAsBytes(malformed))).path("ok").asBoolean());
        for (boolean fail : new boolean[] {false, true}) {
            var captured = new java.util.concurrent.atomic.AtomicReference<char[]>();
            var protocol = new StatusProtocol(() -> "LOCKED", origin -> java.util.List.of(),
                    new FillProtocol(null), new UnlockProtocol(secret -> {
                        captured.set(secret);
                        if (fail) {
                            throw new IllegalStateException("detalhe privado");
                        }
                    }));
            var result = json.readTree(protocol.handle(request("senha fictícia")));
            assertEquals(!fail, result.path("ok").asBoolean());
            assertArrayEquals(new char[captured.get().length], captured.get());
            assertFalse(result.toString().contains("senha fictícia"));
            assertFalse(result.toString().contains("detalhe privado"));
        }
    }

    @Test
    void unlocksOnlyRunningDesktopSessionAndRejectsWrongPassword() throws Exception {
        Path endpoint = directory.resolve("ipc/endpoint.json");
        var client = new StatusClient(endpoint);
        byte[] correct = request("senha mestra fictícia");
        assertEquals("APP_CLOSED", json.readTree(client.query(correct)).path("error").asText());
        try (var vault = new VaultService(directory.resolve("vault.db"))) {
            vault.create("senha mestra fictícia".toCharArray());
            vault.lock();
            var protocol = new StatusProtocol(vault::status, vault::listMatches, new FillProtocol(vault),
                    new UnlockProtocol(vault::unlock));
            try (var server = new StatusServer(endpoint, protocol)) {
                var denied = json.readTree(client.query(request("senha incorreta fictícia")));
                assertFalse(denied.path("ok").asBoolean());
                assertFalse(vault.unlocked());
                var accepted = json.readTree(client.query(correct));
                assertTrue(accepted.path("ok").asBoolean());
                assertEquals("UNLOCKED", accepted.path("status").asText());
                assertTrue(vault.unlocked());
                assertFalse(accepted.toString().contains("senha mestra fictícia"));
            }
        }
        assertEquals("APP_CLOSED", json.readTree(client.query(correct)).path("error").asText());
    }
}
