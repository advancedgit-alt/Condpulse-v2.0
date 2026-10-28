# Estado verificável - 28/10/2026

## Conferencia da implantacao Vercel - 28/10/2026

- PR#9 integrada em6618dd6e6414eec0160f646d9c033803708fbbe6: CI40626727663 passou no head28d95c4 e CI40627068294 passou no merge.51 Vitest,21 jornadas Chromium e os gates Java/JDBC/imagens ficaram verdes. Deployment dpl_2KvNRRECaeDyNmp89xc7K8eHzJND observado Ready/source6618dd6. As correcoes de login/pool/porta descritas abaixo ja estao integradas; o bloqueio real de credenciais/migrations permanece.
- Novo executor manual de banco preparado em .github/workflows/database-operations.yml, com inspect/baseline/migrate separados, SHA revisado exato, ACKs e alvo fixo. Nenhuma operacao desse executor foi executada. Source/JAR, CA e guard Flyway sao conferidos separadamente da API. Procedimento: EXECUTOR_BANCO.md.
- PR#10, head447235c72a6757ebc4d0825246d1a3d4804d1d68: CI40628971269 passou nos tres jobs. Os9 testes do executor passaram, sem skips: Java exato compilado, baseline oficial em PostgreSQL descartavel, V003-V005 aplicadas, replay zero e rejeicao de checksum/historico incompatível. Revisao independente aprovada. Localmente7 passaram e2 ficaram sem execucao por falta de JDK/JAR/PostgreSQL; a prova completa e do CI. A correcao documental seguinte apenas alinha os runbooks a V003-V005, sem mudar executor ou migrations.
- GitHub Environment vazio condpulse-database-operations criado; regra salva permite apenas a branch ccr-5e725112-3x8hyg(1branch/0tags). Nenhum segredo foi adicionado. O unico valor privado esperado e CP_DATABASE_OPERATIONS_PASSWORD, senha postgres ja existente do proprio projeto CondPulse, somente no cofre do executor. CA publica e parametros de destino sao obtidos/validados pelo job. Nao coloca a identidade DDL na Vercel ou na aplicacao. O novo pacote depende de revisao/CI antes de operacao.

- PR#8 integrada em401605522fa2f2a470bb480a406c598f7f508042. CI40620673228 passou nos tres jobs no proprio merge; CI40620361516 tambem passou no head revisado de5c66f. O deployment daquela etapa dpl_45ufsie9cUqg4GHb3UZyt4418eWS esta Ready com source4016055. As referencias antigas abaixo documentam cada etapa, nao substituem esta conferencia.
- A publicacao ainda NAO esta funcional: GET /v1/me retorna500 FUNCTION_INVOCATION_FAILED. Log atual do painel em28/10 as00:05 registra VERCEL_API_RUNTIME_IDENTITY_REQUIRED antes de iniciar Spring. O build concluido nao provisionou a identidade do banco.
- Vercel possui somente as sete configs iniciais em Production. Ausentes DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD, CP_VERCEL_DB_CA_BASE64 e as tres chaves/configs BFF para habilitacao posterior. Nao preencher com marcadores ou identidade administrativa na API.
- SELECT remoto confirmou13 tabelas, nenhuma platform.flyway_schema_history, web_sessions, auth_rate_limits ou onboarding_runs; existem somente cp_api e cp_worker NOLOGIN, sem SUPERUSER/BYPASSRLS. Nao houve escrita remota nesta verificacao. A senha postgres nao e recuperavel pelo painel; nenhum cofre ou executor CondPulse foi informado/identificado. Nao resetar senha nem importar segredo Advanced.
- Correcao em revisao: launcher exporta o pool/porta efetivos e bloqueia overrides SERVER_/MANAGEMENT_; preflight exige esses valores explicitamente. Teste da imagem remove defaults OCI e comprova que os defaults exportados chegaram ao Java antes da validacao de CA, sem iniciar Spring/rede.
- Correcao web em revisao: /v1/me401 somente oferece formulario depois de /auth/csrf valido; indisponibilidade, BFF desligado, renovacao falha ou resposta incerta mantem acesso indisponivel. Probe descarta o token e mutacoes continuam buscando CSRF novo. Nao comprova JDBC/Auth real. Local:51 Vitest, tipos e build passaram;21 jornadas de navegador dependem do CI. Revisao independente aprovada condicionada a esse run.
- Evidencia atual: docs/evidence/vercel-readiness-20261028.json. Bloqueio operacional: acesso JDBC de migracao em cofre/executor proprio, CA validada, baseline oficial002 e V003-V005; depois login runtime exclusivo e configuracao BFF, cadastro aprovado e ensaio de Auth real. A conexao automatica Vercel continua sem acesso ao projeto(403); logs/configs foram conferidos no painel autorizado.

## Código revisado e validado

- PR#1 integrada em b933891: fundação, JWT, migrations e isolamento SQL.
- PR#2 integrada em1446d171: BFF com tokens cifrados, cookie opaco, CSRF/Origin, refresh lease/CAS e logout durável. CP_BFF_ENABLED=false.
- PR#3 integrada em c05501e: fatia residencial de unidades, áreas, criação e cancelamento de reservas. CI40602994695 passou no head8d790ad. CP_RESERVATIONS_ENABLED=false.
- TenantTransactionExecutor revalida conta/vínculo/unidade, usa transação própria e limpa contexto local na mesma conexão.
- Reservas usam política explícita, aceite de versão, snapshot, GiST, cota de admissão por unidade, idempotência e auditoria/outbox atômicos. Cancelamento exige autoria.
- Catálogo de unidades é próprio e ativo; catálogo de áreas exige capacidade residencial. Contrato OpenAPI corresponde às rotas implementadas.
- ADR-0004 delimita o subset e as jornadas ainda ausentes. Portal web residencial implementado na etapa PR#4; demais módulos e aplicativo Expo pendentes.
- PR#4 integrada em c30db60: portal residencial. CI final40607550284 passou nos jobs web/foundation no head8a24bf6.

## Etapa web residencial

- React/TypeScript estrito, Router e Query; versões diretas fixadas, lockfile e Lato local com licença OFL.
- Login, escolha de contexto, regras/agenda, criação/cancelamento e saída chamam somente a API da própria origem, com cookie/CSRF e sem tokens persistidos.
- Resposta incerta mantém corpo/chave, sem retry cego ou confirmação otimista. Troca e negação de contexto removem dados antigos; revalidação válida preserva o rascunho.
- Verificação local: build/typecheck,29 testes Vitest,7 jornadas Chromium (API sintética só no runner), inspeção desktop/móvel e controles50px. Auditoria npm de produção retornou zero alertas na consulta.
- CI40606655792 / head2402a38 passou nos jobs web e foundation:29 Vitest, build/typecheck, imagem Docker readonly/CSP e7 jornadas Chromium contra Nginx em4180. Regressões de Java, JDBC, SQL e Flyway também passaram.
- Dockerfile web com bases por digest, Nginx sem root, CSP e proxy privado. Evidências vinculadas aos tickets e à PR#4.
- Estado real sem API responde com aviso de indisponibilidade e entrada bloqueada; não simula login ou reservas. Ver docs/WEB_IMPLANTACAO.md.

## Pacote de implantação

- PR#5 integrada em8cd977a; CI40610106204 passou nos jobs foundation, web e release-config no head64220fc.
- CP-002/CP-009/CP-066: pacote em implementação, sem release promovida. API usa bases Maven/JRE por digest, probe local e runtime sem root/readonly; web mantém bases fixadas.
- Aplicação e migrador possuem manifestos separados, CA readonly, TLS verify-full, limites e logs com rotação. A API não recebe variáveis DDL e somente a web participa do proxy. BFF/reservas permanecem false.
- Preflight valida configuração expandida sem ecoar valores: rejeita identidade/ambiente divergente, imagem mutável, exposição da API, TLS inadequado, privilégios adicionais e mistura de credenciais.
- Gate: `node --test tests/release-config.test.mjs tests/release-compose.test.mjs` renderiza os dois Compose com valores sintéticos, sem daemon ou rede. CI também constrói/testa a imagem API e o proxy após troca do endereço da API; resultados por head registrados na PR de implantação.
- Verificação local:13 testes Node passaram ao incluir também tests/foundation.test.mjs; renderização real com Compose5.2 e sintaxe dos dois scripts Bash conferidas. Docker local sem daemon: ensaios de containers ficam no CI, sem simular resultado local.
- O smoke da imagem usa PostgreSQL descartável sem TLS; não substitui o ensaio TLS/pooler gerenciado ou Auth real. Nada foi publicado, aplicado ao banco remoto ou contratado nesta etapa. Procedimento: docs/DEPLOY_COOLIFY.md.

## Transição do bootstrap

- PR#6 integrada em26667a8. CI40613593316 no head ed613f3 passou nos três jobs foundation/web/release-config, incluindo baseline oficial em banco descartável, V003 separada e launcher Docker. Revisão independente final sem bloqueadores; equivalência dos143blobs locais/remotos conferida antes do merge. Esse resultado não executou baseline no Supabase.
- Nova conferência remota em27/10/2026, somente leitura: os6 inventários do manifesto coincidem (13 ACLs,124 colunas,2 migrations nativas,37 índices,14 policies,101 constraints). As18 verificações extras também coincidem;13 tabelas ENABLE/FORCE RLS, ator/ownerpostgres e nenhuma história Flyway. Advisor de segurança retornou lints=[].
- Hashes dos comandos nativos foram reconferidos, sem reescrever histórico. Relatório resumido em docs/evidence/baseline-readiness-20261027.json, sem dados de negócio.
- Executor Java puro em target baseline separado da API; inspect por padrão, configuração restrita ao projeto da ADR-0003, fontes/manifesto imutáveis, lock de sessão e ACK por fingerprint. A chamada de escrita é somente Flyway.baseline002; validate/migrate permanece uma operação separada.
- Local:15 testes Node passaram, incluindo os3 Compose. Java/JDBC e launcher da imagem passaram no CI acima; não foram executados localmente por ausência de Java/Maven/daemon Docker.
- Atestado do catálogo não comprova TLS/JDBC remoto, Auth, backup ou disponibilidade. Janela exclusiva sem DDL externo, cofre/credenciais e executor remoto ainda pendentes. Nenhum baseline, V003, fixture ou credencial foi gravado no Supabase nesta etapa. Procedimento: docs/BASELINE_FLYWAY.md.

## Ensaio integrado de homologação

- PR#7 integrada em9592f98: complemento explícito compose.homologation.yaml, inventário próprio e preflight --homologation exigem chaves BFF, HTTPS e redes condpulse-hml. CI final40616259334 no head896659d passou nos três jobs. A stack base continua com flags false e rejeita as variáveis de ativação. Nenhum complemento foi aplicado a host ou projeto remoto.
- Verificação local:23 testes Node passaram (4fundação,11release,1renderização dos4Compose e7guards do ensaio HTTPS). Compose5.2.0 real, sem daemon/rede. O preflight não atesta ingresso restrito, entropia/posse de chaves, privilégios do banco ou migrations remotas.
- Runner tests/integration/https-bff-smoke.mjs usa JAR Spring, Security/JWT/JWKS/cifra, runtime JDBC e PostgreSQL reais, com Auth e origem HTTPS sintéticos somente no runner. Cobre sessão durável, contexto, reserva/replay/cancelamento, refresh/logout, CSRF/Origin e vínculo revogado. Ambiente dos subprocessos tem allowlist para impedir configuração externa de conexão.
- Ensaio HTTPS/Java/PostgreSQL passou no CI40615865225 / head5d76d158: login/contexto, reserva/replay/cancelamento, refresh/logout, cookies, CSRF/Origin e revogação. A sessão cifrada, seu encerramento e contagens1:2:2 de reserva/auditoria/outbox foram conferidos no banco real. O runner busca CSRF por mutação, como a web, e exige404 TENANT_ACCESS_DENIED para vínculo revogado. Revisão independente final sem bloqueadores.
- Os três jobs foundation/web/release-config passaram nesse commit:110 testes Java unitários/HTTP,24 JDBC runtime e8 JDBC baseline,29 Vitest,7 jornadas Chromium,23 verificações Node/Compose/guards e testes das imagens/proxy. Os32 JDBC ignorados no primeiro estágio foram executados nos dois estágios próprios, sem skips.
- Não executado localmente por ausência de Java/Maven/banco. O teste serve assets reais, mas não executa navegador nem Nginx. Não substitui TLS/JDBC gerenciado ou Supabase Auth real. Procedimento e gates em docs/HOMOLOGACAO.md; evidência por commit em docs/evidence/https-homologation-ci-20261027.json.
- Reconferência remota somente SELECT:13 tabelas, todas com ENABLE/FORCE RLS; platform.flyway_schema_history e platform.web_sessions ainda ausentes. Nenhum baseline/V003, fixture, credencial, contratação ou publicação nesta etapa.

## Lote operacional - código validado e deployment em verificação

- PR#8 aberta na branch feat/operations-onboarding, head9e5b5fb638c261b6d6f6f26c936f85a6e48d1160. CI40619649142 concluído com success nos três jobs foundation/web/release-config. O CI citado valida esse commit; atualizações posteriores de documentação exigem conferência do head final na PR. Os resultados antigos acima continuam ligados aos seus próprios commits.
- PRD original finalmente conferido na cópia legível de136574bytes,1685linhas, de CondPulse_PRD_Arquitetura_v1.0.md. Para esta frente, capítulos6/7/15/16 confirmam identidade visual, MOR-01, cache por contexto, estados e respeito a Retry-After. A implementação continua parcial: não há atalhos ativos para módulos ausentes nem inferência de disponibilidade geral pela agenda da unidade.
- Limitação compartilhada de login/refresh implementada com buckets HMAC sem e-mail/IP/cookie bruto no banco, janelas globais e por principal e coleta limitada de expirados. V004 acrescenta tabela com RLS forçado. Resposta429 inclui Retry-After; indisponibilidade do limitador retorna503 antes do provedor/lease. Esses dois casos preservam a sessão de refresh; logout não consulta o limitador. Chave HMAC de32bytes precisa ser independente da chave de cifra e vir do cofre. Java/JDBC e ensaio HTTPS dessa frente passaram no CI citado; Supabase real permanece pendente.
- Frontend: espera de login acessível, deadline monotônico e ausência de retry automático;429 e503 do limitador mantêm a sessão conhecida, com saída disponível. Painel /morador/:tenantId/:unitId/inicio consulta a unidade verificada e até três próximas reservas ativas em30dias locais, trata dados antigos e rejeita payload de outra unidade. Troca/revogação remove cache/cartões; navegação web e móvel permanece no contexto selecionado.
- Onboarding operacional inicial implementado em CLI/target Docker próprio, sem HTTP/DDL/mutação Auth. V005 acrescenta grupo operacional e recibo append-only. Manifesto estrito, verificação Auth Admin por UUID antes da transação, inspect padrão, ACK ligado a projeto/operador/conteúdo, apply transacional e reconciliação de UNKNOWN. Não cria usuário Auth nem reativa conta local suspensa. Nenhum manifesto real ou cadastro remoto foi aplicado. Procedimento: OPERACAO_ONBOARDING.md. CP-020 continua sem aceite integral; convite, tutorial, recuperação e dispositivo não estão implementados.
- Verificação local:40 Vitest passaram; typecheck e build web passaram.21 testes Node de fundação, preflight/renderização Compose e configuração Vercel passaram com o comando node --test tests/foundation.test.mjs tests/release-config.test.mjs tests/release-compose.test.mjs tests/vercel-config.test.mjs. O ensaio de configuração usa Compose5.2.0 sem daemon ou conexão remota.
- Chromium:12 jornadas passaram no CI, sendo7 anteriores e5 novas para espera429, refresh/logout e painel/isolamento. As11 tentadas localmente antes do último caso foram bloqueadas em page.goto por ERR_NETWORK_ACCESS_DENIED no loopback, antes de qualquer asserção funcional; essa limitação local não foi atribuída ao produto. As jornadas do CI usam APIs sintéticas, separadas do ensaio HTTP integrado com JAR/PostgreSQL reais.
- Revisão estática independente do frontend e do limitador sem bloqueadores. O CI passou Java/JDBC, migrations V001–V005/replay/isolamento em banco descartável, HTTPS integrado com o limitador, imagens API/web/baseline/onboarding/Vercel e validação de configuração. O launcher onboarding foi ensaiado sem rede. Merge depende também da revisão final de backend/configuração e do head exato da PR. Evidência: docs/evidence/operations-onboarding-ci-20261027.json.
- Contagens Java desse run:136 testes unitários/HTTP passaram no primeiro estágio (180 encontrados,44 JDBC ignorados antes do banco); depois36 JDBC runtime/onboarding/limites e8 JDBC baseline passaram nos estágios próprios, sem skips. A web passou40 Vitest e12 jornadas Chromium; não somar os44 inicialmente ignorados como testes executados naquele primeiro estágio.
- Projeto Vercel exclusivo condpulse criado no time sindicoadvanced: prj_IeUa4Zjg1IwaPqJQwv525H14I837. O painel passou a mostrar Git conectado e deployment Ready dpl_7NBrikhozHriUpTWymxr7KmwgJmA, source9e5b5fb, com build web e build/push da imagem API concluídos. Alias observado: https://project-rhvdq.vercel.app. Imagem API observada: sha256:f4148627f9f5c35ffcbb802a54dbf66af008c04d254ddb1e8ff8c36b22dda850. Esse estado foi observado no painel durante a sessão; o gatilho da publicação não é atribuído a esta tarefa. Ready comprova build/deployment, não conexão JDBC, rotas de negócio ou Auth real. Builds concorrentes sob demanda foram desativados somente no projeto CondPulse. Não foram importados segredos/serviços da Advanced, contratado domínio/VPS ou feito upgrade.
- Sete configurações de produção foram salvas somente no projeto Vercel CondPulse: PORT8080, pool2, issuer/JWKS do Supabase CondPulse, origem https://project-rhvdq.vercel.app e flags BFF/reservas false. Não foram fornecidas credenciais de banco ou segredos da API. A página pública renderiza "Não foi possível verificar seu acesso", com entrada bloqueada por API sem banco configurado. Isso confirma a interface publicada e o estado de indisponibilidade, não login funcional.

## Evidência automatizada

- CI40602994695 / head8d790ad: 90 testes Java de domínio/serviços/HTTP passaram. Os24 JDBC são ignorados no primeiro estágio, antes do banco migrado.
- Estágio JDBC dedicado:24 testes passaram, sem skips, com PostgreSQL17.11, Hikari e login cp_ci_login membro de cp_api, sem SUPERUSER/BYPASSRLS.
- Banco real: contexto limpo após commit/rollback, troca entre tenants, revogação antes do callback/replay, unidades próprias, sessões, crash/lease vencida e logout.
- Reservas reais: disputa de slot e cota entre áreas, mesma chave concorrente, corpo divergente, cancelamento por versão, outro morador da mesma unidade negado e regras aceitas preservadas. Contagens verificam commit de reserva/auditoria/outbox/idempotência.
- 4 verificações Node,25 verificações SQL, migrations/replay Flyway e smokeHTTP passaram no mesmo run.
- Fixtures são sintéticas em banco descartável. Nenhum desses resultados constitui aceite integral de produto ou ensaio com Supabase Auth real.

## Supabase remoto

- Projeto rpnrqfrfwywglvyykgrg em sa-east-1, PostgreSQL17.11.0.003.
- V001/V002 aplicadas atomicamente na migration nativa20261027225434, conforme ADR-0003. V003 ainda não aplicada remotamente; baseline Flyway oficial ainda não executado.
- 13 tabelas com ENABLE/FORCE RLS;124 colunas,101 constraints,14 policies e37 índices atestados.
- cp_api/cp_worker NOLOGIN, sem SUPERUSER/BYPASSRLS/CREATEROLE/CREATEDB. Runtime exige login próprio no cofre.
- Manifesto distingue hashes dos blobs Git e da representação executada com um LF final adicional; instruções SQL e histórico aplicado preservados.
- Auth existente preservado e conta confirmada reconciliada, sem inventar vínculo de condomínio.
- Buckets privados cp-documents(PDF20MiB), cp-parcel-photos(JPEG/PNG10MiB) e cp-upload-quarantine(PDF/JPEG/PNG20MiB), sem políticas públicas.
- Auth configurado com confirmação de e-mail, bloqueio de senha vazada, troca segura, senha atual na alteração, mínimo12 caracteres e OTP900s. A senha do usuário não foi alterada. Security advisor retornou lints=[] na conferência anterior.
- Esta fatia não executou novas migrations nem gravou fixtures no remoto.

## Próximos gates

- Deployment Vercel Ready observado: validar efetivamente a alternativa Services/Container Runtime beta, fornecer runtime/CA/cofre e origem HTTPS estável e testar rotas, cookies, readiness, JDBC e Auth. A URL observada e o build concluído não comprovam jornada funcional. Coolify dedicado continua alternativa sem host exclusivo disponível. Domínio próprio não é exigido para o ensaio com URL gerada vercel.app.
- Atestar bootstrap e executar baseline002 oficial via migrador JDBC; validate/migrate paraV003/V004/V005. Não fabricar flyway_schema_history nem usar baseline-on-migrate. As migrations novas ainda não foram aplicadas remotamente.
- Ensaiar TLS/pooler gerenciado e Auth/refresh/logout reais, limitação compartilhada e expiração sob falha de rede. O JDBC descartável não substitui esse ensaio. Configurar cofre, SMTP e segundo ambiente isolado; comprovar backup/restore antes do lançamento.
- Executar inspect/revisão/ACK/apply do onboarding com dados aprovados e principal exclusivo; não usar fixtures. Configuração posterior de áreas, aprovação/rejeição/expiração, convidados/revogação, fila/recorrência, gestão, demais telas web, aplicativo Expo, MFA, onboarding do usuário/recuperação, carga e integração física permanecem pendentes.
- Os tickets evoluem como EM_IMPLEMENTACAO e os60 cenários de aceite de produto continuam não concluídos. Nenhuma alteração na Advanced.
