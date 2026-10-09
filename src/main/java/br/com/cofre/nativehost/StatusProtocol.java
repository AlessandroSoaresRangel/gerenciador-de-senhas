package br.com.cofre.nativehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.function.Supplier;

/** Aceita estado e metadados de contas; nenhum comando libera senhas ou notas. */
public final class StatusProtocol {
    private final Supplier<String> status;
    private final AccountQuery accounts;
    private final ObjectMapper json = new ObjectMapper();

    public StatusProtocol(Supplier<String> status) {
        this(
                status,
                origin -> {
                    throw new IllegalStateException("Cofre indisponível.");
                });
    }

    public StatusProtocol(Supplier<String> status, AccountQuery accounts) {
        this.status = status;
        this.accounts = accounts;
    }

    public byte[] handle(byte[] input) throws Exception {
        JsonNode request;
        try {
            request = json.readTree(input);
        } catch (Exception failure) {
            return error("");
        }
        if (request == null) return error("");
        String id = request.path("requestId").asText();
        if (!request.isObject()
                || !request.path("version").isInt()
                || request.path("version").asInt() != 1
                || !request.path("requestId").isTextual()
                || id.isBlank()
                || id.length() > 64) return error("");
        String command = request.path("command").asText();
        if ("LIST_MATCHES".equals(command)
                && request.size() == 4
                && request.path("origin").isTextual()) {
            String origin;
            try {
                origin = br.com.cofre.domain.Credential.origin(request.get("origin").asText());
            } catch (IllegalArgumentException failure) {
                return error(id);
            }
            try {
                return json.writeValueAsBytes(
                        Map.of(
                                "version",
                                1,
                                "requestId",
                                id,
                                "ok",
                                true,
                                "origin",
                                origin,
                                "accounts",
                                accounts.listMatches(origin)));
            } catch (IllegalStateException failure) {
                return json.writeValueAsBytes(
                        Map.of(
                                "version",
                                1,
                                "requestId",
                                id,
                                "ok",
                                false,
                                "error",
                                "VAULT_LOCKED"));
            } catch (Exception failure) {
                return json.writeValueAsBytes(
                        Map.of("version", 1, "requestId", id, "ok", false, "error", "UNAVAILABLE"));
            }
        }
        if (!"GET_STATUS".equals(command) || request.size() != 3) return error(id);
        return json.writeValueAsBytes(
                Map.of("version", 1, "requestId", id, "ok", true, "status", status.get()));
    }

    private byte[] error(String id) throws Exception {
        return json.writeValueAsBytes(
                Map.of("version", 1, "requestId", id, "ok", false, "error", "INVALID_REQUEST"));
    }
}
