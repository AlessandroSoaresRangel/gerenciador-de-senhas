package br.com.cofre.domain;

public record VaultEntry(String id, Credential credential) {
    @Override
    public String toString() {
        return credential.title() + " — " + credential.username();
    }
}
