package br.com.cofre.desktop;

import static org.junit.jupiter.api.Assertions.*;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ListView;
import javafx.scene.layout.Pane;
import javafx.collections.ListChangeListener;
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
    private static final int JAVAFX_TIMEOUT_SECONDS = 20;
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
    void credentialEditorSupportsSaveSearchEditDeleteAndLock() throws Exception {
        var unlocked = new CompletableFuture<Void>();
        var application = new VaultApp();
        var stageReference = new CompletableFuture<Stage>();
        String previousPath = System.getProperty("vault.path");
        try {
            runOnJavaFx(() -> {
                System.setProperty("vault.path", directory.resolve("editor.db").toString());
                Stage stage = new Stage();
                stageReference.complete(stage);
                application.start(stage);
                Pane root = (Pane) stage.getScene().getRoot();
                root.getChildren().addListener((ListChangeListener<Node>) change -> {
                    if (hasLabel(root, "Minhas credenciais")) {
                        unlocked.complete(null);
                    }
                });
                descendants(root)
                        .filter(PasswordField.class::isInstance)
                        .map(PasswordField.class::cast)
                        .forEach(field -> field.setText("senha fictícia de teste"));
                button(root, "Criar cofre").fire();
            });
            unlocked.get(JAVAFX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            runOnJavaFx(() -> {
                Parent root = stageReference.join().getScene().getRoot();
                textField(root, "Título").setText("Conta de teste");
                textField(root, "Usuário / e-mail").setText("usuário fictício");
                textField(root, "Senha do site").setText("senha fictícia");
                button(root, "Salvar").fire();
                assertTrue(hasLabel(root, "Credencial salva."));
                ListView<?> entries = descendants(root)
                        .filter(ListView.class::isInstance)
                        .map(ListView.class::cast)
                        .findFirst().orElseThrow();
                assertEquals(1, entries.getItems().size());
                TextField search = textField(root, "Pesquisar título, usuário ou site");
                search.setText("inexistente");
                assertTrue(entries.getItems().isEmpty());
                search.clear();
                assertEquals(1, entries.getItems().size());
                entries.getSelectionModel().selectFirst();
                assertEquals("Conta de teste", textField(root, "Título").getText());
                textField(root, "Título").setText("Conta editada");
                button(root, "Salvar").fire();
                assertEquals(1, entries.getItems().size());
                entries.getSelectionModel().selectFirst();
                assertEquals("Conta editada", textField(root, "Título").getText());
                button(root, "Excluir selecionada").fire();
                assertTrue(entries.getItems().isEmpty());
                assertTrue(hasLabel(root, "Credencial excluída."));
                TextField passwordField = textField(root, "Senha do site");
                button(root, "Bloquear agora").fire();
                assertTrue(hasLabel(root, "Cofre bloqueado"));
                assertEquals("", passwordField.getText());
            });
        } finally {
            runOnJavaFx(() -> {
                if (stageReference.isDone()) {
                    application.stop();
                    stageReference.join().close();
                }
                if (previousPath == null) {
                    System.clearProperty("vault.path");
                } else {
                    System.setProperty("vault.path", previousPath);
                }
            });
        }
    }

    @FunctionalInterface
    private interface JavaFxOperation {
        void execute() throws Exception;
    }

    private static void runOnJavaFx(JavaFxOperation operation) throws Exception {
        var completed = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                operation.execute();
                completed.complete(null);
            } catch (Throwable failure) {
                completed.completeExceptionally(failure);
            }
        });
        completed.get(JAVAFX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static TextField textField(Parent root, String promptText) {
        return descendants(root)
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> promptText.equals(field.getPromptText()))
                .findFirst().orElseThrow();
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
                        // Usa o ciclo público da janela para provocar perda de foco,
                        // sem reflexão sobre métodos internos do JavaFX.
                        stage.hide();
                        stage.show();
                        assertTrue(
                                descendants(root).anyMatch(node -> node == passwords.get(0)),
                                "Perder foco deve preservar a tela atual.");
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
                            if (started) {
                                application.stop();
                            }
                            stage.close();
                            if (previousPath == null) {
                                System.clearProperty("vault.path");
                            }
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
