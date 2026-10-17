package br.com.cofre.desktop;

import javafx.scene.Group;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

/** Ícone settings do Lucide, adaptado para os vetores nativos do JavaFX.
 * Fonte e licença estão nos recursos settings.svg e lucide-LICENSE.txt. */
final class SettingsIcon extends Group {
    SettingsIcon() {
        SVGPath outline = new SVGPath();
        outline.setContent("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915"
                + " 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831"
                + " 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915"
                + " 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915"
                + " 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831"
                + "A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915");
        Circle center = new Circle(12, 12, 3);
        outline.getStyleClass().add("settings-icon");
        center.getStyleClass().add("settings-icon");
        getChildren().addAll(outline, center);
        setMouseTransparent(true);
    }
}
