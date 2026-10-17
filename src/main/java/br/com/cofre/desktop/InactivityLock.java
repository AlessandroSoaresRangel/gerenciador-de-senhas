package br.com.cofre.desktop;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Contagem monotona de inatividade. Todos os metodos sao chamados na thread JavaFX. */
final class InactivityLock {
    private final LongSupplier monotonicNanos;
    private final Runnable lock;
    private long timeoutNanos;
    private long lastActivityNanos;
    private boolean active;

    InactivityLock(LongSupplier monotonicNanos, Runnable lock) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos);
        this.lock = Objects.requireNonNull(lock);
    }

    void configure(int seconds) {
        if (seconds < 0 || seconds > AppSettings.MAX_IDLE_LOCK_SECONDS) {
            throw new IllegalArgumentException("Tempo de bloqueio invalido.");
        }
        timeoutNanos = TimeUnit.SECONDS.toNanos(seconds);
        activity();
    }

    void start() {
        active = true;
        activity();
    }

    void stop() {
        active = false;
    }

    void activity() {
        lastActivityNanos = monotonicNanos.getAsLong();
    }

    void check() {
        if (active && timeoutNanos > 0 && monotonicNanos.getAsLong() - lastActivityNanos >= timeoutNanos) {
            active = false;
            lock.run();
        }
    }
}
