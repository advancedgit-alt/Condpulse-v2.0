-- ADR-0014: N1 de permanência produz aviso interno ao morador/responsável, com entrega idempotente pelo outbox.
-- Não equivale a push/e-mail nem a prova de presença. Regra desligada, saída ou justificativa antes da entrega suprimem o aviso.
ALTER TABLE cp.notifications DROP CONSTRAINT notifications_kind_check;
ALTER TABLE cp.notifications ADD CONSTRAINT notifications_kind_check
 CHECK(kind IN ('ARRIVAL_WAITING','PARCEL_RECEIVED','WAITLIST_OFFERED','REQUEST_UPDATED','MOVE_DECIDED','STAY_OVERDUE'));
CREATE UNIQUE INDEX outbox_stay_notice_once ON cp.outbox(tenant_id,aggregate_id) WHERE event_type='STAY_OVERDUE';

-- A API já insere outros eventos: não pode antecipar uma tarefa falsa e consumir a chave idempotente antes do N1.
CREATE POLICY outbox_stay_api_source ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(event_type<>'STAY_OVERDUE' OR EXISTS(SELECT 1 FROM cp.stays s
  WHERE s.tenant_id=cp.outbox.tenant_id AND s.id=cp.outbox.aggregate_id AND s.unit_id::text=cp.outbox.payload->>'unitId'
   AND s.l1_alerted_at IS NOT NULL AND s.state='OPEN' AND cp.stay_control_enabled(s.tenant_id,s.kind)));

-- O worker só ganha INSERT das colunas de conteúdo; estado/prazo/tentativas continuam exclusivamente do banco.
GRANT INSERT(tenant_id,event_type,aggregate_id,aggregate_version,payload) ON cp.outbox TO cp_worker;
CREATE POLICY outbox_stay_worker_insert ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_worker
 WITH CHECK(event_type='STAY_OVERDUE' AND state='PENDING' AND attempts=0 AND lease_until IS NULL
  AND EXISTS(SELECT 1 FROM cp.stays s WHERE s.tenant_id=cp.outbox.tenant_id AND s.id=cp.outbox.aggregate_id
   AND s.unit_id::text=cp.outbox.payload->>'unitId' AND s.l1_alerted_at IS NOT NULL AND s.state='OPEN'
   AND cp.stay_control_enabled(s.tenant_id,s.kind)));

CREATE FUNCTION cp.stay_resident_notice() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF OLD.l1_alerted_at IS NULL AND NEW.l1_alerted_at IS NOT NULL AND NEW.state='OPEN' THEN
  INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
  VALUES(NEW.tenant_id,'STAY_OVERDUE',NEW.id,NEW.version,jsonb_build_object('unitId',NEW.unit_id)) ON CONFLICT DO NOTHING;
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION cp.stay_resident_notice() FROM PUBLIC;
CREATE TRIGGER stays_resident_notice AFTER UPDATE OF l1_alerted_at ON cp.stays FOR EACH ROW EXECUTE FUNCTION cp.stay_resident_notice();

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
      WHERE v.tenant_id=o.tenant_id AND v.id=o.aggregate_id AND v.unit_id=cp.notifications.unit_id))
    OR (cp.notifications.kind='STAY_OVERDUE' AND o.event_type='STAY_OVERDUE' AND EXISTS(SELECT 1 FROM cp.stays s
      WHERE s.tenant_id=o.tenant_id AND s.id=o.aggregate_id AND s.unit_id=cp.notifications.unit_id AND s.l1_alerted_at IS NOT NULL))))
  AND cp.notification_recipient(tenant_id,unit_id,account_id,kind));
