package br.com.cofre.application;

import br.com.cofre.backup.VaultBackupService;
import br.com.cofre.crypto.CredentialCipher;
import br.com.cofre.crypto.VaultCrypto;
import br.com.cofre.domain.Credential;
import br.com.cofre.domain.VaultEntry;
import br.com.cofre.storage.SqliteVaultRepository;
import br.com.cofre.storage.VaultHeader;
import br.com.cofre.storage.VaultRepository;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Coordena a sessão e as operações do cofre. A sincronização impede que o bloqueio apague a chave
 * durante uma operação em outra thread. SQL e formato de backup ficam fora do serviço.
 */
public final class VaultService implements AutoCloseable {
    private final VaultRepository repository;
    private final VaultBackupService backups;
    private final CredentialCipher credentialCipher = new CredentialCipher();
    private byte[] vaultKey;

    public VaultService(Path path) throws Exception {
        this(new SqliteVaultRepository(path), new VaultBackupService());
    }

    /** Recebe suas dependências para permitir outras implementações de persistência. */
    public VaultService(VaultRepository repository, VaultBackupService backups) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.backups = java.util.Objects.requireNonNull(backups);
    }

    public synchronized boolean exists() throws SQLException {
        return repository.exists();
    }

    public synchronized boolean unlocked() {
        return vaultKey != null;
    }

    /** Mantém a sessão protegida enquanto o serviço de backup exporta os dados. */
    public synchronized void backup(Path destination) throws Exception {
        requireUnlocked();
        backups.export(destination, repository.readHeader(), list(), vaultKey);
    }

    /** A validação antecede a substituição transacional; a sessão permanece bloqueada. */
    public synchronized void restore(Path source, char[] password) throws Exception {
        lock();
        repository.replace(backups.read(source, password));
    }

    /**
     * Persiste somente a chave protegida pela senha mestra. A sessão recebe a chave aleatória após
     * a gravação do cabeçalho; o array de senha recebido é apagado inclusive em caso de erro.
     */
    public synchronized void create(char[] password) throws Exception {
        byte[] wrappingKey = null;
        byte[] candidateKey = null;
        try {
            if (exists()) throw new IllegalStateException("O cofre já existe.");
            if (password.length < 12)
                throw new IllegalArgumentException("Use pelo menos 12 caracteres na senha mestra.");
            byte[] salt = VaultCrypto.random(VaultCrypto.SALT_LENGTH_BYTES);
            candidateKey = VaultCrypto.random(VaultCrypto.KEY_LENGTH_BYTES);
            wrappingKey =
                    VaultCrypto.derive(
                            password,
                            salt,
                            VaultCrypto.MEMORY,
                            VaultCrypto.ITERATIONS,
                            VaultCrypto.PARALLELISM);
            var encrypted = VaultCrypto.encrypt(wrappingKey, candidateKey, "vault-key:1");
            repository.insertHeader(
                    new VaultHeader(
                            1,
                            salt,
                            VaultCrypto.MEMORY,
                            VaultCrypto.ITERATIONS,
                            VaultCrypto.PARALLELISM,
                            encrypted.nonce(),
                            encrypted.ciphertext()));
            lock();
            vaultKey = candidateKey;
            // A chave agora pertence à sessão, portanto não deve ser apagada pelo finally.
            candidateKey = null;
        } finally {
            Arrays.fill(password, '\0');
            wipe(wrappingKey);
            wipe(candidateKey);
        }
    }

    /**
     * Recupera a chave usando os parâmetros persistidos. A autenticação AES-GCM rejeita senha
     * incorreta ou chave adulterada; qualquer falha mantém a sessão bloqueada e apaga a senha.
     */
    public synchronized void unlock(char[] password) throws Exception {
        lock();
        byte[] wrappingKey = null;
        byte[] candidateKey = null;
        try {
            VaultHeader header = repository.readHeader();
            if (header.version() != 1)
                throw new IllegalStateException("Cofre ausente ou formato não suportado.");
            wrappingKey =
                    VaultCrypto.derive(
                            password,
                            header.salt(),
                            header.memory(),
                            header.iterations(),
                            header.parallelism());
            candidateKey =
                    VaultCrypto.decrypt(
                            wrappingKey, header.nonce(), header.wrappedKey(), "vault-key:1");
            if (candidateKey.length != VaultCrypto.KEY_LENGTH_BYTES)
                throw new IllegalStateException("Chave inválida.");
            vaultKey = candidateKey;
            candidateKey = null;
        } finally {
            Arrays.fill(password, '\0');
            wipe(wrappingKey);
            wipe(candidateKey);
        }
    }

    /** Rejeita a lista inteira e bloqueia a sessão se algum registro não puder ser validado. */
    public synchronized List<VaultEntry> list() throws Exception {
        requireUnlocked();
        List<VaultEntry> entries = new ArrayList<>();
        try {
            for (var item : repository.readItems())
                entries.add(credentialCipher.decrypt(item, vaultKey));
        } catch (Exception failure) {
            lock();
            throw failure;
        }
        entries.sort(
                Comparator.comparing(
                        entry -> entry.credential().title(), String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    public synchronized String save(String id, Credential credential) throws Exception {
        requireUnlocked();
        String itemId = id == null ? UUID.randomUUID().toString() : id;
        repository.saveItem(credentialCipher.encrypt(new VaultEntry(itemId, credential), vaultKey));
        return itemId;
    }

    public synchronized void delete(String id) throws SQLException {
        requireUnlocked();
        repository.deleteItem(id);
    }

    public synchronized void lock() {
        wipe(vaultKey);
        vaultKey = null;
    }

    private void requireUnlocked() {
        if (vaultKey == null) throw new IllegalStateException("Desbloqueie o cofre primeiro.");
    }

    // Limpa buffers sob nosso controle; cópias internas da JVM não têm apagamento garantido.
    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    @Override
    public synchronized void close() throws SQLException {
        lock();
        repository.close();
    }
}
