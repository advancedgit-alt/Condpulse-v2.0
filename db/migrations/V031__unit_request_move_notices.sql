-- CP-034/CP-035 com CP-033 e CP-042: andamento ou resposta da equipe num pedido e decisão de mudança viram aviso na
-- caixa interna de morador e responsável da unidade (os mesmos vínculos que abrem pedido e pedem mudança). Mensagem da
-- própria unidade não gera aviso. O worker lê só identificadores, unidade e situação; nunca título, conversa ou prestador.

CREATE OR REPLACE FUNCTION cp.notification_recipient(target_tenant uuid,target_unit uuid,target_account uuid,notification_kind text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=target_account AND m.status='ACTIVE'
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
   AND (m.role IN ('RESIDENT','RESPONSIBLE') OR (m.role='DEPENDENT' AND coalesce(m.permissions#>(CASE notification_kind
     WHEN 'ARRIVAL_WAITING' THEN ARRAY['visits','decide'] WHEN 'PARCEL_RECEIVED' THEN ARRAY['parcels','view']
     WHEN 'WAITLIST_OFFERED' THEN ARRAY['reservations','create'] END)='true'::jsonb,false)))) $$;

ALTER TABLE cp.notifications DROP CONSTRAINT notifications_kind_check;
ALTER TABLE cp.notifications ADD CONSTRAINT notifications_kind_check
 CHECK(kind IN ('ARRIVAL_WAITING','PARCEL_RECEIVED','WAITLIST_OFFERED','REQUEST_UPDATED','MOVE_DECIDED'));

-- Leitura mínima do worker: identificador, unidade e situação (pedido e mudança) e autoria/visibilidade da mensagem.
GRANT SELECT(id,tenant_id,unit_id,state) ON cp.service_requests TO cp_worker;
GRANT SELECT(id,tenant_id,request_id,author_role,visibility) ON cp.request_messages TO cp_worker;
GRANT SELECT(id,tenant_id,unit_id,state) ON cp.move_requests TO cp_worker;
CREATE POLICY worker_read ON cp.service_requests FOR SELECT TO cp_worker USING(tenant_id=platform.current_tenant_id());
CREATE POLICY worker_read ON cp.request_messages FOR SELECT TO cp_worker USING(tenant_id=platform.current_tenant_id());
CREATE POLICY worker_read ON cp.move_requests FOR SELECT TO cp_worker USING(tenant_id=platform.current_tenant_id());

DROP POLICY notifications_worker_insert ON cp.notifications;
CREATE POLICY notifications_worker_insert ON cp.notifications AS RESTRICTIVE FOR INSERT TO cp_worker
 WITH CHECK(EXISTS(SELECT 1 FROM cp.outbox o WHERE o.tenant_id=cp.notifications.tenant_id AND o.id=cp.notifications.outbox_id
   AND o.state='LEASED' AND o.aggregate_id=cp.notifications.resource_id AND o.payload->>'unitId'=cp.notifications.unit_id::text
   AND ((cp.notifications.kind='ARRIVAL_WAITING' AND o.event_type='ARRIVAL_REQUESTED' AND EXISTS(SELECT 1 FROM cp.arrival_requests a
      WHERE a.tenant_id=o.tenant_id AND a.id=o.aggregate_id AND a.unit_id=cp.notifications.unit_id))
    OR (cp.notifications.kind='PARCEL_RECEIVED' AND o.event_type='PARCEL_RECEIVED' AND EXISTS(SELECT 1 FROM cp.parcels p
      WHERE p.tenant_id=o.tenant_id AND p.id=o.aggregate_id AND p.unit_id=cp.notifications.unit_id))
    OR (cp.notifications.kind='WAITLIST_OFFERED' AND o.event_type='WAITLIST_OFFERED' AND EXISTS(SELECT 1 FROM cp.reservations r
      WHERE r.tenant_id=o.tenant_id AND r.id::text=o.payload->>'reservationId' AND r.kind='BOOKING'
       AND r.unit_id=cp.notifications.unit_id AND r.created_by=cp.notifications.account_id))
    OR (cp.notifications.kind='REQUEST_UPDATED' AND EXISTS(SELECT 1 FROM cp.service_requests s
      WHERE s.tenant_id=o.tenant_id AND s.id=o.aggregate_id AND s.unit_id=cp.notifications.unit_id)
     AND (o.event_type='REQUEST_UPDATED' OR (o.event_type='REQUEST_MESSAGE_ADDED' AND EXISTS(SELECT 1 FROM cp.request_messages q
      WHERE q.tenant_id=o.tenant_id AND q.request_id=o.aggregate_id AND q.id::text=o.payload->>'messageId'
       AND q.author_role='STAFF' AND q.visibility='RESIDENT'))))
    OR (cp.notifications.kind='MOVE_DECIDED' AND o.event_type IN ('MOVE_APPROVED','MOVE_REJECTED') AND EXISTS(SELECT 1 FROM cp.move_requests v
      WHERE v.tenant_id=o.tenant_id AND v.id=o.aggregate_id AND v.unit_id=cp.notifications.unit_id))))
  AND cp.notification_recipient(tenant_id,unit_id,account_id,kind));
