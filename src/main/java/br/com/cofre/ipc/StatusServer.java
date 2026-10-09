package br.com.cofre.ipc;

import br.com.cofre.nativehost.NativeMessageCodec;
import br.com.cofre.nativehost.StatusProtocol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Ponte de status da sessão desktop, limitada ao loopback e autenticada por token efêmero. */
public final class StatusServer implements AutoCloseable {
    private final Path endpoint;
    private final ServerSocket server;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private final NativeMessageCodec frames = new NativeMessageCodec();
    private final StatusProtocol protocol;
    private volatile boolean closed;

    public StatusServer(Path endpoint, StatusProtocol protocol) throws Exception {
        this.endpoint = endpoint;
        this.protocol = protocol;
        Files.createDirectories(endpoint.toAbsolutePath().getParent());
        PrivateEndpoint.restrict(endpoint.toAbsolutePath().getParent());
        lockChannel =
                FileChannel.open(
                        endpoint.resolveSibling("session.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
        } catch (Exception failure) {
            lockChannel.close();
            throw failure;
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IllegalStateException("Já existe uma sessão desktop nesta pasta.");
        }
        lock = acquired;
        server = new ServerSocket();
        try {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            Files.deleteIfExists(endpoint);
            Files.write(
                    endpoint,
                    json.writeValueAsBytes(Map.of("port", server.getLocalPort(), "token", token)));
            PrivateEndpoint.restrict(endpoint);
        } catch (Exception failure) {
            server.close();
            lock.release();
            lockChannel.close();
            throw failure;
        }
        Thread thread = new Thread(this::serve, "vault-status-ipc");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2000);
                byte[] input = frames.read(socket.getInputStream());
                if (input == null) continue;
                var request = json.readTree(input);
                String supplied = request.path("token").asText();
                if (!MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) continue;
                byte[] response = protocol.handle(json.writeValueAsBytes(request.get("request")));
                frames.write(socket.getOutputStream(), response);
            } catch (Exception ignored) {
                // Conexões inválidas não encerram a sessão e não geram logs com dados recebidos.
            }
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        server.close();
        try {
            Files.deleteIfExists(endpoint);
        } finally {
            lock.release();
            lockChannel.close();
        }
    }
}
