# Testes PostgreSQL reais
Somente banco descartável chamado condpulse_ci ou com prefixo cp_test_,
em localhost/127.0.0.1/postgres. Nunca apontar para Supabase/produção.
Requisitos: Python3 e psql; variáveis PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE.
Confirme CP_DB_TEST_OK=DISPOSABLE para liberar fixtures sintéticas.

O CI executa Flyway pelo perfil migrate antes do suite e define
CP_SCHEMA_ALREADY_MIGRATED=true. A segunda execução do migrador valida histórico/checksums.
Em execução local descartável, sem essa flag, o suite aplica os arquivos SQL com psql.
Isso é um teste de DDL em banco de teste, não o processo de release de produção.

Cenários: FORCE RLS, roles sem bypass, contexto ausente/ator/tenant,
FK cruzada, 50 reservas concorrentes, adjacência, buffer, cancelamento,
retirada única, primeira decisão, auditoria append-only, idempotência por ator,
rollback de outbox e revogação de vínculo. Não substitui E2E, carga ou teste físico.
