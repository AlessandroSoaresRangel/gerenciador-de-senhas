package br.com.cofre.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Responsável apenas pelo esquema SQLite, consultas e transações. */
public final class SqliteVaultRepository implements VaultRepository {
    private static final int BUSY_TIMEOUT_MILLIS = 5000;
    private static final String INSERT_ITEM_SQL = """
            INSERT INTO vault_item (id, version, nonce, ciphertext)
            VALUES (?, ?, ?, ?)
            """;
    private static final String UPSERT_ITEM_SQL = INSERT_ITEM_SQL + """
            ON CONFLICT(id) DO UPDATE SET
                version = excluded.version,
                nonce = excluded.nonce,
                ciphertext = excluded.ciphertext
            """;
    private final Connection connection;

    public SqliteVaultRepository(Path path) throws Exception {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS vault_header (
                        id INTEGER PRIMARY KEY CHECK(id = 1),
                        version INTEGER NOT NULL,
                        salt BLOB NOT NULL,
                        memory INTEGER NOT NULL,
                        iterations INTEGER NOT NULL,
                        parallelism INTEGER NOT NULL,
                        nonce BLOB NOT NULL,
                        wrapped_key BLOB NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS vault_item (
                        id TEXT PRIMARY KEY,
                        version INTEGER NOT NULL,
                        nonce BLOB NOT NULL,
                        ciphertext BLOB NOT NULL
                    )
                    """);
        } catch (Exception failure) {
            connection.close();
            throw failure;
        }
    }

    @Override
    public boolean exists() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT 1 FROM vault_header")) {
            return rows.next();
        }
    }

    @Override
    public VaultHeader readHeader() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT * FROM vault_header WHERE id=1")) {
            if (!row.next()) {
                throw new IllegalStateException("Cofre ausente.");
            }
            return new VaultHeader(
                    row.getInt("version"),
                    row.getBytes("salt"),
                    row.getInt("memory"),
                    row.getInt("iterations"),
                    row.getInt("parallelism"),
                    row.getBytes("nonce"),
                    row.getBytes("wrapped_key"));
        }
    }

    @Override
    public void insertHeader(VaultHeader header) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("""
                        INSERT INTO vault_header
                            (id, version, salt, memory, iterations, parallelism, nonce, wrapped_key)
                        VALUES (1, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setInt(1, header.version());
            statement.setBytes(2, header.salt());
            statement.setInt(3, header.memory());
            statement.setInt(4, header.iterations());
            statement.setInt(5, header.parallelism());
            statement.setBytes(6, header.nonce());
            statement.setBytes(7, header.wrappedKey());
            statement.executeUpdate();
        }
    }

    @Override
    public List<EncryptedItem> readItems() throws SQLException {
        List<EncryptedItem> items = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT * FROM vault_item")) {
            while (row.next()) {
                items.add(
                        new EncryptedItem(
                                row.getString("id"),
                                row.getInt("version"),
                                row.getBytes("nonce"),
                                row.getBytes("ciphertext")));
            }
        }
        return items;
    }

    @Override
    public void saveItem(EncryptedItem item) throws SQLException {
        writeItem(item, true);
    }

    private void writeItem(EncryptedItem item, boolean updateExisting) throws SQLException {
        String sql = updateExisting ? UPSERT_ITEM_SQL : INSERT_ITEM_SQL;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, item.id());
            statement.setInt(2, item.version());
            statement.setBytes(3, item.nonce());
            statement.setBytes(4, item.ciphertext());
            statement.executeUpdate();
        }
    }

    @Override
    public void deleteItem(String id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM vault_item WHERE id=?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    /** Cabeçalho e itens são substituídos juntos; qualquer falha restaura o estado anterior. */
    @Override
    public void replace(VaultSnapshot snapshot) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM vault_item");
                statement.executeUpdate("DELETE FROM vault_header");
            }
            insertHeader(snapshot.header());
            for (EncryptedItem item : snapshot.items()) {
                writeItem(item, false);
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
