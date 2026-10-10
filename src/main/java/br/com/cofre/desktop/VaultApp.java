package br.com.cofre.desktop;

import br.com.cofre.application.VaultService;
import br.com.cofre.domain.Credential;
import br.com.cofre.domain.VaultEntry;
import br.com.cofre.ipc.StatusServer;
import br.com.cofre.nativehost.FillProtocol;
import br.com.cofre.nativehost.StatusProtocol;
import br.com.cofre.nativehost.UnlockProtocol;
import br.com.cofre.nativehost.SaveAccountProtocol;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class VaultApp extends Application {
    private static final int ROOT_SPACING_PIXELS = 14;
    private static final int CONTROL_SPACING_PIXELS = 10;
    private static final int LABEL_SPACING_PIXELS = 6;
    private static final int SECTION_SPACING_PIXELS = 20;
    private static final int WINDOW_PADDING_PIXELS = 24;
    private static final int WINDOW_WIDTH_PIXELS = 1100;
    private static final int WINDOW_HEIGHT_PIXELS = 820;
    private static final int ACCOUNT_CARD_WIDTH_PIXELS = 300;
    private static final int EDITOR_WIDTH_PIXELS = 720;
    private static final int NOTES_VISIBLE_ROWS = 4;
    private static final int ACCESS_CARD_WIDTH_PIXELS = 480;
    private static final int MIN_WINDOW_WIDTH_PIXELS = 960;
    private static final int MIN_WINDOW_HEIGHT_PIXELS = 740;

    private VaultService vault;
    private StatusServer statusServer;
    private final DesktopTray desktopTray;
    private WindowLifecycle windowLifecycle;
    private final VBox root = new VBox(ROOT_SPACING_PIXELS);
    private final Label message = new Label();
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    operation -> {
                        Thread workerThread = new Thread(operation, "vault-kdf");
                        workerThread.setDaemon(true);
                        return workerThread;
                    });
    private int generation;
    private boolean busy;
    private boolean listenersBound;
    private boolean stopped;
    private CompletableFuture<Void> pendingExtensionUnlock;
    private final FlowPane accountCards = new FlowPane(SECTION_SPACING_PIXELS, SECTION_SPACING_PIXELS);
    private final TextField title = new TextField();
    private final TextField username = new TextField();
    private final TextField origins = new TextField();
    private final CheckBox autofill = new CheckBox("Permitir preenchimento automático nas origens cadastradas");
    private final TextField search = new TextField();
    private final TextField password = new TextField();
    private final TextArea notes = new TextArea();
    private String editing;

    public VaultApp() {
        this(new AwtDesktopTray());
    }

    VaultApp(DesktopTray desktopTray) {
        this.desktopTray = Objects.requireNonNull(desktopTray);
    }

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
                                new UnlockProtocol(this::unlockFromExtension),
                                new SaveAccountProtocol(vault, () -> Platform.runLater(() -> {
                                    if (!stopped && vault.unlocked()) {
                                        refresh();
                                    }
                                }))));
        root.setPadding(new Insets(WINDOW_PADDING_PIXELS));
        root.getStyleClass().add("app-root");
        message.getStyleClass().add("status-message");
        message.setWrapText(true);
        message.managedProperty().bind(message.textProperty().isNotEmpty());
        Scene scene = new Scene(root, WINDOW_WIDTH_PIXELS, WINDOW_HEIGHT_PIXELS);
        scene.getStylesheets().add(Objects.requireNonNull(
                VaultApp.class.getResource("vault.css"), "Tema da interface ausente").toExternalForm());
        stage.setMinWidth(MIN_WINDOW_WIDTH_PIXELS);
        stage.setMinHeight(MIN_WINDOW_HEIGHT_PIXELS);
        stage.setTitle("Cofre local");
        stage.setScene(scene);
        configureWindowLifecycle(stage);
        showLocked();
        stage.show();
    }

    private void configureWindowLifecycle(Stage stage) {
        windowLifecycle = new WindowLifecycle(desktopTray, new WindowLifecycle.Window() {
            @Override
            public void hide() {
                Platform.setImplicitExit(false);
                stage.hide();
            }

            @Override
            public void minimize() {
                stage.setIconified(true);
                message.setText("Bandeja indisponível neste ambiente. O aplicativo permanece na barra de tarefas.");
            }

            @Override
            public void restore() {
                if (!stopped) {
                    stage.setIconified(false);
                    stage.show();
                    stage.toFront();
                    stage.requestFocus();
                }
            }

            @Override
            public void exit() { Platform.exit(); }
        });
        stage.setOnCloseRequest(event -> {
            if (!stopped) {
                event.consume();
                windowLifecycle.requestClose(askCloseChoice(stage));
            }
        });
    }

    private WindowLifecycle.CloseChoice askCloseChoice(Stage stage) {
        ButtonType keepRunning = new ButtonType("Manter em segundo plano", ButtonBar.ButtonData.YES);
        ButtonType exit = new ButtonType("Encerrar aplicativo", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                "Deseja manter o aplicativo na bandeja do sistema, perto do relógio?\n"
                        + "A sessão atual será mantida. Se a bandeja não estiver disponível, a janela será minimizada.",
                keepRunning, exit, cancel);
        dialog.initOwner(stage);
        dialog.setTitle("Fechar Cofre local");
        dialog.setHeaderText("Manter o aplicativo em segundo plano?");
        dialog.getDialogPane().getStylesheets().addAll(stage.getScene().getStylesheets());
        ButtonType selected = dialog.showAndWait().orElse(cancel);
        if (selected == keepRunning) {
            return WindowLifecycle.CloseChoice.KEEP_RUNNING;
        }
        if (selected == exit) {
            return WindowLifecycle.CloseChoice.EXIT;
        }
        return WindowLifecycle.CloseChoice.CANCEL;
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
            heading.getStyleClass().add("page-heading");
            PasswordField master = new PasswordField();
            master.setPromptText("Senha mestra");
            PasswordField confirmation = new PasswordField();
            confirmation.setPromptText("Confirme a senha mestra");
            Button submit = new Button(creating ? "Criar cofre" : "Desbloquear");
            submit.getStyleClass().add("primary-button");
            submit.setMaxWidth(Double.MAX_VALUE);
            submit.setDefaultButton(true);
            submit.setOnAction(event -> authenticate(creating, master, confirmation, submit));
            root.getChildren()
                    .addAll(
                            heading,
                            new Label(
                                    "Os dados ficam criptografados neste computador. Guarde a senha"
                                            + " mestra: não há recuperação."),
                            labeledField("Senha mestra", master));
            if (creating) {
                root.getChildren()
                        .addAll(
                                labeledField("Confirmar senha mestra", confirmation),
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
            if (!creating) {
                Button reset = new Button("Excluir cofre e começar do zero");
                reset.getStyleClass().add("danger-button");
                reset.setMaxWidth(Double.MAX_VALUE);
                reset.setOnAction(event -> confirmVaultDeletion());
                root.getChildren().add(root.getChildren().indexOf(message), reset);
            }
            centerAccessCard();
        } catch (Exception e) {
            message.setText("Não foi possível acessar o arquivo do cofre.");
            root.getChildren().add(message);
        }
    }

    private void confirmVaultDeletion() {
        if (busy) {
            return;
        }
        ButtonType delete = new ButtonType("Excluir definitivamente", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.WARNING, "", delete, cancel);
        dialog.initOwner(root.getScene().getWindow());
        dialog.setTitle("Excluir cofre");
        dialog.setHeaderText("Excluir todas as contas e a senha mestra?");
        TextField confirmation = new TextField();
        confirmation.setPromptText("Digite EXCLUIR");
        Label warning = new Label("Esta ação exclui o banco atual e não pode ser desfeita. "
                + "Os arquivos de backup existentes serão preservados. Digite EXCLUIR para confirmar.");
        warning.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(CONTROL_SPACING_PIXELS,
                warning, labeledField("Confirmação da exclusão", confirmation)));
        dialog.getDialogPane().getStylesheets().addAll(root.getScene().getStylesheets());
        Button deleteButton = (Button) dialog.getDialogPane().lookupButton(delete);
        deleteButton.getStyleClass().add("danger-button");
        deleteButton.setDefaultButton(false);
        deleteButton.disableProperty().bind(confirmation.textProperty().isNotEqualTo("EXCLUIR"));
        if (dialog.showAndWait().orElse(cancel) == delete && !busy) {
            deleteVault();
        }
    }

    /** Executa a exclusão confirmada fora da thread JavaFX e retorna à criação do cofre. */
    private void deleteVault() {
        generation++;
        busy = true;
        clearSensitive();
        message.setText("Excluindo cofre…");
        worker.submit(() -> {
            Exception failure = null;
            try {
                vault.deleteVault();
            } catch (Exception exception) {
                failure = exception;
            }
            Exception result = failure;
            Platform.runLater(() -> {
                busy = false;
                if (stopped) {
                    return;
                }
                showLocked();
                message.setText(result == null
                        ? "Cofre excluído. Crie uma nova senha mestra para começar do zero."
                        : "Não foi possível concluir a exclusão do cofre. Confira o arquivo antes de tentar novamente.");
            });
        });
    }

    private void showVault() {
        clearForm();
        root.getChildren().clear();
        message.setText("");
        Label heading = new Label("Minhas credenciais");
        heading.getStyleClass().add("page-heading");
        Button block = new Button("Bloquear agora");
        block.setOnAction(event -> lock());
        Button add = new Button("Adicionar conta");
        add.getStyleClass().add("primary-button");
        add.setOnAction(event -> {
            clearForm();
            showCredentialForm();
        });
        search.setPromptText("Pesquisar título, usuário ou site");
        bindCredentialListeners();
        HBox toolbar = new HBox(CONTROL_SPACING_PIXELS, search, add);
        HBox.setHgrow(search, Priority.ALWAYS);
        ScrollPane cardsScroll = new ScrollPane(accountCards);
        cardsScroll.setFitToWidth(true);
        cardsScroll.getStyleClass().add("cards-scroll");
        VBox.setVgrow(cardsScroll, Priority.ALWAYS);
        root.getChildren().addAll(createHeader(heading, block),
                new Label("Selecione uma conta para editar ou adicione um novo cadastro."),
                toolbar, cardsScroll, message, createBackupControls());
        refresh();
    }

    private void bindCredentialListeners() {
        if (!listenersBound) {
            search.textProperty().addListener((observable, previousText, currentText) -> refresh());
            listenersBound = true;
        }
    }

    private void showCredentialForm() {
        root.getChildren().clear();
        accountCards.getChildren().clear();
        message.setText("");
        Label heading = new Label(editing == null ? "Adicionar conta" : "Editar conta");
        heading.getStyleClass().add("page-heading");
        Button block = new Button("Bloquear agora");
        block.setOnAction(event -> lock());
        Button back = new Button("Voltar para contas");
        back.setOnAction(event -> showVault());
        VBox editor = createCredentialEditor();
        editor.setMaxWidth(EDITOR_WIDTH_PIXELS);
        StackPane centered = new StackPane(editor);
        centered.setAlignment(Pos.TOP_CENTER);
        ScrollPane editorScroll = new ScrollPane(centered);
        editorScroll.setFitToWidth(true);
        editorScroll.getStyleClass().add("cards-scroll");
        VBox.setVgrow(editorScroll, Priority.ALWAYS);
        root.getChildren().addAll(createHeader(heading, block), back, editorScroll, message);
    }

    private void editCredential(VaultEntry entry) {
        editing = entry.id();
        Credential credential = entry.credential();
        title.setText(credential.title());
        username.setText(credential.username());
        password.setText(credential.password());
        origins.setText(String.join(", ", credential.allowedOrigins()));
        notes.setText(credential.notes());
        autofill.setSelected(credential.autofillEnabled());
        showCredentialForm();
    }

    private VBox createAccountCard(VaultEntry entry) {
        Credential credential = entry.credential();
        Label accountTitle = new Label(credential.title());
        accountTitle.getStyleClass().add("section-heading");
        accountTitle.setWrapText(true);
        Label accountUsername = new Label(credential.username());
        accountUsername.setWrapText(true);
        Label accountPassword = new Label("Senha oculta");
        accountPassword.setWrapText(true);
        Button showPassword = new Button("Exibir senha");
        showPassword.setOnAction(event -> {
            boolean revealing = "Exibir senha".equals(showPassword.getText());
            accountPassword.setText(revealing ? credential.password() : "Senha oculta");
            showPassword.setText(revealing ? "Ocultar senha" : "Exibir senha");
        });
        Button copyPassword = new Button("Copiar senha");
        copyPassword.setOnAction(event -> copyPassword(credential.password()));
        HBox passwordActions = new HBox(CONTROL_SPACING_PIXELS, showPassword, copyPassword);
        Label sites = new Label(credential.allowedOrigins().isEmpty()
                ? "Nenhum site cadastrado" : String.join("\n", credential.allowedOrigins()));
        sites.getStyleClass().add("muted-label");
        sites.setWrapText(true);
        Button edit = new Button("Editar conta");
        edit.setMaxWidth(Double.MAX_VALUE);
        edit.setOnAction(event -> editCredential(entry));
        VBox card = new VBox(CONTROL_SPACING_PIXELS, accountTitle, accountUsername,
                labeledField("Senha", accountPassword), passwordActions, sites, edit);
        card.getStyleClass().addAll("card", "account-card");
        card.setPrefWidth(ACCOUNT_CARD_WIDTH_PIXELS);
        return card;
    }

    private VBox createCredentialEditor() {
        title.setPromptText("Título");
        username.setPromptText("Usuário / e-mail");
        password.setPromptText("Senha do site");
        Button generatePassword = new Button("Gerar senha");
        var passwordGenerator = new br.com.cofre.application.PasswordGenerator();
        generatePassword.setOnAction(event -> password.setText(passwordGenerator.generate()));
        origins.setPromptText("https://conta.exemplo.com (separe origens por vírgulas)");
        notes.setPromptText("Notas");
        notes.setPrefRowCount(NOTES_VISIBLE_ROWS);
        Button save = new Button("Salvar");
        save.setOnAction(event -> saveCredential());
        Button fresh = new Button("Novo cadastro");
        fresh.setOnAction(
                e -> {
                    clearForm();
                    showCredentialForm();
                });
        Button delete = new Button("Excluir selecionada");
        delete.setOnAction(event -> deleteCredential());
        delete.setDisable(editing == null);
        VBox editor =
                new VBox(
                        CONTROL_SPACING_PIXELS,
                        new Label("Cadastro / edição"),
                        labeledField("Título", title),
                        labeledField("Usuário / e-mail", username),
                        labeledField("Senha", passwordControls(password, generatePassword)),
                        labeledField("Sites permitidos", origins),
                        autofill,
                        labeledField("Notas", notes),
                        new HBox(CONTROL_SPACING_PIXELS, save, fresh, delete));
        editor.getStyleClass().add("card");
        save.getStyleClass().add("primary-button");
        delete.getStyleClass().add("danger-button");
        autofill.setWrapText(true);
        notes.setWrapText(true);
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
            showVault();
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
            showVault();
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
        HBox controls = new HBox(CONTROL_SPACING_PIXELS, new Label("Backup"), backupPath, export, restore);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().add("backup-bar");
        return controls;
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
        heading.getStyleClass().add("page-heading");
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
                        labeledField("Arquivo de backup", sourcePath),
                        labeledField("Senha mestra do backup", backupPassword),
                        replace,
                        new HBox(CONTROL_SPACING_PIXELS, submit, back),
                        message);
        submit.getStyleClass().add("primary-button");
        replace.setWrapText(true);
        centerAccessCard();
    }

    private VBox labeledField(String text, Node control) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        label.setLabelFor(control);
        return new VBox(LABEL_SPACING_PIXELS, label, control);
    }

    private void copyPassword(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        boolean copied = Clipboard.getSystemClipboard().setContent(content);
        message.setText(copied ? "Senha copiada." : "Não foi possível copiar a senha.");
    }

    private HBox passwordControls(TextField field, Button generate) {
        Button copy = new Button("Copiar senha");
        copy.disableProperty().bind(field.textProperty().isEmpty());
        copy.setOnAction(event -> copyPassword(field.getText()));
        HBox controls = new HBox(CONTROL_SPACING_PIXELS, field, generate, copy);
        HBox.setHgrow(field, Priority.ALWAYS);
        return controls;
    }

    private HBox createHeader(Label heading, Button lockButton) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label brand = new Label("COFRE LOCAL");
        brand.getStyleClass().add("brand-label");
        VBox identity = new VBox(LABEL_SPACING_PIXELS, brand, heading);
        HBox header = new HBox(SECTION_SPACING_PIXELS, identity, spacer, lockButton);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    /** Centraliza as telas de acesso; mensagens longas continuam dentro do cartão. */
    private void centerAccessCard() {
        VBox card = new VBox(ROOT_SPACING_PIXELS);
        card.getStyleClass().add("access-card");
        card.setMaxWidth(ACCESS_CARD_WIDTH_PIXELS);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        List<Node> content = new ArrayList<>(root.getChildren());
        root.getChildren().clear();
        Label brand = new Label("COFRE LOCAL");
        brand.getStyleClass().add("brand-label");
        card.getChildren().add(brand);
        card.getChildren().addAll(content);
        for (Node node : content) {
            if (node instanceof Label label) {
                label.setWrapText(true);
            }
        }
        StackPane centered = new StackPane(card);
        VBox.setVgrow(centered, Priority.ALWAYS);
        root.getChildren().add(centered);
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
     * remove os resultados da tela e informa o erro, preservando a escolha de sessão do usuário.
     */
    private void refresh() {
        if (!vault.unlocked()) {
            return;
        }
        try {
            String term = search.getText().toLowerCase(Locale.ROOT);
            List<VaultEntry> matches = vault.list().stream()
                    .filter(entry -> matchesSearch(entry.credential(), term))
                    .toList();
            accountCards.getChildren().clear();
            if (matches.isEmpty()) {
                Label empty = new Label("Nenhuma credencial encontrada.");
                empty.getStyleClass().add("muted-label");
                accountCards.getChildren().add(empty);
            } else {
                for (VaultEntry entry : matches) {
                    accountCards.getChildren().add(createAccountCard(entry));
                }
            }
        } catch (Exception e) {
            accountCards.getChildren().clear();
            clearForm();
            message.setText("Falha de integridade ou leitura. Os dados não puderam ser carregados.");
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
        accountCards.getChildren().clear();
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
        worker.shutdownNow();
        vault.close();
        if (pendingExtensionUnlock != null) {
            // close espera a operação criptográfica antes de liberar o array ao protocolo.
            pendingExtensionUnlock.completeExceptionally(new IllegalStateException("Aplicativo fechado."));
            pendingExtensionUnlock = null;
        }
        clearSensitive();
        if (windowLifecycle != null) {
            windowLifecycle.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
