package br.com.cofre.application;

import static org.junit.jupiter.api.Assertions.*;

import br.com.cofre.domain.Credential;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class VaultMatchesTest {
    @TempDir Path directory;

    @Test
    void matchExactHttpsOriginAndExposeOnlyAccountMetadata() throws Exception {
        try (var vault = new VaultService(directory.resolve("vault.db"))) {
            vault.create("senha mestra fictícia".toCharArray());
            String id =
                    vault.save(
                            null,
                            new Credential(
                                    "Conta",
                                    "usuário",
                                    "segredo",
                                    List.of("https://conta.exemplo.com"),
                                    false,
                                    "notas privadas"));
            var matches = vault.listMatches("https://CONTA.exemplo.com:443/");
            assertEquals(1, matches.size());
            assertEquals(id, matches.getFirst().id());
            assertEquals("usuário", matches.getFirst().username());
            String response =
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(matches);
            assertFalse(response.contains("segredo"));
            assertFalse(response.contains("notas privadas"));
            assertTrue(vault.listMatches("https://exemplo.com").isEmpty());
            assertTrue(vault.listMatches("https://conta.exemplo.com.site-falso.com").isEmpty());
            assertTrue(vault.listMatches("https://conta.exemplo.com:8443").isEmpty());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> vault.listMatches("http://conta.exemplo.com"));
            vault.lock();
            assertThrows(
                    IllegalStateException.class,
                    () -> vault.listMatches("https://conta.exemplo.com"));
        }
    }
}
