package br.com.cofre.desktop;

/** Integração opcional com a bandeja do ambiente desktop. */
interface DesktopTray extends AutoCloseable {
    boolean install(Runnable openWindow, Runnable exitApplication);

    @Override
    void close();
}
