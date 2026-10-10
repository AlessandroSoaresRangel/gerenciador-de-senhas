package br.com.cofre.nativehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.function.Supplier;

/** Valida a versão/correlação e encaminha as operações aos responsáveis por cada comando. */
public final class StatusProtocol {
    public static final int PROTOCOL_VERSION = 1;
    private static final int MAX_REQUEST_IDENTIFIER_LENGTH = 64;
    private static final int STATUS_REQUEST_FIELD_COUNT = 3;
    private static final int MATCHES_REQUEST_FIELD_COUNT = 4;
    private final Supplier<String> status;
    private final AccountQuery accounts;
    private final FillProtocol fill;
    private final UnlockProtocol unlock;
    private final SaveAccountProtocol saveAccount;
    private final ObjectMapper json = new ObjectMapper();

    public StatusProtocol(Supplier<String> status) {
        this(
                status,
                origin -> {
                    throw new IllegalStateException("Cofre indisponível.");
                });
    }

    public StatusProtocol(Supplier<String> status, AccountQuery accounts) {
        this(status, accounts, new FillProtocol(null));
    }

    public StatusProtocol(Supplier<String> status, AccountQuery accounts, FillProtocol fill) {
        this(status, accounts, fill, new UnlockProtocol(null));
    }

    public StatusProtocol(Supplier<String> status, AccountQuery accounts, FillProtocol fill, UnlockProtocol unlock) {
        this(status, accounts, fill, unlock, new SaveAccountProtocol(null, () -> {}));
    }

    public StatusProtocol(Supplier<String> status, AccountQuery accounts, FillProtocol fill,
            UnlockProtocol unlock, SaveAccountProtocol saveAccount) {
        this.status = status;
        this.accounts = accounts;
        this.fill = fill;
        this.unlock = unlock;
        this.saveAccount = saveAccount;
    }

    public byte[] handle(byte[] input) throws Exception {
        JsonNode request;
        try {
            request = json.readTree(input);
        } catch (Exception failure) {
            return error("");
        }
        if (request == null) {
            return error("");
        }
        String id = request.path("requestId").asText();
        if (!request.isObject()
                || !request.path("version").isInt()
                || request.path("version").asInt() != PROTOCOL_VERSION
                || !request.path("requestId").isTextual()
                || id.isBlank()
                || id.length() > MAX_REQUEST_IDENTIFIER_LENGTH) {
            return error("");
        }
        String command = request.path("command").asText();
        if ("SAVE_NEW_ACCOUNT".equals(command)) {
            return saveAccount.handle(request, id);
        }
        if ("UNLOCK_VAULT".equals(command)) {
            return unlock.handle(request, id);
        }
        if ("REQUEST_FILL".equals(command) || "REQUEST_AUTOMATIC_FILL".equals(command)
                || "CONFIRM_FILL".equals(command)) {
            return fill.handle(request, id);
        }
        if ("LIST_MATCHES".equals(command)
                && request.size() == MATCHES_REQUEST_FIELD_COUNT
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
                                PROTOCOL_VERSION,
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
                                PROTOCOL_VERSION,
                                "requestId",
                                id,
                                "ok",
                                false,
                                "error",
                                "VAULT_LOCKED"));
            } catch (Exception failure) {
                return json.writeValueAsBytes(
                        Map.of("version", PROTOCOL_VERSION, "requestId", id, "ok", false, "error", "UNAVAILABLE"));
            }
        }
        if (!"GET_STATUS".equals(command) || request.size() != STATUS_REQUEST_FIELD_COUNT) {
            return error(id);
        }
        return json.writeValueAsBytes(
                Map.of("version", PROTOCOL_VERSION, "requestId", id, "ok", true, "status", status.get()));
    }

    private byte[] error(String id) throws Exception {
        return json.writeValueAsBytes(
                Map.of("version", PROTOCOL_VERSION, "requestId", id, "ok", false, "error", "INVALID_REQUEST"));
    }
}
