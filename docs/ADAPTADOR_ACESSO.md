# Adaptador de acesso — contrato interno e simulador (CP-043)

Código: `apps/backend/src/main/java/br/com/condpulse/accessintegration`. Contrato de origem: `spec/api/contrato_integracao.md`
(proposta de domínio, não documentação de fabricante). Nenhum parceiro, equipamento ou endpoint real é contatado; o
simulador não está ligado a nenhum perfil da aplicação.

## Contrato (`AccessAdapter`, `AccessModels`)

Operações: `capabilities`, `upsertPerson`, `applyCredential`, `getApplication`, `revokeCredential`, `getEvents`
(cursor, janela de até 24 h, até 100 por página), `health` e `requestOpen` (desligada no lançamento). Resultado por
destino: `ACCEPTED`, `APPLIED`, `REJECTED`, `RATE_LIMITED` (com `retryAfter`), `UNKNOWN`, `UNAVAILABLE`, cada um com a
conduta da tabela do contrato; só `APPLIED` ativa algo para a pessoa. Envelope de evento normalizado `schemaVersion 1`;
direção, evidência e versão ausentes continuam ausentes (null).

## Simulador (`SimulatedAccessAdapter`)

Determinístico, em memória, com relógio injetado e um cenário por equipamento: `CONFIRM`, `ACK_ONLY`,
`TIMEOUT_APPLIED`, `TIMEOUT_LOST`, `REJECT`, `RATE_LIMITED`, `OFFLINE`. Regras impostas:

- Comando idempotente por credencial, versão e ação: repetir devolve o mesmo trabalho sem aplicar de novo; destino
  incerto, limitado ou fora do ar é tentado de novo na repetição, recusado e confirmado não.
- Confirmação parcial: cada destino tem o próprio resultado; uma falha aparece no destino, sem esconder os demais.
- Tempo esgotado é `UNKNOWN`; a consulta (`getApplication`) mostra se a versão chegou (`APPLIED`) ou não
  (`UNKNOWN`/`VERSION_NOT_PRESENT`), e só então a repetição é segura.
- Recebimento (`ACCEPTED`) não é aplicação; a confirmação tardia de versão antiga é descartada.
- Revogação tem prioridade: versão menor que a aplicada é `STALE_VERSION`; mesma versão com outra ação é
  `VERSION_CONFLICT`; nada reativa credencial revogada.
- Eventos duplicados mantêm o mesmo `eventId` (a deduplicação é da caixa de entrada, CP-047).
- Abertura remota desligada por padrão; ligada no simulador, pedido vencido, equipamento fora do ar ou repetição
  nunca abre de novo nem vira fila.

## Evidências

`SimulatedAccessAdapterTest` (11 cenários). Rodado localmente com `javac` e um executor mínimo de teste (sem Maven
local) e no CI `foundation` com JUnit.

## Pendentes

Adaptador real (CP-044) depende de parceiro, contrato e sandbox — BLOQUEADO: escolha e contrato do parceiro físico —
responsável — comercial/integrador. Orquestração de credenciais (estado desejado, CP-045/CP-046) e caixa de entrada
autenticada (CP-047) ainda não existem.
