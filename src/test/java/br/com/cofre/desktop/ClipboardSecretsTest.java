package br.com.cofre.desktop;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ClipboardSecretsTest {
    @Test
    void expirationAndLockClearOnlyOwnedContentAndIgnoreOlderTimers() {
        var clipboard = new AtomicReference<String>();
        var timers = new ArrayList<Runnable>();
        var secrets = new ClipboardSecrets(clipboard::get, value -> { clipboard.set(value); return true; },
                () -> clipboard.set(null), timers::add);
        assertTrue(secrets.copy("synthetic first"));
        assertTrue(secrets.copy("synthetic second"));
        timers.get(0).run();
        assertEquals("synthetic second", clipboard.get());
        timers.get(1).run();
        assertNull(clipboard.get());
        secrets.copy("synthetic secret");
        clipboard.set("user replacement");
        secrets.clear();
        assertEquals("user replacement", clipboard.get());
        secrets.copy("synthetic secret");
        secrets.clear();
        assertNull(clipboard.get());
    }
}
