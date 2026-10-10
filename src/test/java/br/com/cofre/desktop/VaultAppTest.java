package br.com.cofre.desktop;

import static org.junit.jupiter.api.Assertions.*;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.Pane;
import javafx.collections.ListChangeListener;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

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

    @Test
    void extensionUnlocksSameSessionAndUpdatesDesktopScreen() throws Exception {
        Path database = directory.resolve("extension.db");
        try (var vault = new br.com.cofre.application.VaultService(database)) {
            vault.create("senha mestra fictícia".toCharArray());
            vault.save(null, new br.com.cofre.domain.Credential("Conta existente", "usuario teste",
                    "segredo ficticio", List.of(), false, ""));
            vault.save(null, new br.com.cofre.domain.Credential("Outra conta existente", "outro usuario",
                    "outro segredo ficticio", List.of(), false, ""));
        }
        var tray = new RecordingTray();
        var application = new VaultApp(tray);
        var stageReference = new CompletableFuture<Stage>();
        String previousPath = System.getProperty("vault.path");
        try {
            runOnJavaFx(() -> {
                System.setProperty("vault.path", database.toString());
                Stage stage = new Stage();
                stageReference.complete(stage);
                application.start(stage);
            });
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var client = new br.com.cofre.ipc.StatusClient(directory.resolve("ipc/endpoint.json"));
            byte[] request = json.writeValueAsBytes(java.util.Map.of("version", 1,
                    "command", "UNLOCK_VAULT", "requestId", "desktop-test", "password", "senha mestra fictícia"));
            assertTrue(json.readTree(client.query(request)).path("ok").asBoolean());
            runOnJavaFx(() -> {
                Parent root = stageReference.join().getScene().getRoot();
                assertTrue(hasLabel(root, "Minhas credenciais"));
                assertTrue(hasLabel(root, "Conta existente"));
                assertTrue(hasLabel(root, "Outra conta existente"));
                assertFalse(descendants(root).anyMatch(PasswordField.class::isInstance));
                Stage stage = stageReference.join();
                closeWindowChoosing(stage, "Cancelar");
                assertTrue(stage.isShowing());
                assertTrue(hasLabel(root, "Minhas credenciais"));
                assertNull(tray.openWindow);
                closeWindowChoosing(stage, "Manter em segundo plano");
                assertFalse(stage.isShowing());
                assertNotNull(tray.openWindow);
                assertTrue(hasLabel(root, "Conta existente"));
                tray.openWindow.run();
                assertTrue(stage.isShowing());
                assertTrue(hasLabel(root, "Minhas credenciais"));
                stage.setIconified(true);
                assertTrue(hasLabel(root, "Conta existente"));
                stage.setIconified(false);
                button(root, "Bloquear agora").fire();
                assertTrue(hasLabel(root, "Cofre bloqueado"));
                assertFalse(hasLabel(root, "Conta existente"));
            });
        } finally {
            runOnJavaFx(() -> {
                application.stop();
                assertTrue(tray.closed);
                stageReference.join().hide();
                if (previousPath == null) {
                    System.clearProperty("vault.path");
                } else {
                    System.setProperty("vault.path", previousPath);
                }
            });
        }
    }

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

    private static void closeWindowChoosing(Stage stage, String choice) {
        Platform.runLater(() -> {
            Window dialog = Window.getWindows().stream()
                    .filter(window -> window != stage && window.isShowing())
                    .filter(window -> window instanceof Stage dialogStage
                            && "Fechar Cofre local".equals(dialogStage.getTitle()))
                    .findFirst().orElseThrow();
            button(dialog.getScene().getRoot(), choice).fire();
        });
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    @Test
    void deletionRequiresTypedConfirmationAndReturnsToCreationScreen() throws Exception {
        Path database = directory.resolve("delete-ui.db");
        try (var vault = new br.com.cofre.application.VaultService(database)) {
            vault.create("senha fictícia de teste".toCharArray());
        }
        var application = new VaultApp();
        var stageReference = new CompletableFuture<Stage>();
        var deleted = new CompletableFuture<Void>();
        String previousPath = System.getProperty("vault.path");
        try {
            runOnJavaFx(() -> {
                System.setProperty("vault.path", database.toString());
                Stage stage = new Stage();
                stageReference.complete(stage);
                application.start(stage);
                Pane root = (Pane) stage.getScene().getRoot();
                Platform.runLater(() -> {
                    Parent dialog = deletionDialogRoot(stage);
                    assertTrue(button(dialog, "Excluir definitivamente").isDisabled());
                    button(dialog, "Cancelar").fire();
                });
                button(root, "Excluir cofre e começar do zero").fire();
                assertTrue(hasLabel(root, "Cofre bloqueado"));
                root.getChildren().addListener((ListChangeListener<Node>) change -> {
                    if (hasLabel(root, "Crie seu cofre local")) {
                        deleted.complete(null);
                    }
                });
                Platform.runLater(() -> {
                    Parent dialog = deletionDialogRoot(stage);
                    TextField confirmation = textField(dialog, "Digite EXCLUIR");
                    confirmation.setText("excluir");
                    assertTrue(button(dialog, "Excluir definitivamente").isDisabled());
                    confirmation.setText("EXCLUIR");
                    assertFalse(button(dialog, "Excluir definitivamente").isDisabled());
                    button(dialog, "Excluir definitivamente").fire();
                });
                button(root, "Excluir cofre e começar do zero").fire();
            });
            deleted.get(JAVAFX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            runOnJavaFx(() -> {
                Parent root = stageReference.join().getScene().getRoot();
                assertTrue(hasLabel(root, "Crie seu cofre local"));
                assertTrue(hasLabel(root, "Cofre excluído. Crie uma nova senha mestra para começar do zero."));
                assertFalse(descendants(root).filter(Button.class::isInstance).map(Button.class::cast)
                        .anyMatch(button -> "Excluir cofre e começar do zero".equals(button.getText())));
            });
        } finally {
            runOnJavaFx(() -> {
                application.stop();
                stageReference.join().hide();
                if (previousPath == null) {
                    System.clearProperty("vault.path");
                } else {
                    System.setProperty("vault.path", previousPath);
                }
            });
        }
    }

    private static Parent deletionDialogRoot(Stage owner) {
        return Window.getWindows().stream()
                .filter(window -> window != owner && window.isShowing())
                .filter(window -> window instanceof Stage stage && "Excluir cofre".equals(stage.getTitle()))
                .findFirst().orElseThrow().getScene().getRoot();
    }

    private static final class RecordingTray implements DesktopTray {
        private Runnable openWindow;
        private boolean closed;

        @Override
        public boolean install(Runnable openWindow, Runnable exitApplication) {
            this.openWindow = openWindow;
            return true;
        }

        @Override
        public void close() { closed = true; }
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
                assertFalse(descendants(root).anyMatch(PasswordField.class::isInstance));
                button(root, "Adicionar conta").fire();
                textField(root, "Título").setText("Rascunho");
                button(root, "Voltar para contas").fire();
                assertFalse(hasLabel(root, "Rascunho"));
                button(root, "Adicionar conta").fire();
                assertEquals("", textField(root, "Título").getText());
                textField(root, "Título").setText("Conta de teste");
                textField(root, "Usuário / e-mail").setText("usuário fictício");
                button(root, "Gerar senha").fire();
                String generatedPassword = textField(root, "Senha do site").getText();
                assertFalse(textField(root, "Senha do site") instanceof PasswordField);
                button(root, "Copiar senha").fire();
                assertEquals(generatedPassword, javafx.scene.input.Clipboard.getSystemClipboard().getString());
                assertEquals(16, generatedPassword.length());
                assertTrue(generatedPassword.chars().anyMatch(Character::isUpperCase));
                assertTrue(generatedPassword.chars().anyMatch(Character::isLowerCase));
                assertTrue(generatedPassword.chars().anyMatch(Character::isDigit));
                assertTrue(generatedPassword.chars().anyMatch(character -> !Character.isLetterOrDigit(character)));
                button(root, "Salvar").fire();
                assertTrue(hasLabel(root, "Credencial salva."));
                assertTrue(hasLabel(root, "Conta de teste"));
                assertTrue(hasLabel(root, "usuário fictício"));
                assertFalse(descendants(root).anyMatch(PasswordField.class::isInstance));
                assertFalse(hasLabel(root, generatedPassword));
                button(root, "Exibir senha").fire();
                assertTrue(hasLabel(root, generatedPassword));
                button(root, "Ocultar senha").fire();
                assertFalse(hasLabel(root, generatedPassword));
                button(root, "Copiar senha").fire();
                assertEquals(generatedPassword, javafx.scene.input.Clipboard.getSystemClipboard().getString());
                button(root, "Adicionar conta").fire();
                textField(root, "Título").setText("Segunda conta");
                textField(root, "Senha do site").setText("senha fictícia");
                button(root, "Salvar").fire();
                assertTrue(hasLabel(root, "Conta de teste"));
                assertTrue(hasLabel(root, "Segunda conta"));
                TextField search = textField(root, "Pesquisar título, usuário ou site");
                search.setText("inexistente");
                assertFalse(hasLabel(root, "Conta de teste"));
                assertTrue(hasLabel(root, "Nenhuma credencial encontrada."));
                search.setText("Segunda");
                assertTrue(hasLabel(root, "Segunda conta"));
                assertFalse(hasLabel(root, "Conta de teste"));
                search.clear();
                editCard(root, "Conta de teste").fire();
                assertEquals(generatedPassword, textField(root, "Senha do site").getText());
                assertFalse(textField(root, "Senha do site") instanceof PasswordField);
                button(root, "Copiar senha").fire();
                assertEquals(generatedPassword, javafx.scene.input.Clipboard.getSystemClipboard().getString());
                textField(root, "Título").setText("Conta editada");
                button(root, "Salvar").fire();
                assertTrue(hasLabel(root, "Conta editada"));
                assertFalse(hasLabel(root, "Conta de teste"));
                editCard(root, "Conta editada").fire();
                button(root, "Excluir selecionada").fire();
                assertFalse(hasLabel(root, "Conta editada"));
                assertTrue(hasLabel(root, "Credencial excluída."));
                editCard(root, "Segunda conta").fire();
                TextField passwordField = textField(root, "Senha do site");
                button(root, "Bloquear agora").fire();
                assertTrue(hasLabel(root, "Cofre bloqueado"));
                assertEquals("", passwordField.getText());
                assertFalse(hasLabel(root, "Segunda conta"));
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
        // O conteúdo existe antes do primeiro pulso que cria a skin do ScrollPane.
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            return Stream.concat(Stream.of(node), descendants(scroll.getContent()));
        }
        Stream<Node> children =
                node instanceof Parent parent
                        ? parent.getChildrenUnmodifiable().stream()
                                .flatMap(VaultAppTest::descendants)
                        : Stream.empty();
        return Stream.concat(Stream.of(node), children);
    }

    private static Button editCard(Parent root, String title) {
        Parent card = descendants(root)
                .filter(node -> node.getStyleClass().contains("account-card"))
                .map(Parent.class::cast)
                .filter(node -> hasLabel(node, title))
                .findFirst().orElseThrow();
        return button(card, "Editar conta");
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
