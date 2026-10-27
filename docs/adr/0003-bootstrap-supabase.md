# ADR-0003 — Bootstrap controlado no Supabase

Decisão de 27/10/2026, revisada independentemente.

O projeto existe em sa-east-1, com Auth já cadastrado, mas cp/platform não possuem objetos
de negócio. O terminal não resolve DNS externo e não há conexão JDBC disponível nesta sessão.
Para iniciar a estrutura real, o bootstrap executa as mesmas instruções SQL de V001/V002 validadas pelo CI,
em um único bloco transacional pelo mecanismo nativo de migrations do Supabase.

O source permanece exclusivamente em db/migrations. O manifesto registra source commit,
hash SHA256 de cada arquivo e do comando executado, além do ID nativo de migration e
atestação posterior de tabelas, constraints, roles e RLS. Auth/Storage/public gerenciados
não são recriados. Roles de execução são NOLOGIN, sem senha ou privilégios elevados.

Exceção única de inicialização, restrita a cp/platform vazios e sem flyway_schema_history.
Não preencher manualmente flyway_schema_history. Quando a conexão JDBC do migrador estiver
disponível, conferir manifesto + histórico nativo + estrutura/RLS/ACL/ownership e executar
o comando oficial Flyway baseline na versão 002, seguido de validate/migrate. Baseline
registra somente um marcador: não valida a equivalência do schema nem gera checksums das
versões anteriores. baseline-on-migrate continua false. Não adotar baseline003 antes de
V003 efetivamente aplicada e atestada. Alterações seguintes permanecem sob Flyway.

Owner de bootstrap é postgres; o futuro migrador usa a mesma identidade DDL ou uma
transferência de ownership explícita e revisada. Runtime nunca recebe essa identidade.
Flyway administra somente platform/cp; extensions pertence ao recurso gerenciado.
clean-disabled permanece true.

Precisão da atestação: os arquivos locais usados no comando de bootstrap continham um
LF final adicional em relação aos blobs Git. O manifesto preserva os hashes exatos da
representação executada e registra separadamente canonicalGitSourceSha256, com hash e
blob de cada arquivo do commit original. Nenhuma instrução SQL difere e nenhuma migration
aplicada ou seu histórico nativo foi reescrito. A distinção deve ser conferida no baseline.

Referências oficiais: https://documentation.red-gate.com/flyway/reference/commands/baseline
e https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting
