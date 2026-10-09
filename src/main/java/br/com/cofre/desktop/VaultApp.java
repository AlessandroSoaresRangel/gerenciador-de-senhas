package br.com.cofre.desktop;

import br.com.cofre.application.VaultService;
import br.com.cofre.domain.Credential;
import br.com.cofre.domain.VaultEntry;

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
    private VaultService vault;
    private Stage stage;
    private final VBox root = new VBox(14);
    private final Label message = new Label();
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "vault-kdf");
                        t.setDaemon(true);
                        return t;
                    });
    private Timeline timer;
    private long activity = System.nanoTime(), tick = activity;
    private int generation;
    private boolean busy;
    private boolean listenersBound;
    private final ListView<VaultEntry> items = new ListView<>();
    private final TextField title = new TextField(),
            username = new TextField(),
            origins = new TextField(),
            search = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextArea notes = new TextArea();
    private String editing;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        String custom = System.getProperty("vault.path");
        Path path =
                custom == null
                        ? Path.of(System.getProperty("user.home"), ".cofre-local", "vault.db")
                        : Path.of(custom);
        vault = new VaultService(path);
        root.setPadding(new Insets(24));
        Scene scene = new Scene(root, 900, 650);
        scene.addEventFilter(InputEvent.ANY, event -> activity = System.nanoTime());
        stage.setTitle("Cofre local");
        stage.setScene(scene);
        stage.focusedProperty()
                .addListener(
                        (o, old, focused) -> {
                            if (!focused) lock();
                        });
        stage.iconifiedProperty()
                .addListener(
                        (o, old, minimized) -> {
                            if (minimized) lock();
                        });
        timer =
                new Timeline(
                        new KeyFrame(
                                Duration.seconds(1),
                                e -> {
                                    long now = System.nanoTime();
                                    // Uma pausa longa entre ticks sugere suspensão ou atraso da UI.
                                    // Ao retomar, bloqueia por precaução, além do limite de
                                    // inatividade.
                                    if (now - tick > TimeUnit.SECONDS.toNanos(10)
                                            || now - activity > TimeUnit.MINUTES.toNanos(5)) lock();
                                    tick = now;
                                }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        showLocked();
        stage.show();
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
            heading.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");
            PasswordField master = new PasswordField();
            master.setPromptText("Senha mestra");
            PasswordField confirmation = new PasswordField();
            confirmation.setPromptText("Confirme a senha mestra");
            Button submit = new Button(creating ? "Criar cofre" : "Desbloquear");
            submit.setDefaultButton(true);
            submit.setOnAction(
                    e -> {
                        if (busy) return;
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
                        int request = generation;
                        message.setText("Processando…");
                        worker.submit(
                                () -> {
                                    Exception failure = null;
                                    try {
                                        if (creating) vault.create(secret);
                                        else vault.unlock(secret);
                                    } catch (Exception ex) {
                                        failure = ex;
                                    }
                                    Exception result = failure;
                                    Platform.runLater(
                                            () -> {
                                                busy = false;
                                                // Um resultado atrasado não pode reabrir a
                                                // interface
                                                // se houve bloqueio ou perda de foco durante o
                                                // cálculo.
                                                if (request != generation || !stage.isFocused()) {
                                                    vault.lock();
                                                    showLocked();
                                                    return;
                                                }
                                                submit.setDisable(false);
                                                if (result != null) {
                                                    message.setText(
                                                            result
                                                                            instanceof
                                                                            IllegalArgumentException
                                                                    ? result.getMessage()
                                                                    : "Não foi possível abrir o"
                                                                            + " cofre. Confira a"
                                                                            + " senha; o arquivo"
                                                                            + " também pode estar"
                                                                            + " danificado.");
                                                } else {
                                                    activity = System.nanoTime();
                                                    showVault();
                                                }
                                            });
                                });
                    });
            root.getChildren()
                    .addAll(
                            heading,
                            new Label(
                                    "Os dados ficam criptografados neste computador. Guarde a senha"
                                            + " mestra: não há recuperação."),
                            master);
            if (creating)
                root.getChildren()
                        .addAll(confirmation, new Label("Senha mestra: pelo menos 12 caracteres."));
            Button restore = new Button("Restaurar backup");
            restore.setOnAction(
                    event -> {
                        if (!busy) showRestore();
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
        heading.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");
        Button block = new Button("Bloquear agora");
        block.setOnAction(e -> lock());
        search.setPromptText("Pesquisar título, usuário ou site");
        // Os controles são reutilizados após desbloquear; não acumula listeners a cada sessão.
        if (!listenersBound) {
            search.textProperty().addListener((o, a, b) -> refresh());
            items.getSelectionModel()
                    .selectedItemProperty()
                    .addListener(
                            (o, a, b) -> {
                                if (b == null) return;
                                editing = b.id();
                                var c = b.credential();
                                title.setText(c.title());
                                username.setText(c.username());
                                password.setText(c.password());
                                origins.setText(String.join(", ", c.allowedOrigins()));
                                notes.setText(c.notes());
                            });
            listenersBound = true;
        }
        title.setPromptText("Título");
        username.setPromptText("Usuário / e-mail");
        password.setPromptText("Senha do site");
        origins.setPromptText("https://conta.exemplo.com (separe origens por vírgulas)");
        notes.setPromptText("Notas");
        notes.setPrefRowCount(4);
        Button save = new Button("Salvar");
        save.setOnAction(
                e -> {
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
                                        false,
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
                });
        Button fresh = new Button("Novo cadastro");
        fresh.setOnAction(
                e -> {
                    items.getSelectionModel().clearSelection();
                    clearForm();
                });
        Button delete = new Button("Excluir selecionada");
        delete.setOnAction(
                e -> {
                    if (editing == null) return;
                    try {
                        vault.delete(editing);
                        clearForm();
                        refresh();
                        message.setText("Credencial excluída.");
                    } catch (Exception ex) {
                        message.setText("Não foi possível excluir.");
                    }
                });
        VBox left = new VBox(10, search, items);
        VBox.setVgrow(items, Priority.ALWAYS);
        left.setPrefWidth(330);
        VBox right =
                new VBox(
                        10,
                        new Label("Cadastro / edição"),
                        title,
                        username,
                        password,
                        origins,
                        notes,
                        new HBox(10, save, fresh, delete));
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox body = new HBox(20, left, right);
        VBox.setVgrow(body, Priority.ALWAYS);
        root.getChildren()
                .addAll(
                        new HBox(20, heading, block),
                        new Label(
                                "Bloqueio após 5 minutos, ao perder foco ou ao detectar interrupção"
                                        + " da sessão."),
                        body,
                        message);
        TextField backupPath = new TextField();
        backupPath.setPromptText("Caminho completo do arquivo de backup (pasta + nome do arquivo)");
        HBox.setHgrow(backupPath, Priority.ALWAYS);
        Button export = new Button("Salvar backup");
        export.setOnAction(
                event -> {
                    if (busy) return;
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
                    if (!busy) showRestore();
                });
        root.getChildren().add(new HBox(10, backupPath, export, restore));
        refresh();
    }

    /**
     * A restauração começa com a sessão bloqueada e exige a senha do backup. A confirmação fica na
     * própria tela, evitando diálogos que seriam interrompidos pelo bloqueio por foco.
     */
    private void showRestore() {
        lock();
        root.getChildren().clear();
        Label heading = new Label("Restaurar backup");
        heading.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");
        TextField sourcePath = new TextField();
        sourcePath.setPromptText("Caminho completo do arquivo .cofre");
        PasswordField backupPassword = new PasswordField();
        backupPassword.setPromptText("Senha mestra usada no backup");
        CheckBox replace =
                new CheckBox("Confirmo a substituição de todos os dados do cofre atual.");
        Button submit = new Button("Validar e restaurar");
        submit.setOnAction(
                event -> {
                    if (busy) return;
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
                    if (!busy) showLocked();
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
                        new HBox(10, submit, back),
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
                                if (restoring) showLocked();
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
        if (!vault.unlocked()) return;
        try {
            String term = search.getText().toLowerCase(Locale.ROOT);
            items.getItems()
                    .setAll(
                            vault.list().stream()
                                    .filter(
                                            e ->
                                                    (e.credential().title()
                                                                    + " "
                                                                    + e.credential().username()
                                                                    + " "
                                                                    + String.join(
                                                                            " ",
                                                                            e.credential()
                                                                                    .allowedOrigins()))
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains(term))
                                    .toList());
        } catch (Exception e) {
            lock();
            message.setText("Falha de integridade ou leitura. O cofre foi bloqueado.");
        }
    }

    private void clearForm() {
        editing = null;
        title.clear();
        username.clear();
        password.clear();
        origins.clear();
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
        generation++;
        timer.stop();
        worker.shutdownNow();
        vault.close();
        clearSensitive();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
