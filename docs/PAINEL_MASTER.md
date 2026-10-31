# Painel master (fase 4, OPS-01)

Flag: `CP_PLATFORM_ENABLED` (padrão `false`). Com a flag desligada as rotas `/v1/platform/*`, o aceite do convite
de gestor (`/v1/manager-invitations/accept`) e o primeiro acesso do gestor (`/auth/manager-invitation/accept`, que
exige também `CP_BFF_ENABLED`) não existem. A situação de cada migration por ambiente fica em `STATUS.md`; a V035
(criação de condomínio pelo painel) **não foi aplicada a nenhum banco remoto** e exige revisão e autorização próprias.

## O que o painel faz

| Operação | Rota | Capacidade |
| --- | --- | --- |
| Papel da própria conta (e se falta o segundo fator) | `GET /v1/platform/session` | papel vigente |
| Catálogo de condomínios: nome, fuso, estado, versão, origem, recibo do onboarding, uso agregado e assinatura; busca opcional `q` | `GET /v1/platform/tenants` | `tenants.read` (assinatura: `billing.read` e financeiro ligado) |
| Ficha do condomínio: dados, uso, assinatura, gestores, convites de gestor e 10 eventos recentes da plataforma | `GET /v1/platform/tenants/{id}` | `tenants.read` (eventos: `platform.audit.read`) |
| Criar condomínio, unidades, área inicial opcional e convite do primeiro gestor (V035) | `POST /v1/platform/tenants` + `Idempotency-Key` | `tenants.manage` |
| Novo convite de primeiro gestor (revoga o pendente anterior) | `POST /v1/platform/tenants/{id}/manager-invitations` | `tenants.manage` |
| Revogar convite de gestor pendente, com motivo | `POST /v1/platform/tenants/{id}/manager-invitations/{convite}/revoke` | `tenants.manage` |
| Renomear, suspender ou reativar pela versão lida, com motivo | `PUT /v1/platform/tenants/{id}` | `tenants.manage` |
| Aceite do convite pela conta convidada (sessão já aberta / primeiro acesso web) | `POST /v1/manager-invitations/accept` / `POST /auth/manager-invitation/accept` | própria conta, mesmo e-mail |
| Auditoria da plataforma, por cursor | `GET /v1/platform/audit-events` | `platform.audit.read` |
| Fila de tarefas por condomínio, com alerta de fila antiga (V032) | `GET /v1/platform/outbox` | `outbox.operate` e `tenants.read` |
| Tarefas com falha ou ignoradas (sem conteúdo) | `GET /v1/platform/outbox/tasks?state=&tenantId=` | `outbox.operate` |
| Devolver tarefa FAILED/SKIPPED para a fila, com motivo auditado | `POST /v1/platform/outbox/tasks/{id}/replay` | `outbox.operate` |

- **Papel**: `PLATFORM_ADMIN` em `platform.platform_roles`, um por conta, `ACTIVE` ou `REVOKED`, com motivo.
  A role runtime só lê o próprio papel; não concede, altera nem revoga papéis.
- **Capacidades**: publicadas em `app.platform_capabilities` pelo `PlatformTransactionExecutor` somente depois
  de conta ativa, papel vigente e token `aal2`, e somente no contexto local da transação. Sem AAL2 a sessão
  informa `mfaRequired` e toda operação recebe `403 MFA_REQUIRED`; conta sem papel recebe
  `403 PLATFORM_ACCESS_DENIED`. O papel é relido em cada requisição: revogação vale na próxima.
- **Banco (V010)**: `platform.tenants` ganha `version`/`updated_at`; leitura global só com `tenants.read`; o
  UPDATE só alcança linhas com `tenants.read` e grava só com `tenants.manage` (sem ela, 42501); membro do
  condomínio não alcança nem trava a linha do catálogo; o fuso fica fora do grant. Do recibo de onboarding a
  role lê só `operation_id`, `tenant_id`, contagens e data — não o hash do manifesto nem o principal do banco.
  `platform.platform_audit_log` é append-only para a role runtime: inserção exige o próprio ator e
  `tenants.manage`; `id` e `created_at` vêm do banco. A gravação da trilha a cada mudança é garantida pelo
  serviço, na mesma transação da alteração (o banco não exige a linha de auditoria; risco residual registrado).
- **Convite em condomínio suspenso**: o aceite devolve `404 INVITATION_NOT_AVAILABLE` e nada é gravado; o banco
  também recusa o vínculo. O convidado sem vínculo só enxerga o condomínio ativo do convite pendente apresentado.
- **Busca**: `q` procura trecho do nome sem diferenciar maiúsculas (acentos contam; `%` e `_` são texto) ou o id
  exato; alcança condomínios além dos 200 primeiros da lista em ordem alfabética.
- **Nome**: comprimento em code points (como o banco e o onboarding), qualquer espaço Unicode vira um espaço e
  caracteres de controle ou de formatação invisível (U+200B, U+202E…) são recusados — a web aplica a mesma regra.
  No `PUT`, `name` ausente ou `null` mantém o nome atual: suspender ou reativar nunca renomeia.
- **Mudanças**: pedido igual ao estado atual devolve `applied=false` sem gravar (repetição natural); versão
  divergente com mudança real é `409 TENANT_CHANGED`. Cada mudança grava um evento: `TENANT_RENAMED` (com nome
  anterior e novo), `TENANT_SUSPENDED` ou `TENANT_REACTIVATED`, com o motivo.
- **Suspensão**: o condomínio sai de `/v1/me` e o executor de tenant passa a recusar o contexto
  (`404 TENANT_ACCESS_DENIED`) na próxima requisição de moradores e gestão local. Não apaga dados, sessões ou
  reservas e não é automática por atraso de pagamento (fase 5).

## Criação de condomínio pelo painel (V035)

Substitui o manifesto manual para o caso comum (condomínio novo, unidades, primeiro gestor e, opcionalmente, uma área),
com o mesmo rigor: papel `PLATFORM_ADMIN` com AAL2, motivo obrigatório, trilha da plataforma e idempotência. O
onboarding operacional (`OPERACAO_ONBOARDING.md`) continua disponível para lotes com moradores e vínculos.

- **Pedido**: nome (até 160 code points, mesma regra do PUT), fuso IANA (aceito pelo Java **e** por
  `pg_timezone_names` na mesma transação), de 1 a 250 unidades (1 a 40 caracteres, sem repetição ignorando maiúsculas),
  primeiro gestor (nome, e-mail e validade do convite de 1 a 14 dias, padrão 7), área inicial opcional (mesma validação
  da gestão local: `AmenityManagementModels.validate`, regra versão 1, área ativa) e motivo (até 500).
- **Idempotência**: cabeçalho `Idempotency-Key` (`[A-Za-z0-9._:-]{16,128}`), único por conta em
  `platform.tenant_creations`, com o SHA-256 do pedido normalizado. Mesma chave e mesmo pedido: `200` com
  `applied=false`, o condomínio já criado e **sem** token (nada é gravado de novo); mesma chave com outro pedido:
  `409 TENANT_CREATION_KEY_REUSED`; corrida entre duas requisições iguais: a perdedora relê e devolve a repetição.
- **Transação única**: registro da criação → condomínio (ativo, versão 0) → unidades → área → convite → eventos
  `TENANT_CREATED` e `TENANT_MANAGER_INVITED`. Resposta `201` com o token do convite, mostrado só nesta resposta.
- **Banco (V035)**: o INSERT em `platform.tenants`, e as políticas restritivas de INSERT em `cp.units`/`cp.amenities`
  (V006), só aceitam o condomínio cujo registro em `tenant_creations` foi gravado **nesta mesma transação**
  (`creation_xid = pg_current_xact_id()`) pela mesma conta e com `tenants.manage` publicada. Assim a plataforma não
  insere unidade ou área em condomínio existente, nem condomínio sem registro. Um gatilho de restrição diferido exige,
  no COMMIT, o evento `TENANT_CREATED` do mesmo ator na trilha da plataforma (a falta aborta com 23514). Nenhuma
  credencial Auth Admin, DDL, `BYPASSRLS` ou `SECURITY DEFINER`.

### Primeiro gestor: convite de uso único (sem e-mail→UUID no servidor)

A API runtime **não** recebe a chave Auth Admin (privilegiada, só do job de onboarding — ADR-0007) e, sem ela, não há
caminho seguro para resolver e-mail → UUID do Auth. Também foi descartado colar o UUID do Auth no painel: a API não
consegue conferir se a conta existe, tem e-mail confirmado ou está banida. Escolha: o **mecanismo de convite já
existente** (token opaco de 32 bytes em base64url, só o SHA-256 no banco, e-mail só como SHA-256 de
`<id>:<e-mail normalizado>` — o mesmo desenho do convite CP-020 e do link CP-037), em tabela própria
`platform.manager_invitations`:

- A plataforma emite o convite (na criação ou depois, pela ficha) e entrega o link `/<origem>/convite-gestor#token=…`
  por canal privado. O token vai só no fragmento: não chega a servidores nem a logs de acesso; a web guarda o token só
  no `sessionStorage` da aba, com chave própria (`condpulse:manager-invitation`).
- O gestor aceita com a **própria conta Auth** (login com senha, que no Supabase exige e-mail confirmado): o claim
  `email` do token precisa bater com o hash do convite. O aceite cria o espelho local da conta (se faltar), o vínculo
  `MANAGER` **ativo** sem unidade (a decisão foi da plataforma ao emitir, com AAL2 e auditoria) e registra
  `MANAGER_INVITATION_ACCEPTED` na auditoria do condomínio. É o único caminho da API em que um vínculo nasce ativo.
- O banco confere o mesmo que o serviço: política `membership_manager_invitation_insert` exige a própria conta,
  `MANAGER`/`ACTIVE`/sem unidade, condomínio do contexto local, convite pendente e dentro do prazo pelo hash local da
  transação (`app.manager_invitation_hash`), mesmo nome do convite e condomínio ativo. O aceite marca o convite como
  `ACCEPTED` somente se o vínculo da própria conta existir. Respostas negativas são neutras
  (`INVITATION_NOT_AVAILABLE`): token inválido, expirado, revogado, já usado, outro e-mail ou condomínio suspenso.
- Um convite pendente por condomínio (índice parcial); emitir outro revoga o anterior na mesma transação. Novo convite só
  para condomínio **ativo** e **sem gestor vigente** (`TENANT_MANAGER_EXISTS`): depois do primeiro gestor, outros
  vínculos (inclusive gestores) seguem pela gestão local. A plataforma não aprova, suspende nem revoga vínculos.
- Limites herdados do convite CP-020: `email_verified` não é conferido no claim (a prova é o login no provedor, que
  exige e-mail confirmado na configuração do projeto) e o hash do e-mail não usa HMAC.

### Uso agregado e assinatura no catálogo

`usage` traz unidades ativas, vínculos vigentes (`ACTIVE` dentro da vigência), gestores vigentes e a última
atividade conhecida (maior data entre a atualização do condomínio, de vínculos, de unidades e a trilha da plataforma).
`billing` (somente com `CP_PLATFORM_BILLING_ENABLED` e `billing.read`) traz assinatura ativa, cobranças em aberto e
vencidas — vencida não bloqueia acesso. `origin` diz se o condomínio veio do onboarding ou do painel.

**Decisão a confirmar pelo responsável**: para contar vínculos e listar gestores, a V035 abre leitura de `cp.units` e
`cp.memberships` de todos os condomínios para a sessão com `tenants.read` (papel `PLATFORM_ADMIN` + AAL2). A API
devolve só contagens e o nome/situação/início dos gestores, mas o banco não restringe colunas por política: com
`tenants.read` publicada, a role runtime enxergaria nome, papel, unidade e situação dos vínculos. Alternativas mais
fechadas (contadores mantidos por gatilho ou função `SECURITY DEFINER` com dono sem acesso de escrita) ficam para
decisão própria.

## Módulos por condomínio (V045)

A central libera ou suspende módulos de cada condomínio, dentro das flags globais (a flag desligada continua sem rota).

| Operação | Rota | Capacidade |
| --- | --- | --- |
| Estado dos módulos: decisão da central (padrão liberado, versão 0), flag global e efeito real | `GET /v1/platform/tenants/{id}/modules` | `tenants.read` |
| Liberar ou suspender um módulo pela versão lida, com motivo (3 a 500) | `PUT /v1/platform/tenants/{id}/modules` | `tenant_modules.manage` e `tenants.read` |

- **Módulos**: `reservations`, `parcels`, `visits`, `announcements`, `requests`, `finance`, `access_credentials`,
  `facial_access` — os mesmos grupos das flags `CP_*_ENABLED`. Unidades da portaria valem com encomendas **ou** visitas;
  decisões de reserva e áreas comuns da gestão seguem o módulo de reservas; anexos seguem o módulo de pedidos.
- **Toda rota de condomínio** (`/v1/tenants/**`) declara o módulo (`@RequiresTenantModule`) ou a isenção explícita com
  motivo (`@TenantModuleExempt`: unidades, blocos, vínculos, importação, locação, auditoria, caixa interna e canal em
  tempo real); um teste de arquitetura recusa rota sem nenhuma das duas.
- **Padrão**: sem linha em `platform.tenant_modules` o condomínio segue liberado; nada muda até a central alterar.
- **Efeito**: as rotas do módulo respondem `403 MODULE_DISABLED` no condomínio suspenso a partir da próxima requisição. A
  conferência acontece no executor do condomínio (e no técnico do acesso facial), na mesma transação e **depois** do
  vínculo: quem não tem acesso ao condomínio continua recebendo 404 sem saber do módulo. O link público de visita fica
  indisponível com a mesma resposta neutra. `/v1/me` devolve `tenantModules` (módulos liberados por condomínio) para o
  menu. Nada é apagado; o webhook do Asaas continua liquidando cobranças.
- **Banco**: RLS forçado; gravação só com `tenant_modules.manage` e o próprio ator (`updated_by`), nascimento na versão
  1 e cada mudança na versão seguinte (gatilho), sem DELETE; gatilho de restrição diferido exige no COMMIT o evento
  `TENANT_MODULE_ENABLED/DISABLED` do mesmo ator, módulo e versão. `tenants.manage` deixou de gravar eventos
  `TENANT_MODULE_*`. Leitura: plataforma, contexto do próprio condomínio, condomínios dos vínculos ativos da conta e
  link de visita apresentado.
- **Mudanças**: pedido igual ao estado atual devolve `applied=false` sem gravar; versão divergente com mudança real é
  `409 TENANT_MODULE_CHANGED`. Mudanças do mesmo condomínio ficam em fila pela trava da linha do condomínio.
- **Limites**: worker/outbox e sincronização do acesso facial ainda não consultam o módulo (tarefas já enfileiradas
  seguem). Tela na central: fatia 6 do desenho.

## Tela web (`/central`, antes `/master`)

A central administradora fica em `/central` (`/master/...` redireciona, preservando parâmetros; `/master/fila` virou
`/central/operacao`). Depois do login, conta só de plataforma vai direto à central; conta com plataforma e condomínio vê
o atalho da central no seletor `/entrada`. Seções futuras (módulos por condomínio, contas e acessos, chaves e
integrações, acesso facial) aparecem desabilitadas, sem rota, até o backend existir. A tela consulta
`/v1/platform/session`; conta sem papel vê "Área restrita" sem nenhum dado da plataforma; flag desligada mostra
"Painel indisponível"; sem AAL2 a tela cadastra/verifica o autenticador antes de qualquer outra rota. Seções:
Condomínios (lista com estado, fuso, versão, origem, recibo do onboarding, uso agregado e assinatura; "Novo
condomínio"; "Abrir ficha"; alterar nome ou estado com motivo, pela versão lida ao abrir, com aviso e recarga quando
outra pessoa mudou o condomínio) e Auditoria (por cursor, horários em America/Sao_Paulo). A ficha
(`/central/condominios/{id}`) mostra dados, uso, assinatura, gestores, convites (emitir/revogar) e eventos recentes, e
permite suspender ou reativar com motivo.

- **Novo condomínio**: a chave de operação nasce com o formulário. Resultado incerto (rede ou 5xx depois do envio)
  trava os campos e oferece "Reenviar os mesmos dados" (mesma chave: devolve o condomínio já criado, sem duplicar) ou
  descartar e conferir a lista. O link do convite aparece uma única vez, com botão de copiar; repetição não mostra link.
- **Aceite**: `/convite-gestor#token=…` reaproveita a tela de convite (`InvitationPage kind="manager"`): quem já tem
  sessão confirma; quem ainda não tem conta local entra com e-mail e senha no primeiro acesso.
- A web pode ser publicada antes da API: sem `usage`/`billing`/`origin` na resposta, a lista mostra "uso não informado"
  em vez de falhar. Repetição natural aparece como informação, nunca como sucesso; `403
PLATFORM_ACCESS_DENIED` em qualquer consulta remove os dados da tela.

## O que o painel não faz

- Não usa a credencial Auth Admin nem consulta o Auth por e-mail: o primeiro gestor prova a conta ao aceitar o convite.
  Lotes com moradores e vínculos continuam no onboarding operacional (`OPERACAO_ONBOARDING.md`).
- Não mostra conteúdo residencial (moradores, reservas, encomendas, auditoria local); só contagens e gestores (ver a
  decisão a confirmar acima sobre a leitura de `cp.units`/`cp.memberships` no banco).
- Não aprova, suspende ou revoga vínculos, não cria moradores e não convida gestor adicional quando já há gestor
  vigente (isso é da gestão local).
- Não age em nome de outra conta, não concede papel de plataforma e não dá poder de plataforma a `MANAGER`.
  Suporte temporário com finalidade, vigência e escopo (OPS-01) continua pendente.

## Conceder e revogar o papel (operação, fora da API)

O primeiro administrador é concedido por SQL operacional revisado, executado pela identidade de migração ou de
operação aprovada — nunca pela role runtime. Modelo, com valores reais preenchidos só na execução revisada:

```sql
INSERT INTO platform.platform_roles(account_id,role,status,reason)
VALUES('<uuid-da-conta-existente>','PLATFORM_ADMIN','ACTIVE','<motivo e referência da aprovação>');
-- Revogação (o motivo da concessão é preservado em reason):
UPDATE platform.platform_roles SET status='REVOKED',revoked_at=clock_timestamp(),revoke_reason='<motivo e referência>'
WHERE account_id='<uuid-da-conta>';
-- Nova concessão depois de revogar:
UPDATE platform.platform_roles SET status='ACTIVE',revoked_at=NULL,revoke_reason=NULL,granted_at=clock_timestamp(),
 reason='<motivo e referência da nova aprovação>' WHERE account_id='<uuid-da-conta>';
```

Cada INSERT, UPDATE ou DELETE em `platform_roles` grava, por gatilho e na mesma transação,
`platform.platform_role_events` (operação, estado, motivos e `executed_by` = identidade do banco que executou).
Nenhuma role de aplicação lê ou escreve essa trilha. As duas tabelas têm RLS forçado sem política para escrita:
o SQL só funciona com identidade de migração/operação que ignore RLS (por exemplo, com `BYPASSRLS`) — conferir
a identidade antes da execução revisada. A conta precisa existir e estar ativa em `platform.accounts` e ter fator
TOTP verificado para usar o painel. Registrar a execução (quem aprovou, quando, run) em `STATUS.md`. Nenhuma
concessão foi feita.

### Roteiro revisado do primeiro administrador

[`ops/sql/platform-admin-grant.sql`](../ops/sql/platform-admin-grant.sql) executa a concessão inicial numa única
transação, sob trava de `platform_roles` que serializa execuções simultâneas, e só conclui se todas as pré-condições valerem: identidade de execução fora de `cp_api`/`cp_worker`/
`cp_onboarding` (nem membro desses grupos), motivo de 10 a 500 caracteres, conta existente e ativa em
`platform.accounts`, fator TOTP verificado em `auth.mfa_factors` e nenhum papel de plataforma ativo (o roteiro serve
só para o primeiro). No fim confere, na mesma transação, um único papel ativo da conta e o evento gravado pelo gatilho.
Ensaiado em PostgreSQL descartável com a cadeia V001 em diante e um `auth.mfa_factors` sintético: sem TOTP, motivo
curto, conta inexistente, segundo administrador, variável ausente e execução como `cp_api` falharam sem gravar; o caso
válido gravou um papel e um evento `INSERT/ACTIVE`. Duas execuções simultâneas para contas diferentes: sem a trava as duas
concediam o papel; com a trava a segunda espera e é recusada.

BLOQUEADO: aprovação escrita do responsável nomeando a conta que recebe `PLATFORM_ADMIN`, cadastro do TOTP dessa
conta e escolha da identidade de operação que executa — responsável — canal privado; a execução registra run/horário
e quem aprovou em `STATUS.md`. Gestão local não implica papel de plataforma.

## Pendências

- Aplicação remota da V035 (criação pelo painel), com aprovação explícita, e republicação da API.
- Decisão do responsável sobre a leitura agregada de `cp.units`/`cp.memberships` pela sessão de plataforma (V035).
- Envio do link do convite por e-mail (SMTP ainda pendente): hoje a administração copia e envia por canal privado.
- Ensaio com Supabase Auth real do aceite do convite de gestor (e-mail confirmado, conta nova e conta existente).
- Suporte temporário justificado (OPS-01). Contratos/planos e situação financeira: ver
  [FINANCEIRO_PLATAFORMA.md](FINANCEIRO_PLATAFORMA.md) (fase 5).
- Ensaio com Supabase Auth real (AAL2) e aceite do painel em navegador com dados reais.
- Exigir no banco a linha de auditoria a cada mudança de `platform.tenants` (hoje garantida pelo serviço).
- Teste de duas alterações simultâneas pelo HTTP e paginação por cursor do catálogo (hoje: 200 + busca).
