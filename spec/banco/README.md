# Banco de dados de referência

Os arquivos SQL NÃO foram executados contra PostgreSQL nesta entrega. São base para revisão, testes automatizados e adaptação ao projeto Supabase real. Não aplicar em produção diretamente.

## Ordem

1. Identificar a versão PostgreSQL do projeto e permissões disponíveis para extensões/roles.
2. Criar banco descartável compatível e snapshot inicial.
3. Executar `00_roles_example.sql` como operador autorizado. As roles são NOLOGIN: criar logins separados e secretos no ambiente/cofre e atribuir associação estritamente necessária.
4. Executar `V001__core_reference.sql` e `V002__rls_reference.sql` via Flyway, usando usuário de migração distinto.
5. Rodar testes de RLS, constraints e concorrência como cp_api/cp_worker, não como owner ou superuser.
6. Revisar privilégios de todos os schemas e impedir exposição do schema cp na Data API.
7. Gerar migrations adicionais, sem editar migrations já aplicadas. Só depois promover o artefato homologado.

`UNIQUE NULLS NOT DISTINCT` requer PostgreSQL 15 ou posterior. Verificar btree_gist no schema correto. Nenhuma tabela usa o schema `auth` como dono das regras de negócio: accounts referencia a identidade por UUID e o provisionamento é controlado. Não criar triggers invasivos em tabelas internas do provedor sem análise específica.

## Limite do modelo de permissão

RLS faz isolamento de TENANT. O backend ainda deve aplicar permissões por pessoa, unidade, função e finalidade. O banco não confirma automaticamente que um titular pode ver todo o histórico de outros adultos da unidade.

Os papéis administrativos necessários para provisionar accounts/tenants e executar retenção não são papéis de requisição comum. Criá-los por procedimento separado, com permissões mínimas, auditoria e sem acesso em frontend. O BFF acessa web_sessions por hash; tokens do provedor devem ser cifrados usando chave no cofre, nunca apenas codificados em Base64.

## Casos obrigatórios a implementar

- FKs compostas rejeitam referência a outra unidade/tenant.
- Exclusão GiST impede sobreposição incluindo buffer e estados bloqueantes.
- Handoff único rejeita segunda retirada em corrida.
- Idempotency-Key muda payload: conflito, não reutilização silenciosa.
- Contexto set_config local não vaza por pool.
- Conta sem actor/tenant não acessa negócio.
- Role de API não altera eventos imutáveis/auditoria.
- Worker enumera tenants por registry mínimo e abre uma transação por tenant.
- Version check impede lost update e callbacks antigos não alteram estado novo.

A validação de relação semântica entre reserva/unidade, delegação/encomenda e dispositivo/conector deve existir no serviço mesmo quando há FK. O SQL de referência reforça relações críticas, mas não substitui testes.
