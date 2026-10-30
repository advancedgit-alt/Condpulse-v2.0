-- CP-040: a gestão que decide reservas (reservations.decide) passa a ver a fila de espera do condomínio, só para
-- leitura: unidade, área, período, situação e prazo da oferta. Entrar, sair, aceitar e a oferta continuam só com a
-- unidade e com a passada da fila (políticas de INSERT e UPDATE inalteradas). Morador continua vendo só a própria.
DROP POLICY reservation_waitlist_read ON cp.reservation_waitlist;
CREATE POLICY reservation_waitlist_read ON cp.reservation_waitlist AS RESTRICTIVE FOR SELECT TO cp_api
 USING(cp.reservation_member(tenant_id,unit_id,'create') OR cp.reservation_member(tenant_id,unit_id,'cancel')
  OR cp.waitlist_offering(tenant_id,amenity_id) OR platform.has_capability('reservations.decide'));
