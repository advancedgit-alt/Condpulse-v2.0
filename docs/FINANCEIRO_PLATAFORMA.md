# Financeiro da plataforma (fase 5)

Flags: `CP_PLATFORM_ENABLED` e `CP_PLATFORM_BILLING_ENABLED` (padrão `false`, inclusive na Vercel). Com qualquer uma
desligada as rotas `/v1/platform/billing/*` não existem; com o financeiro desligado a sessão do painel também omite
`billing.*`, para a tela não oferecer a aba. V011 não foi aplicada a nenhum banco remoto.

## Escopo

Relação comercial da assinatura CondPulse com cada condomínio: planos, assinaturas, cobranças por período e
pagamentos **registrados manualmente**. Fora do escopo: contabilidade condominial, rateio, boleto, gateway, cobrança
automática, emissão fiscal e bloqueio por atraso. Nenhum preço é presumido: planos só entram com valor aprovado pelo
responsável, com o motivo e a referência da aprovação na auditoria.

## Rotas

| Operação | Rota | Capacidade |
| --- | --- | --- |
| Planos | `GET/POST /v1/platform/billing/plans`, `PUT /v1/platform/billing/plans/{id}` | `billing.read` / `billing.manage` |
| Assinaturas | `GET/POST /v1/platform/billing/subscriptions`, `POST …/subscriptions/{id}/end` | `billing.read` / `billing.manage` |
| Gerar cobrança de um período | `POST …/subscriptions/{id}/charges` | `billing.manage` |
| Cobranças (filtro `OPEN`, `OVERDUE`, `PAID`, `CANCELLED`) | `GET …/charges` | `billing.read` |
| Cancelar cobrança, registrar pagamento | `POST …/charges/{id}/cancel`, `POST …/charges/{id}/payments` | `billing.manage` |
| Estornar pagamento registrado | `POST …/payments/{id}/void` | `billing.manage` |

As capacidades vêm do papel `PLATFORM_ADMIN` com AAL2, publicadas por transação como no painel master
([PAINEL_MASTER.md](PAINEL_MASTER.md)).

## Regras

- **Valores** em centavos inteiros, de R$ 0,01 a R$ 1.000.000,00 (sem plano gratuito neste recorte). **Datas civis**
  (AAAA-MM-DD, conferidas no calendário) no fuso America/Sao_Paulo, a partir de 2000.
- **Plano**: nome único, preço e período (mensal ou anual), disponível ou não; alteração pela versão lida. Preço novo
  vale só para assinaturas novas.
- **Assinatura**: uma ativa por condomínio; valor e período copiados do plano disponível na contratação (o banco
  confere); início e vencimento entre os dias 1 e 28; nova assinatura não começa antes do encerramento da anterior.
  Encerrar exige data depois do início e nenhuma cobrança não cancelada além dela; a data pode ser futura
  (encerramento agendado: a tela mostra "Encerramento agendado" até a data). Encerrar não suspende o condomínio
  (suspensão é ação própria do painel).
- **Cobrança**: um período alinhado ao início da assinatura (mesmo dia de cada mês, ou mesma data a cada ano), com o
  valor contratado; vencimento no primeiro dia de vencimento a partir do início do período. Java e banco calculam fim,
  valor e vencimento pela mesma regra; o banco recusa divergência. Períodos não cancelados do mesmo condomínio não se
  sobrepõem (restrição de exclusão), inclusive entre assinaturas; período cancelado pode ser cobrado de novo.
  Assinatura encerrada ainda aceita cobrança de período que termina até o encerramento (cobrança esquecida).
  "Vencida" é calculada na leitura (em aberto com vencimento anterior a hoje) e **não bloqueia o acesso**.
- **Pagamento**: valor igual ao da cobrança (sem parcial), data até hoje, forma (Pix, transferência, boleto, outro) e
  referência do comprovante (3 a 200 caracteres). Um comprovante registrado (forma + referência, sem diferença de
  caixa ou espaços nas pontas) quita uma única cobrança; estornado, pode quitar a cobrança certa. A cobrança só fica
  "paga" junto com o pagamento: um gatilho diferido confere no commit que `PAID` ⇔ exatamente um pagamento `RECORDED`
  do mesmo valor. Estorno marca o pagamento como `VOIDED`, com motivo e responsável, e reabre a cobrança; cobrança paga
  não é cancelada sem estorno. Cancelamento, estorno e encerramento são finais (o banco recusa a volta).
- **Auditoria**: cada ação grava um evento `BILLING_*` na trilha da plataforma (com motivo quando exigido). O banco só
  aceita `BILLING_*` com `billing.manage` e `TENANT_*` com `tenants.manage`.
- **Banco (V011)**: RLS forçado por capacidade; o `UPDATE` só alcança linhas com `billing.read` e grava só com
  `billing.manage`; valores, vínculos e datas de criação ficam fora do grant de alteração; sem `DELETE`; worker e
  onboarding sem acesso. Gatilhos `BEFORE` conferem a inclusão (assinatura igual ao plano disponível e sem sobrepor a
  anterior; cobrança alinhada, com valor, fim e vencimento da regra e dentro do encerramento; pagamento com data até
  hoje) e a alteração (somente transições permitidas, versão avançando uma unidade, horários pelo relógio do banco).
  Registro de referência invisível (sem `billing.read`) faz a escrita falhar fechada. O `DatabaseRoleGuard` recusa
  role de execução com `DELETE`/`TRUNCATE` nessas tabelas ou sem RLS forçado nelas.

## Tela web

Aba **Financeiro** em `/master`, visível só com `billing.read`: Cobranças (filtro por situação; registrar pagamento,
cancelar, estornar), Assinaturas (nova, com busca do condomínio por nome ou código; gerar cobrança; encerrar) e Planos
(novo, alterar). A lista de assinaturas mostra até 500, com aviso ao atingir o limite. As mensagens vêm do que o
servidor devolveu; erro de rede ou do servidor depois do envio aparece como resultado incerto, com orientação de
atualizar e conferir antes de repetir (o banco impede pagamento duplicado e versão antiga).

## Pendências

- Limite conhecido: o banco não exige o evento de auditoria na mesma transação da alteração financeira; o serviço
  sempre grava os dois juntos (mesma transação), mas SQL direto com a role da aplicação poderia alterar sem trilha.
- Aplicação remota da V011 com aprovação; planos e valores reais aprovados pelo responsável.
- Conciliação com extrato bancário, aviso de vencimento (worker/SMTP) e relatórios.
- Ensaio com Auth real (AAL2) e aceite em navegador com dados reais.
