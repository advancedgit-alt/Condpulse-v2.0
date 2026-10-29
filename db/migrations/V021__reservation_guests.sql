-- CP-038: convidados de uma reserva. Cada convidado é um convite (visita prevista) da unidade da reserva, com a janela
-- derivada do evento: começa até 1 hora antes do início (ou agora, se o evento já começou) e termina no fim do uso.
-- Encerrar a reserva (cancelada, rejeitada ou vencida) revoga todos os convites ligados a ela na mesma transação, por
-- qualquer caminho (morador, gestão, expiração), e uma repetição do cancelamento não reativa nada.

ALTER TABLE cp.visit_permits ADD COLUMN reservation_id uuid,
 ADD CONSTRAINT visit_permits_reservation_fk FOREIGN KEY(tenant_id,reservation_id) REFERENCES cp.reservations(tenant_id,id);
CREATE INDEX visit_permits_by_reservation ON cp.visit_permits(tenant_id,reservation_id) WHERE reservation_id IS NOT NULL;
GRANT INSERT(reservation_id) ON cp.visit_permits TO cp_api;

-- Convidado de reserva: mesma unidade, reserva de uso (não bloqueio) confirmada e ainda não terminada, janela igual à do
-- evento, até a capacidade da área em convidados ativos (trava por reserva contra inclusões simultâneas).
CREATE FUNCTION cp.reservation_guest_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE target record; active_guests integer;
BEGIN
 IF NEW.reservation_id IS NULL THEN RETURN NEW; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.reservation_guests:'||NEW.tenant_id::text||':'||NEW.reservation_id::text,0));
 SELECT r.unit_id,r.kind,r.status,r.starts_at,r.ends_at,a.capacity INTO target
  FROM cp.reservations r JOIN cp.amenities a ON a.tenant_id=r.tenant_id AND a.id=r.amenity_id
  WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.reservation_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'reserva indisponível' USING ERRCODE='42501'; END IF;
 IF target.kind<>'BOOKING' OR target.unit_id IS DISTINCT FROM NEW.unit_id THEN
  RAISE EXCEPTION 'convidado só na reserva da própria unidade' USING ERRCODE='42501';
 END IF;
 IF target.status<>'CONFIRMED' OR target.ends_at<=clock_timestamp() THEN
  RAISE EXCEPTION 'reserva não confirmada ou já encerrada' USING ERRCODE='23514';
 END IF;
 IF NEW.valid_until<>target.ends_at OR NEW.valid_from<target.starts_at-interval '1 hour' OR NEW.valid_from>target.starts_at THEN
  RAISE EXCEPTION 'janela do convidado diferente da do evento' USING ERRCODE='23514';
 END IF;
 SELECT count(*) INTO active_guests FROM cp.visit_permits p
  WHERE p.tenant_id=NEW.tenant_id AND p.reservation_id=NEW.reservation_id AND p.state='ACTIVE';
 IF active_guests>=target.capacity THEN RAISE EXCEPTION 'capacidade da área atingida' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.reservation_guest_guard() FROM PUBLIC;
CREATE TRIGGER visit_permits_reservation_guard BEFORE INSERT ON cp.visit_permits
 FOR EACH ROW EXECUTE FUNCTION cp.reservation_guest_guard();

-- Encerramento da reserva revoga os convidados na mesma transação, em nome de quem encerrou. A marca local da transação
-- libera somente esses convites para quem encerra (a gestão não os vê no dia a dia) e é limpa logo depois. Sem ator
-- (processo sem conta) a transação falha: nunca encerrar a reserva deixando convidados válidos.
CREATE FUNCTION cp.reservation_guests_close() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE pending integer;
BEGIN
 IF NEW.status NOT IN ('CANCELLED','REJECTED','EXPIRED') OR OLD.status IN ('CANCELLED','REJECTED','EXPIRED') THEN RETURN NEW; END IF;
 IF platform.current_actor_id() IS NULL THEN
  -- Papel sem acesso a convites (ex.: worker) não consegue conferir: falha fechado.
  IF NOT has_table_privilege(current_user,'cp.visit_permits','SELECT') THEN
   RAISE EXCEPTION 'encerramento sem conta não confere convidados' USING ERRCODE='42501';
  END IF;
  PERFORM set_config('app.closing_reservation',NEW.id::text,true);
  SELECT count(*) INTO pending FROM cp.visit_permits p WHERE p.tenant_id=NEW.tenant_id AND p.reservation_id=NEW.id
   AND p.state='ACTIVE' AND p.valid_until>clock_timestamp();
  PERFORM set_config('app.closing_reservation','',true);
  IF pending>0 THEN RAISE EXCEPTION 'encerramento sem conta não revoga convidados' USING ERRCODE='42501'; END IF;
  RETURN NEW;
 END IF;
 PERFORM set_config('app.closing_reservation',NEW.id::text,true);
 UPDATE cp.visit_permits SET state='REVOKED',revoked_by=platform.current_actor_id(),
   revoke_reason=CASE NEW.status WHEN 'CANCELLED' THEN 'Reserva cancelada' WHEN 'REJECTED' THEN 'Reserva rejeitada' ELSE 'Reserva vencida' END,
   version=version+1
  WHERE tenant_id=NEW.tenant_id AND reservation_id=NEW.id AND state='ACTIVE' AND valid_until>clock_timestamp();
 SELECT count(*) INTO pending FROM cp.visit_permits p WHERE p.tenant_id=NEW.tenant_id AND p.reservation_id=NEW.id
  AND p.state='ACTIVE' AND p.valid_until>clock_timestamp();
 PERFORM set_config('app.closing_reservation','',true);
 IF pending>0 THEN RAISE EXCEPTION 'convidados da reserva não foram revogados' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.reservation_guests_close() FROM PUBLIC;
CREATE TRIGGER reservations_close_guests AFTER UPDATE OF status ON cp.reservations
 FOR EACH ROW EXECUTE FUNCTION cp.reservation_guests_close();

-- Convites liberados só durante o encerramento da própria reserva (marca local da transação).
CREATE FUNCTION cp.reservation_closing(target_reservation uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT target_reservation IS NOT NULL AND target_reservation::text=nullif(current_setting('app.closing_reservation',true),'') $$;
REVOKE ALL ON FUNCTION cp.reservation_closing(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.reservation_closing(uuid) TO cp_api;

DROP POLICY permits_read_scope ON cp.visit_permits;
CREATE POLICY permits_read_scope ON cp.visit_permits AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('concierge.operate') OR cp.visit_member(tenant_id,unit_id,NULL)
  OR (created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create'))
  OR cp.permit_link_open(tenant_id,id) OR cp.reservation_closing(reservation_id));
DROP POLICY permits_revoke ON cp.visit_permits;
CREATE POLICY permits_revoke ON cp.visit_permits AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(cp.visit_member(tenant_id,unit_id,NULL) OR (created_by=platform.current_actor_id() AND cp.visit_member(tenant_id,unit_id,'create'))
  OR cp.reservation_closing(reservation_id))
 WITH CHECK(state='REVOKED' AND revoked_by=platform.current_actor_id());
