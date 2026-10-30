-- CP-040/CP-035: a oferta da fila de espera vira aviso na caixa interna de quem entrou na fila, enquanto a retenção vale.
-- O aviso aponta a entrada da fila (o agregado do evento WAITLIST_OFFERED); o worker confere o autor pela retenção
-- indicada no evento, sem ler a fila (que ele só alcança durante a passada de oferta). Dependente autor recebe só com a
-- permissão de reservar, a mesma que o deixou entrar na fila.

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
ALTER TABLE cp.notifications ADD CONSTRAINT notifications_kind_check CHECK(kind IN ('ARRIVAL_WAITING','PARCEL_RECEIVED','WAITLIST_OFFERED'));

-- O worker grava somente o aviso da tarefa que arrendou, do tipo correspondente ao evento, para a unidade real do
-- registro e para destinatário apto; a oferta, só para quem fez a entrada (autor da retenção do evento).
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
       AND r.unit_id=cp.notifications.unit_id AND r.created_by=cp.notifications.account_id))))
  AND cp.notification_recipient(tenant_id,unit_id,account_id,kind));
