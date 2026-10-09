package br.com.cofre.nativehost;

import br.com.cofre.application.VaultService;
import br.com.cofre.domain.Credential;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Valida os pedidos e mantém detalhes de falhas e dados do cofre fora das respostas de erro. */
public final class FillProtocol {
    private static final int MAX_ACCOUNT_IDENTIFIER_LENGTH = 64;
    private static final int MAX_DOCUMENT_IDENTIFIER_LENGTH = 200;
    private static final int MAX_AUTHORIZATION_LENGTH = 64;
    private static final int FILL_REQUEST_FIELD_COUNT = 6;
    private static final int CONFIRM_REQUEST_FIELD_COUNT = 7;
    private final VaultService vault;
    private final boolean validationOnly;
    private final ObjectMapper json = new ObjectMapper();

    public FillProtocol(VaultService vault) {
        this(vault, false);
    }

    private FillProtocol(VaultService vault, boolean validationOnly) {
        this.vault = vault;
        this.validationOnly = validationOnly;
    }

    /** O host valida o envelope; a autorização efetiva pertence à sessão desktop. */
    public static FillProtocol validationOnly() {
        return new FillProtocol(null, true);
    }

    public byte[] handle(JsonNode request, String id) throws Exception {
        boolean confirm = "CONFIRM_FILL".equals(request.path("command").asText());
        if (request.size() != (confirm ? CONFIRM_REQUEST_FIELD_COUNT : FILL_REQUEST_FIELD_COUNT)
                || !request.path("origin").isTextual()
                || !validText(request, "accountId", MAX_ACCOUNT_IDENTIFIER_LENGTH)
                || !validText(request, "document", MAX_DOCUMENT_IDENTIFIER_LENGTH)
                || (confirm && !validText(request, "authorization", MAX_AUTHORIZATION_LENGTH))) {
            return denied(id);
        }
        try {
            String origin = Credential.origin(request.path("origin").asText());
            String accountId = request.path("accountId").asText();
            String document = request.path("document").asText();
            if (vault == null) {
                return validationOnly ? accepted(id) : denied(id);
            }
            if (confirm) {
                vault.confirmFill(request.path("authorization").asText(), origin, accountId, document);
                return accepted(id);
            }
            return json.writeValueAsBytes(
                    Map.of(
                            "version", StatusProtocol.PROTOCOL_VERSION,
                            "requestId", id,
                            "ok", true,
                            "origin", origin,
                            "accountId", accountId,
                            "document", document,
                            "credential", "REQUEST_AUTOMATIC_FILL".equals(request.path("command").asText())
                                    ? vault.requestAutomaticFill(origin, accountId, document)
                                    : vault.requestFill(origin, accountId, document)));
        } catch (Exception failure) {
            return denied(id);
        }
    }

    private boolean validText(JsonNode request, String field, int maximumLength) {
        JsonNode value = request.path(field);
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= maximumLength;
    }

    private byte[] accepted(String id) throws Exception {
        return json.writeValueAsBytes(
                Map.of("version", StatusProtocol.PROTOCOL_VERSION, "requestId", id, "ok", true));
    }

    private byte[] denied(String id) throws Exception {
        return json.writeValueAsBytes(
                Map.of("version", StatusProtocol.PROTOCOL_VERSION, "requestId", id,
                        "ok", false, "error", "FILL_DENIED"));
    }
}
