package br.com.cofre.nativehost;

import br.com.cofre.application.VaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SaveAccountProtocolTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    private byte[] request(String origin, String password) throws Exception {
        return json.writeValueAsBytes(Map.of("version", 1, "command", "SAVE_NEW_ACCOUNT",
                "requestId", "save-test", "origin", origin, "title", "Conta fictícia",
                "username", "usuario teste", "password", password));
    }

    @Test
    void confirmedSaveUsesDesktopSessionAndDoesNotDuplicateOrExposePassword() throws Exception {
        try (var vault = new VaultService(directory.resolve("saved.db"))) {
            vault.create("senha mestra fictícia".toCharArray());
            var notifications = new AtomicInteger();
            var protocol = new StatusProtocol(vault::status, vault::listMatches, new FillProtocol(vault),
                    new UnlockProtocol(null), new SaveAccountProtocol(vault, notifications::incrementAndGet));
            byte[] input = request("https://conta.exemplo.com", "senha fictícia");
            var result = json.readTree(protocol.handle(input));
            assertTrue(result.path("ok").asBoolean());
            assertTrue(result.path("saved").asBoolean());
            assertFalse(result.toString().contains("senha fictícia"));
            assertFalse(json.readTree(protocol.handle(input)).path("saved").asBoolean());
            assertEquals(1, notifications.get());
            assertEquals(1, vault.list().size());
            assertEquals("senha fictícia", vault.list().getFirst().credential().password());
            vault.lock();
            assertFalse(json.readTree(protocol.handle(input)).path("ok").asBoolean());
        }
    }

    @Test
    void hostRejectsMalformedSaveRequestsBeforeForwarding() throws Exception {
        var protocol = new StatusProtocol(() -> "APP_CLOSED", origin -> java.util.List.of(),
                FillProtocol.validationOnly(), UnlockProtocol.validationOnly(), SaveAccountProtocol.validationOnly());
        assertTrue(json.readTree(protocol.handle(request("https://conta.exemplo.com", "teste"))).path("ok").asBoolean());
        for (String origin : java.util.List.of("http://conta.exemplo.com", "https://conta.exemplo.com/login")) {
            assertFalse(json.readTree(protocol.handle(request(origin, "teste"))).path("ok").asBoolean());
        }
        assertFalse(json.readTree(protocol.handle(request("https://conta.exemplo.com", ""))).path("ok").asBoolean());
        assertFalse(json.readTree(protocol.handle(request("https://conta.exemplo.com", "x".repeat(4097)))).path("ok").asBoolean());
        var node = json.readTree(request("https://conta.exemplo.com", "teste"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("extra", true);
        assertFalse(json.readTree(protocol.handle(json.writeValueAsBytes(node))).path("ok").asBoolean());
    }
}
