package br.com.cofre.desktop;

import static org.junit.jupiter.api.Assertions.*;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Teste de integração JavaFX: precisa de uma sessão gráfica e usa apenas um cofre temporário. */
class VaultAppTest {
    @TempDir Path directory;

    @BeforeAll
    static void initializeJavaFx() throws Exception {
        var initialized = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    initialized.complete(null);
                });
        initialized.get(15, TimeUnit.SECONDS);
    }

    @Test
    void creationRejectsMismatchedPasswordsAndRestorationRequiresConfirmation() throws Exception {
        var completed = new CompletableFuture<Void>();
        Platform.runLater(
                () -> {
                    String previousPath = System.getProperty("vault.path");
                    VaultApp application = new VaultApp();
                    Stage stage = new Stage();
                    boolean started = false;
                    try {
                        System.setProperty(
                                "vault.path", directory.resolve("interface.db").toString());
                        application.start(stage);
                        started = true;
                        Parent root = stage.getScene().getRoot();
                        assertTrue(hasLabel(root, "Crie seu cofre local"));
                        List<PasswordField> passwords =
                                descendants(root)
                                        .filter(PasswordField.class::isInstance)
                                        .map(PasswordField.class::cast)
                                        .toList();
                        passwords.get(0).setText("senha de teste longa");
                        passwords.get(1).setText("outra senha de teste");
                        // Simula a transição de foco sem depender de qual janela o SO coloca à
                        // frente.
                        var setFocused =
                                javafx.stage.Window.class.getDeclaredMethod(
                                        "setFocused", boolean.class);
                        setFocused.setAccessible(true);
                        setFocused.invoke(stage, true);
                        setFocused.invoke(stage, false);
                        assertTrue(
                                descendants(root).anyMatch(node -> node == passwords.get(0)),
                                "Alternar para o navegador deve preservar a tela atual.");
                        button(root, "Criar cofre").fire();
                        assertTrue(hasLabel(root, "As senhas não coincidem."));
                        button(root, "Restaurar backup").fire();
                        assertTrue(hasLabel(root, "Restaurar backup"));
                        button(root, "Validar e restaurar").fire();
                        assertTrue(hasLabel(root, "Informe o arquivo e confirme a substituição."));
                        button(root, "Voltar").fire();
                        assertTrue(hasLabel(root, "Crie seu cofre local"));
                    } catch (Throwable failure) {
                        completed.completeExceptionally(failure);
                    } finally {
                        try {
                            if (started) application.stop();
                            stage.close();
                            if (previousPath == null) System.clearProperty("vault.path");
                            else System.setProperty("vault.path", previousPath);
                            completed.complete(null);
                        } catch (Exception failure) {
                            completed.completeExceptionally(failure);
                        }
                    }
                });
        completed.get(20, TimeUnit.SECONDS);
    }

    private static Stream<Node> descendants(Node node) {
        Stream<Node> children =
                node instanceof Parent parent
                        ? parent.getChildrenUnmodifiable().stream()
                                .flatMap(VaultAppTest::descendants)
                        : Stream.empty();
        return Stream.concat(Stream.of(node), children);
    }

    private static Button button(Parent root, String text) {
        return descendants(root)
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasLabel(Parent root, String text) {
        return descendants(root)
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(label -> text.equals(label.getText()));
    }
}
