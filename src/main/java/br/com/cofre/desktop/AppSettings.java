package br.com.cofre.desktop;

/** Preferencias de tempo, em segundos; zero desativa somente o bloqueio automatico. */
public record AppSettings(int idleLockSeconds, int clipboardClearSeconds) {
    public static final int DEFAULT_IDLE_LOCK_SECONDS = 300;
    public static final int DEFAULT_CLIPBOARD_CLEAR_SECONDS = 30;
    public static final int MAX_IDLE_LOCK_SECONDS = 86_400;
    public static final int MAX_CLIPBOARD_CLEAR_SECONDS = 3_600;

    public AppSettings {
        if (idleLockSeconds < 0 || idleLockSeconds > MAX_IDLE_LOCK_SECONDS
                || clipboardClearSeconds < 1 || clipboardClearSeconds > MAX_CLIPBOARD_CLEAR_SECONDS) {
            throw new IllegalArgumentException("Tempos fora dos limites permitidos.");
        }
    }

    public static AppSettings defaults() {
        return new AppSettings(DEFAULT_IDLE_LOCK_SECONDS, DEFAULT_CLIPBOARD_CLEAR_SECONDS);
    }
}
