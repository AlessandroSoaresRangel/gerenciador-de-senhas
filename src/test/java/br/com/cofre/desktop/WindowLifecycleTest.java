package br.com.cofre.desktop;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WindowLifecycleTest {
    @Test
    void keepRunningPreservesSessionAndTrayCanRestoreOrExit() {
        var window = new TestWindow();
        var tray = new TestTray(true);
        try (var lifecycle = new WindowLifecycle(tray, window)) {
            lifecycle.requestClose(WindowLifecycle.CloseChoice.KEEP_RUNNING);
            assertEquals(List.of("hide"), window.events);
            tray.open.run();
            tray.exit.run();
            assertEquals(List.of("hide", "restore", "exit"), window.events);
        }
        assertTrue(tray.closed);
    }

    @Test
    void unsupportedTrayMinimizesWithoutLockingOrHiding() {
        var window = new TestWindow();
        var tray = new TestTray(false);
        try (var lifecycle = new WindowLifecycle(tray, window)) {
            lifecycle.requestClose(WindowLifecycle.CloseChoice.KEEP_RUNNING);
            assertEquals(List.of("minimize"), window.events);
        }
        assertTrue(tray.closed);
    }

    @Test
    void cancelPreservesWindowAndExitEndsApplication() {
        var window = new TestWindow();
        var tray = new TestTray(true);
        try (var lifecycle = new WindowLifecycle(tray, window)) {
            lifecycle.requestClose(WindowLifecycle.CloseChoice.CANCEL);
            assertTrue(window.events.isEmpty());
            assertNull(tray.open);
            lifecycle.requestClose(WindowLifecycle.CloseChoice.EXIT);
            assertEquals(List.of("exit"), window.events);
            assertNull(tray.open);
        }
        assertTrue(tray.closed);
    }

    private static final class TestTray implements DesktopTray {
        private final boolean supported;
        private Runnable open;
        private Runnable exit;
        private boolean closed;

        TestTray(boolean supported) { this.supported = supported; }

        @Override
        public boolean install(Runnable openWindow, Runnable exitApplication) {
            open = openWindow;
            exit = exitApplication;
            return supported;
        }

        @Override
        public void close() { closed = true; }
    }

    private static final class TestWindow implements WindowLifecycle.Window {
        private final List<String> events = new ArrayList<>();
        public void hide() { events.add("hide"); }
        public void minimize() { events.add("minimize"); }
        public void restore() { events.add("restore"); }
        public void exit() { events.add("exit"); }
    }
}
