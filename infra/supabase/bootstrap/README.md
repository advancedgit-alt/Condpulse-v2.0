# Bootstrap do provedor
Este diretorio registra configuracoes do recurso gerenciado, nao migrations de negocio.
A configuracao administrativa da funcao public.rls_auto_enable() foi registrada no
historico nativo Supabase em 20261027210735, nome condpulse_bootstrap_restrict_rls_event_function.

A funcao era SECURITY DEFINER de retorno event_trigger e pertencia a postgres.
O corpo/event trigger foi preservado. Foram retirados EXECUTE de PUBLIC/anon/authenticated.
Read-back: anon=false e authenticated=false; security advisor retornou lints=[].
Isso trata os avisos observados; nao constitui uma auditoria completa do produto.

Migrations cp/platform continuam exclusivamente em db/migrations, executadas por Flyway.
Nao aplicar esses arquivos com o conector SQL ou inventar flyway_schema_history.
Nenhuma migration de negocio foi executada no projeto remoto nesta etapa.

Storage e Auth devem usar APIs/painel do provedor. storage.* e considerado read-only
para operacoes SQL de dados, conforme https://supabase.com/docs/guides/storage/schema/design.
A criacao de buckets/SMTP/redirect/MFA permanece pendente.
