package br.com.cofre.desktop;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Controla somente o ultimo segredo copiado; acesso e expiracao ocorrem na thread JavaFX. */
final class ClipboardSecrets {
    private final Supplier<String> read;
    private final Predicate<String> write;
    private final Runnable clearClipboard;
    private final Consumer<Runnable> scheduleExpiration;
    private String ownedSecret;
    private long generation;

    ClipboardSecrets(Supplier<String> read, Predicate<String> write, Runnable clearClipboard,
            Consumer<Runnable> scheduleExpiration) {
        this.read = read;
        this.write = write;
        this.clearClipboard = clearClipboard;
        this.scheduleExpiration = scheduleExpiration;
    }

    boolean copy(String secret) {
        if (!write.test(secret)) {
            return false;
        }
        ownedSecret = secret;
        long copyGeneration = ++generation;
        scheduleExpiration.accept(() -> {
            if (generation == copyGeneration) {
                clear();
            }
        });
        return true;
    }

    void clear() {
        try {
            if (ownedSecret != null && ownedSecret.equals(read.get())) {
                clearClipboard.run();
            }
        } finally {
            ownedSecret = null;
            generation++;
        }
    }
}
