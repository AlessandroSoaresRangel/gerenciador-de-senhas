package br.com.cofre.desktop;

/** Coordena o fechamento sem depender dos componentes JavaFX ou AWT. */
final class WindowLifecycle implements AutoCloseable {
    enum CloseChoice { KEEP_RUNNING, EXIT, CANCEL }

    interface Window {
        void hide();
        void minimize();
        void restore();
        void exit();
    }

    private final DesktopTray tray;
    private final Window window;

    WindowLifecycle(DesktopTray tray, Window window) {
        this.tray = tray;
        this.window = window;
    }

    void requestClose(CloseChoice choice) {
        switch (choice) {
            case CANCEL -> { }
            case EXIT -> window.exit();
            case KEEP_RUNNING -> {
                if (tray.install(window::restore, window::exit)) {
                    window.hide();
                } else {
                    window.minimize();
                }
            }
        }
    }

    @Override
    public void close() {
        tray.close();
    }
}
