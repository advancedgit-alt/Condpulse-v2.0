# Financeiro condominial com Asaas (V036, ADR-0008)

Situação: preparado neste PR, **nada aplicado nem executado**. A V036 não foi aplicada a nenhum banco remoto, nenhum
workflow foi disparado e a API real do Asaas não foi chamada. Somente sandbox: `https://api-sandbox.asaas.com/v3`
(docs.asaas.com, "Autenticação"; chave do sandbox com prefixo `$aact_hmlg_`, a de produção `$aact_prod_` é recusada).

Separado do financeiro da assinatura SaaS (ADR-0007, [FINANCEIRO_PLATAFORMA.md](FINANCEIRO_PLATAFORMA.md)).

## Flag e segredos

| Nome | Onde | Efeito |
| --- | --- | --- |
| `CP_FINANCE_ENABLED` | Fly (gravada pelo `fly-api` junto com a chave) | Padrão `false`: rotas `/v1/.../finance/*` e o webhook não existem (404). |
| `CP_ASAAS_API_KEY` | secret do GitHub (`condpulse-database-operations`) → Fly | Só no servidor, cabeçalho `access_token`; nunca em log, resposta ou navegador. Com a flag ligada e sem chave válida do sandbox a API não sobe. |
| `CP_ASAAS_WEBHOOK_TOKEN` | gerado no runner pelo `fly-api` → Fly e Asaas | 43 caracteres (32 bytes base64url). Comparado em tempo constante com o cabeçalho `asaas-access-token`. Nunca impresso. |
| `CP_ASAAS_WEBHOOK_EMAIL` | **variável** (não segredo) do Environment | E-mail exigido pelo Asaas para avisos do webhook (fila pausada etc.). Obrigatório quando o token é gerado. |

Caminho do segredo: mesmo padrão de `CP_STORAGE_SERVICE_KEY` ([FLY_API.md](FLY_API.md)): só o `apply` recebe a chave;
com o login READY grava só os segredos do financeiro (e do Storage, se vier), confere os nomes de volta e vale no
próximo `deploy`. Depois de gravada, `apply` sem a chave é recusado (`FLY_ASAAS_KEY_REQUIRED`).

### Webhook configurado pelo próprio workflow

Quando a Fly ainda não tem `CP_ASAAS_WEBHOOK_TOKEN`, o `apply`:
1. gera o token no runner;
2. chama o Asaas sandbox (`GET /webhooks`, depois `POST /webhooks` ou `PUT /webhooks/{id}` se já houver um com a mesma
   URL) com `url=https://project-rhvdq.vercel.app/v1/finance/asaas/webhook`, `authToken`=token, `enabled=true`,
   `interrupted=false`, `sendType=SEQUENTIALLY` e eventos `PAYMENT_CREATED`, `PAYMENT_UPDATED`, `PAYMENT_CONFIRMED`,
   `PAYMENT_RECEIVED`, `PAYMENT_OVERDUE`, `PAYMENT_DELETED`, `PAYMENT_REFUNDED`;
3. só então grava token, chave e flag na Fly e confere os nomes.

Ninguém precisa copiar o token. Se a Fly já tem o token, nada no Asaas é tocado. Falha no Asaas: nada é gravado na Fly
(`FLY_ASAAS_*`). Rotação: `flyctl secrets unset CP_ASAAS_WEBHOOK_TOKEN` revisado e novo `apply` (o `PUT` troca o token
no Asaas). Entre o `apply` e o `deploy` o Asaas recebe 401 e reenvia (entrega "pelo menos uma vez"; a fila pausa depois de
15 falhas seguidas e precisa ser reativada no painel ou por outro `PUT`).

O executor só fala com `https://api-sandbox.asaas.com/v3/webhooks` (GET da lista, POST, PUT); qualquer outro destino é
`FLY_ASAAS_DESTINATION_DENIED`. O `$` da chave é literal no parser do `flyctl secrets import` 0.4.110 (sem expansão).

## Regras

- **Pagador por unidade** (`cp.finance_payers`): proprietário (`OWNER`) ou responsável (`RESPONSIBLE`) ativo da unidade,
  com nome, CPF/CNPJ (dígitos verificadores conferidos) e e-mail. Um ativo por unidade; trocar substitui (`REPLACED`). A
  API devolve documento e e-mail mascarados; a unidade não lê o cadastro do pagador. O cliente do Asaas é criado com
  referência externa = id do pagador e `notificationDisabled=true` (sem e-mail/SMS do Asaas nesta entrega).
- **Locação** (decisão desta entrega): a gestão escolhe o pagador da unidade alugada (proprietário ou o responsável
  inquilino). A barreira da V034 continua absoluta: proprietário de unidade alugada não vê cobranças da unidade, nem como
  pagador; a tela da gestão avisa e a fatura pode ser enviada pelo link do Asaas.
- **Quem vê na unidade**: morador, responsável e proprietário com vínculo vigente (dependente não), só cobranças já
  emitidas (`PENDING_ISSUE` fica só na gestão).
- **Cobrança** (`cp.finance_charges`): taxa condominial, reserva, multa ou outro; boleto ou PIX; R$ 5,00 a
  R$ 1.000.000,00; vencimento de hoje a dois anos. Nasce `PENDING_ISSUE`; a emissão no Asaas acontece fora da transação,
  com referência externa = id da cobrança e reserva curta (90 s) no banco: antes de criar, procura o pagamento pela
  referência (falha no meio não duplica). Falha deixa `issueError` (`ASAAS_UNAVAILABLE` ou `ASAAS_REJECTED`) e "Emitir"
  tenta de novo. Situações: `OPEN`, `OVERDUE`, `PAID`, `CANCELLED`, `REFUNDED`; cancelada e estornada são finais.
- **Recorrente** (`cp.finance_recurring_fees`): taxa mensal com dia 1–28, para uma unidade ou todas com pagador. "Gerar
  competência" (mês atual a 12 meses) cria uma cobrança por unidade e taxa; repetir não duplica (índice único); vencimento
  já passado é pulado; emite até 20 e o resto fica para "Emitir pendentes". Não há geração automática (sem worker).
- **Cancelar**: motivo obrigatório; exclui no Asaas e depois grava. Paga não cancela (`FINANCE_CHARGE_CLOSED`/`FINANCE_ASAAS_REJECTED`).
- **Sincronizar** (conciliação pontual): relê o pagamento no Asaas e aplica a situação.
- **Webhook** `POST /v1/finance/asaas/webhook`: sem sessão nem CSRF; token conferido antes de qualquer leitura; o corpo
  não é confiável: a situação é relida no Asaas (`GET /payments/{id}`) fora da transação; numa transação dedicada (sem
  ator, sem capacidade, só `app.finance_webhook_charge`) grava o evento (id único), a situação e o outbox
  `FINANCE_CHARGE_UPDATED`. Evento repetido: `DUPLICATE`, nada muda. Pagamento alheio ou não vinculado: `IGNORED` (200).
  Asaas indisponível: 503 (o Asaas reenvia). Status sem equivalente (estorno solicitado, chargeback...) só atualiza o
  status bruto exibido à gestão. Pagamento restaurado no Asaas depois de cancelado não reabre a cobrança.
- **Inadimplência**: cobranças `OPEN`/`OVERDUE` com vencimento antes de hoje (fuso do condomínio), por unidade.
- **Relatório**: por mês de vencimento (1 a 24 meses): emitido, pago, a vencer, vencido e canceladas.
- **Auditoria**: `FINANCE_PAYER_SET`, `FINANCE_CHARGE_CREATED`/`ISSUED`/`CANCELLED`/`SYNCED`,
  `FINANCE_RECURRING_FEE_CREATED`/`ENDED`, `FINANCE_RECURRING_GENERATED`, com motivo quando a ação muda dados. O webhook
  não tem ator: a trilha dele é `cp.finance_webhook_events`.

## Rotas (flag `CP_FINANCE_ENABLED`)

Gestão (`finance.manage`, papel `MANAGER`, AAL2): `GET .../management/finance/units`, `PUT .../units/{u}/payer`,
`GET|POST .../charges` (POST com `Idempotency-Key`), `POST .../charges/issue-pending`, `POST .../charges/{c}/issue|cancel|sync`,
`GET|POST .../recurring-fees`, `POST .../recurring-fees/{f}/end`, `POST .../recurring-fees/generate`,
`GET .../delinquency`, `GET .../report?from=AAAA-MM&until=AAAA-MM`. Unidade: `GET /v1/tenants/{t}/units/{u}/finance/charges`
e `GET .../finance/charges/{c}/payment` (segunda via). Contrato em `contracts/openapi.json`.

## Banco (V036)

RLS ENABLE/FORCE e `tenant_boundary` nas quatro tabelas; barreira `rental_owner_hidden` (V034) nas três com `unit_id`;
pagadores e taxas só com `finance.manage`; cobranças com `finance.manage`, unidade (vínculo vigente, só emitidas) ou o
webhook (só a cobrança identificada); grants por coluna (conteúdo, autoria e horários vêm do banco); sem `DELETE`;
worker e implantação sem acesso. Gatilhos conferem pagador ativo da unidade, igualdade com a taxa ativa, conteúdo
imutável, versão avançando um, transições permitidas e o que o webhook pode mudar. O `DatabaseRoleGuard` recusa role com
`DELETE`/`TRUNCATE` ou colunas a mais nessas tabelas.

## Web

Gestão › **Financeiro**: Cobranças (filtro, emitir, sincronizar, cancelar com motivo, link da fatura), Nova cobrança,
Pagadores (com aviso de unidade alugada), Taxas recorrentes (criar, encerrar, gerar competência, emitir pendentes),
Inadimplência e Relatório. Morador › **Financeiro**: em aberto e histórico; "Segunda via" busca no Asaas na hora a linha
digitável ou o PIX copia e cola (copiar) e os links da fatura e do boleto (somente domínio `asaas.com`).

## Testes

- PostgreSQL (`tests/database/run.py`, `finance_checks`): autoria e situação do banco, pagador só OWNER/RESPONSIBLE, um
  por unidade, documento imutável, unidade não lê pagador, outra unidade/condomínio não veem, pendente invisível à unidade,
  webhook só a cobrança identificada e sem ator, evento repetido ignorado, transições finais, recorrente única por
  competência, proprietário de unidade alugada não vê, trilha só da gestão, sem DELETE, worker sem acesso.
- JDBC (`FinanceJdbcTest`, Asaas em memória): fluxo da gestão, AAL1 recusado, segunda via, negativos de unidade e
  locação, webhook com token errado (401), duplicado e releitura no Asaas, cancelamento, falha de emissão e reemissão sem
  duplicar, sincronização, competência repetida, inadimplência.
- Unitários: `FinanceRulesTest`, `FinanceWebhookServiceTest`, `AsaasHttpGatewayTest` (Asaas simulado em 127.0.0.1, sem rede).
- `tests/fly-api.test.mjs`: chave só do sandbox, token gerado e webhook configurado antes da Fly, PUT idempotente, nada
  impresso, só destinos de webhook do sandbox.
- Web: `api/finance.test.ts` e `e2e/finance.spec.ts` (inclui acessibilidade da tela do morador).

## Pendências e decisões para o responsável

- Cadastrar a variável `CP_ASAAS_WEBHOOK_EMAIL` no Environment `condpulse-database-operations` (e-mail que recebe avisos do
  Asaas sobre o webhook). Sem ela, o primeiro `apply` com a chave falha com `FLY_ASAAS_WEBHOOK_EMAIL_REQUIRED`.
- Confirmar que a chave cadastrada é do sandbox no formato `$aact_hmlg_...`; chave em outro formato é recusada
  (`FLY_ASAAS_KEY_INVALID`) sem eco.
- Com a chave já cadastrada, qualquer `apply` futuro liga o financeiro na Fly (mesmo comportamento do Storage).
- Pagador de unidade alugada: a gestão escolhe; proprietário pagador de unidade alugada não vê a cobrança no app.
- Avisos do Asaas por e-mail/SMS desligados (`notificationDisabled`); ligar exige decisão (custo e dados de terceiros).
- Sem geração automática mensal, multa/juros por atraso, desconto, parcelamento, split, notas fiscais e conciliação
  bancária fora do Asaas (ADR-0008).
- Produção do Asaas (chave, conta, taxas) bloqueada até OK explícito.
