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

## Correções e testes em andamento
- PR #172: `session_not_found`/`session_expired` do provedor deve encerrar a sessão local e exigir login novo; não tratar como código TOTP errado. O erro foi observado no endpoint challenge antes da verificação do código.
- Regressão primeiro: commit `34768b05e9a88d848c0d37f0b782532142c7082d`, run [41318979255](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/41318979255), falha esperada dos testes novos. Resultado da correção deve ser registrado depois do CI.
- CI: `SecurityLogJdbcTest`, `SecurityCommandJdbcTest` e `StayJdbcTest` incluídos na execução JDBC habilitada no PR #172. A primeira execução expôs fixtures antigas; o teste agora exige papel/contrato real e verifica falha fechada quando a leitura não pode ser auditada.
- Backend real: etapa JDBC de 41320514980 concluída com sucesso, incluindo os três testes ativados. A fundação continua nas checagens de imagens; não declarar o workflow inteiro aprovado antes de terminar.
- Ensaio web 41320514980: 224 jornadas passaram; uma falhou porque o próprio teste recarregava a seção já aberta pelo login e contava duas consultas. Correção mantém a exigência de uma leitura, removendo a navegação duplicada do teste. Resultado final ainda depende do novo CI.
- Carga concreta pela interface HML em 01/11: reserva `3446ff02-529f-4849-b254-338cb98a76fa`, unidade `e66c13d1-f21f-47f1-9610-5c2c3cea85dc`, salão em 03/11 das 10h às 11h America/Sao_Paulo, CONFIRMED; banco confirmou UTC 13h–14h e limpeza até 14h30 UTC. Convidado TESTE HML 20261101 Convidado Sintético salvo e visível em Acessos. Preservados.
- Visita concreta: convite `4c264ef2-66bc-4448-98be-4e53d0525403`, visitante TESTE HML 20261101 Visitante Sintético, 02/11 10h–12h local (UTC 13h–15h), ACTIVE no banco e Agendado na interface. Convite de convidado da reserva `de6a0a54-a7f6-49c4-877c-bbff17e41c17` vinculado à reserva, UTC 12h–14h (uma hora antes), também conferido.
- Worker concreto: RESERVATION_CREATED dessa reserva processado em 01/11 15:20:04 UTC, attempts=1, state=SKIPPED, sem erro. Esse tipo não tem efeito implementado em InboxEffect; consumo de fila comprovado, envio de mensagem não. SMTP do Auth não é canal de eventos de negócio.
- Dados em 01/11 antes da carga: dois tenants; zero demo, zero parceiros, zero eventos no Log de Segurança e outbox vazio. Não dizer que o demo está provisionado só porque a V053 existe.
- A autorização do responsável nesta sessão permite correções, testes completos, atualizações documentais e carga de dados sintéticos identificados na HML. Preservar os dados de teste para revisão; não apagar sem pedido posterior.

## Pendências reais
1. Nova sessão autenticada com AAL2 e ensaio das áreas administrativas após a correção.
2. Conferir backup compatível, aplicar/repetir V055 e republicar API/worker no commit correspondente. A consulta de backup pelo painel está bloqueada por CAPTCHA após login seguro; não confirmar a caixa de backup no workflow sem evidência. O inspect 41319118946 atesta somente bootstrap anterior ao baseline; ATTESTATION_FAILED depois do baseline é esperado por desenho (EXECUTOR_BANCO.md), não é evidência de corrupção.
3. Conferir `CP_DEMO_ENABLED` na configuração e na API; provisionar demo e vínculos de teste autorizados.
4. Exercitar gravações reais de reserva, pedido/conversa, anexos, avisos, encomendas, visitas, ocorrências e permanências; conferir efeitos, replay e negações.
5. Provar processamento do outbox. Worker da fila de espera segue false; não confundir com outbox.
6. Console/fila/SLA, passagem de turno e demais lacunas CPX; Log de Segurança na gestão ainda sem seção web.
7. App em aparelho, equipamento físico piloto, carga representativa, backup/restore e promoção operacional.
8. Administradora: D1–D9 e fatias próprias; schema adm ausente na consulta de 01/11.

Não declarar homologado apenas por CI/healthcheck. Registrar conta de teste (sem segredo), ambiente, commit, operação e resultado.
