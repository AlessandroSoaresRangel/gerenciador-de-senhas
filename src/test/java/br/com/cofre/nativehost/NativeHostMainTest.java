package br.com.cofre.nativehost;

import static org.junit.jupiter.api.Assertions.*;

import br.com.cofre.ipc.StatusServer;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

class NativeHostMainTest {
    @TempDir Path directory;
    private static final String ORIGIN = "chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/";

    private Process startHost(String origin) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(
                        executable,
                        "-Duser.home=" + directory,
                        "-Dcofre.extensionOrigin=" + ORIGIN,
                        "-cp",
                        System.getProperty("java.class.path"),
                        NativeHostMain.class.getName(),
                        origin)
                .start();
    }

    @Test
    void forwardFramedRequestToDesktopSession() throws Exception {
        Path endpoint = directory.resolve(".cofre-local/ipc/endpoint.json");
        try (var server = new StatusServer(endpoint, new StatusProtocol(() -> "UNLOCKED"))) {
            Process host = startHost(ORIGIN);
            try {
                var codec = new NativeMessageCodec();
                codec.write(
                        host.getOutputStream(),
                        "{\"version\":1,\"command\":\"GET_STATUS\",\"requestId\":\"process-test\"}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                host.getOutputStream().close();
                assertTrue(host.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, host.exitValue());
                var response = new ObjectMapper().readTree(codec.read(host.getInputStream()));
                assertEquals("UNLOCKED", response.get("status").asText());
                assertEquals("process-test", response.get("requestId").asText());
                assertNull(codec.read(host.getInputStream()));
            } finally {
                host.destroyForcibly();
            }
        }
    }

    @Test
    void rejectUnregisteredOriginBeforeReadingMessages() throws Exception {
        Process host = startHost("chrome-extension://bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/");
        try {
            assertTrue(host.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, host.exitValue());
            assertEquals(-1, host.getInputStream().read());
        } finally {
            host.destroyForcibly();
        }
    }
}
