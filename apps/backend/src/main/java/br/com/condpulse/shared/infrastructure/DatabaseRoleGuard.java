package br.com.condpulse.shared.infrastructure;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Confere a role de execução durante a criação do contexto, antes de o servidor web abrir a porta. Como ApplicationRunner,
 * a conferência rodava com a porta já aberta e a prontidão ainda REFUSING_TRAFFIC: a primeira requisição depois de um
 * início a frio na Vercel recebia 503 OUT_OF_SERVICE (e podia ser atendida antes de a role ser conferida).
 */
@Component @Profile({"api","worker"})
public class DatabaseRoleGuard implements SmartInitializingSingleton {
 private final JdbcTemplate jdbc;
 public DatabaseRoleGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 @Override public void afterSingletonsInstantiated() { verify(); }
 public void verify() {
  // Trilhas e papéis de plataforma (V010), financeiro (V011), registros de acesso (V014), outbox/caixa interna (V015) e
  // avisos (V016) conferidos por to_regclass: banco ainda sem a migration não falha aqui. O financeiro nunca apaga: estorno e
  // cancelamento mantêm a linha; o registro de acesso nunca muda: correção é um evento adicional, e unidade, pessoa,
  // horários e origem vêm do banco; tarefa do outbox e aviso não são apagados pela aplicação, que também não escolhe
  // estado, tentativa ou prazo da tarefa nem altera o aviso além da abertura. Pedidos (V017): nada apagado; mensagem
  // não muda; a gestão altera só andamento, responsável, prazo, resolução e versão; nomes e horários vêm do banco.
  // Importação (V018): lote e linhas nunca apagados; o lote só muda de estado, parcial, contagens e versão, com autoria e
  // horários do banco; a linha só recebe a unidade aplicada. Anexos (V019): nunca apagados; só estado, motivo e hash
  // mudam, e o banco escolhe estado inicial, prazos e horários. Links de convite (V020): nunca apagados; só a revogação
  // muda, e o banco grava os horários. Regras de prestador (V022): nunca apagadas; só a revogação muda.
  // INSERT/UPDATE por has_any_column_privilege/has_column_privilege (inclui grant por coluna); DELETE/TRUNCATE só
  // existem na tabela. Antes da V015 (banco remoto ainda na V005) a API tem INSERT de tabela no outbox, como sempre
  // teve: a conferência por coluna do outbox só vale com a V015 aplicada (marcada pela caixa interna).
  Boolean unsafe=jdbc.queryForObject("""
    select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication
     or has_schema_privilege(current_user,'cp','CREATE')
     or has_schema_privilege(current_user,'platform','CREATE')
     or has_any_column_privilege(current_user,'cp.audit_log','UPDATE')
     or has_table_privilege(current_user,'cp.audit_log','DELETE,TRUNCATE')
     or coalesce(has_any_column_privilege(current_user,to_regclass('platform.platform_audit_log'),'UPDATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.platform_audit_log'),'DELETE,TRUNCATE'),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('platform.platform_roles'),'INSERT,UPDATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.platform_roles'),'DELETE,TRUNCATE'),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('platform.platform_role_events'),'INSERT,UPDATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.platform_role_events'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.billing_plans'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.billing_subscriptions'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.billing_charges'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.billing_payments'),'DELETE,TRUNCATE'),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('cp.access_events'),'UPDATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.access_events'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.access_events') and c.attname in ('unit_id','visitor_id','occurred_at','received_at','source')),false)
     or has_table_privilege(current_user,'cp.outbox','DELETE,TRUNCATE')
     or coalesce(to_regclass('cp.notifications') is not null and (select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT'))
       from pg_attribute c where c.attrelid='cp.outbox'::regclass
        and c.attname in ('state','attempts','available_at','lease_until','last_error','processed_at')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.notifications'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.announcements'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT,UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.announcements') and c.attname in ('published_by','published_at','archived_by','archived_at',
        'created_at','updated_at')),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('cp.announcement_reads'),'UPDATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.announcement_reads'),'DELETE,TRUNCATE'),false)
     or coalesce(has_column_privilege(current_user,to_regclass('cp.announcement_reads'),'read_at','INSERT'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.notifications') and c.attnum>0 and not c.attisdropped and c.attname<>'state'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.service_requests'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.service_requests') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('state','assignee_membership_id','due_on','resolution','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.service_requests') and c.attname in ('requester_name','created_at','updated_at','resolved_at',
        'closed_at')),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('cp.request_messages'),'UPDATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.request_messages'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.request_messages') and c.attname in ('author_name','created_at')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.import_batches'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.import_rows'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.import_batches') and c.attname in ('state','partial','created_units','existing_units',
        'created_blocks','created_by','created_at','decided_by','decided_at','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.import_batches') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('state','partial','created_units','existing_units','created_blocks','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.import_rows') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('applied','unit_id')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.import_rows') and c.attname in ('applied','unit_id')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.reservation_waitlist'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.reservation_waitlist') and c.attname in ('state','offer_reservation_id','offered_at',
        'offer_expires_at','closed_at','created_at','updated_at','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.reservation_waitlist') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('state','offer_reservation_id','offered_at','offer_expires_at','closed_at','updated_at','version')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.visit_rules'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.visit_rules') and c.attname in ('state','created_at','revoked_by','revoked_at','revoke_reason','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.visit_rules') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('state','version','revoked_by','revoke_reason')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.permit_links'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.permit_links') and c.attname in ('created_at','revoked_by','revoked_at')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.permit_links') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('revoked_by','revoked_at')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.attachments'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.attachments') and c.attname in ('state','rejection','sha256','created_at',
        'upload_expires_at','completed_at')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.attachments') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('state','rejection','sha256')),false)
    from pg_roles where rolname=current_user
    """,Boolean.class);
  if(!Boolean.FALSE.equals(unsafe))
   throw new IllegalStateException("Role de execução com privilégios incompatíveis.");
  Integer unprotected=jdbc.queryForObject("""
    select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where c.relkind='r' and(not c.relrowsecurity or not c.relforcerowsecurity)
     and(n.nspname='cp' or c.oid in(to_regclass('platform.platform_roles'),to_regclass('platform.platform_audit_log'),
      to_regclass('platform.platform_role_events'),to_regclass('platform.billing_plans'),
      to_regclass('platform.billing_subscriptions'),to_regclass('platform.billing_charges'),to_regclass('platform.billing_payments')))
    """,Integer.class);
  if(unprotected==null || unprotected>0)
   throw new IllegalStateException("Schema de negócio sem proteção RLS completa.");
 }
}
