package br.com.cofre.nativehost;

import br.com.cofre.application.VaultService;
import br.com.cofre.domain.Credential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** Recebe somente o cadastro confirmado na página privada da extensão. */
public final class SaveAccountProtocol {
    private static final int REQUEST_FIELD_COUNT = 7;
    private static final int MAX_LABEL_LENGTH_CHARACTERS = 512;
    private static final int MAX_ORIGIN_LENGTH_CHARACTERS = 2048;
    private final VaultService vault;
    private final boolean validationOnly;
    private final Runnable afterSave;
    private final ObjectMapper json = new ObjectMapper();

    public SaveAccountProtocol(VaultService vault, Runnable afterSave) {
        this(vault, afterSave, false);
    }

    private SaveAccountProtocol(VaultService vault, Runnable afterSave, boolean validationOnly) {
        this.vault = vault;
        this.afterSave = afterSave;
        this.validationOnly = validationOnly;
    }

    public static SaveAccountProtocol validationOnly() {
        return new SaveAccountProtocol(null, () -> {}, true);
    }

    public byte[] handle(JsonNode request, String requestId) throws Exception {
        try {
            if (request.size() != REQUEST_FIELD_COUNT
                    || !validText(request, "origin", MAX_ORIGIN_LENGTH_CHARACTERS)
                    || !validText(request, "title", MAX_LABEL_LENGTH_CHARACTERS)
                    || !validText(request, "username", MAX_LABEL_LENGTH_CHARACTERS)
                    || !validText(request, "password", UnlockProtocol.MAX_PASSWORD_LENGTH_CHARACTERS)) {
                return denied(requestId);
            }
            String origin = Credential.origin(request.path("origin").asText());
            if (validationOnly) {
                return accepted(requestId, new VaultService.AccountRegistration(false, "", false));
            }
            if (vault == null) {
                return denied(requestId);
            }
            VaultService.AccountRegistration registration = vault.registerNewAccount(origin, request.path("title").asText(),
                    request.path("username").asText(), request.path("password").asText());
            if (registration.saved()) {
                afterSave.run();
            }
            return accepted(requestId, registration);
        } catch (Exception failure) {
            return denied(requestId);
        } finally {
            if (request instanceof ObjectNode object) {
                object.put("password", "");
            }
        }
    }

    private boolean validText(JsonNode request, String field, int maximumLength) {
        JsonNode value = request.path(field);
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= maximumLength;
    }

    private byte[] accepted(String id, VaultService.AccountRegistration registration) throws Exception {
        return json.writeValueAsBytes(Map.of("version", StatusProtocol.PROTOCOL_VERSION,
                "requestId", id, "ok", true, "saved", registration.saved(),
                "accountId", registration.accountId(), "automaticEnabled", registration.automaticEnabled()));
    }

    private byte[] denied(String id) throws Exception {
        return json.writeValueAsBytes(Map.of("version", StatusProtocol.PROTOCOL_VERSION,
                "requestId", id, "ok", false, "error", "SAVE_DENIED"));
    }
}
