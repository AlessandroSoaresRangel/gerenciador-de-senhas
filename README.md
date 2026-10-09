# Cofre local — etapas 1 e 2

Aplicativo desktop Java/JavaFX: criação e desbloqueio com senha mestra, cadastro, edição, pesquisa, exclusão, bloqueio, backup e restauração do cofre. Implementa os itens 1 e 2 da ordem de implementação. A integração com navegador fica para as próximas etapas.

## Executar

Requisitos: JDK 21 ou superior e Maven 3.9 ou superior.

```powershell
mvn clean test
mvn javafx:run
```

Os testes espelham os pacotes do código em `src/test/java/br/com/cofre`: `domain`, `crypto`, `application`, `backup`, `storage` e `desktop`. O teste de integração `VaultAppTest` precisa de uma sessão gráfica: abre e fecha uma janela temporária e usa um banco de teste, sem acessar seu cofre pessoal. Para executar somente os testes que não usam interface:

```powershell
mvn test "-Dtest=*Test,!VaultAppTest"
```

Na primeira execução, crie a senha mestra com pelo menos 12 caracteres. O SQLite fica em `%USERPROFILE%\.cofre-local\vault.db`. Para usar outro arquivo:

```powershell
mvn javafx:run "-Djavafx.options=-Dvault.path=C:/caminho/cofre.db"
```

Cadastre título, usuário, senha, origens HTTPS (opcionais, separadas por vírgula) e notas. Selecione uma credencial para editá-la ou excluí-la. A pesquisa funciona somente com o cofre desbloqueado.

## Backup e restauração

Com o cofre desbloqueado, informe o caminho completo de um novo arquivo (por exemplo `C:/Users/seu-usuario/Documents/cofre.cofre`) e clique em **Salvar backup**. A pasta precisa existir. Arquivos existentes nunca são sobrescritos.

Clique em **Restaurar backup**, disponível também na tela bloqueada, informe o caminho do arquivo e a senha mestra usada quando ele foi criado. Confirme a substituição e clique em **Validar e restaurar**. A restauração substitui todas as credenciais e a senha mestra do cofre atual pelas do backup; não mescla os dados. Faça uma cópia do cofre atual antes de restaurar.

O backup tem formato identificado e versionado, com cabeçalho para recuperar a chave e um conteúdo completo protegido por AES-GCM. Senha incorreta, conteúdo adulterado, arquivo truncado e versão desconhecida são recusados antes de alterar o banco. Falhas na gravação da restauração desfazem a transação. Ao terminar, desbloqueie o cofre com a senha do backup.

O limite de arquivo é 64 MiB. A exportação publica somente o arquivo completo e limpa o temporário criptografado; uma interrupção abrupta do processo pode deixar um temporário na pasta. Backup e restauração são executados em segundo plano. Uma restauração já confirmada continua mesmo se a janela perder foco e termina com o cofre bloqueado.

## Proteção e limites

- Argon2id v1.3: 64 MiB, 3 iterações e paralelismo 1, salt aleatório de 16 bytes.
- Chave aleatória de 256 bits, protegida pela chave derivada da senha mestra. AES-256-GCM com nonce aleatório de 96 bits em cada gravação e autenticação do ID/versão do registro.
- Títulos, usuários, senhas, origens e notas ficam dentro do conteúdo criptografado; não há índice em texto aberto. A senha mestra não é persistida.
- Bloqueio manual, após 5 minutos sem interação, ao minimizar ou perder foco. A perda de foco é deliberadamente conservadora e também cobre a ida para outros aplicativos. Uma pausa do relógio de eventos acima de 10 segundos bloqueia o cofre ao retomar da suspensão; não há integração nativa com eventos do Windows nesta etapa.
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

`VaultService` recebe um `VaultRepository` por construtor e delega o backup ao `VaultBackupService`. O repositório recebe somente registros criptografados; não conhece a senha mestra nem o formato de backup. A interface continua usando o serviço para que o bloqueio seja sincronizado com as operações. O esquema SQLite e o formato de backup da versão 1 foram preservados.

Os testes cobrem persistência, senha incorreta, bloqueio, CRUD, ausência de campos em texto aberto, rejeição de adulteração, compatibilidade com backups da versão 1, substituição do cofre e rollback em falhas de gravação.

Referências de implementação: [OpenJFX/Maven](https://openjfx.io/openjfx-docs/#maven) e [Argon2 do Bouncy Castle](https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/generators/Argon2BytesGenerator.java).
