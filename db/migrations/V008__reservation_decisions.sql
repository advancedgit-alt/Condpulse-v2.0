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
CREATE POLICY reservations_block_update ON cp.reservations AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(kind<>'BLOCK' OR platform.has_capability('amenities.manage'))
 WITH CHECK(kind<>'BLOCK' OR platform.has_capability('amenities.manage'));
-- Motivo da decisão é exibido ao solicitante e à gestão; limite evita texto livre sem fronteira.
ALTER TABLE cp.reservations ADD CONSTRAINT reservations_decision_reason_length
 CHECK(decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 500);
CREATE INDEX reservations_management_window ON cp.reservations(tenant_id,starts_at);
