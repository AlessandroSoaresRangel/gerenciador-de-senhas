package br.com.cofre.nativehost;

import br.com.cofre.application.VaultService;
import br.com.cofre.domain.Credential;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FillProtocolTest {
    @TempDir Path directory;

    @Test
    void automaticCommandChecksVaultOptInAndHostAcceptsEnvelope() throws Exception {
        var json = new ObjectMapper();
        try (var vault = new VaultService(directory.resolve("automatic.db"))) {
            vault.create("senha de teste longa".toCharArray());
            String id = vault.save(null, new Credential("Conta", "teste", "senha fictícia",
                    List.of("https://exemplo.com"), false, ""));
            var request = Map.of("version", 1, "requestId", "automatic", "command", "REQUEST_AUTOMATIC_FILL",
                    "origin", "https://exemplo.com", "accountId", id, "document", "document");
            byte[] input = json.writeValueAsBytes(request);
            var host = new StatusProtocol(vault::status, vault::listMatches, FillProtocol.validationOnly());
            assertTrue(json.readTree(host.handle(input)).path("ok").asBoolean());
            var desktop = new StatusProtocol(vault::status, vault::listMatches, new FillProtocol(vault));
            assertFalse(json.readTree(desktop.handle(input)).path("ok").asBoolean());
            vault.save(id, new Credential("Conta", "teste", "senha fictícia",
                    List.of("https://exemplo.com"), true, ""));
            assertTrue(json.readTree(desktop.handle(input)).path("ok").asBoolean());
        }
    }
    @Test
    void protocolReleasesOnlySelectedCredentialAndRejectsReplay() throws Exception {
        var json = new ObjectMapper();
        try (var vault = new VaultService(directory.resolve("vault.db"))) {
            vault.create("senha de teste longa".toCharArray());
            String id = vault.save(null, new Credential(
                    "Conta", "usuário", "senha fictícia",
                    List.of("https://exemplo.com"), false, "nota privada"));
            var protocol = new StatusProtocol(vault::status, vault::listMatches, new FillProtocol(vault));
            var request = new HashMap<String, Object>(Map.of(
                    "version", 1,
                    "requestId", "test",
                    "command", "REQUEST_FILL",
                    "origin", "https://exemplo.com",
                    "accountId", id,
                    "document", "document"));
            var result = json.readTree(protocol.handle(json.writeValueAsBytes(request)));
            assertEquals("senha fictícia", result.get("credential").get("password").asText());
            assertFalse(result.toString().contains("nota privada"));
            request.put("command", "CONFIRM_FILL");
            request.put("authorization", result.get("credential").get("authorization").asText());
            assertTrue(json.readTree(protocol.handle(json.writeValueAsBytes(request))).get("ok").asBoolean());
            assertFalse(json.readTree(protocol.handle(json.writeValueAsBytes(request))).get("ok").asBoolean());
        }
    }
}
