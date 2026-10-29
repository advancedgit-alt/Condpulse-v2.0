# MFA de equipe — operação e recuperação

Referências: PRD cap. 9 e 18, CP-013, AC-004/AC-007.

## Regra implementada

- Capacidades de equipe derivam do papel vigente no condomínio: `MANAGER`
  (unidades, vínculos, áreas, decisões de reserva e auditoria) e `CONCIERGE`
  (operação da portaria). `SUPPORT` não recebe capacidade até existir concessão
  temporária auditada. Papéis residenciais nunca herdam capacidade de equipe.
- Toda capacidade de equipe exige token com `aal=aal2`, validado no backend.
  Papel correto com AAL1 recebe `403 MFA_REQUIRED`, sem alteração de dados.
- Somente após vínculo vigente e AAL2 o executor grava `app.capabilities` no
  contexto local da transação. As políticas de escrita das próximas migrations
  usam esse contexto como segunda barreira, além da autorização Java.

## Fluxo web (BFF)

1. `GET /auth/mfa` informa o nível da sessão e os fatores TOTP da conta.
2. `POST /auth/mfa/enroll` devolve URI `otpauth`, segredo e QR SVG uma única
   vez, com `no-store`. O fator só vale depois de verificado.
3. `POST /auth/mfa/verify` desafia e verifica o código de 6 dígitos. A sessão
   fica reservada contra renovação concorrente. Sucesso troca, na mesma transação,
   a sessão antiga por uma nova sessão opaca AAL2 com o mesmo limite absoluto.
   Recusa definitiva do provedor (código errado, fator inexistente, limite)
   mantém a sessão. Qualquer resultado incerto depois do envio (indisponibilidade,
   token inesperado, falha ao gravar) encerra a sessão local e no provedor, sem
   repetição: a pessoa entra novamente.

Limites por conta (HMAC do identificador, cobrados só depois de validar a
sessão): 5 verificações e 5 cadastros a cada 15 minutos; 30 consultas de estado
por minuto. Novas sessões da mesma conta não renovam as tentativas.

Todos exigem `CP_BFF_ENABLED=true`, Origin exato e CSRF nas mutações.

## Recuperação quando o aparelho é perdido

A API não possui credencial administrativa do Auth e não remove fatores.
Procedimento manual, por pessoa autorizada da operação CondPulse:

1. Confirmar a identidade do solicitante por canal já cadastrado e registrar
   chamado com data, solicitante, responsável e motivo. Nunca aceitar só e-mail
   ou telefone informado na própria solicitação.
2. Suspender temporariamente os vínculos de equipe da conta, se houver suspeita
   de comprometimento.
3. No painel do projeto Supabase do ambiente correto, remover o fator TOTP do
   usuário e encerrar as sessões dele.
4. A pessoa entra com senha (AAL1), cadastra novo fator e verifica. Somente então
   o vínculo de equipe volta a operar.
5. Registrar a conclusão no chamado. O segundo fator nunca é desativado
   informalmente nem por mensagem.

## Risco do primeiro cadastro

O Supabase permite cadastrar o primeiro fator com sessão de senha (AAL1). Quem
obtiver a senha de uma pessoa de equipe ainda sem fator poderia cadastrar o
próprio TOTP. Até existir prova adicional automatizada:

- o vínculo `MANAGER`/`CONCIERGE` só é ativado depois que a pessoa cadastra o
  fator em atendimento supervisionado pela operação;
- habilitar no painel do Supabase as notificações de segurança por e-mail para
  cadastro e remoção de fator (configuração pendente, depende de SMTP próprio);
- qualquer cadastro não reconhecido segue o procedimento de recuperação acima.

## Limitações

Não foi ensaiado com Supabase Auth real nesta etapa: os testes usam provedor
sintético. Recuperação por códigos de backup e step-up por operação não estão
implementados. Telas web de MFA dependem da PR de gestão.
