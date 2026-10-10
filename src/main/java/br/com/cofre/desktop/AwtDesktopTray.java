package br.com.cofre.desktop;

import javafx.application.Platform;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

/** AWT oferece a mesma integração no Windows e nos desktops Linux com bandeja compatível. */
final class AwtDesktopTray implements DesktopTray {
    private static final int ICON_SIZE_PIXELS = 32;
    private static final System.Logger LOGGER = System.getLogger(AwtDesktopTray.class.getName());
    private TrayIcon icon;

    @Override
    public boolean install(Runnable openWindow, Runnable exitApplication) {
        boolean[] installed = {false};
        try {
            onAwtThread(() -> {
                if (!SystemTray.isSupported()) {
                    return;
                }
                if (icon != null) {
                    installed[0] = true;
                    return;
                }
                PopupMenu menu = new PopupMenu();
                MenuItem open = new MenuItem("Abrir Cofre local");
                open.addActionListener(event -> Platform.runLater(openWindow));
                MenuItem exit = new MenuItem("Encerrar aplicativo");
                exit.addActionListener(event -> Platform.runLater(exitApplication));
                menu.add(open);
                menu.addSeparator();
                menu.add(exit);
                TrayIcon candidate = new TrayIcon(createIcon(), "Cofre local", menu);
                candidate.setImageAutoSize(true);
                candidate.addActionListener(event -> Platform.runLater(openWindow));
                try {
                    SystemTray.getSystemTray().add(candidate);
                    icon = candidate;
                    installed[0] = true;
                } catch (AWTException | SecurityException failure) {
                    LOGGER.log(System.Logger.Level.DEBUG, "Bandeja indisponível", failure);
                }
            });
        } catch (IllegalStateException failure) {
            LOGGER.log(System.Logger.Level.DEBUG, "Bandeja indisponível", failure);
        }
        return installed[0];
    }

    @Override
    public void close() {
        if (icon == null) {
            return;
        }
        onAwtThread(() -> {
            if (icon != null) {
                SystemTray.getSystemTray().remove(icon);
                icon = null;
            }
        });
    }

    /** Evita acessar componentes AWT pela thread JavaFX; callbacks retornam via runLater. */
    private static void onAwtThread(Runnable operation) {
        if (EventQueue.isDispatchThread()) {
            operation.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(operation);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Integração com a bandeja interrompida", failure);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Falha na integração com a bandeja", failure.getCause());
        }
    }

    private static BufferedImage createIcon() {
        BufferedImage image = new BufferedImage(ICON_SIZE_PIXELS, ICON_SIZE_PIXELS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D drawing = image.createGraphics();
        try {
            drawing.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawing.setColor(new Color(0x4169cf));
            drawing.fillRoundRect(0, 0, ICON_SIZE_PIXELS, ICON_SIZE_PIXELS, 10, 10);
            drawing.setColor(Color.WHITE);
            drawing.setStroke(new java.awt.BasicStroke(3));
            drawing.drawArc(10, 6, 12, 16, 0, 180);
            drawing.fillRoundRect(8, 14, 16, 12, 4, 4);
            drawing.setColor(new Color(0x4169cf));
            drawing.fillOval(14, 18, 4, 5);
        } finally {
            drawing.dispose();
        }
        return image;
    }
}
