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
- CI: `SecurityLogJdbcTest`, `SecurityCommandJdbcTest` e `StayJdbcTest` precisam entrar na execução JDBC habilitada. Os dois primeiros estavam ignorados no verify da base auditada.
- Dados em 01/11 antes da carga: dois tenants; zero demo, zero parceiros, zero eventos no Log de Segurança e outbox vazio. Não dizer que o demo está provisionado só porque a V053 existe.
- A autorização do responsável nesta sessão permite correções, testes completos, atualizações documentais e carga de dados sintéticos identificados na HML. Preservar os dados de teste para revisão; não apagar sem pedido posterior.

## Pendências reais
1. Nova sessão autenticada com AAL2 e ensaio das áreas administrativas após a correção.
2. Confirmar a V055 aplicada/repetida e republicar API/worker no commit correspondente.
3. Conferir `CP_DEMO_ENABLED` na configuração e na API; provisionar demo e vínculos de teste autorizados.
4. Exercitar gravações reais de reserva, pedido/conversa, anexos, avisos, encomendas, visitas, ocorrências e permanências; conferir efeitos, replay e negações.
5. Provar processamento do outbox. Worker da fila de espera segue false; não confundir com outbox.
6. Console/fila/SLA, passagem de turno e demais lacunas CPX; Log de Segurança na gestão ainda sem seção web.
7. App em aparelho, equipamento físico piloto, carga representativa, backup/restore e promoção operacional.
8. Administradora: D1–D9 e fatias próprias; schema adm ausente na consulta de 01/11.

Não declarar homologado apenas por CI/healthcheck. Registrar conta de teste (sem segredo), ambiente, commit, operação e resultado.
