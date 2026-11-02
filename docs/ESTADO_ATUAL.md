# Estado atual do CondPulse

Atualizado em 01/11/2026, America/Sao_Paulo. Resumo operacional vigente. STATUS.md conserva o histórico; uma ausência antiga não é uma pendência atual.

## Situação para testes

A homologação está publicada, com dados sintéticos preservados e jornadas reais exercitadas nos acessos existentes. Isto não representa aceite de produção nem conclusão de todos os módulos do PRD.

- Web: https://project-rhvdq.vercel.app — projeto CondPulse; /auth e /v1 encaminham à Fly.
- Banco HML: `rpnrqfrfwywglvyykgrg`, até V056. API `condpulse-v2-0`; worker `condpulse-v2-0-worker`, ambos gru.
- Produção operacional `tkxcujckpsuawnzkvpjg` permanece separada, sem novo apontamento ou escrita desta rodada. O rótulo Vercel Production da web continua usando HML.
- Autorização da rodada: correções, testes e carga sintética. Não apagar/resetar os dados sem pedido posterior.

## Concluído e conferido

| Item | Evidência |
| --- | --- |
| Sessão/MFA | PR #172 integrado (44c5a40). Regressões primeiro falharam no comportamento antigo; CI 41321268569 e revisões passaram no head02a9bbb. Novo login, TOTP e Central/gestão com “Segundo fator confirmado” exercitados na API publicada. |
| Configuração demo | PR #173 integrado (f7928e0), CI41322696996 e revisões concluídos. Inspect41329994162/apply41330326771: profile=demo, changed=[CP_DEMO_ENABLED], true lido de volta e nenhum segredo gerado. |
| V055 | [41329884896](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41329884896): uma migration, target055, validated. [41330443589](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41330443589): repetição validada, zero migrations. SQL confirmou success e dados anteriores preservados. |
| API publicada — recibo anterior | [41331226181](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41331226181), SHA f7928e0605883be45181ef528c43e1a7976f0a86: readiness200/UP na primeira consulta, uma máquina gru, flag demo presente. Inspect41330605853, apply41330874165 e inspect41331032067 confirmaram login existente READY/eligible; sem nova geração ou rotação de chaves. |
| Worker publicado — recibo anterior | PR #174 integrado (608604a), CI41330570917 e revisões concluídos; CI do merge41332636458 também passou. [41354940226](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41354940226): seis leituras started, zero exits. Outbox, fila de espera e alertas de permanência ligados; pool1 e intervalos60s. Hardware sync desligado. |
| Mesma fonte backend — recibo anterior | API f7928e0 e worker608604a têm a mesma árvore apps/backend: e6db1cb29552eb695808982c1c106f2d69de2ea5. A diferença é manifesto de worker, teste do manifesto e documentação; não houve mudança de Java entre essas publicações. |
| SMTP do Auth | Resend configurado em30/10, remetente nao-responda@condpulse.com.br, recebimento registrado no histórico. Não pedir nova escolha de provedor/chaves. E-mail/push de eventos de negócio não estão implementados no OutboxEffect; caixa interna é outro canal. |
| RLS e log | SQL posterior à V055:69 tabelas cp, zero sem ENABLE/FORCE RLS. Verificador do banco conferiu24 eventos do demo com cadeia íntegra. Isso não substitui testes de autorização; os JDBC cobrem negações. |
| Backup disponível | Painel Supabase conferido após login do responsável: físico de01/11 07:52:12 UTC (04:52:12 local). Anterior à carga de teste; não inclui bytes do Storage. Restore e metas RPO/RTO ainda não comprovados. CAPTCHA/login dessa conferência foram concluídos; não repetir como bloqueio atual. |

## Dados e jornadas reais

| Jornada | Resultado |
| --- | --- |
| Reserva | 3446ff02-529f-4849-b254-338cb98a76fa, salão em03/11 10h–11h local; CONFIRMED, limpeza até11h30. SQL confirmou UTC13h–14h e ocupação até14h30. Segunda reserva em04/11 recusada por cota: original1, nova0. |
| Convidado e visita | Convidado sintético vinculado à reserva; convite de02/11 10h–12h local, 4c264ef2-66bc-4448-98be-4e53d0525403, ACTIVE/Agendado. |
| Pedido e conversa | 4a523c37-1775-4318-9f89-c905b7a5b51e: criado pelo morador, resposta pública da gestão e comentário interno, TRIAGED → IN_PROGRESS → RESOLVED, versão3, prazo03/11. Resolução e conversa preservadas. Morador viu a resposta pública e não viu o texto interno. |
| Anexos | JPEG81.255bytes AVAILABLE; original/banco/download com SHA-256 idêntico. Texto inofensivo rotulado JPEG: REJECTED/TYPE_MISMATCH, sem download. Não é teste de todos os tamanhos/formatos. |
| Outbox e leitura | Resposta pública e atualizações do pedido produziram DELIVERED, uma tentativa e sem erro; quatro avisos internos. Uma abertura autenticada gravou READ_IN_APP; três avisos permaneceram AVAILABLE. RESERVATION_CREATED foi SKIPPED por não haver efeito para esse tipo; não anunciar envio de e-mail. |
| Comunicado | 502f23cc-2512-4692-aae8-30f72905f4ee: rascunho, publicação, abertura pelo morador e uma linha em announcement_reads. É abertura na aplicação, não ciência jurídica. |
| Isolamento da interface | URL com unidade A no condomínio B mostrou Unidade indisponível, sem a reserva de A. Leitura residencial após uso da gestão não exibiu o comentário interno. |
| Demo | dcb28363-d009-46a7-8be4-ffb0795d1927, geração1, [DEMO] Residencial Jardim Fictício; parceiro21b494e6-0847-4073-aa3d-1ef67879f84e. 2torres/32unidades/3áreas,9 vínculos sintéticos,3avisos/3encomendas/2visitas/2ocorrências/2pedidos,6pessoas sintéticas no parceiro,4escalas e12eventos de exemplo (24linhas no log após os demais eventos). Zero vínculos automáticos da conta real. |
| Contador residencial | Concluídos (RESOLVED) deixam de contar como “em andamento”, preservando a linha e a conversa no histórico. Regressão incluída no cenário e2e existente de Pedidos nesta entrega. |

Evidências estruturadas: [hml-functional-20261101.json](../evidence/hml-functional-20261101.json) e [release-hml-20261101.json](../evidence/release-hml-20261101.json). Roteiro: [ROTEIRO_TESTES_HML.md](ROTEIRO_TESTES_HML.md).

## Vínculos explícitos do demo — continuação de 01/11/2026

O responsável confirmou no chat os papéis da própria conta, limitados à geração1 e ao parceiro fictício. Foram exercitados os fluxos normais da aplicação, sem escrita direta de autorização no SQL:

| Papel | Estado observado | Próximo requisito |
| --- | --- | --- |
| Gestão do condomínio demo | ACTIVE; convite da Central aceito pela própria conta; gestão com AAL2 aberta | Nenhum novo consentimento para este vínculo já concluído |
| Gestor do parceiro demo | ACTIVE; concessão na Central e painel com AAL2 aberto | Carteira com1 contrato, equipe com7 pessoas/papéis e4 escalas sintéticas consultadas |
| Portaria do condomínio demo | Convite aceito, vínculo PENDING | Outro gestor ativo precisa aprovar; autoaprovação recusada com409 MEMBERSHIP_SELF_DECISION_DENIED |
| Morador do condomínio demo | Convite da Torre A fictícia,101 criado e aceito; vínculo PENDING, conferido por SELECT | Outro gestor ativo precisa aprovar; confirmação da unidade já recebida |

A recusa de autoaprovação é regra de segurança válida, não erro para remover. Não trocar ator no SQL nem aprovar diretamente na tabela. O responsável já confirmou especificamente a unidade A-101 e uma segunda conta de teste existente como gestor adicional; essa autorização não deve ser pedida novamente. O segundo convite de gestor ainda não foi criado: depende da publicação descrita abaixo e de aceite/autenticação pela própria conta.

O Log de Segurança e as permanências tinham controllers e regras de domínio, mas faltavam os caminhos autenticados em SecurityConfiguration. A consulta real pela web recebia403 antes do serviço. PR #176 inclui os caminhos/métodos exatos e sete testes HTTP (identidade/parâmetros, CSRF/Bearer, MFA/negações, anônimos, métodos vizinhos e flags desligadas); não amplia papéis nem altera RLS. CI 41369953058 no head ba80806 e revisões concluídos. Publicação da API 41371039046 e worker 41371155348, fonte b1d4598273cb14c3f37834de8a779e47424a15ae. Conferência publicada: log consultado, cadeia íntegra com28 eventos e duas leituras SENSITIVE_VIEWED registradas; seção residencial de permanências vazia carregada com vínculo pré-existente na outra HML. A conta também é MANAGER local, portanto esse ensaio não comprova conta exclusivamente de parceiro. Entrada/alerta/saída operacional permanecem não executados. Recibo: [demo-access-hml-20261101.json](../evidence/demo-access-hml-20261101.json).

## Gestor adicional e V056 — continuação de 01/11/2026

A Central permite convidar o primeiro gestor, mas encaminha os gestores adicionais à gestão local. O formulário, serviço e constraint locais ainda recusavam MANAGER. O PR #178 fecha essa lacuna com a decisão explícita [ADR-0013](adr/0013-convite-de-gestor-local.md): gestor atual com AAL2 emite convite sem unidade; somente a conta destinatária aceita; o vínculo permanece PENDING até aprovação de outro gestor com AAL2. A proibição de autoaprovação e a proteção do último gestor permanecem. SUPPORT continua excluído.

- Fonte integrada: `6686a6cbc9d2bd743ca7348ebf79267ef9e3df8e`. CI [41373167469](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41373167469): cinco jobs success,346 Vitest,226 jornadas Chromium e286 JDBC sem falha/erro/ignorado no estágio JDBC; revisões de código e segurança concluídas.
- V056 aplicada pelo executor protegido [41374111396](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41374111396): target056,validated,migrationsExecuted1. Repetição [41374484966](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41374484966): validated,migrationsExecuted0. Histórico Flyway e constraints confirmados por SELECT; sem escrita SQL de vínculos.
- Backup físico de01/11 07:52:12UTC reconferido antes da migration; continua anterior à carga e sem bytes do Storage, sem aceite de restore/RPO/RTO.
- Inspeção anterior da API [41374557917](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41374557917) concluída com sucesso na fonte6686a6c: login existente READY/eligible. Naquele ponto ainda não havia deploy; recibos atuais na linha seguinte.
- Controle do navegador recuperado. Inspeções worker41378055107 e API41378128601: logins existentes READY/eligible, fonte a8f1b3af376014512ac57d9bce5e800ea51a8e66. Publicação do worker41378365139:6started/0exits; API41378543488: readiness200/UP na primeira consulta. O fluxo de gestor adicional está publicado, mas seu convite/aceite/aprovação ainda não foi exercitado na HML.
- Próximo passo: a sessão da primeira conta expirou; refazer login pelo fluxo seguro. Criar o convite já autorizado, obter aceite da segunda conta, aprovação pela gestão atual e AAL2 na segunda conta. O responsável configura o próprio MFA dessa conta sem expor segredo/QR. Depois, o segundo gestor aprova RESIDENT A-101/CONCIERGE PENDING. Não repetir consentimentos nem contornar a autoaprovação.

Recibo atualizado: [demo-access-hml-20261101.json](../evidence/demo-access-hml-20261101.json).

## Testes e limite de desempenho

CI do contador residencial (#175): 41356383522, cinco jobs success, 346 testes unitários web,225 jornadas Chromium e284 JDBC habilitados com zero falhas/erros/ignorados; SecurityLogJdbcTest, SecurityCommandJdbcTest e StayJdbcTest agora entram na execução real. O executor do worker passou em14 testes locais e no CI. O contador também foi conferido na publicação 49ab995: pedido RESOLVED preservado, heading Pedidos sem contagem em andamento e três mensagens públicas; texto interno ausente. A correção HTTP #176 adiciona sete testes de integração sem mudar a quantidade de testes web/JDBC.

Carga [41322229545](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41322229545), SHA44c5a40:100rps/120s/1.000sessões/pool2,11.999chamadas, zero erros/descarte e62 recusas de regra esperadas. p50=18,9ms/p95=805,6ms/p99=1.738,3ms. As classes de leitura ultrapassaram400ms e criação/cancelamento ultrapassaram800ms. O job terminou com sucesso, mas as metas de latência não foram atendidas. Relatório: [load-synthetic-20261101.json](../evidence/load-synthetic-20261101.json). API, banco e gerador estavam no mesmo runner descartável; não é capacidade da HML.

## Execução por fases

O responsável solicitou continuidade em ciclos de verificar/corrigir/testar/registrar. Plano e critérios: [PLANO_FINALIZACAO_HML.md](PLANO_FINALIZACAO_HML.md). Fase1 concluída; fase2 depende de login/aceite/aprovação/MFA próprios. Não há execução permanente em segundo plano criada por este plano. Recibo: [loop-validation-hml-20261101.json](../evidence/loop-validation-hml-20261101.json).

## Pendências reais

1. Concluir login da primeira conta e convite/aceite/autenticação/aprovação da segunda conta já autorizada. #178 está publicado; V056 aplicada/repetida. Morador A-101 e portaria seguem aceitos/PENDING até aprovação de outro gestor ativo com AAL2. Navegador recuperado; impedimento atual: sessão expirada. Não repetir consentimentos.
2. Jornada real de portaria/parceiro, oferta/aceite da fila e alertas de permanência, após os vínculos autorizados. Rotinas publicadas/ligadas não equivalem a esses percursos concluídos; o demo nasceu sem permanências abertas.
3. Profiling da cauda de latência, carga representativa na hospedagem e metas p95.
4. Recuperação completa por e-mail, aparelho mobile, hardware/firmware piloto, backup do Storage e restore cronometrado, RPO/RTO e promoção operacional.
5. Console/fila/SLA, passagem de turno e demais lacunas CPX; Log de Segurança da gestão ainda sem seção web própria. Administradora ERP D1–D9 é outra entrega, não implementação concluída por esta homologação.

Não chamar o sistema inteiro de entregue só por CI, healthcheck ou dados de demonstração. Não repetir como pendência SMTP, hospedagem do worker, V053–V055, configuração do demo ou autenticação que já têm os recibos acima.

## Controle de prestadores/visitantes/festas solicitado nesta continuação

Nova prioridade: [ADR-0014](adr/0014-controle-de-permanencia-e-reentrada.md). Em implementação: V057 e tela de regras com ativação, janela diária, tolerâncias e vínculo da permanência com reserva/área. Não aplicada/publicada nesta etapa. Aviso real ao morador, vinculação de identidade entre cadastros, restrição/liberação responsável de reentrada e confirmação em equipamento ainda precisam ser concluídos/testados. O registro de entrada/saída e os alertas de V055 já existentes não equivalem a essa entrega integral.
