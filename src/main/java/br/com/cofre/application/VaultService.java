package br.com.cofre.application;

import br.com.cofre.backup.VaultBackupService;
import br.com.cofre.backup.VaultBackups;
import br.com.cofre.crypto.CredentialCipher;
import br.com.cofre.crypto.CredentialEncryption;
import br.com.cofre.crypto.VaultCrypto;
import br.com.cofre.domain.AccountMatch;
import br.com.cofre.domain.Credential;
import br.com.cofre.domain.FillCredential;
import br.com.cofre.domain.VaultEntry;
import br.com.cofre.storage.SqliteVaultRepository;
import br.com.cofre.storage.VaultHeader;
import br.com.cofre.storage.VaultRepository;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Coordena a sessão e as operações do cofre. A sincronização impede que o bloqueio apague a chave
 * durante uma operação em outra thread. SQL e formato de backup ficam fora do serviço.
 */
public final class VaultService implements AutoCloseable {
    public static final int MIN_MASTER_PASSWORD_LENGTH_CHARACTERS = 12;
    private static final int MAX_DOCUMENT_IDENTIFIER_LENGTH = 200;

    private final VaultRepository repository;
    private final VaultBackups backups;
    private final CredentialEncryption credentialCipher;
    private final FillAuthorizations fillAuthorizations;
    private byte[] vaultKey;

    public VaultService(Path path) throws Exception {
        this(new SqliteVaultRepository(path), new VaultBackupService());
    }

    public VaultService(VaultRepository repository, VaultBackups backups) {
        this(repository, backups, new CredentialCipher(), new FillAuthorizations(Clock.systemUTC()));
    }

    /** Injeta as fronteiras e as autorizações para permitir testes com tempo controlado. */
    public VaultService(
            VaultRepository repository,
            VaultBackups backups,
            CredentialEncryption credentialCipher,
            FillAuthorizations fillAuthorizations) {
        this.repository = Objects.requireNonNull(repository);
        this.backups = Objects.requireNonNull(backups);
        this.credentialCipher = Objects.requireNonNull(credentialCipher);
        this.fillAuthorizations = Objects.requireNonNull(fillAuthorizations);
    }

    /** Normaliza a origem e aplica igualdade exata, retornando somente metadados de seleção. */
    public synchronized List<AccountMatch> listMatches(String origin) throws Exception {
        requireUnlocked();
        String normalized = Credential.origin(origin);
        return list().stream()
                .filter(entry -> entry.credential().allowedOrigins().contains(normalized))
                .map(
                        entry ->
                                new AccountMatch(
                                        entry.id(),
                                        entry.credential().title(),
                                        entry.credential().username()))
                .toList();
    }

    /** Libera somente a conta autorizada, vinculada a uma operação curta no documento. */
    public synchronized FillCredential requestFill(
            String origin, String accountId, String document) throws Exception {
        return requestFill(origin, accountId, document, false);
    }

    public synchronized FillCredential requestAutomaticFill(
            String origin, String accountId, String document) throws Exception {
        return requestFill(origin, accountId, document, true);
    }

    private FillCredential requestFill(
            String origin, String accountId, String document, boolean automatic) throws Exception {
        requireUnlocked();
        String normalized = Credential.origin(origin);
        if (document == null
                || document.isBlank()
                || document.length() > MAX_DOCUMENT_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException("Documento inválido.");
        }
        VaultEntry entry =
                list().stream()
                        .filter(item -> item.id().equals(accountId)
                                && item.credential().allowedOrigins().contains(normalized)
                                && (!automatic || item.credential().autofillEnabled()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Conta não autorizada para esta origem."));
        FillAuthorizations.Ticket ticket = fillAuthorizations.issue(normalized, accountId, document);
        return new FillCredential(
                ticket.token(),
                ticket.expiresAt(),
                entry.credential().username(),
                entry.credential().password());
    }

    /** Consome a autorização; bloqueio, edição e exclusão invalidam os pedidos pendentes. */
    public synchronized void confirmFill(String token, String origin, String accountId, String document) {
        requireUnlocked();
        fillAuthorizations.consume(token, Credential.origin(origin), accountId, document);
    }

    public synchronized boolean exists() throws SQLException {
        return repository.exists();
    }

    public synchronized boolean unlocked() {
        return vaultKey != null;
    }

    /** Consulta atômica da sessão; não expõe chave, credenciais nem quantidade de registros. */
    public synchronized String status() {
        if (unlocked()) {
            return "UNLOCKED";
        }
        try {
            return exists() ? "LOCKED" : "NOT_CREATED";
        } catch (SQLException failure) {
            return "UNAVAILABLE";
        }
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
            if (exists()) {
                throw new IllegalStateException("O cofre já existe.");
            }
            if (password.length < MIN_MASTER_PASSWORD_LENGTH_CHARACTERS) {
                throw new IllegalArgumentException(
                        "Use pelo menos " + MIN_MASTER_PASSWORD_LENGTH_CHARACTERS
                                + " caracteres na senha mestra.");
            }
            byte[] salt = VaultCrypto.random(VaultCrypto.SALT_LENGTH_BYTES);
            candidateKey = VaultCrypto.random(VaultCrypto.KEY_LENGTH_BYTES);
            wrappingKey =
                    VaultCrypto.derive(
                            password,
                            salt,
                            VaultCrypto.MEMORY_KIBIBYTES,
                            VaultCrypto.ITERATIONS,
                            VaultCrypto.PARALLELISM);
            var encrypted =
                    VaultCrypto.encrypt(wrappingKey, candidateKey, VaultHeader.KEY_AUTHENTICATION_CONTEXT);
            repository.insertHeader(
                    new VaultHeader(
                            VaultHeader.FORMAT_VERSION,
                            salt,
                            VaultCrypto.MEMORY_KIBIBYTES,
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
            if (header.version() != VaultHeader.FORMAT_VERSION) {
                throw new IllegalStateException("Cofre ausente ou formato não suportado.");
            }
            wrappingKey =
                    VaultCrypto.derive(
                            password,
                            header.salt(),
                            header.memory(),
                            header.iterations(),
                            header.parallelism());
            candidateKey =
                    VaultCrypto.decrypt(
                            wrappingKey, header.nonce(), header.wrappedKey(), VaultHeader.KEY_AUTHENTICATION_CONTEXT);
            if (candidateKey.length != VaultCrypto.KEY_LENGTH_BYTES) {
                throw new IllegalStateException("Chave inválida.");
            }
            vaultKey = candidateKey;
            candidateKey = null;
        } finally {
            Arrays.fill(password, '\0');
            wipe(wrappingKey);
            wipe(candidateKey);
        }
    }

    /** Rejeita a lista inteira se um registro falhar na validação, sem bloquear a sessão. */
    public synchronized List<VaultEntry> list() throws Exception {
        requireUnlocked();
        List<VaultEntry> entries = new ArrayList<>();
        for (var item : repository.readItems()) {
            entries.add(credentialCipher.decrypt(item, vaultKey));
        }
        entries.sort(
                Comparator.comparing(
                        entry -> entry.credential().title(), String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    public synchronized String save(String id, Credential credential) throws Exception {
        requireUnlocked();
        fillAuthorizations.clear();
        String itemId = id == null ? UUID.randomUUID().toString() : id;
        repository.saveItem(credentialCipher.encrypt(new VaultEntry(itemId, credential), vaultKey));
        return itemId;
    }

    /** O cadastro confirmado pela extensão não sobrescreve outra conta do mesmo usuário e origem. */
    public synchronized boolean saveNewAccount(String origin, String title, String username, String password)
            throws Exception {
        return registerNewAccount(origin, title, username, password).saved();
    }

    public record AccountRegistration(boolean saved, String accountId, boolean automaticEnabled) {}

    /** A verificação e gravação usam a mesma sessão: só a primeira conta da origem recebe o automático. */
    public synchronized AccountRegistration registerNewAccount(String origin, String title, String username, String password)
            throws Exception {
        requireUnlocked();
        String normalizedOrigin = Credential.origin(origin);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Informe o usuário.");
        }
        String normalizedUsername = username.trim();
        List<AccountMatch> accounts = listMatches(normalizedOrigin);
        if (accounts.stream()
                .anyMatch(account -> account.username().trim().equals(normalizedUsername))) {
            return new AccountRegistration(false, "", false);
        }
        boolean automaticEnabled = accounts.isEmpty();
        String accountId = save(null, new Credential(title, normalizedUsername, password,
                List.of(normalizedOrigin), automaticEnabled, ""));
        return new AccountRegistration(true, accountId, automaticEnabled);
    }

    public synchronized void delete(String id) throws SQLException {
        requireUnlocked();
        fillAuthorizations.clear();
        repository.deleteItem(id);
    }

    public synchronized void lock() {
        fillAuthorizations.clear();
        wipe(vaultKey);
        vaultKey = null;
    }

    /** Exclusão explícita dispensa a senha antiga e invalida a sessão e autorizações. */
    public synchronized void deleteVault() throws Exception {
        lock();
        repository.deleteVault();
    }

    private void requireUnlocked() {
        if (vaultKey == null) {
            throw new IllegalStateException("Desbloqueie o cofre primeiro.");
        }
    }

    // Limpa buffers sob nosso controle; cópias internas da JVM não têm apagamento garantido.
    private static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        lock();
        repository.close();
    }
}
