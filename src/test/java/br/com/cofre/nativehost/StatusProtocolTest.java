package br.com.cofre.nativehost;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

class StatusProtocolTest {
    @Test
    void listAccountMetadataAndRejectInvalidOrigins() throws Exception {
        var protocol =
                new StatusProtocol(
                        () -> "UNLOCKED",
                        origin -> {
                            assertEquals("https://exemplo.com", origin);
                            return java.util.List.of(
                                    new br.com.cofre.domain.AccountMatch("id", "Conta", "usuário"));
                        });
        String request =
                "{\"version\":1,\"command\":\"LIST_MATCHES\",\"requestId\":\"test\",\"origin\":\"https://exemplo.com\"}";
        var response = json.readTree(protocol.handle(request.getBytes(StandardCharsets.UTF_8)));
        assertTrue(response.get("ok").asBoolean());
        assertEquals(3, response.get("accounts").get(0).size());
        assertFalse(
                json.readTree(
                                protocol.handle(
                                        request.replace("https://", "http://")
                                                .getBytes(StandardCharsets.UTF_8)))
                        .get("ok")
                        .asBoolean());
        var locked =
                new StatusProtocol(
                        () -> "LOCKED",
                        origin -> {
                            throw new IllegalStateException();
                        });
        assertEquals(
                "VAULT_LOCKED",
                json.readTree(locked.handle(request.getBytes(StandardCharsets.UTF_8)))
                        .get("error")
                        .asText());
    }

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reflectLiveSessionStateAndEchoRequestId() throws Exception {
        var state = new AtomicReference<>("LOCKED");
        var protocol = new StatusProtocol(state::get);
        byte[] request =
                "{\"version\":1,\"command\":\"GET_STATUS\",\"requestId\":\"test\"}"
                        .getBytes(StandardCharsets.UTF_8);
        assertEquals("LOCKED", json.readTree(protocol.handle(request)).get("status").asText());
        state.set("UNLOCKED");
        var response = json.readTree(protocol.handle(request));
        assertEquals("UNLOCKED", response.get("status").asText());
        assertEquals("test", response.get("requestId").asText());
        assertEquals(4, response.size());
    }

    @Test
    void rejectMalformedOrUnsupportedRequestsWithoutConsultingSession() throws Exception {
        var protocol =
                new StatusProtocol(
                        () -> {
                            fail("Não deve consultar a sessão");
                            return "LOCKED";
                        });
        for (String request :
                new String[] {
                    "{",
                    "{}",
                    "{\"version\":2,\"command\":\"GET_STATUS\",\"requestId\":\"test\"}",
                    "{\"version\":1,\"command\":\"REQUEST_FILL\",\"requestId\":\"test\"}"
                }) {
            assertFalse(
                    json.readTree(protocol.handle(request.getBytes(StandardCharsets.UTF_8)))
                            .get("ok")
                            .asBoolean());
        }
    }
}
