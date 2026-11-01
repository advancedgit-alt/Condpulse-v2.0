# Estado atual do CondPulse

Atualizado em 01/11/2026, America/Sao_Paulo. Este é o resumo operacional vigente; `STATUS.md` preserva o histórico.
Antes de repetir uma operação, confira o estado remoto e a evidência abaixo. Documento antigo não é pendência nova.

## Ambientes e release
- Repositório `advancedgit-alt/Condpulse-v2.0`, base `ccr-5e725112-3x8hyg`.
- Homologação: web `https://project-rhvdq.vercel.app`; API `condpulse-v2-0` e worker `condpulse-v2-0-worker` na Fly, região gru.
- A etiqueta Vercel Production deste projeto continua usando o banco de homologação `rpnrqfrfwywglvyykgrg`.
- Produção operacional: `tkxcujckpsuawnzkvpjg`, só V001–V017; consulta de 01/11 confirmou zero usuários Auth, zero buckets e zero logins runtime/worker. Não trocar apontamento como atalho.

## Concluído e conferido
| Item | Estado e evidência |
| --- | --- |
| V053 e V054 HML | Aplicadas: migrate [41268794055](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41268794055), target 054, 2 migrations. Repetição [41268965234](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41268965234): zero migrations. SELECT atual de 01/11 confirmou versões 053/054 success. |
| API e worker da release 75c4203 | API [41269213900](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41269213900): readiness 200/UP na primeira consulta. Worker [41269486261](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41269486261): 6 leituras started e zero exits. |
| SMTP | Resend configurado em 30/10 pelo responsável, remetente nao-responda@condpulse.com.br; o registro de recebimento de link/convite está em STATUS. Não pedir nova escolha de provedor como se não existisse. Recuperação segura completa e entrega de cada tipo de mensagem continuam testes distintos. |
| Worker outbox | Hospedado na Fly e `CP_WORKER_OUTBOX_ENABLED=true` desde PR #119. Fila consultada em 01/11 vazia: ainda não equivale a entrega comprovada de evento de negócio. |
| Segredos operacionais | Storage e código de retirada já cadastrados e levados à Fly conforme STATUS/recibos; nenhum valor lido. Conferir funcionamento por teste, não gerar de novo por histórico antigo. |
| Acesso real | Login, seletor com dois condomínios e portal do morador (início, reservas, acessos e encomendas) exercitados em 01/11. Salão de festas e regras carregados. Não foram feitas gravações nessa auditoria inicial. |
| Isolamento estrutural HML | SELECT em 01/11: 67 tabelas cp, todas com ENABLE/FORCE RLS; isso não substitui os testes de negação. |
| PR #170 | Merge autorizado em 01/11: `36511067f40cdd4a251d0e17f2d5621379a522d1`. Inclui V055/permanências. Aplicação HML e deploy ainda precisam de recibo próprio. |

## Correções e testes de 01/11
- PR #172 integrado: `44c5a40e1b48e3e6148ed917c7d3e94f1a6ed8ab`. Códigos exatos `session_not_found`/`session_expired` do provedor encerram a sessão local e exigem login novo, sem expor corpo de erro nem repetir TOTP. A publicação na Fly ainda não ocorreu.
- Regressão primeiro: 34768b0/run 41318979255 reproduziu a falha. Correção e testes no head 02a9bbb: [41321268569](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41321268569), todos os cinco jobs success; revisões automáticas de código e segurança concluídas no mesmo head.
- Evidência do CI: 346 testes unitários web, 225 jornadas Chromium, 284 JDBC reais com zero falhas/erros/ignorados na execução habilitada. SecurityLogJdbcTest, SecurityCommandJdbcTest e StayJdbcTest incluídos; exportação usa papel/contrato real, teste nega capacidade forjada e comprova rollback/falha fechada da auditoria.
- Ensaio anterior 41320514980 encontrou uma recarga duplicada feita pelo próprio teste web. A correção aguarda a rota já aberta pelo login, mantendo a exigência de uma consulta auditada. A suíte completa posterior passou.
- Carga concreta pela interface HML: reserva `3446ff02-529f-4849-b254-338cb98a76fa`, unidade `e66c13d1-f21f-47f1-9610-5c2c3cea85dc`, salão em 03/11 10h–11h local, CONFIRMED. Banco: UTC 13h–14h, limpeza até 14h30 UTC. Convidado sintético vinculado, janela UTC 12h–14h.
- Convite avulso `4c264ef2-66bc-4448-98be-4e53d0525403`, TESTE HML 20261101 Visitante Sintético, 02/11 10h–12h local, ACTIVE no banco e Agendado na interface.
- Pedido `4a523c37-1775-4318-9f89-c905b7a5b51e`, OPEN, título TESTE HML 20261101 — manutenção sintética. Mensagem salva e visível. JPEG de 81.255 bytes aceito, AVAILABLE e baixado; SHA-256 original/banco/download iguais. Texto inofensivo com extensão JPEG recusado, REJECTED/TYPE_MISMATCH, sem botão de download.
- Teste de cota: segunda reserva em 04/11 10h–11h local recusada; SQL confirmou uma reserva original e zero no período recusado. URL de unidade A no condomínio B mostrou Unidade indisponível, sem a reserva de A (evidência da barreira da interface, não substitui RLS).
- Worker: RESERVATION_CREATED da reserva processado em 01/11 15:20:04 UTC, attempts=1, SKIPPED, sem erro. Tipo sem efeito no InboxEffect: consumo comprovado, envio não. SMTP do Auth é outro fluxo.
- Inspeção Fly [41321868132](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41321868132): uma máquina gru; CP_DEMO_ENABLED ausente na Vercel e na Fly. O helper de login devolveu FLY_RUNTIME_INSPECTION_FAILED/eligible=false; nenhum apply/deploy executado. Histórico HML ainda V054 e JAR revisado inclui V055; nova inspeção após migration é necessária.
- Ensaio de carga [41322229545](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41322229545), commit 44c5a40: 100rps/120s/1.000 sessões/pool2 em banco/API descartáveis do runner. Concluído: 11.999 chamadas, 100rps obtidos, zero erros e zero descartes, 62 recusas de regra esperadas. p50=18,9ms, p95=805,6ms e p99=1.738,3ms; cada classe de leitura ultrapassou a meta p95 de 400ms, e criação/cancelamento ultrapassaram 800ms. Execução bem-sucedida não é aceite de desempenho. Relatório: [load-synthetic-20261101.json](../evidence/load-synthetic-20261101.json). Não mede capacidade da HML; profiling e ensaio no ambiente real continuam pendentes.
- Evidência estruturada com IDs, horários e limites: [hml-functional-20261101.json](../evidence/hml-functional-20261101.json).
- Dados em 01/11 antes da carga: dois tenants; zero demo, zero parceiros, zero eventos no Log de Segurança e outbox vazio. Não dizer que o demo está provisionado só porque a V053 existe.
- A autorização do responsável nesta sessão permite correções, testes completos, atualizações documentais e carga de dados sintéticos identificados na HML. Preservar os dados de teste para revisão; não apagar sem pedido posterior.

## Pendências reais
1. Nova sessão autenticada com AAL2 e ensaio das áreas administrativas após a correção.
2. Conferir backup compatível, aplicar/repetir V055 e republicar API/worker no commit correspondente. A consulta de backup pelo painel está bloqueada por CAPTCHA após login seguro; não confirmar a caixa de backup no workflow sem evidência. O inspect 41319118946 atesta somente bootstrap anterior ao baseline; ATTESTATION_FAILED depois do baseline é esperado por desenho (EXECUTOR_BANCO.md), não é evidência de corrupção.
3. Aplicar o perfil demo do executor runtime-flags, copiar pela inspeção/apply Fly com a mesma geração existente e publicar; então criar demo e vínculos pela Central com AAL2. O perfil demo altera somente CP_DEMO_ENABLED e não gera segredos; procedimento em [DEMO_HOMOLOGACAO.md](DEMO_HOMOLOGACAO.md).
4. Completar as gravações de equipe (avisos, encomendas, respostas, ocorrências e permanências), efeitos, replay e negações. Reserva, convidado, visita, pedido/mensagem e upload/download já têm evidência concreta acima.
5. Provar processamento do outbox. Worker da fila de espera segue false; não confundir com outbox.
6. Investigar a cauda de latência no runner (metas p95 ainda não atendidas). Console/fila/SLA, passagem de turno e demais lacunas CPX; Log de Segurança na gestão ainda sem seção web.
7. App em aparelho, equipamento físico piloto, carga representativa, backup/restore e promoção operacional.
8. Administradora: D1–D9 e fatias próprias; schema adm ausente na consulta de 01/11.

Não declarar homologado apenas por CI/healthcheck. Registrar conta de teste (sem segredo), ambiente, commit, operação e resultado.
