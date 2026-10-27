# Estado verificável - 27/10/2026

## Código revisado e validado

- PR#1 integrada em b933891: fundação, JWT, migrations e isolamento SQL.
- PR#2 integrada em1446d171: BFF com tokens cifrados, cookie opaco, CSRF/Origin, refresh lease/CAS e logout durável. CP_BFF_ENABLED=false.
- PR#3: fatia residencial de unidades, áreas, criação e cancelamento de reservas. Revisão independente favorável; CI40602994695 passou no head8d790ad. CP_RESERVATIONS_ENABLED=false.
- TenantTransactionExecutor revalida conta/vínculo/unidade, usa transação própria e limpa contexto local na mesma conexão.
- Reservas usam política explícita, aceite de versão, snapshot, GiST, cota de admissão por unidade, idempotência e auditoria/outbox atômicos. Cancelamento exige autoria.
- Catálogo de unidades é próprio e ativo; catálogo de áreas exige capacidade residencial. Contrato OpenAPI corresponde às rotas implementadas.
- ADR-0004 delimita o subset e as jornadas ainda ausentes. Interface web/mobile ainda não implementada.

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
- Configuração revisada de áreas, aprovação/rejeição/expiração, convidados/revogação, fila/recorrência, gestão, frontend/mobile, MFA, onboarding, carga, restore/PITR e integração física permanecem pendentes.
- Os tickets evoluem como EM_IMPLEMENTACAO e os60 cenários de aceite de produto continuam não concluídos. Nenhuma alteração na Advanced.
