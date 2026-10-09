package br.com.cofre.domain;

/** Resposta transitória de preenchimento; não deve ser registrada em logs. */
public record FillCredential(String authorization, long expiresAt, String username, String password) {
    @Override
    public String toString() {
        return "FillCredential[redacted]";
    }
}
