package br.com.cofre.application;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Autorizações curtas, de uso único, sem armazenar senhas. O serviço sincroniza o acesso. */
public final class FillAuthorizations {
    private static final long AUTHORIZATION_LIFETIME_MILLIS = 10_000;
    private static final int MAX_PENDING_AUTHORIZATIONS = 100;
    private final Clock clock;
    private final Map<String, Permit> pending = new HashMap<>();

    private record Permit(String origin, String accountId, String document, long expiresAt) {}

    public record Ticket(String token, long expiresAt) {}

    public FillAuthorizations(Clock clock) {
        this.clock = clock;
    }

    public Ticket issue(String origin, String accountId, String document) {
        pending.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= clock.millis());
        if (pending.size() >= MAX_PENDING_AUTHORIZATIONS) {
            throw new IllegalStateException("Muitas solicitações pendentes.");
        }
        long expiresAt = clock.millis() + AUTHORIZATION_LIFETIME_MILLIS;
        String token = UUID.randomUUID().toString();
        pending.put(token, new Permit(origin, accountId, document, expiresAt));
        return new Ticket(token, expiresAt);
    }

    /** Remove antes de validar para impedir reutilização, mesmo após uma tentativa inválida. */
    public void consume(String token, String origin, String accountId, String document) {
        Permit permit = pending.remove(token);
        if (permit == null
                || permit.expiresAt() <= clock.millis()
                || !permit.origin().equals(origin)
                || !permit.accountId().equals(accountId)
                || !permit.document().equals(document)) {
            throw new IllegalStateException("Autorização inválida ou expirada.");
        }
    }

    public void clear() {
        pending.clear();
    }
}
