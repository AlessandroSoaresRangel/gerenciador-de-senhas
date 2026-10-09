package br.com.cofre.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class CredentialTest {
    @Test
    void normalizeDeduplicateAndProtectOriginsFromExternalChanges() {
        var origins =
                new ArrayList<>(
                        List.of(" https://CONTA.exemplo.com:443/ ", "https://conta.exemplo.com"));
        var credential = new Credential("Conta", "usuário", "senha de teste", origins, false, "");
        origins.clear();
        assertEquals(List.of("https://conta.exemplo.com"), credential.allowedOrigins());
        assertThrows(
                UnsupportedOperationException.class,
                () -> credential.allowedOrigins().add("https://outro.com"));
    }

    @Test
    void preserveSubdomainsAndNonDefaultPorts() {
        assertEquals(
                "https://conta.exemplo.com:8443",
                Credential.origin("https://conta.exemplo.com:8443/"));
        assertNotEquals(
                Credential.origin("https://exemplo.com"),
                Credential.origin("https://conta.exemplo.com"));
        assertNotEquals(
                Credential.origin("https://exemplo.com"),
                Credential.origin("https://exemplo.com.site-falso.com"));
    }

    @Test
    void rejectAddressesThatAreNotHttpsOrigins() {
        for (String address :
                List.of(
                        "http://exemplo.com",
                        "exemplo.com",
                        "https://",
                        "https://usuario:senha@exemplo.com",
                        "https://exemplo.com/login",
                        "https://exemplo.com?conta=1",
                        "https://exemplo.com#login")) {
            assertThrows(IllegalArgumentException.class, () -> Credential.origin(address), address);
        }
    }

    @Test
    void rejectMissingRequiredFieldsAndSupportAutofill() {
        for (String title : new String[] {null, "", "   "}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Credential(title, "", "senha", List.of(), false, ""));
        }
        for (String password : new String[] {null, ""}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Credential("Conta", "", password, List.of(), false, ""));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new Credential("Conta", null, "senha", List.of(), false, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Credential("Conta", "", "senha", null, false, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Credential("Conta", "", "senha", List.of(), false, null));
        assertTrue(new Credential("Conta", "", "senha", List.of(), true, "").autofillEnabled());
    }
}
