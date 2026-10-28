# Estado verificável - 27/10/2026

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

- Nova conferência remota em27/10/2026, somente leitura: os6 inventários do manifesto coincidem (13 ACLs,124 colunas,2 migrations nativas,37 índices,14 policies,101 constraints). As18 verificações extras também coincidem;13 tabelas ENABLE/FORCE RLS, ator/ownerpostgres e nenhuma história Flyway. Advisor de segurança retornou lints=[].
- Hashes dos comandos nativos foram reconferidos, sem reescrever histórico. Relatório resumido em docs/evidence/baseline-readiness-20261027.json, sem dados de negócio.
- Executor Java puro em target baseline separado da API; inspect por padrão, configuração restrita ao projeto da ADR-0003, fontes/manifesto imutáveis, lock de sessão e ACK por fingerprint. A chamada de escrita é somente Flyway.baseline002; validate/migrate permanece uma operação separada.
- Local:15 testes Node passaram, incluindo os3 Compose. Os testes Java/JDBC preparados e o launcher da imagem exigem execução no CI; não foram executados localmente por ausência de Java/Maven/daemon Docker. Resultados por commit ficam na PR desta etapa.
- Atestado do catálogo não comprova TLS/JDBC remoto, Auth, backup ou disponibilidade. Janela exclusiva sem DDL externo, cofre/credenciais e executor remoto ainda pendentes. Nenhum baseline, V003, fixture ou credencial foi gravado no Supabase nesta etapa. Procedimento: docs/BASELINE_FLYWAY.md.

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

- Aplicação sem deploy: host dedicado, domínio HTTPS, cofre, SMTP e segundo ambiente ainda pendentes.
- Atestar bootstrap e executar baseline002 oficial via migrador JDBC; validate/migrate paraV003. Não fabricar flyway_schema_history nem usar baseline-on-migrate.
- Ensaiar TLS/pooler gerenciado e Auth/refresh/logout reais, limites de tentativas e expiração sob falha de rede. O JDBC descartável não substitui esse ensaio.
- Configuração revisada de áreas, aprovação/rejeição/expiração, convidados/revogação, fila/recorrência, gestão, demais telas web, aplicativo Expo, MFA, onboarding, carga, restore/PITR e integração física permanecem pendentes.
- Os tickets evoluem como EM_IMPLEMENTACAO e os60 cenários de aceite de produto continuam não concluídos. Nenhuma alteração na Advanced.
