package br.com.cofre.desktop;

import br.com.cofre.application.VaultService;
import br.com.cofre.domain.Credential;
import br.com.cofre.domain.VaultEntry;
import br.com.cofre.ipc.StatusServer;
import br.com.cofre.nativehost.FillProtocol;
import br.com.cofre.nativehost.StatusProtocol;
import br.com.cofre.nativehost.UnlockProtocol;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class VaultApp extends Application {
    private static final int ROOT_SPACING_PIXELS = 14;
    private static final int CONTROL_SPACING_PIXELS = 10;
    private static final int SECTION_SPACING_PIXELS = 20;
    private static final int WINDOW_PADDING_PIXELS = 24;
    private static final int WINDOW_WIDTH_PIXELS = 900;
    private static final int WINDOW_HEIGHT_PIXELS = 650;
    private static final int ENTRY_LIST_WIDTH_PIXELS = 330;
    private static final int NOTES_VISIBLE_ROWS = 4;
    private static final int SESSION_CHECK_INTERVAL_SECONDS = 1;
    private static final int MAX_TIMER_PAUSE_SECONDS = 10;
    private static final int INACTIVITY_TIMEOUT_MINUTES = 5;
    private static final String HEADING_STYLE = "-fx-font-size: 26px; -fx-font-weight: bold;";

    private VaultService vault;
    private StatusServer statusServer;
    private final VBox root = new VBox(ROOT_SPACING_PIXELS);
    private final Label message = new Label();
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    operation -> {
                        Thread workerThread = new Thread(operation, "vault-kdf");
                        workerThread.setDaemon(true);
                        return workerThread;
                    });
    private Timeline timer;
    private long lastActivityNanos = System.nanoTime();
    private long lastTickNanos = lastActivityNanos;
    private int generation;
    private boolean busy;
    private boolean listenersBound;
    private boolean stopped;
    private CompletableFuture<Void> pendingExtensionUnlock;
    private final ListView<VaultEntry> items = new ListView<>();
    private final TextField title = new TextField();
    private final TextField username = new TextField();
    private final TextField origins = new TextField();
    private final CheckBox autofill = new CheckBox("Permitir preenchimento automático nas origens cadastradas");
    private final TextField search = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextArea notes = new TextArea();
    private String editing;

    @Override
    public void start(Stage stage) throws Exception {
        String custom = System.getProperty("vault.path");
        Path path =
                custom == null
                        ? Path.of(System.getProperty("user.home"), ".cofre-local", "vault.db")
                        : Path.of(custom);
        vault = new VaultService(path);
        statusServer =
                new StatusServer(
                        path.toAbsolutePath().getParent().resolve("ipc/endpoint.json"),
                        new StatusProtocol(vault::status, vault::listMatches, new FillProtocol(vault),
                                new UnlockProtocol(this::unlockFromExtension)));
        root.setPadding(new Insets(WINDOW_PADDING_PIXELS));
        Scene scene = new Scene(root, WINDOW_WIDTH_PIXELS, WINDOW_HEIGHT_PIXELS);
        scene.addEventFilter(InputEvent.ANY, event -> lastActivityNanos = System.nanoTime());
        stage.setTitle("Cofre local");
        stage.setScene(scene);
        // Alternar para o navegador deve manter a sessão disponível à extensão.
        stage.iconifiedProperty()
                .addListener(
                        (observable, previouslyMinimized, minimized) -> {
                            if (minimized) {
                                lock();
                            }
                        });
        timer =
                new Timeline(
                        new KeyFrame(
                                Duration.seconds(SESSION_CHECK_INTERVAL_SECONDS),
                                event -> checkSessionTimeout()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        showLocked();
        stage.show();
    }

    /** Uma pausa longa entre verificações sugere suspensão; ao retomar, bloqueia por precaução. */
    private void checkSessionTimeout() {
        long nowNanos = System.nanoTime();
        boolean timerInterrupted =
                nowNanos - lastTickNanos > TimeUnit.SECONDS.toNanos(MAX_TIMER_PAUSE_SECONDS);
        boolean inactive =
                nowNanos - lastActivityNanos > TimeUnit.MINUTES.toNanos(INACTIVITY_TIMEOUT_MINUTES);
        if (timerInterrupted || inactive) {
            lock();
        }
        lastTickNanos = nowNanos;
    }

    /**
     * Reconstrói a tela sem credenciais e escolhe criação ou desbloqueio conforme o arquivo. A
     * derivação de chave roda no worker; somente o retorno à thread JavaFX altera a tela.
     */
    private void showLocked() {
        clearSensitive();
        root.getChildren().clear();
        try {
            boolean creating = !vault.exists();
            Label heading = new Label(creating ? "Crie seu cofre local" : "Cofre bloqueado");
            heading.setStyle(HEADING_STYLE);
            PasswordField master = new PasswordField();
            master.setPromptText("Senha mestra");
            PasswordField confirmation = new PasswordField();
            confirmation.setPromptText("Confirme a senha mestra");
            Button submit = new Button(creating ? "Criar cofre" : "Desbloquear");
            submit.setDefaultButton(true);
            submit.setOnAction(event -> authenticate(creating, master, confirmation, submit));
            root.getChildren()
                    .addAll(
                            heading,
                            new Label(
                                    "Os dados ficam criptografados neste computador. Guarde a senha"
                                            + " mestra: não há recuperação."),
                            master);
            if (creating) {
                root.getChildren()
                        .addAll(
                                confirmation,
                                new Label("Senha mestra: pelo menos "
                                        + VaultService.MIN_MASTER_PASSWORD_LENGTH_CHARACTERS
                                        + " caracteres."));
            }
            Button restore = new Button("Restaurar backup");
            restore.setOnAction(
                    event -> {
                        if (!busy) {
                            showRestore();
                        }
                    });
            root.getChildren()
                    .addAll(
                            submit,
                            restore,
                            message,
                            new Label(
                                    "Arquivo: "
                                            + (System.getProperty(
                                                    "vault.path",
                                                    Path.of(
                                                                    System.getProperty("user.home"),
                                                                    ".cofre-local",
                                                                    "vault.db")
                                                            .toString()))));
        } catch (Exception e) {
            message.setText("Não foi possível acessar o arquivo do cofre.");
            root.getChildren().add(message);
        }
    }

    private void showVault() {
        root.getChildren().clear();
        message.setText("");
        Label heading = new Label("Minhas credenciais");
        heading.setStyle(HEADING_STYLE);
        Button block = new Button("Bloquear agora");
        block.setOnAction(e -> lock());
        search.setPromptText("Pesquisar título, usuário ou site");
        bindCredentialListeners();
        VBox editor = createCredentialEditor();
        VBox entryList = new VBox(CONTROL_SPACING_PIXELS, search, items);
        VBox.setVgrow(items, Priority.ALWAYS);
        entryList.setPrefWidth(ENTRY_LIST_WIDTH_PIXELS);
        HBox body = new HBox(SECTION_SPACING_PIXELS, entryList, editor);
        VBox.setVgrow(body, Priority.ALWAYS);
        root.getChildren()
                .addAll(
                        new HBox(SECTION_SPACING_PIXELS, heading, block),
                        new Label(
                                "Bloqueio após " + INACTIVITY_TIMEOUT_MINUTES
                                + " minutos, ao minimizar ou ao detectar interrupção"
                                        + " da sessão."),
                        body,
                        message);
        root.getChildren().add(createBackupControls());
        refresh();
    }

    private void bindCredentialListeners() {
        // Os controles são reutilizados após desbloquear; não acumula listeners a cada sessão.
        if (!listenersBound) {
            search.textProperty().addListener((observable, previousText, currentText) -> refresh());
            items.getSelectionModel()
                    .selectedItemProperty()
                    .addListener(
                            (observable, previousEntry, selectedEntry) -> {
                                if (selectedEntry == null) {
                                    return;
                                }
                                editing = selectedEntry.id();
                                Credential credential = selectedEntry.credential();
                                title.setText(credential.title());
                                username.setText(credential.username());
                                password.setText(credential.password());
                                origins.setText(String.join(", ", credential.allowedOrigins()));
                                notes.setText(credential.notes());
                                autofill.setSelected(credential.autofillEnabled());
                            });
            listenersBound = true;
        }
    }

    private VBox createCredentialEditor() {
        title.setPromptText("Título");
        username.setPromptText("Usuário / e-mail");
        password.setPromptText("Senha do site");
        origins.setPromptText("https://conta.exemplo.com (separe origens por vírgulas)");
        notes.setPromptText("Notas");
        notes.setPrefRowCount(NOTES_VISIBLE_ROWS);
        Button save = new Button("Salvar");
        save.setOnAction(event -> saveCredential());
        Button fresh = new Button("Novo cadastro");
        fresh.setOnAction(
                e -> {
                    items.getSelectionModel().clearSelection();
                    clearForm();
                });
        Button delete = new Button("Excluir selecionada");
        delete.setOnAction(event -> deleteCredential());
        VBox editor =
                new VBox(
                        CONTROL_SPACING_PIXELS,
                        new Label("Cadastro / edição"),
                        title,
                        username,
                        password,
                        origins,
                        autofill,
                        notes,
                        new HBox(CONTROL_SPACING_PIXELS, save, fresh, delete));
        HBox.setHgrow(editor, Priority.ALWAYS);
        return editor;
    }

    private void saveCredential() {
        try {
            List<String> sites =
                    origins.getText().isBlank()
                            ? List.of()
                            : Arrays.stream(origins.getText().split(","))
                                    .map(String::trim)
                                    .toList();
            vault.save(
                    editing,
                    new Credential(
                            title.getText(),
                            username.getText(),
                            password.getText(),
                            sites,
                            autofill.isSelected(),
                            notes.getText()));
            clearForm();
            refresh();
            message.setText("Credencial salva.");
        } catch (Exception ex) {
            message.setText(
                    ex instanceof IllegalArgumentException
                            ? ex.getMessage()
                            : "Não foi possível salvar a credencial.");
        }
    }

    private void deleteCredential() {
        if (editing == null) {
            return;
        }
        try {
            vault.delete(editing);
            clearForm();
            refresh();
            message.setText("Credencial excluída.");
        } catch (Exception ex) {
            message.setText("Não foi possível excluir.");
        }
    }

    private HBox createBackupControls() {
        TextField backupPath = new TextField();
        backupPath.setPromptText("Caminho completo do arquivo de backup (pasta + nome do arquivo)");
        HBox.setHgrow(backupPath, Priority.ALWAYS);
        Button export = new Button("Salvar backup");
        export.setOnAction(
                event -> {
                    if (busy) {
                        return;
                    }
                    try {
                        Path destination = Path.of(backupPath.getText());
                        if (backupPath.getText().isBlank()) {
                            message.setText("Informe o caminho do backup.");
                            return;
                        }
                        runBackupOperation(() -> vault.backup(destination), false);
                    } catch (IllegalArgumentException failure) {
                        message.setText("Caminho de backup inválido.");
                    }
                });
        Button restore = new Button("Restaurar backup");
        restore.setOnAction(
                event -> {
                    if (!busy) {
                        showRestore();
                    }
                });
        return new HBox(CONTROL_SPACING_PIXELS, backupPath, export, restore);
    }

    private void authenticate(
            boolean creating, PasswordField master, PasswordField confirmation, Button submit) {
        if (busy) {
            return;
        }
        if (creating && !master.getText().equals(confirmation.getText())) {
            message.setText("As senhas não coincidem.");
            return;
        }
        char[] secret = master.getText().toCharArray();
        master.clear();
        confirmation.clear();
        busy = true;
        submit.setDisable(true);
        // Guarda a sessão que iniciou o pedido para detectar bloqueios posteriores.
        int requestGeneration = generation;
        message.setText("Processando…");
        worker.submit(
                () -> {
                    Exception failure = null;
                    try {
                        if (creating) {
                            vault.create(secret);
                        } else {
                            vault.unlock(secret);
                        }
                    } catch (Exception ex) {
                        failure = ex;
                    }
                    Exception result = failure;
                    Platform.runLater(() -> completeAuthentication(requestGeneration, submit, result));
                });
    }

    /**
     * O servidor IPC espera o resultado, mas JavaFX continua livre durante Argon2. A geração
     * invalida o retorno se houve bloqueio ou fechamento; operações desktop em curso têm prioridade.
     */
    private void unlockFromExtension(char[] secret) throws Exception {
        var completion = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            if (busy || stopped) {
                completion.completeExceptionally(new IllegalStateException("Operação em curso."));
                return;
            }
            busy = true;
            pendingExtensionUnlock = completion;
            int requestGeneration = generation;
            message.setText("Desbloqueando pela extensão…");
            worker.submit(() -> {
                Exception failure = null;
                try {
                    vault.unlock(secret);
                } catch (Exception exception) {
                    failure = exception;
                }
                Exception result = failure;
                Platform.runLater(() -> {
                    if (stopped) {
                        completion.completeExceptionally(new IllegalStateException("Aplicativo fechado."));
                        return;
                    }
                    completeAuthentication(requestGeneration, null, result);
                    if (requestGeneration == generation && result == null && vault.unlocked()) {
                        completion.complete(null);
                    } else {
                        completion.completeExceptionally(new IllegalStateException("Desbloqueio recusado."));
                    }
                    pendingExtensionUnlock = null;
                });
            });
        });
        completion.get();
    }

    /** Um resultado atrasado não reabre a interface se houve bloqueio durante a derivação. */
    private void completeAuthentication(int requestGeneration, Button submit, Exception failure) {
        busy = false;
        if (requestGeneration != generation) {
            vault.lock();
            showLocked();
            return;
        }
        if (submit != null) {
            submit.setDisable(false);
        }
        if (failure != null) {
            message.setText(
                    failure instanceof IllegalArgumentException
                            ? failure.getMessage()
                            : "Não foi possível abrir o cofre. Confira a senha; o arquivo"
                                    + " também pode estar danificado.");
        } else {
            lastActivityNanos = System.nanoTime();
            showVault();
        }
    }

    /**
     * A restauração começa com a sessão bloqueada e exige a senha do backup. A confirmação fica na
     * própria tela, deixando explícito que os dados atuais serão substituídos.
     */
    private void showRestore() {
        lock();
        root.getChildren().clear();
        Label heading = new Label("Restaurar backup");
        heading.setStyle(HEADING_STYLE);
        TextField sourcePath = new TextField();
        sourcePath.setPromptText("Caminho completo do arquivo .cofre");
        PasswordField backupPassword = new PasswordField();
        backupPassword.setPromptText("Senha mestra usada no backup");
        CheckBox replace =
                new CheckBox("Confirmo a substituição de todos os dados do cofre atual.");
        Button submit = new Button("Validar e restaurar");
        submit.setOnAction(
                event -> {
                    if (busy) {
                        return;
                    }
                    if (!replace.isSelected() || sourcePath.getText().isBlank()) {
                        message.setText("Informe o arquivo e confirme a substituição.");
                        return;
                    }
                    try {
                        Path source = Path.of(sourcePath.getText());
                        char[] secret = backupPassword.getText().toCharArray();
                        backupPassword.clear();
                        runBackupOperation(() -> vault.restore(source, secret), true);
                    } catch (IllegalArgumentException failure) {
                        message.setText("Caminho de backup inválido.");
                    }
                });
        Button back = new Button("Voltar");
        back.setOnAction(
                event -> {
                    if (!busy) {
                        showLocked();
                    }
                });
        root.getChildren()
                .addAll(
                        heading,
                        new Label(
                                "A restauração substitui o cofre inteiro. Salve um backup do cofre"
                                        + " atual antes de continuar."),
                        sourcePath,
                        backupPassword,
                        replace,
                        new HBox(CONTROL_SPACING_PIXELS, submit, back),
                        message);
    }

    @FunctionalInterface
    private interface BackupOperation {
        void execute() throws Exception;
    }

    /**
     * Executa I/O e derivação de chave fora da thread JavaFX. Após confirmar a restauração, o
     * trabalho segue mesmo se a janela perder foco; seu resultado nunca desbloqueia o cofre.
     */
    private void runBackupOperation(BackupOperation operation, boolean restoring) {
        busy = true;
        message.setText("Processando…");
        worker.submit(
                () -> {
                    Exception failure = null;
                    try {
                        operation.execute();
                    } catch (Exception exception) {
                        failure = exception;
                    }
                    Exception result = failure;
                    Platform.runLater(
                            () -> {
                                busy = false;
                                if (restoring) {
                                    showLocked();
                                }
                                if (result != null) {
                                    if (!restoring && result instanceof IllegalArgumentException) {
                                        message.setText(result.getMessage());
                                        return;
                                    }
                                    message.setText(
                                            restoring
                                                    ? "Restauração recusada. Confira o arquivo e a"
                                                            + " senha. Os dados anteriores foram"
                                                            + " preservados."
                                                    : "Não foi possível salvar o backup. Use um"
                                                          + " novo arquivo em uma pasta existente e"
                                                          + " desbloqueie o cofre.");
                                } else {
                                    message.setText(
                                            restoring
                                                    ? "Backup restaurado. Desbloqueie com a senha"
                                                            + " mestra do backup."
                                                    : "Backup criptografado salvo.");
                                }
                            });
                });
    }

    /**
     * Pesquisa apenas nos dados descriptografados da sessão. Uma falha de leitura ou integridade
     * remove as credenciais da tela por meio do bloqueio, sem manter resultados antigos visíveis.
     */
    private void refresh() {
        if (!vault.unlocked()) {
            return;
        }
        try {
            String term = search.getText().toLowerCase(Locale.ROOT);
            items.getItems()
                    .setAll(
                            vault.list().stream()
                                    .filter(entry -> matchesSearch(entry.credential(), term))
                                    .toList());
        } catch (Exception e) {
            lock();
            message.setText("Falha de integridade ou leitura. O cofre foi bloqueado.");
        }
    }

    private boolean matchesSearch(Credential credential, String term) {
        String searchableText = credential.title() + " " + credential.username() + " "
                + String.join(" ", credential.allowedOrigins());
        return searchableText.toLowerCase(Locale.ROOT).contains(term);
    }

    private void clearForm() {
        editing = null;
        title.clear();
        username.clear();
        password.clear();
        origins.clear();
        autofill.setSelected(false);
        notes.clear();
    }

    private void clearSensitive() {
        items.getItems().clear();
        clearForm();
        search.clear();
    }

    private void lock() {
        // Invalida também o retorno de uma criação/desbloqueio ainda em processamento.
        generation++;
        vault.lock();
        showLocked();
        message.setText("");
    }

    @Override
    public void stop() throws Exception {
        stopped = true;
        if (statusServer != null) {
            statusServer.close();
        }
        generation++;
        timer.stop();
        worker.shutdownNow();
        vault.close();
        if (pendingExtensionUnlock != null) {
            // close espera a operação criptográfica antes de liberar o array ao protocolo.
            pendingExtensionUnlock.completeExceptionally(new IllegalStateException("Aplicativo fechado."));
            pendingExtensionUnlock = null;
        }
        clearSensitive();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
