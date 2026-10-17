package br.com.cofre.storage;

import java.sql.SQLException;
import java.util.List;

/** Contrato de persistência: implementações recebem somente dados já criptografados. */
public interface VaultRepository extends AutoCloseable {
    boolean exists() throws SQLException;

    VaultHeader readHeader() throws SQLException;

    void insertHeader(VaultHeader header) throws SQLException;

    List<EncryptedItem> readItems() throws SQLException;

    java.util.Optional<EncryptedItem> readItem(String id) throws SQLException;

    void saveItem(EncryptedItem item) throws SQLException;

    void deleteItem(String id) throws SQLException;

    void replace(VaultSnapshot snapshot) throws SQLException;

    void deleteVault() throws Exception;

    @Override
    void close() throws SQLException;
}
