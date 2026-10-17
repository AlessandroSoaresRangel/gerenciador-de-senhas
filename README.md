# Cofre local

## Instalador Windows

Para gerar um instalador que inclui desktop, Native Host e Java, instale o JDK
21 ou superior, Maven e Inno Setup 6 na máquina de build. Execute na raiz:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-installer.ps1
```

O build executa os testes Java e gera `target/installer-output/CofreLocal-Setup.exe`.
Esse arquivo pode ser distribuído sozinho: o destinatário não precisa de JDK,
Maven ou Node.js. O instalador Windows x64 instala por usuário, cria atalhos e
solicita o ID de 32 letras exibido em `chrome://extensions`. Para testar antes
da publicação, carregue a extensão sem compactação e informe seu ID.
A extensão ainda precisa ser instalada separadamente no Chrome.

Para preencher o ID da versão publicada por padrão:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-installer.ps1 -ExtensionId SEU_ID
```

O parâmetro é validado antes do build. Se necessário, informe `-CompilerPath`
com o caminho de `ISCC.exe`. Builds sucessivos usam pastas de preparação novas
e sobrescrevem o instalador de saída. Feche o desktop e o Chrome antes de
atualizar uma instalação. A desinstalação preserva `.cofre-local` e backups;
remove o registro do host somente se ele ainda pertence a essa instalação.
O Native Host encaminha os pedidos ao desktop já aberto, sem abrir outro banco.
O instalador gerado não possui assinatura digital.

Teste da configuração do build:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\tests\installer-config.test.ps1
```

Aplicativo desktop Java/JavaFX: criação e desbloqueio com senha mestra, cadastro, edição, pesquisa, exclusão, bloqueio, backup e restauração do cofre. A extensão Chrome consulta a sessão e mostra as contas correspondentes ao site aberto pelo host Native Messaging. Implementa os itens 1 a 6 da ordem de implementação, incluindo preenchimento de login mediante clique e automático autorizado por site.

## Executar

Requisitos: JDK 21 ou superior e Maven 3.9 ou superior.

```powershell
mvn clean test
mvn javafx:run
```

Os testes espelham os pacotes do código em `src/test/java/br/com/cofre`: `domain`, `crypto`, `application`, `backup`, `storage`, `desktop`, `nativehost` e `ipc`. O teste de integração `VaultAppTest` precisa de uma sessão gráfica: abre e fecha uma janela temporária e usa um banco de teste, sem acessar seu cofre pessoal. Para executar somente os testes que não usam interface:

```powershell
mvn test "-Dtest=*Test,!VaultAppTest"
```

Na primeira execução, crie a senha mestra com 12 a 4.096 caracteres. Cofres antigos com senhas maiores continuam acessíveis pelo desktop; o desbloqueio pela extensão aceita até 4.096 caracteres. O SQLite fica em `%USERPROFILE%\.cofre-local\vault.db`. Para usar outro arquivo:

```powershell
mvn javafx:run "-Djavafx.options=-Dvault.path=C:/caminho/cofre.db"
```

Cadastre título, usuário, senha, origens HTTPS (opcionais, separadas por vírgula) e notas. Selecione uma credencial para editá-la ou excluí-la. A pesquisa funciona somente com o cofre desbloqueado.

No cadastro, clique em **Gerar senha** para preencher o campo com uma senha aleatória de 16 caracteres, incluindo maiúsculas, minúsculas, números e símbolos. A geração usa `SecureRandom`; clique em **Salvar** para gravar a credencial no cofre. O formulário permite visualizar e copiar a senha. Nos cards, use **Exibir senha** e **Ocultar senha** para alternar a visualização; **Copiar senha** funciona mesmo com a senha oculta.

Na tela da senha mestra, **Excluir cofre e começar do zero** permite apagar o banco atual sem conhecer a senha antiga. Digite **EXCLUIR** e confirme **Excluir definitivamente**. Todas as contas e a senha mestra antiga são removidas; o aplicativo prepara um banco vazio e abre a criação de uma nova senha mestra. Os arquivos de backup existentes são preservados. Cancelar a confirmação mantém o cofre atual.

Ao clicar no botão de fechar a janela, escolha **Manter em segundo plano**, **Encerrar aplicativo** ou **Cancelar**. Manter em segundo plano preserva a sessão e coloca um ícone na bandeja do sistema, perto do relógio. Use o menu do ícone para **Abrir Cofre local** ou **Encerrar aplicativo**; a ação padrão do ícone também reabre a janela. O aplicativo continua disponível para a extensão com o estado atual do cofre.

A integração usa a bandeja do sistema disponível no Windows e em ambientes Linux compatíveis com `java.awt.SystemTray`. O suporte no Linux depende do ambiente gráfico e de sua configuração de bandeja. Se o sistema não oferecer a bandeja ou recusar a criação do ícone, a janela permanece minimizada na barra de tarefas, permitindo reabri-la normalmente.

## Extensão e Native Messaging — Windows/Chrome

Requisitos adicionais: Node.js 22+, npm e um JDK com `jpackage`. A instalação do host é por usuário, sem privilégios de administrador.

1. Na pasta `browser-extension`, execute `npm.cmd ci` e `npm.cmd test`. Isso compila o TypeScript para `dist` e executa os testes da extensão.
2. Abra `chrome://extensions`, ative o modo do desenvolvedor, clique em **Carregar sem compactação** e selecione a pasta `browser-extension` inteira. Copie o ID exibido pelo Chrome.
3. Copie `.env.example` para `.env` na raiz do projeto e preencha `CHROME_EXTENSION_ID` com esse ID:

```dotenv
CHROME_EXTENSION_ID=SEU_ID_DE_32_LETRAS
```

Depois execute o instalador, sem precisar repetir o ID:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-native-host.ps1
```

O instalador compila o host, gera um executável com runtime Java em `%LOCALAPPDATA%\CofreLocal` e registra seu manifesto em `HKCU\Software\Google\Chrome\NativeMessagingHosts\br.com.cofre.local`. Somente o ID configurado pode conectá-lo. O `.env` e ignorado pelo Git, enquanto `.env.example` fica versionado. A opcao `-ExtensionId ID` continua disponivel e tem prioridade sobre o arquivo. Configuracoes ausentes, duplicadas ou invalidas sao recusadas antes do build. Reexecute o instalador após alterar o host ou se o ID da extensão mudar. O parâmetro de política de execução vale apenas para esse processo.

4. Inicie o desktop com `mvn javafx:run`. Abra o popup da extensão e clique em **Atualizar**. Ele distingue cofre bloqueado, desbloqueado, ainda não criado, aplicativo fechado e falha de leitura. Se o host não estiver instalado, mostra conexão indisponível.

Com o cofre desbloqueado e um site HTTPS aberto, o popup lista título e usuário das contas que tenham aquela origem cadastrada. Por exemplo, para a página `https://conta.exemplo.com/login`, cadastre `https://conta.exemplo.com` na credencial. Subdomínios diferentes, portas não padrão e domínios semelhantes não correspondem. HTTP e páginas internas do navegador são recusados.

A consulta usa `LIST_MATCHES` e devolve somente ID, título e usuário; não envia senha, notas ou todas as origens cadastradas. A origem vem da API de abas do navegador; a permissão `tabs` permite identificar o site nas consultas automáticas. A permissão `activeTab` continua sendo usada para preencher mediante clique. Se a aba navegar durante a consulta, a resposta é descartada. A extensão consulta as contas automaticamente ao concluir uma navegação HTTPS na aba ativa, incluindo recarregamentos. Ao ativar uma aba ainda não consultada, faz a consulta. Eventos repetidos da mesma visita não repetem a consulta enquanto o service worker permanece ativo; reiniciar o worker reinicia esse controle. O indicador no ícone mostra a quantidade de contas encontradas e é limpo na próxima navegação ou em caso de falha. O aplicativo precisa estar aberto e o cofre desbloqueado. O popup também consulta ao abrir e ao clicar em Atualizar, sem consultas periódicas. Os resultados refletem a última consulta; alterações na sessão são exibidas na próxima atualização. Se a consulta informar bloqueio, aplicativo fechado ou falha, a lista é removida. O botão **Preencher** de cada conta insere usuário e senha somente após seu clique. O envio do formulário fica a seu cargo.

O preenchimento aceita um único formulário de login visível no documento principal, com um campo de usuário e um de senha. HTTP, iframes, formulários ambíguos, campos ocultos, campos `new-password` e formulários que enviam para outra origem são recusados. Formulários sem elemento `form`, Shadow DOM e login em múltiplas etapas ainda não são suportados. A permissão `scripting` executa a inspeção e o preenchimento no mundo isolado, usando o acesso temporário de `activeTab`.

A liberação é vinculada à conta, origem e documento, com autorização de uso único válida por 10 segundos. Bloqueio, edição e exclusão invalidam autorizações pendentes. A extensão verifica novamente a aba e as referências do formulário antes de inserir os dados, sem salvar a senha nem devolvê-la ao popup. Uma senha já entregue ao documento não pode ser retirada pelo bloqueio posterior; após o preenchimento, o código do próprio site pode ler os campos.

Ao atualizar de uma etapa anterior: execute novamente `npm.cmd test` na pasta da extensão, recarregue a extensão em `chrome://extensions`, reexecute o instalador do host com o mesmo ID e reinicie o desktop. O executável anteriormente instalado não aceita o novo comando até ser atualizado.

O cofre não tem bloqueio por inatividade, suspensão, minimização ou envio à bandeja. A sessão permanece no estado escolhido pelo usuário; use **Bloquear agora** para bloqueá-la.

### Oferecer cadastro após enviar um login

Ao enviar um formulário HTTPS com usuário e senha, a extensão consulta as contas da origem exata do site. Se o usuário ainda não estiver cadastrado, abre uma janela privada **Salvar esta conta?**, com o site, usuário, nome editável e as opções **Salvar no cofre** e **Agora não**. O envio do formulário continua normalmente; essa detecção não confirma se o login foi aceito pelo site.

O aplicativo deve estar aberto e o cofre desbloqueado para oferecer e gravar o cadastro. Ao salvar com sucesso, a janela fecha automaticamente. Se não houver outra conta para a origem exata do site, o cadastro habilita o preenchimento automático no cofre e a extensão seleciona essa conta para o site, respeitando a permissão concedida pelo navegador. Se houver outra conta, o novo cadastro não habilita o automático e preserva a seleção existente. A verificação é feita novamente no desktop durante a gravação; o cadastro não sobrescreve um usuário já cadastrado para essa origem. Se a gravação falhar, a janela permanece aberta para uma nova tentativa. O rascunho permanece temporariamente em memória, expira em dois minutos e é descartado ao cancelar ou fechar a janela. A senha não vai para `storage`, URL, logs ou elementos visíveis da página; somente a janela privada de confirmação recebe o rascunho antes de encaminhar o cadastro confirmado ao desktop.

O content script é executado no frame principal das páginas HTTPS e observa o evento de envio do formulário. Formulários com múltiplas senhas, `new-password`, campos ocultos ou ação em outra origem são ignorados. Logins em várias etapas, iframes e páginas que não enviam um formulário HTML não estão cobertos por essa detecção. Recarregue a extensão e as páginas abertas após atualizar. No Windows, execute novamente `scripts/install-native-host.ps1` para atualizar o host com o comando de cadastro, e reinicie o desktop.

### Desbloquear pela extensão

Com o aplicativo desktop aberto e o cofre bloqueado, abra o popup, digite a senha mestra e clique em **Desbloquear cofre**. A extensão desbloqueia a mesma sessão usada pelo desktop, atualiza a tela do aplicativo e consulta novamente as contas do site.

Se o desktop estiver fechado, a extensão pede para abri-lo; o host não abre o banco nem inicia outra sessão. A criação do cofre continua no desktop. Uma senha incorreta mantém o cofre bloqueado. Operações desktop em curso recusam o pedido, e um bloqueio durante o processamento invalida o desbloqueio pendente.

A senha mestra é enviada apenas pelo popup próprio, pelo host e pela ponte local autenticada até o desktop. O campo é limpo no envio; a senha não é persistida, incluída nas respostas ou registrada em logs. Strings e cópias internas de JavaScript/JVM não permitem garantir apagamento imediato da memória. Para aplicar esta atualização, recarregue a extensão, reexecute o instalador do host e reinicie o desktop.

O host não abre outra sessão SQLite. Ele consulta a sessão desktop por loopback autenticado, com token aleatório em um arquivo restrito ao dono por ACL do Windows (ou permissões POSIX). O arquivo de descoberta é apagado ao fechar o desktop, e um lock impede duas sessões na mesma pasta. Não há servidor acessível pela rede externa nem senhas persistidas na extensão. O protocolo aceita `GET_STATUS`, `UNLOCK_VAULT`, `LIST_MATCHES`, `REQUEST_FILL`, `REQUEST_AUTOMATIC_FILL` e `CONFIRM_FILL`, com versão e ID de correlação; mensagens inválidas são rejeitadas e os frames são limitados a 64 KiB.

### Preenchimento automático por site

1. No desktop, edite a conta, marque **Permitir preenchimento automático nas origens cadastradas** e salve.
2. Na extensão, clique em **Usar automaticamente neste site** na conta desejada e conceda o acesso solicitado pelo Chrome.
3. Com o cofre desbloqueado, o login será preenchido quando a aba ativa terminar de carregar ou for ativada. O formulário nunca é enviado automaticamente.

A extensão guarda somente a origem exata e o identificador da conta escolhida. Há uma conta automática por origem; escolher outra substitui a anterior. A permissão opcional usa o host HTTPS conforme a [API de permissões do Chrome](https://developer.chrome.com/docs/extensions/reference/api/permissions); a regra do cofre e a escolha da extensão distinguem também subdomínios e portas. O automático preserva campos já preenchidos e reutiliza as verificações de documento, formulário, expiração e bloqueio do preenchimento por clique.

Use **Desativar automático neste site** para apagar a escolha, ou desmarque a opção no desktop para impedir novas autorizações daquela credencial. Revogar o acesso ao site nas configurações do Chrome também impede o automático. Formulários inseridos depois do carregamento podem ser preenchidos por clique ou ao reativar a aba. Depois de atualizar a extensão, recarregue-a em `chrome://extensions` para aplicar as novas permissões.

O caminho de descoberta padrão é `%USERPROFILE%\.cofre-local\ipc\endpoint.json`. A extensão instalada consulta somente o cofre padrão; cofres iniciados com `vault.path` personalizado mantêm uma descoberta separada para testes. O instalador desta etapa é específico para Chrome no Windows.

Para remover a integração, exclua somente a chave de registro `HKCU\Software\Google\Chrome\NativeMessagingHosts\br.com.cofre.local` e a instalação correspondente em `%LOCALAPPDATA%\CofreLocal`; o cofre fica em outra pasta. Remova também a extensão em `chrome://extensions`.

Referência do protocolo e registro: [Native Messaging do Chrome](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging).

## Backup e restauração

Com o cofre desbloqueado, informe o caminho completo de um novo arquivo (por exemplo `C:/Users/seu-usuario/Documents/cofre.cofre`) e clique em **Salvar backup**. A pasta precisa existir. Arquivos existentes nunca são sobrescritos.

Clique em **Restaurar backup**, disponível também na tela bloqueada, informe o caminho do arquivo e a senha mestra usada quando ele foi criado. Confirme a substituição e clique em **Validar e restaurar**. A restauração substitui todas as credenciais e a senha mestra do cofre atual pelas do backup; não mescla os dados. Faça uma cópia do cofre atual antes de restaurar.

O backup tem formato identificado e versionado, com cabeçalho para recuperar a chave e um conteúdo completo protegido por AES-GCM. Senha incorreta, conteúdo adulterado, arquivo truncado e versão desconhecida são recusados antes de alterar o banco. Falhas na gravação da restauração desfazem a transação. Ao terminar, desbloqueie o cofre com a senha do backup.

O limite de arquivo é 64 MiB. A exportação publica somente o arquivo completo e limpa o temporário criptografado; uma interrupção abrupta do processo pode deixar um temporário na pasta. Backup e restauração são executados em segundo plano. Uma restauração já confirmada continua mesmo se a janela perder foco e termina com o cofre bloqueado.

## Proteção e limites

- Argon2id v1.3: 64 MiB, 3 iterações e paralelismo 1, salt aleatório de 16 bytes.
- Chave aleatória de 256 bits, protegida pela chave derivada da senha mestra. AES-256-GCM com nonce aleatório de 96 bits em cada gravação e autenticação do ID/versão do registro.
- Títulos, usuários, senhas, origens e notas ficam dentro do conteúdo criptografado; não há índice em texto aberto. A senha mestra não é persistida.
- Bloqueio por escolha do usuário em **Bloquear agora**. Encerrar o aplicativo apaga a chave da sessão; restaurar um backup exige desbloqueio com a senha do backup. Falhas de leitura ou integridade recusam os dados e preservam o estado da sessão.
- O bloqueio limpa a chave ativa e os campos/lista da interface. Strings e cópias internas da JVM não têm garantia de apagamento imediato da memória.
- Não há recuperação de senha mestra: sem a senha usada na criação do backup, ele não pode ser restaurado. O banco ainda expõe metadados como quantidade e tamanho dos registros; o backup expõe o cabeçalho criptográfico e o tamanho total.

O código é separado por responsabilidade, em um único projeto Maven:

| Pacote | Responsabilidade |
|---|---|
| `domain` | Credenciais, origens e identificadores dos itens |
| `crypto` | Argon2id, AES-GCM e conversão de credenciais em registros criptografados |
| `application` | Sessão, bloqueio e coordenação das operações do cofre |
| `backup` | Formato versionado, validação e leitura/escrita de backups |
| `storage` | Contrato `VaultRepository`, implementação SQLite e transações |
| `desktop` | Interface JavaFX |
| `nativehost` | Framing binário, validação de mensagens e processo iniciado pelo Chrome |
| `ipc` | Descoberta privada e consulta autenticada da sessão desktop |

`VaultService` recebe um `VaultRepository` por construtor e delega o backup ao `VaultBackupService`. O repositório recebe somente registros criptografados; não conhece a senha mestra nem o formato de backup. A interface continua usando o serviço para que o bloqueio seja sincronizado com as operações. O esquema SQLite e o formato de backup da versão 1 foram preservados.

Os testes cobrem persistência, senha incorreta, bloqueio, CRUD, ausência de campos em texto aberto, rejeição de adulteração, compatibilidade com backups da versão 1, substituição do cofre e rollback em falhas de gravação.

Referências de implementação: [OpenJFX/Maven](https://openjfx.io/openjfx-docs/#maven) e [Argon2 do Bouncy Castle](https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/generators/Argon2BytesGenerator.java).

Senhas copiadas pelo desktop são removidas da área de transferência no prazo configurado (30 segundos por padrão), ao bloquear ou ao encerrar o aplicativo, desde que o conteúdo ainda seja o copiado pelo cofre. Conteúdo substituído pelo usuário é preservado; históricos e cópias mantidas por outros aplicativos não são controlados pelo cofre.

O preenchimento lê e autentica apenas o registro selecionado pelo ID. Um registro corrompido continua sendo recusado, mas não impede preencher outra conta saudável. A listagem e a busca por origem ainda precisam descriptografar os registros, pois os metadados permanecem criptografados; essas operações rejeitam a lista inteira em caso de corrupção.

Na ponte local, endpoint ausente ou conexão recusada indicam `APP_CLOSED`; timeout indica `TIMEOUT`; endpoint ou resposta inválidos indicam `UNAVAILABLE`.

### Configurações do desktop

Clique no botão **⚙** para abrir as configurações, disponível tanto na tela de acesso quanto no cabeçalho das contas. Ajuste os tempos em segundos e clique em **Salvar configurações**. **Voltar** descarta alterações não salvas.

- **Bloqueio automático:** padrão de 300 segundos (5 minutos), de 1 a 86.400 segundos; use 0 para desativar. Teclado, movimento/clique do mouse e rolagem dentro do desktop reiniciam a contagem. O prazo começa ao criar/desbloquear o cofre, inclusive pela extensão, e continua com a janela oculta ou minimizada. Consultas da extensão não renovam o prazo. O bloqueio limpa os campos e invalida as autorizações de preenchimento pendentes.
- **Limpeza da área de transferência:** padrão de 30 segundos, de 1 a 3.600 segundos. Cada cópia usa o prazo salvo. Abrir as configurações também limpa um segredo ainda copiado pelo cofre.

As preferências ficam em `app-settings.properties`, na mesma pasta do banco, e são carregadas na próxima execução. Alterar esses tempos não modifica o formato do cofre ou dos backups. Se o arquivo de preferências estiver inválido ou inacessível, o aplicativo avisa e usa os padrões. Novos tempos entram em vigor após salvar; a contagem de inatividade é reiniciada.
