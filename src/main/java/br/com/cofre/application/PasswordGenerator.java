package br.com.cofre.application;

import java.security.SecureRandom;

/** Gera senhas para credenciais de sites. */
public final class PasswordGenerator {
    public static final int PASSWORD_LENGTH_CHARACTERS = 10;
    private static final String UPPERCASE_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE_LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SYMBOLS = "!@#$%&*?+=_-";
    private static final String ALLOWED_CHARACTERS =
            UPPERCASE_LETTERS + LOWERCASE_LETTERS + DIGITS + SYMBOLS;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Sorteia cada posição sem viés e rejeita candidatos sem as quatro categorias.
     * Assim, todas as senhas válidas têm a mesma probabilidade, sem posições previsíveis.
     */
    public String generate() {
        String candidate;
        do {
            var characters = new StringBuilder(PASSWORD_LENGTH_CHARACTERS);
            for (int position = 0; position < PASSWORD_LENGTH_CHARACTERS; position++) {
                characters.append(ALLOWED_CHARACTERS.charAt(secureRandom.nextInt(ALLOWED_CHARACTERS.length())));
            }
            candidate = characters.toString();
        } while (!containsCategory(candidate, UPPERCASE_LETTERS)
                || !containsCategory(candidate, LOWERCASE_LETTERS)
                || !containsCategory(candidate, DIGITS)
                || !containsCategory(candidate, SYMBOLS));
        return candidate;
    }

    private static boolean containsCategory(String password, String category) {
        return password.chars().anyMatch(character -> category.indexOf(character) >= 0);
    }
}
