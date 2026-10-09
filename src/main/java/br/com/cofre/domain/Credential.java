package br.com.cofre.domain;

import java.net.URI;
import java.util.List;

public record Credential(
        String title,
        String username,
        String password,
        List<String> allowedOrigins,
        boolean autofillEnabled,
        String notes) {
    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int UNSPECIFIED_PORT = -1;

    public Credential {
        if (title == null || title.isBlank() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Informe título e senha.");
        }
        if (username == null || notes == null || allowedOrigins == null) {
            throw new IllegalArgumentException("Credencial inválida.");
        }
        allowedOrigins = allowedOrigins.stream().map(Credential::origin).distinct().toList();
    }

    /**
     * Aceita apenas a origem HTTPS, sem dados de login, caminho de página, query ou fragmento.
     * Normaliza o host e a porta padrão para que endereços equivalentes tenham a mesma forma,
     * preservando a distinção entre subdomínios e entre portas não padrão.
     */
    public static String origin(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
            return "https://"
                    + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                    + (uri.getPort() == UNSPECIFIED_PORT || uri.getPort() == DEFAULT_HTTPS_PORT
                            ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Use uma origem HTTPS sem caminho, por exemplo https://conta.exemplo.com.");
        }
    }
}
