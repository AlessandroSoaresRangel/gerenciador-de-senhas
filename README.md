# Cofre local — etapas 1 a 4

Aplicativo desktop Java/JavaFX: criação e desbloqueio com senha mestra, cadastro, edição, pesquisa, exclusão, bloqueio, backup e restauração do cofre. A extensão Chrome consulta a sessão e mostra as contas correspondentes ao site aberto pelo host Native Messaging. Implementa os itens 1 a 4 da ordem de implementação; preenchimento fica para as próximas etapas.

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

Na primeira execução, crie a senha mestra com pelo menos 12 caracteres. O SQLite fica em `%USERPROFILE%\.cofre-local\vault.db`. Para usar outro arquivo:

```powershell
mvn javafx:run "-Djavafx.options=-Dvault.path=C:/caminho/cofre.db"
```

Cadastre título, usuário, senha, origens HTTPS (opcionais, separadas por vírgula) e notas. Selecione uma credencial para editá-la ou excluí-la. A pesquisa funciona somente com o cofre desbloqueado.

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

A consulta usa `LIST_MATCHES` e devolve somente ID, título e usuário; não envia senha, notas ou todas as origens cadastradas. A origem vem da API de abas do navegador, com permissão temporária `activeTab` ao abrir a extensão. Se a aba navegar durante a consulta, a resposta é descartada. O popup consulta ao abrir e ao clicar em Atualizar, sem consultas periódicas. Os resultados refletem a última consulta; alterações na sessão são exibidas na próxima atualização. Se a consulta informar bloqueio, aplicativo fechado ou falha, a lista é removida. Não há preenchimento nem envio de formulários nesta etapa.

Ao atualizar da etapa 3: execute novamente `npm.cmd test` na pasta da extensão, recarregue a extensão em `chrome://extensions`, reexecute o instalador do host com o mesmo ID e reinicie o desktop. O executável anteriormente instalado não aceita o novo comando até ser atualizado.

Alternar para o navegador ou abrir o popup não bloqueia o cofre. O desbloqueio permanece exclusivamente na interface desktop. A inatividade é medida pelas interações no desktop: consultar o estado pela extensão não renova o prazo de 5 minutos.

O host não abre outra sessão SQLite. Ele consulta a sessão desktop por loopback autenticado, com token aleatório em um arquivo restrito ao dono por ACL do Windows (ou permissões POSIX). O arquivo de descoberta é apagado ao fechar o desktop, e um lock impede duas sessões na mesma pasta. Não há servidor acessível pela rede externa, acesso permanente a sites, content script ou credenciais persistidas na extensão. O protocolo aceita `GET_STATUS` e `LIST_MATCHES`, com versão e ID de correlação; mensagens inválidas são rejeitadas e os frames são limitados a 64 KiB.

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
- Bloqueio manual, após 5 minutos sem interação no desktop ou ao minimizar. Alternar para outra janela mantém a sessão desbloqueada para permitir usar a extensão. Uma pausa do relógio de eventos acima de 10 segundos bloqueia o cofre ao retomar da suspensão; não há integração nativa com eventos de bloqueio do Windows nesta etapa.
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
| `nativehost` | Framing binário, validação de `GET_STATUS` e processo iniciado pelo Chrome |
| `ipc` | Descoberta privada e consulta autenticada da sessão desktop |

`VaultService` recebe um `VaultRepository` por construtor e delega o backup ao `VaultBackupService`. O repositório recebe somente registros criptografados; não conhece a senha mestra nem o formato de backup. A interface continua usando o serviço para que o bloqueio seja sincronizado com as operações. O esquema SQLite e o formato de backup da versão 1 foram preservados.

Os testes cobrem persistência, senha incorreta, bloqueio, CRUD, ausência de campos em texto aberto, rejeição de adulteração, compatibilidade com backups da versão 1, substituição do cofre e rollback em falhas de gravação.

Referências de implementação: [OpenJFX/Maven](https://openjfx.io/openjfx-docs/#maven) e [Argon2 do Bouncy Castle](https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/generators/Argon2BytesGenerator.java).
