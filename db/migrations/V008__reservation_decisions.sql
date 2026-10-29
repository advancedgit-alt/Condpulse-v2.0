-- Aprovação e bloqueios de reserva (CP-023). Segunda barreira no banco, além da autorização Java:
-- somente quem decide reservas confirma pedido ou rejeita; área que exige aprovação nunca recebe
-- reserva já confirmada pelo fluxo residencial; somente quem gerencia áreas cria ou altera bloqueio
-- de manutenção. Moradores continuam podendo cancelar e expirar dentro da autorização do serviço.
CREATE POLICY reservations_decision_update ON cp.reservations AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(true)
 WITH CHECK(status NOT IN ('CONFIRMED','REJECTED') OR platform.has_capability('reservations.decide'));
CREATE POLICY reservations_approval_insert ON cp.reservations AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(kind<>'BOOKING' OR status<>'CONFIRMED' OR NOT EXISTS(
  SELECT 1 FROM cp.amenities a
  WHERE a.tenant_id=cp.reservations.tenant_id AND a.id=cp.reservations.amenity_id AND a.approval_required
 ));
CREATE POLICY reservations_block_insert ON cp.reservations AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(kind<>'BLOCK' OR platform.has_capability('amenities.manage'));
-- A API só cria pedido pendente ou reserva confirmada, e bloqueio sempre confirmado. HELD fica para a fila de
-- espera (ainda inexistente); estados encerrados nunca nascem por INSERT.
CREATE POLICY reservations_insert_states ON cp.reservations AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(CASE kind WHEN 'BLOCK' THEN status='CONFIRMED' ELSE status IN ('PENDING_APPROVAL','CONFIRMED') END);
CREATE POLICY reservations_block_update ON cp.reservations AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(kind<>'BLOCK' OR platform.has_capability('amenities.manage'))
 WITH CHECK(kind<>'BLOCK' OR platform.has_capability('amenities.manage'));
-- Máquina de estados no banco, para qualquer papel: estados encerrados não voltam e nada retorna a HELD ou a
-- PENDING_APPROVAL. Transições permitidas: HELD→PENDING_APPROVAL/CONFIRMED/CANCELLED/EXPIRED,
-- PENDING_APPROVAL→CONFIRMED/REJECTED/CANCELLED/EXPIRED e CONFIRMED→CANCELLED.
CREATE FUNCTION cp.reservation_transition_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
     (OLD.status='HELD' AND NEW.status IN ('PENDING_APPROVAL','CONFIRMED','CANCELLED','EXPIRED'))
  OR (OLD.status='PENDING_APPROVAL' AND NEW.status IN ('CONFIRMED','REJECTED','CANCELLED','EXPIRED'))
  OR (OLD.status='CONFIRMED' AND NEW.status='CANCELLED')) THEN
  RAISE EXCEPTION 'Transição de reserva não permitida.' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.reservation_transition_guard() FROM PUBLIC;
CREATE TRIGGER reservations_transition_guard BEFORE UPDATE OF status ON cp.reservations
 FOR EACH ROW EXECUTE FUNCTION cp.reservation_transition_guard();
-- Motivo da decisão é exibido ao solicitante e à gestão; limite evita texto livre sem fronteira.
ALTER TABLE cp.reservations ADD CONSTRAINT reservations_decision_reason_length
 CHECK(decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 500);
CREATE INDEX reservations_management_window ON cp.reservations(tenant_id,starts_at);
