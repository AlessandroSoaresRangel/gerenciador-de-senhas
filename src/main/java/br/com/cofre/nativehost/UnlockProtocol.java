package br.com.cofre.nativehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Map;

/** O host valida o pedido; somente a sessão desktop recebe a operação de desbloqueio. */
public final class UnlockProtocol {
    public static final int MAX_PASSWORD_LENGTH_CHARACTERS = 4096;
    private static final int REQUEST_FIELD_COUNT = 4;
    private final UnlockAction unlock;
    private final boolean validationOnly;
    private final ObjectMapper json = new ObjectMapper();

    @FunctionalInterface
    public interface UnlockAction {
        void unlock(char[] password) throws Exception;
    }

    public UnlockProtocol(UnlockAction unlock) {
        this(unlock, false);
    }

    private UnlockProtocol(UnlockAction unlock, boolean validationOnly) {
        this.unlock = unlock;
        this.validationOnly = validationOnly;
    }

    public static UnlockProtocol validationOnly() {
        return new UnlockProtocol(null, true);
    }

    /** Nunca inclui a senha ou detalhes de criptografia nas respostas, inclusive em falhas. */
    public byte[] handle(JsonNode request, String requestId) throws Exception {
        JsonNode password = request.path("password");
        if (request.size() != REQUEST_FIELD_COUNT || !password.isTextual()
                || password.asText().isEmpty()
                || password.asText().length() > MAX_PASSWORD_LENGTH_CHARACTERS) {
            return denied(requestId);
        }
        if (validationOnly) {
            return accepted(requestId);
        }
        if (unlock == null) {
            return denied(requestId);
        }
        char[] secret = password.asText().toCharArray();
        try {
            unlock.unlock(secret);
            return accepted(requestId);
        } catch (Exception failure) {
            return denied(requestId);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private byte[] accepted(String requestId) throws Exception {
        return json.writeValueAsBytes(Map.of("version", StatusProtocol.PROTOCOL_VERSION,
                "requestId", requestId, "ok", true, "status", "UNLOCKED"));
    }

    private byte[] denied(String requestId) throws Exception {
        return json.writeValueAsBytes(Map.of("version", StatusProtocol.PROTOCOL_VERSION,
                "requestId", requestId, "ok", false, "error", "UNLOCK_DENIED"));
    }
}
