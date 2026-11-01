# Estado atual do CondPulse

Atualizado em 01/11/2026, America/Sao_Paulo. Resumo operacional vigente. STATUS.md conserva o histórico; uma ausência antiga não é uma pendência atual.

## Situação para testes

A homologação está publicada, com dados sintéticos preservados e jornadas reais exercitadas nos acessos existentes. Isto não representa aceite de produção nem conclusão de todos os módulos do PRD.

- Web: https://project-rhvdq.vercel.app — projeto CondPulse; /auth e /v1 encaminham à Fly.
- Banco HML: `rpnrqfrfwywglvyykgrg`, até V055. API `condpulse-v2-0`; worker `condpulse-v2-0-worker`, ambos gru.
- Produção operacional `tkxcujckpsuawnzkvpjg` permanece separada, sem novo apontamento ou escrita desta rodada. O rótulo Vercel Production da web continua usando HML.
- Autorização da rodada: correções, testes e carga sintética. Não apagar/resetar os dados sem pedido posterior.

## Concluído e conferido

| Item | Evidência |
| --- | --- |
| Sessão/MFA | PR #172 integrado (44c5a40). Regressões primeiro falharam no comportamento antigo; CI 41321268569 e revisões passaram no head02a9bbb. Novo login, TOTP e Central/gestão com “Segundo fator confirmado” exercitados na API publicada. |
| Configuração demo | PR #173 integrado (f7928e0), CI41322696996 e revisões concluídos. Inspect41329994162/apply41330326771: profile=demo, changed=[CP_DEMO_ENABLED], true lido de volta e nenhum segredo gerado. |
| V055 | [41329884896](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41329884896): uma migration, target055, validated. [41330443589](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41330443589): repetição validada, zero migrations. SQL confirmou success e dados anteriores preservados. |
| API publicada | [41331226181](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41331226181), SHA f7928e0605883be45181ef528c43e1a7976f0a86: readiness200/UP na primeira consulta, uma máquina gru, flag demo presente. Inspect41330605853, apply41330874165 e inspect41331032067 confirmaram login existente READY/eligible; sem nova geração ou rotação de chaves. |
| Worker publicado | PR #174 integrado (608604a), CI41330570917 e revisões concluídos; CI do merge41332636458 também passou. [41354940226](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41354940226): seis leituras started, zero exits. Outbox, fila de espera e alertas de permanência ligados; pool1 e intervalos60s. Hardware sync desligado. |
| Mesma fonte backend | API f7928e0 e worker608604a têm a mesma árvore apps/backend: e6db1cb29552eb695808982c1c106f2d69de2ea5. A diferença é manifesto de worker, teste do manifesto e documentação; não houve mudança de Java entre essas publicações. |
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

## Testes e limite de desempenho

CI das correções:346 testes unitários web,225 jornadas Chromium e284 JDBC habilitados com zero falhas/erros/ignorados; SecurityLogJdbcTest, SecurityCommandJdbcTest e StayJdbcTest agora entram na execução real. O executor do worker passou em14 testes locais e no CI. A regressão do contador usa o cenário web existente; consulte o CI desta entrega para o resultado do head atual.

Carga [41322229545](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41322229545), SHA44c5a40:100rps/120s/1.000sessões/pool2,11.999chamadas, zero erros/descarte e62 recusas de regra esperadas. p50=18,9ms/p95=805,6ms/p99=1.738,3ms. As classes de leitura ultrapassaram400ms e criação/cancelamento ultrapassaram800ms. O job terminou com sucesso, mas as metas de latência não foram atendidas. Relatório: [load-synthetic-20261101.json](../evidence/load-synthetic-20261101.json). API, banco e gerador estavam no mesmo runner descartável; não é capacidade da HML.

## Pendências reais

1. Vincular explicitamente as contas de quem testará o demo. Nenhuma conta real recebeu papel ao criá-lo. Novos papéis/acessos precisam de confirmação específica; não conceder automaticamente nem fabricar ator/capacidade no SQL.
2. Jornada real de portaria/parceiro, oferta/aceite da fila e alertas de permanência, após os vínculos autorizados. Rotinas publicadas/ligadas não equivalem a esses percursos concluídos; o demo nasceu sem permanências abertas.
3. Profiling da cauda de latência, carga representativa na hospedagem e metas p95.
4. Recuperação completa por e-mail, aparelho mobile, hardware/firmware piloto, backup do Storage e restore cronometrado, RPO/RTO e promoção operacional.
5. Console/fila/SLA, passagem de turno e demais lacunas CPX; Log de Segurança da gestão ainda sem seção web própria. Administradora ERP D1–D9 é outra entrega, não implementação concluída por esta homologação.

Não chamar o sistema inteiro de entregue só por CI, healthcheck ou dados de demonstração. Não repetir como pendência SMTP, hospedagem do worker, V053–V055, configuração do demo ou autenticação que já têm os recibos acima.
