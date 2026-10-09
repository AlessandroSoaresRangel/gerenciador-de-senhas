# Preferências de desenvolvimento

Estas instruções se aplicam a todo o projeto.

## Legibilidade

- Priorize código claro e fácil de manter, especialmente nas operações de criptografia.
- Use nomes descritivos para classes, métodos, variáveis e constantes.
- Evite abreviações vagas, múltiplas declarações na mesma linha e várias instruções na mesma linha.
- Separe responsabilidades em métodos coesos; mantenha validações e etapas importantes fáceis de identificar.
- Siga SOLID ao organizar o projeto: separe sessão, persistência, criptografia, backup e interface; use contratos e injeção de dependências nas fronteiras relevantes, sem criar abstrações sem propósito.
- Substitua números mágicos por constantes com nomes que indiquem seu propósito e suas unidades.
- Mantenha formatação consistente e organize SQL extenso em blocos de texto legíveis.
- Use comentários para explicar decisões, unidades e detalhes de segurança; evite comentários que apenas repetem o código.
- Documente métodos e fluxos mais complexos, explicando suas etapas, contratos e motivos. Métodos e variáveis com nomes autoexplicativos não precisam de comentários adicionais.
- Ao refatorar, preserve o comportamento e o formato dos dados existentes, salvo quando uma mudança for solicitada.

## TDD

- Use desenvolvimento orientado por testes (TDD) para novas funcionalidades e correções de comportamento.
- Siga o ciclo **Red → Green → Refactor**:
  1. Escreva primeiro um teste que expresse o comportamento esperado. Para um bug, reproduza a falha no teste.
  2. Execute o teste e confirme que falha pelo motivo esperado, não por erro de configuração ou compilação.
  3. Implemente o mínimo necessário para fazer o teste passar.
  4. Refatore para melhorar a legibilidade e a organização, mantendo os testes passando.
- Teste comportamentos observáveis, incluindo casos de erro relevantes, em vez de reproduzir detalhes da implementação.
- Em refatorações sem mudança de comportamento, execute os testes existentes; acrescente testes de caracterização antes de alterar código cujo comportamento relevante ainda não esteja coberto.
- Alterações apenas em documentação ou formatação não exigem novos testes.
- Execute os testes pertinentes ao concluir e informe o resultado, incluindo qualquer impedimento à execução.
- Nunca use senhas ou credenciais reais nos testes.
