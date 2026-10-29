# Evidência de migrations de produção — 29/10/2026

Projeto Supabase: `CondPulse-producao` (`tkxcujckpsuawnzkvpjg`), região São Paulo.

- Source revisado: `077e9888b0074c7446017bb3f87df16c61fa50a8`.
- Workflow: [migrate 40884195154](https://github.com/advancedgit-alt/Condpulse-v2.0/actions/runs/40884195154), conclusão com sucesso.
- Resultado do executor: `migrated=true`, `validated=true`, alvo `017`, 17 migrations executadas.
- Readback direto: um marcador Flyway `SCHEMA` criado pelo Flyway e versões SQL V001–V017, todas bem-sucedidas com checksum.
- 33 tabelas de aplicação verificadas com ENABLE/FORCE RLS e owner `postgres`; sem ownership inesperado de schemas, funções ou sequences.
- Grupos `cp_api`, `cp_worker` e `cp_onboarding` são NOLOGIN e sem SUPERUSER, BYPASSRLS, CREATEDB, CREATEROLE, REPLICATION ou CREATE nos schemas `cp`/ `platform`.
- Após migrations: zero tenants, contas locais, unidades, vínculos, áreas, papéis de plataforma e recibos de onboarding. Nenhum tenant foi criado por esta operação.

## Limites e pendências

Este registro prova a instalação do schema, não a prontidão operacional da aplicação. Runtime de produção, credenciais de menor privilégio, Auth, Storage, origem/domínio, jornadas reais, capacidade e recuperação/restore precisam de validação própria. O cadastro do tenant de homologação permanece aguardando aprovação explícita do titular e dos papéis.

Nenhum segredo ou dado pessoal está registrado neste arquivo.
