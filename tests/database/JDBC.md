# Ensaio JDBC e reutilização de pool

Os testes JdbcPoolIsolationTest, WebSessionStoreJdbcTest e ReservationServiceJdbcTest usam
PostgreSQL, Hikari e JdbcTransactionManager reais. Os dois primeiros mantêm pool de tamanho1
para reutilizar a mesma conexão física entre contas, tenants e sessões. A classe de reservas
usa pool separada com teto4 para exercitar concorrência real. Não há chamadas a Supabase Auth.

O build unitário os ignora quando CP_JDBC_TEST_OK não é DISPOSABLE. Para executá-los,
primeiro aplicar migrations pelo perfil migrate e criar o login descartável cp_ci_login
como membro de cp_api, conforme o smokeHTTP do CI. Em seguida executar:

```sh
mvn -B -ntp -f apps/backend/pom.xml -Dtest=JdbcPoolIsolationTest,WebSessionStoreJdbcTest,ReservationServiceJdbcTest test
```

Variáveis do step: CP_JDBC_TEST_OK=DISPOSABLE; CP_TEST_DB_USER=cp_ci_login;
CP_TEST_DB_PASSWORD com a senha sintética desse login; PGHOST/PGPORT/PGDATABASE e
PGUSER/PGPASSWORD do container descartável. Não usar credenciais de produção.
O guard aceita somente localhost/127.0.0.1/postgres e banco condpulse_ci ou cp_test_*;
nunca recebe uma URL JDBC externa. O login runtime é conferido sem BYPASSRLS/superuser.

As fixtures têm UUIDs próprios e são removidas ao terminar a classe. A credencial
administrativa é usada apenas para criar/limpar fixtures e simular suspensão, revogação,
expiração e crash. Todas as chamadas de produção sob teste usam o login runtime.

Cobertura: CurrentIdentityQuery com @Transactional real; TenantTransactionExecutor e
TenantUnitsQuery de produção; alternância de conta/vínculo; revogação antes do callback;
unidades próprias mesmo quando RLS permite outras unidades do mesmo tenant; unidade
inativa; RLS com UUID cruzado; limpeza de contexto após commit/rollback; lease concorrente;
crash com lease vencida e DELETE commitado antes do401; corrida refresh/logout;
expiração absoluta e suspensão. Nenhum resultado equivale à homologação do pooler
gerenciado, TLS externo ou Auth/refresh reais.

Reservas: criar e repetir chave sem duplicar; concorrência da mesma chave; mesma chave com corpo diferente; disputa de
slot entre unidades; disputa da cota da mesma unidade entre áreas; versão de cancelamento,
retries sem duplicar auditoria/outbox; revogação negando replay; unidade e área alheias
sem acesso; cancelamento pelas regras aceitas mesmo após alteração da política atual.
A consulta administrativa de contagens confirma o estado efetivamente commitado.
