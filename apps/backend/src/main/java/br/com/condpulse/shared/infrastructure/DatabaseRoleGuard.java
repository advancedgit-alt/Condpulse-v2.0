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
  // muda, e o banco grava os horários. Regras de prestador (V022): nunca apagadas; só a revogação muda. Credenciais (V026): nunca apagadas; só a
  // revogação muda; confirmação por equipamento vem do banco; conector e equipamento só pela operação da plataforma. Eventos de
  // equipamento (V027): nunca alterados nem apagados; unidade e horário de ingestão vêm do banco. Saúde (V028): nunca apagada;
  // horários de verificação, observação e mudança de estado vêm do banco. Ocorrências da portaria (V033): nunca apagadas;
  // autoria, nomes e horários vêm do banco. Locações (V034): nunca apagadas; só o encerramento muda. Financeiro condominial (V036):
  // nada apagado; evento do webhook nunca muda; pagador, taxa e cobrança só mudam pelas colunas de transição. Concessões
  // técnicas (V037): nunca apagadas; só a revogação muda; estado inicial, identidade do banco e horários vêm do banco.
  // Sujeitos e contextos (V038): nunca apagados; só estado, época e encerramento mudam; tipo e vigência vêm da origem.
  // Evidência de idade (V039): nunca apagada nem editada; só revogada/substituída; o worker nunca lê a data cifrada.
  // Catálogo e homologação (V040): perfil só por migration; homologação só muda de estado por decisão; decisão nunca muda.
  // Áreas e pontos (V041): nunca apagados; código, condomínio, autoria inicial e horários fixos. Outbox (V032): a operação da plataforma só devolve
  // tarefa para a fila; conteúdo, condomínio e evento da tarefa nunca mudam por nenhum login de runtime.
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
     or coalesce(has_table_privilege(current_user,to_regclass('cp.concierge_occurrences'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.unit_rentals'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.unit_rentals') and c.attnum>0 and not c.attisdropped and c.attname not in ('ended_by','ended_at')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT,UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.concierge_occurrences') and c.attname in ('created_by','created_by_name','created_at','resolved_by',
        'resolved_by_name','resolved_at')),false)
     or coalesce(to_regclass('cp.notifications') is not null and (select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE'))
       from pg_attribute c where c.attrelid='cp.outbox'::regclass
        and c.attname in ('tenant_id','event_type','aggregate_id','aggregate_version','payload','created_at','attempt_base')),false)
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
     or coalesce(has_table_privilege(current_user,to_regclass('cp.move_requests'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.move_rules'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.move_requests') and c.attname in ('state','decided_by','decided_at','decision_note','cancelled_by',
        'created_at','updated_at','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.move_requests') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('state','decided_by','decision_note','cancelled_by','version')),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('cp.device_events'),'UPDATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.device_events'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.device_events') and c.attname in ('id','unit_id','ingested_at')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.device_event_cursors'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.access_connector_health'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.access_device_health'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT') or has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE'))
       from pg_attribute c where (c.attrelid=to_regclass('cp.access_connector_health') and c.attname in ('checked_at','last_reachable_at'))
        or (c.attrelid=to_regclass('cp.access_device_health') and c.attname in ('observed_at','state_since'))),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.access_credentials'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.access_credentials') and c.attname in ('desired_state','desired_version','revoke_reason','revoked_by',
        'revoked_at','external_ref','created_at','updated_at','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.access_credentials') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('desired_state','desired_version','revoke_reason','revoked_by','version')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.credential_syncs'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT,UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.credential_syncs') and c.attname in ('observed_version','observed_action','confirmed_at','updated_at')),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('cp.access_connectors'),'INSERT,UPDATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.access_connectors'),'DELETE,TRUNCATE'),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('cp.access_devices'),'INSERT,UPDATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.access_devices'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.parcel_delegations'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.parcel_delegations') and c.attname in ('state','attempts','created_at','used_by','used_at',
        'revoked_by','closed_at','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.parcel_delegations') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('state','attempts','used_by','revoked_by','version')),false)
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
     or coalesce(has_table_privilege(current_user,to_regclass('cp.finance_payers'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.finance_recurring_fees'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.finance_charges'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.finance_webhook_events'),'DELETE,TRUNCATE'),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('cp.finance_webhook_events'),'UPDATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.finance_payers') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('asaas_customer_id','state','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.finance_recurring_fees') and c.attnum>0 and not c.attisdropped and c.attname not in ('state','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.finance_charges') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('state','asaas_payment_id','asaas_status','invoice_url','bank_slip_url','paid_at','paid_cents','issue_error',
         'issue_lease_until','issue_attempts','next_issue_at','cancelled_by','cancel_reason','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.finance_charges') and c.attname in ('state','asaas_payment_id','paid_at','paid_cents','created_by',
        'issue_attempts','next_issue_at',
        'created_at','cancelled_by','cancelled_at','updated_at','version')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.technical_grants'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.technical_grants') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('status','revoked_by_account_id','revoke_reason','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.technical_grants') and c.attname in ('status','granted_by_db_user','approved_at','revoked_at',
        'revoked_by_account_id','revoke_reason','created_at','updated_at','version')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.access_subjects'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.subject_contexts'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.access_subjects') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('status','restriction_epoch','blocked_reason','blocked_by','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.subject_contexts') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('status','status_reason','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.access_subjects') and c.attname in ('status','restriction_epoch','external_ref','blocked_at',
        'identity_verified_at','created_at','updated_at','version')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.age_evidence'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.facial_access_settings'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('cp.age_evidence') and c.attnum>0 and not c.attisdropped
        and c.attname not in ('status','status_reason','status_changed_by','version')),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'INSERT')) from pg_attribute c
       where c.attrelid=to_regclass('cp.age_evidence') and c.attname in ('status','verified_at','status_changed_at','created_at','version')),false)
     or coalesce(has_column_privilege(current_user,to_regclass('cp.age_evidence'),'birth_date_ciphertext','SELECT') and pg_has_role(current_user,'cp_worker','USAGE'),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('platform.access_model_profiles'),'INSERT,UPDATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.access_model_profiles'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.access_homologations'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('platform.access_homologation_decisions'),'DELETE,TRUNCATE'),false)
     or coalesce(has_any_column_privilege(current_user,to_regclass('platform.access_homologation_decisions'),'UPDATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid=to_regclass('platform.access_homologations') and c.attnum>0 and not c.attisdropped and c.attname not in ('status','version')),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.access_areas'),'DELETE,TRUNCATE'),false)
     or coalesce(has_table_privilege(current_user,to_regclass('cp.access_points'),'DELETE,TRUNCATE'),false)
     or coalesce((select bool_or(has_column_privilege(current_user,c.attrelid,c.attname,'UPDATE')) from pg_attribute c
       where c.attrelid in (to_regclass('cp.access_areas'),to_regclass('cp.access_points')) and c.attname in ('id','tenant_id','code','created_by','created_at','updated_at')),false)
    from pg_roles where rolname=current_user
    """,Boolean.class);
  if(!Boolean.FALSE.equals(unsafe))
   throw new IllegalStateException("Role de execução com privilégios incompatíveis.");
  Integer unprotected=jdbc.queryForObject("""
    select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where c.relkind='r' and(not c.relrowsecurity or not c.relforcerowsecurity)
     and(n.nspname='cp' or c.oid in(to_regclass('platform.platform_roles'),to_regclass('platform.platform_audit_log'),
      to_regclass('platform.platform_role_events'),to_regclass('platform.billing_plans'),
      to_regclass('platform.billing_subscriptions'),to_regclass('platform.billing_charges'),to_regclass('platform.billing_payments'),
      to_regclass('platform.access_model_profiles'),to_regclass('platform.access_homologations'),to_regclass('platform.access_homologation_decisions')))
    """,Integer.class);
  if(unprotected==null || unprotected>0)
   throw new IllegalStateException("Schema de negócio sem proteção RLS completa.");
 }
}
