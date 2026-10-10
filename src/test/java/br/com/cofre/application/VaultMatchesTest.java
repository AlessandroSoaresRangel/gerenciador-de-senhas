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
    void extensionSavesOnlyNewUserForExactOriginAndRequiresUnlockedSession() throws Exception {
        try (var vault = new VaultService(directory.resolve("capture.db"))) {
            vault.create("senha mestra fictícia".toCharArray());
            assertTrue(vault.saveNewAccount("https://conta.exemplo.com", "Conta", "teste", "senha fictícia"));
            assertFalse(vault.saveNewAccount("https://conta.exemplo.com", "Outra", "teste", "outra senha"));
            assertTrue(vault.saveNewAccount("https://outro.exemplo.com", "Outra", "teste", "outra senha"));
            assertEquals(2, vault.list().size());
            assertEquals("senha fictícia", vault.list().getFirst().credential().password());
            assertTrue(vault.list().getFirst().credential().autofillEnabled());
            assertTrue(vault.saveNewAccount("https://conta.exemplo.com", "Terceira", "outro", "teste"));
            assertFalse(vault.list().stream().filter(entry -> entry.credential().username().equals("outro"))
                    .findFirst().orElseThrow().credential().autofillEnabled());
            vault.lock();
            assertThrows(IllegalStateException.class, () ->
                    vault.saveNewAccount("https://conta.exemplo.com", "Conta", "novo", "senha fictícia"));
        }
    }

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
            for (String unrelatedOrigin : List.of(
                    "https://outro-site.com",
                    "https://sub.conta.exemplo.com",
                    "https://conta.exemplo.com:8443",
                    "https://conta.exemplo.com.site-falso.com")) {
                vault.save(null, new Credential("Conta de outro site", "outro usuário",
                        "senha fictícia", List.of(unrelatedOrigin), false, ""));
            }
            vault.save(null, new Credential("Conta sem site", "sem origem",
                    "senha fictícia", List.of(), false, ""));
            var matches = vault.listMatches("https://CONTA.exemplo.com:443/");
            assertEquals(1, matches.size());
            assertEquals(id, matches.getFirst().id());
            assertEquals("usuário", matches.getFirst().username());
            String response =
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(matches);
            assertFalse(response.contains("segredo"));
            assertFalse(response.contains("notas privadas"));
            assertFalse(response.contains("Conta de outro site"));
            assertFalse(response.contains("Conta sem site"));
            assertTrue(vault.listMatches("https://exemplo.com").isEmpty());
            assertEquals(1, vault.listMatches("https://conta.exemplo.com.site-falso.com").size());
            assertEquals(1, vault.listMatches("https://conta.exemplo.com:8443").size());
            String secondAccountId = vault.save(null, new Credential("Segunda conta do site",
                    "segundo usuário", "senha fictícia", List.of("https://conta.exemplo.com"), false, ""));
            assertEquals(java.util.Set.of(id, secondAccountId),
                    vault.listMatches("https://conta.exemplo.com").stream()
                            .map(br.com.cofre.domain.AccountMatch::id)
                            .collect(java.util.stream.Collectors.toSet()));
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
