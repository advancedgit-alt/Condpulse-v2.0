-- CP-040: fila de espera de reservas. A unidade entra na fila de um período ocupado, com as regras aceitas naquele
-- momento. Quando o período é liberado (cancelamento, rejeição, expiração), a oferta ao próximo da fila cria uma retenção
-- HELD de até 15 minutos (nunca depois do início) em nome de quem entrou na fila. A exclusão GiST continua sendo a única
-- garantia de ocupação: duas ofertas nunca capturam o mesmo período. Aceitar transforma a retenção em reserva (ou em
-- pedido, se as regras aceitas exigem aprovação); recusar ou deixar vencer libera o período e a fila avança.
--
-- Ordem de travas: a oferta roda em transação própria e toma primeiro a trava da fila da área; as linhas de reserva que
-- ela expira são lidas com SKIP LOCKED (nunca espera um comando de unidade). Os comandos da unidade (criar, cancelar,
-- aceitar, sair) não tomam a trava da fila.

CREATE TABLE cp.reservation_waitlist (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 amenity_id uuid NOT NULL,
 unit_id uuid NOT NULL,
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 occupied_from timestamptz NOT NULL,
 occupied_until timestamptz NOT NULL,
 rule_snapshot jsonb NOT NULL CHECK(jsonb_typeof(rule_snapshot)='object'),
 fee_cents bigint NOT NULL DEFAULT 0 CHECK(fee_cents>=0),
 -- WAITING: na fila; OFFERED: retenção em vigor; ACCEPTED: virou reserva ou pedido; LEFT: saiu ou recusou;
 -- EXPIRED: oferta não aceita no prazo; CLOSED: o período começou sem vaga.
 state text NOT NULL DEFAULT 'WAITING' CHECK(state IN ('WAITING','OFFERED','ACCEPTED','LEFT','EXPIRED','CLOSED')),
 offer_reservation_id uuid,
 offered_at timestamptz,
 offer_expires_at timestamptz,
 closed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,amenity_id) REFERENCES cp.amenities(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 FOREIGN KEY(tenant_id,offer_reservation_id) REFERENCES cp.reservations(tenant_id,id),
 CHECK(isfinite(starts_at) AND isfinite(ends_at) AND isfinite(occupied_from) AND isfinite(occupied_until)),
 CHECK(starts_at<ends_at AND occupied_from<=starts_at AND occupied_until>=ends_at),
 CHECK(state<>'WAITING' OR (offer_reservation_id IS NULL AND offered_at IS NULL AND offer_expires_at IS NULL)),
 CHECK(state NOT IN ('OFFERED','ACCEPTED','EXPIRED') OR (offer_reservation_id IS NOT NULL AND offered_at IS NOT NULL
  AND offer_expires_at IS NOT NULL)),
 CHECK((state IN ('WAITING','OFFERED'))=(closed_at IS NULL))
);
-- Uma entrada aberta por unidade e período; uma entrada por retenção; a fila de cada área em ordem estável.
CREATE UNIQUE INDEX reservation_waitlist_open_slot ON cp.reservation_waitlist(tenant_id,unit_id,amenity_id,starts_at,ends_at)
 WHERE state IN ('WAITING','OFFERED');
CREATE UNIQUE INDEX reservation_waitlist_offer ON cp.reservation_waitlist(tenant_id,offer_reservation_id)
 WHERE offer_reservation_id IS NOT NULL;
CREATE INDEX reservation_waitlist_queue ON cp.reservation_waitlist(tenant_id,amenity_id,created_at,id) WHERE state='WAITING';
CREATE INDEX reservation_waitlist_by_unit ON cp.reservation_waitlist(tenant_id,unit_id,created_at DESC,id);

REVOKE ALL ON cp.reservation_waitlist FROM PUBLIC;
GRANT SELECT ON cp.reservation_waitlist TO cp_api;
GRANT INSERT(id,tenant_id,amenity_id,unit_id,created_by,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot,fee_cents)
 ON cp.reservation_waitlist TO cp_api;
GRANT UPDATE(state,offer_reservation_id,offered_at,offer_expires_at,closed_at,updated_at,version) ON cp.reservation_waitlist TO cp_api;
ALTER TABLE cp.reservation_waitlist ENABLE ROW LEVEL SECURITY;
ALTER TABLE cp.reservation_waitlist FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON cp.reservation_waitlist TO cp_api
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());

-- Vínculo residencial vigente na unidade (adulto, ou dependente com a permissão de reservas indicada).
CREATE FUNCTION cp.reservation_member(target_tenant uuid,target_unit uuid,dependent_permission text) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=platform.current_actor_id() AND m.status='ACTIVE'
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
   AND (m.role IN ('RESIDENT','RESPONSIBLE') OR (dependent_permission IS NOT NULL AND m.role='DEPENDENT'
    AND coalesce(m.permissions#>ARRAY['reservations',dependent_permission]='true'::jsonb,false)))) $$;
REVOKE ALL ON FUNCTION cp.reservation_member(uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.reservation_member(uuid,uuid,text) TO cp_api;

-- Marcas locais da transação, ligadas só dentro das funções abaixo e limpas antes de devolver.
CREATE FUNCTION cp.waitlist_offering(target_tenant uuid,target_amenity uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT target_tenant IS NOT NULL AND target_amenity IS NOT NULL
  AND target_tenant::text||':'||target_amenity::text=nullif(current_setting('app.waitlist_offer',true),'') $$;
REVOKE ALL ON FUNCTION cp.waitlist_offering(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.waitlist_offering(uuid,uuid) TO cp_api;
CREATE FUNCTION cp.waitlist_accepting(target_reservation uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT target_reservation IS NOT NULL AND target_reservation::text=nullif(current_setting('app.waitlist_accept',true),'') $$;
REVOKE ALL ON FUNCTION cp.waitlist_accepting(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.waitlist_accepting(uuid) TO cp_api;

-- A unidade vê e mexe nas próprias entradas; a oferta (marca da área) vê e avança a fila inteira da área.
CREATE POLICY reservation_waitlist_read ON cp.reservation_waitlist AS RESTRICTIVE FOR SELECT TO cp_api
 USING(cp.reservation_member(tenant_id,unit_id,'create') OR cp.reservation_member(tenant_id,unit_id,'cancel')
  OR cp.waitlist_offering(tenant_id,amenity_id));
CREATE POLICY reservation_waitlist_join ON cp.reservation_waitlist AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(created_by=platform.current_actor_id() AND state='WAITING' AND cp.reservation_member(tenant_id,unit_id,'create'));
CREATE POLICY reservation_waitlist_change ON cp.reservation_waitlist AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(cp.reservation_member(tenant_id,unit_id,'create') OR cp.reservation_member(tenant_id,unit_id,'cancel')
  OR cp.waitlist_offering(tenant_id,amenity_id))
 WITH CHECK(cp.reservation_member(tenant_id,unit_id,'create') OR cp.reservation_member(tenant_id,unit_id,'cancel')
  OR cp.waitlist_offering(tenant_id,amenity_id));

-- Máquina de estados da entrada: WAITING→OFFERED/LEFT/CLOSED; OFFERED→ACCEPTED/LEFT/EXPIRED; o resto é final.
-- Versão sobe exatamente 1 a cada mudança; horários vêm do banco.
CREATE FUNCTION cp.reservation_waitlist_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.starts_at<=clock_timestamp() THEN RAISE EXCEPTION 'período já começou' USING ERRCODE='23514'; END IF;
  NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at;
  RETURN NEW;
 END IF;
 IF NEW.version<>OLD.version+1 OR (NEW.tenant_id,NEW.amenity_id,NEW.unit_id,NEW.created_by,NEW.starts_at,NEW.ends_at)
   IS DISTINCT FROM (OLD.tenant_id,OLD.amenity_id,OLD.unit_id,OLD.created_by,OLD.starts_at,OLD.ends_at) THEN
  RAISE EXCEPTION 'alteração de fila não permitida' USING ERRCODE='23514';
 END IF;
 IF NOT ((OLD.state='WAITING' AND NEW.state IN ('OFFERED','LEFT','CLOSED'))
   OR (OLD.state='OFFERED' AND NEW.state IN ('ACCEPTED','LEFT','EXPIRED'))) THEN
  RAISE EXCEPTION 'transição de fila não permitida' USING ERRCODE='23514';
 END IF;
 IF OLD.state='OFFERED' AND NEW.offer_reservation_id IS DISTINCT FROM OLD.offer_reservation_id THEN
  RAISE EXCEPTION 'oferta não pode trocar de retenção' USING ERRCODE='23514';
 END IF;
 NEW.updated_at:=clock_timestamp();
 IF NEW.state NOT IN ('WAITING','OFFERED') THEN NEW.closed_at:=NEW.updated_at; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.reservation_waitlist_guard() FROM PUBLIC;
CREATE TRIGGER reservation_waitlist_guard BEFORE INSERT OR UPDATE ON cp.reservation_waitlist
 FOR EACH ROW EXECUTE FUNCTION cp.reservation_waitlist_guard();

-- Retenção HELD só nasce pela oferta da fila; confirmar sem a capacidade de decidir só ao aceitar a própria oferta.
DROP POLICY reservations_insert_states ON cp.reservations;
CREATE POLICY reservations_insert_states ON cp.reservations AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(CASE kind WHEN 'BLOCK' THEN status='CONFIRMED'
  ELSE status IN ('PENDING_APPROVAL','CONFIRMED') OR (status='HELD' AND cp.waitlist_offering(tenant_id,amenity_id)) END);
DROP POLICY reservations_decision_update ON cp.reservations;
CREATE POLICY reservations_decision_update ON cp.reservations AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(true)
 WITH CHECK(status NOT IN ('CONFIRMED','REJECTED') OR platform.has_capability('reservations.decide')
  OR (status='CONFIRMED' AND cp.waitlist_accepting(id)));

-- A retenção da oferta e a entrada da fila mudam juntas, por qualquer caminho: aceitar (CONFIRMED/PENDING_APPROVAL),
-- recusar (CANCELLED) ou vencer (EXPIRED). Retenção anterior à fila (sem entrada) só muda de estado.
CREATE FUNCTION cp.reservation_waitlist_offer_closed() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
 UPDATE cp.reservation_waitlist SET state=CASE NEW.status WHEN 'CANCELLED' THEN 'LEFT' WHEN 'EXPIRED' THEN 'EXPIRED' ELSE 'ACCEPTED' END,
   version=version+1
  WHERE tenant_id=NEW.tenant_id AND offer_reservation_id=NEW.id AND state='OFFERED';
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.reservation_waitlist_offer_closed() FROM PUBLIC;
CREATE TRIGGER reservations_waitlist_offer_closed AFTER UPDATE OF status ON cp.reservations FOR EACH ROW
 WHEN(OLD.status='HELD' AND NEW.status<>'HELD') EXECUTE FUNCTION cp.reservation_waitlist_offer_closed();

-- Autor ainda elegível no momento da oferta (mesma regra de quem entra: adulto da unidade, ou dependente com
-- reservations.create; conta e unidade ativas). Autor sem vínculo não segura o período: a entrada é encerrada e a
-- oferta passa ao próximo. Durante a oferta (marca local da transação), a API lê os vínculos do condomínio; o worker já
-- lê os vínculos residenciais ativos (V015).
CREATE FUNCTION cp.waitlist_author_eligible(target_tenant uuid,target_unit uuid,target_account uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  JOIN platform.accounts a ON a.id=m.account_id AND a.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=target_account AND m.status='ACTIVE'
   AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())
   AND (m.role IN ('RESIDENT','RESPONSIBLE') OR (m.role='DEPENDENT'
    AND coalesce(m.permissions#>ARRAY['reservations','create']='true'::jsonb,false)))) $$;
REVOKE ALL ON FUNCTION cp.waitlist_author_eligible(uuid,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.waitlist_author_eligible(uuid,uuid,uuid) TO cp_api,cp_worker;
-- Cota da unidade pelas regras aceitas na entrada: reservas ativas (retenção e pedido no prazo, confirmada) que ainda
-- não terminaram, contra policy.maximumActivePerUnit. Regra ilegível não tem cota.
CREATE FUNCTION cp.waitlist_quota_available(target_tenant uuid,target_unit uuid,snapshot jsonb) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT CASE WHEN snapshot->'policy'->>'maximumActivePerUnit' ~ '^[1-9][0-9]{0,2}$'
  THEN (SELECT count(*) FROM cp.reservations r WHERE r.tenant_id=target_tenant AND r.unit_id=target_unit AND r.kind='BOOKING'
    AND r.status IN ('HELD','PENDING_APPROVAL','CONFIRMED') AND r.ends_at>clock_timestamp()
    AND NOT (r.status IN ('HELD','PENDING_APPROVAL') AND r.expires_at<=clock_timestamp()))
   <(snapshot->'policy'->>'maximumActivePerUnit')::integer
  ELSE false END $$;
REVOKE ALL ON FUNCTION cp.waitlist_quota_available(uuid,uuid,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.waitlist_quota_available(uuid,uuid,jsonb) TO cp_api,cp_worker;
CREATE POLICY membership_waitlist_offer_read ON cp.memberships FOR SELECT TO cp_api
 USING(tenant_id=platform.current_tenant_id() AND coalesce(current_setting('app.waitlist_offer',true),'') LIKE tenant_id::text||':%');
CREATE POLICY account_waitlist_offer_read ON platform.accounts FOR SELECT TO cp_api
 USING(coalesce(current_setting('app.waitlist_offer',true),'') LIKE platform.current_tenant_id()::text||':%'
  AND EXISTS(SELECT 1 FROM cp.memberships m WHERE m.account_id=platform.accounts.id AND m.tenant_id=platform.current_tenant_id()));

-- Oferta: em transação própria, sob a trava da fila da área. Expira retenções e pedidos vencidos da área (linhas
-- travadas por outro comando ficam para a próxima passada), encerra entradas cujo período começou e oferece cada período
-- livre ao primeiro da fila, em ordem de entrada, até o início do período (a fila vem antes da reserva direta).
-- Devolve quantas ofertas criou. Papel sem INSERT em reservas não oferece; o worker recebe só as colunas da retenção.
CREATE FUNCTION cp.waitlist_offer(target_tenant uuid,target_amenity uuid) RETURNS integer
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE offered integer:=0; entry record; stale record; held uuid; deadline timestamptz;
BEGIN
 IF target_tenant IS NULL OR target_amenity IS NULL OR target_tenant IS DISTINCT FROM platform.current_tenant_id() THEN
  RAISE EXCEPTION 'condomínio indisponível' USING ERRCODE='42501';
 END IF;
 IF NOT has_column_privilege(current_user,'cp.reservations','id','INSERT') THEN RETURN 0; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.waitlist:'||target_tenant::text||':'||target_amenity::text,0));
 PERFORM set_config('app.waitlist_offer',target_tenant::text||':'||target_amenity::text,true);
 FOR stale IN SELECT r.id,r.status FROM cp.reservations r
   WHERE r.tenant_id=target_tenant AND r.amenity_id=target_amenity AND r.status IN ('HELD','PENDING_APPROVAL')
    AND r.expires_at<=clock_timestamp()
   ORDER BY r.id FOR UPDATE SKIP LOCKED LOOP
  UPDATE cp.reservations SET status='EXPIRED',updated_at=clock_timestamp(),version=version+1,
    decision_reason=CASE stale.status WHEN 'HELD' THEN 'Oferta da fila de espera não aceita no prazo.'
     ELSE 'Prazo de decisão encerrado sem confirmação.' END
   WHERE id=stale.id;
  INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   SELECT r.tenant_id,'RESERVATION_EXPIRED',r.id,r.version,jsonb_build_object('reservationId',r.id,'unitId',r.unit_id,
    'amenityId',r.amenity_id,'status',r.status,'version',r.version)
   FROM cp.reservations r WHERE r.id=stale.id;
 END LOOP;
 UPDATE cp.reservation_waitlist SET state='CLOSED',version=version+1
  WHERE tenant_id=target_tenant AND amenity_id=target_amenity AND state='WAITING' AND starts_at<=clock_timestamp();
 -- Autor sem vínculo elegível não segura o período: a entrada é encerrada.
 UPDATE cp.reservation_waitlist w SET state='CLOSED',version=w.version+1
  WHERE w.id IN (SELECT x.id FROM cp.reservation_waitlist x WHERE x.tenant_id=target_tenant AND x.amenity_id=target_amenity
   AND x.state='WAITING' AND NOT cp.waitlist_author_eligible(x.tenant_id,x.unit_id,x.created_by) FOR UPDATE SKIP LOCKED);
 -- Só entradas que podem receber agora entram no lote (unidade sem cota fica aguardando sem ocupar o lote).
 FOR entry IN SELECT w.* FROM cp.reservation_waitlist w
   WHERE w.tenant_id=target_tenant AND w.amenity_id=target_amenity AND w.state='WAITING'
    AND w.starts_at>clock_timestamp() AND cp.waitlist_author_eligible(w.tenant_id,w.unit_id,w.created_by)
    AND cp.waitlist_quota_available(w.tenant_id,w.unit_id,w.rule_snapshot)
    AND EXISTS(SELECT 1 FROM cp.amenities a WHERE a.tenant_id=w.tenant_id AND a.id=w.amenity_id AND a.active)
    AND NOT EXISTS(SELECT 1 FROM cp.reservations r WHERE r.tenant_id=w.tenant_id AND r.amenity_id=w.amenity_id
     AND r.status IN ('HELD','PENDING_APPROVAL','CONFIRMED')
     AND tstzrange(r.occupied_from,r.occupied_until,'[)') && tstzrange(w.occupied_from,w.occupied_until,'[)'))
   ORDER BY w.created_at,w.id LIMIT 50 FOR UPDATE OF w SKIP LOCKED LOOP
  IF NOT cp.waitlist_author_eligible(entry.tenant_id,entry.unit_id,entry.created_by) THEN
   UPDATE cp.reservation_waitlist SET state='CLOSED',version=version+1 WHERE id=entry.id;
   CONTINUE;
  END IF;
  -- Cota da unidade pelas regras aceitas, sob a trava da unidade (a mesma da reserva direta e do aceite). A criação
  -- toma a trava da unidade antes da trava da fila; aqui a ordem é a inversa, então só se tenta: trava ocupada encerra
  -- esta passada (a próxima continua da mesma posição), sem impasse e sem pular a ordem da fila.
  IF NOT pg_try_advisory_xact_lock(hashtextextended('cp.reservations:'||entry.tenant_id::text||':'||entry.unit_id::text,0)) THEN
   EXIT;
  END IF;
  -- Reconferência sob a trava: unidade que chegou ao limite (ou regra aceita ilegível) continua aguardando.
  IF NOT cp.waitlist_quota_available(entry.tenant_id,entry.unit_id,entry.rule_snapshot) THEN CONTINUE; END IF;
  held:=gen_random_uuid();
  deadline:=least(clock_timestamp()+interval '15 minutes',entry.starts_at);
  BEGIN
   INSERT INTO cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,
     occupied_until,expires_at,rule_snapshot,fee_cents)
    VALUES(held,entry.tenant_id,entry.amenity_id,entry.unit_id,entry.created_by,'BOOKING','HELD',entry.starts_at,entry.ends_at,
     entry.occupied_from,entry.occupied_until,deadline,entry.rule_snapshot,entry.fee_cents);
  EXCEPTION WHEN exclusion_violation THEN
   -- Outro comando ocupou o período no meio do caminho: a entrada continua na fila.
   CONTINUE;
  END;
  UPDATE cp.reservation_waitlist SET state='OFFERED',offer_reservation_id=held,offered_at=clock_timestamp(),
    offer_expires_at=deadline,version=version+1
   WHERE id=entry.id;
  INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload)
   VALUES(entry.tenant_id,'WAITLIST_OFFERED',entry.id,entry.version+1,jsonb_build_object('waitlistId',entry.id,
    'unitId',entry.unit_id,'amenityId',entry.amenity_id,'reservationId',held,'expiresAt',deadline));
  offered:=offered+1;
 END LOOP;
 PERFORM set_config('app.waitlist_offer','',true);
 RETURN offered;
END $$;
REVOKE ALL ON FUNCTION cp.waitlist_offer(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.waitlist_offer(uuid,uuid) TO cp_api;

-- Aceite da própria oferta (a API já conferiu autoria, vínculo e cota sob a trava da unidade), em área ativa: retenção em vigor vira
-- reserva confirmada, ou pedido com prazo de decisão se as regras aceitas exigem aprovação. Devolve o novo estado.
CREATE FUNCTION cp.waitlist_accept(target_tenant uuid,target_entry uuid,expected_version bigint) RETURNS text
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE entry record; target record; next_status text;
BEGIN
 SELECT * INTO entry FROM cp.reservation_waitlist WHERE tenant_id=target_tenant AND id=target_entry FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'entrada indisponível' USING ERRCODE='42501'; END IF;
 IF entry.created_by IS DISTINCT FROM platform.current_actor_id() THEN RAISE EXCEPTION 'oferta de outra pessoa' USING ERRCODE='42501'; END IF;
 IF entry.state<>'OFFERED' OR entry.version<>expected_version THEN RAISE EXCEPTION 'entrada alterada' USING ERRCODE='23514'; END IF;
 SELECT r.id,r.status,r.expires_at,r.starts_at INTO target FROM cp.reservations r
  WHERE r.tenant_id=target_tenant AND r.id=entry.offer_reservation_id FOR UPDATE;
 IF NOT FOUND OR target.status<>'HELD' OR target.expires_at<=clock_timestamp() THEN
  RAISE EXCEPTION 'oferta vencida' USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM cp.amenities a WHERE a.tenant_id=target_tenant AND a.id=entry.amenity_id AND a.active) THEN
  RAISE EXCEPTION 'área inativa' USING ERRCODE='23514';
 END IF;
 next_status:=CASE WHEN entry.rule_snapshot->>'approvalRequired'='true' THEN 'PENDING_APPROVAL'
  WHEN entry.rule_snapshot->>'approvalRequired'='false' THEN 'CONFIRMED' END;
 IF next_status IS NULL THEN RAISE EXCEPTION 'regras aceitas sem aprovação definida' USING ERRCODE='23514'; END IF;
 PERFORM set_config('app.waitlist_accept',target.id::text,true);
 UPDATE cp.reservations SET status=next_status,updated_at=clock_timestamp(),version=version+1,
   expires_at=CASE WHEN next_status='PENDING_APPROVAL' THEN least(clock_timestamp()+interval '24 hours',target.starts_at) END
  WHERE id=target.id;
 PERFORM set_config('app.waitlist_accept','',true);
 RETURN next_status;
END $$;
REVOKE ALL ON FUNCTION cp.waitlist_accept(uuid,uuid,bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.waitlist_accept(uuid,uuid,bigint) TO cp_api;

-- Posição na fila: quantas entradas aguardam antes desta (por ordem de entrada) disputando um período que se sobrepõe,
-- mais um. Só para quem enxerga a própria entrada; devolve apenas o número, nunca dados de outras unidades.
CREATE FUNCTION cp.waitlist_position(target_tenant uuid,target_entry uuid) RETURNS integer
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE entry record; previous text:=coalesce(current_setting('app.waitlist_offer',true),''); ahead integer;
BEGIN
 SELECT w.amenity_id,w.state,w.created_at,w.id,w.occupied_from,w.occupied_until INTO entry FROM cp.reservation_waitlist w
  WHERE w.tenant_id=target_tenant AND w.id=target_entry;
 IF NOT FOUND OR entry.state<>'WAITING' THEN RETURN NULL; END IF;
 PERFORM set_config('app.waitlist_offer',target_tenant::text||':'||entry.amenity_id::text,true);
 SELECT count(*) INTO ahead FROM cp.reservation_waitlist w
  WHERE w.tenant_id=target_tenant AND w.amenity_id=entry.amenity_id AND w.state='WAITING'
   AND (w.created_at,w.id)<(entry.created_at,entry.id)
   AND tstzrange(w.occupied_from,w.occupied_until,'[)') && tstzrange(entry.occupied_from,entry.occupied_until,'[)');
 PERFORM set_config('app.waitlist_offer',previous,true);
 RETURN ahead+1;
END $$;
REVOKE ALL ON FUNCTION cp.waitlist_position(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.waitlist_position(uuid,uuid) TO cp_api;

-- Prioridade da fila sobre a reserva direta: com a trava da fila da área (a mesma da oferta), diz se alguém aguarda um
-- período livre que se sobrepõe ao pedido. A criação chama antes de gravar; com fila, recusa e a oferta segue para quem
-- aguarda. Devolve só o booleano, sem dados de outras unidades.
CREATE FUNCTION cp.waitlist_blocks(target_tenant uuid,target_amenity uuid,range_from timestamptz,range_until timestamptz) RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE previous text:=coalesce(current_setting('app.waitlist_offer',true),''); waiting boolean;
BEGIN
 IF target_tenant IS NULL OR target_amenity IS NULL OR target_tenant IS DISTINCT FROM platform.current_tenant_id() THEN
  RAISE EXCEPTION 'condomínio indisponível' USING ERRCODE='42501';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cp.waitlist:'||target_tenant::text||':'||target_amenity::text,0));
 PERFORM set_config('app.waitlist_offer',target_tenant::text||':'||target_amenity::text,true);
 SELECT EXISTS(SELECT 1 FROM cp.reservation_waitlist w WHERE w.tenant_id=target_tenant AND w.amenity_id=target_amenity
   AND w.state='WAITING' AND w.starts_at>clock_timestamp()
   AND tstzrange(w.occupied_from,w.occupied_until,'[)') && tstzrange(range_from,range_until,'[)')
   -- Só bloqueia quem a oferta consegue atender: autor elegível, unidade com cota e período aguardado inteiro livre;
   -- entrada de período ainda ocupado em parte não impede o uso dos horários livres ao lado.
   AND cp.waitlist_author_eligible(w.tenant_id,w.unit_id,w.created_by) AND cp.waitlist_quota_available(w.tenant_id,w.unit_id,w.rule_snapshot)
   AND NOT EXISTS(SELECT 1 FROM cp.reservations r WHERE r.tenant_id=w.tenant_id AND r.amenity_id=w.amenity_id
    AND r.status IN ('HELD','PENDING_APPROVAL','CONFIRMED') AND NOT (r.status IN ('HELD','PENDING_APPROVAL') AND r.expires_at<=clock_timestamp())
    AND tstzrange(r.occupied_from,r.occupied_until,'[)') && tstzrange(w.occupied_from,w.occupied_until,'[)')))
  -- Período pedido ainda ocupado: a exclusão recusa a criação como conflito comum; a prioridade vale para o período livre.
  AND NOT EXISTS(SELECT 1 FROM cp.reservations r WHERE r.tenant_id=target_tenant AND r.amenity_id=target_amenity
   AND r.status IN ('HELD','PENDING_APPROVAL','CONFIRMED') AND NOT (r.status IN ('HELD','PENDING_APPROVAL') AND r.expires_at<=clock_timestamp())
   AND tstzrange(r.occupied_from,r.occupied_until,'[)') && tstzrange(range_from,range_until,'[)')) INTO waiting;
 PERFORM set_config('app.waitlist_offer',previous,true);
 RETURN waiting;
END $$;
REVOKE ALL ON FUNCTION cp.waitlist_blocks(uuid,uuid,timestamptz,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.waitlist_blocks(uuid,uuid,timestamptz,timestamptz) TO cp_api;

-- Worker (cp_worker, sem conta): a mesma oferta em passadas periódicas, para a fila andar sem requisição da API
-- (oferta vencida, pedido vencido). Tudo restrito à marca da oferta da área: o worker não lê nem altera entradas fora
-- dela, só cria retenção HELD e só grava os eventos da própria oferta.
GRANT EXECUTE ON FUNCTION cp.waitlist_offer(uuid,uuid),cp.waitlist_offering(uuid,uuid) TO cp_worker;
GRANT SELECT(id,tenant_id,active) ON cp.amenities TO cp_worker;
GRANT SELECT ON cp.reservation_waitlist TO cp_worker;
GRANT UPDATE(state,offer_reservation_id,offered_at,offer_expires_at,closed_at,updated_at,version) ON cp.reservation_waitlist TO cp_worker;
CREATE POLICY tenant_boundary_worker ON cp.reservation_waitlist TO cp_worker
 USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY reservation_waitlist_worker ON cp.reservation_waitlist AS RESTRICTIVE TO cp_worker
 USING(cp.waitlist_offering(tenant_id,amenity_id)) WITH CHECK(cp.waitlist_offering(tenant_id,amenity_id));
GRANT INSERT(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,expires_at,rule_snapshot,fee_cents)
 ON cp.reservations TO cp_worker;
CREATE POLICY reservations_worker_insert ON cp.reservations AS RESTRICTIVE FOR INSERT TO cp_worker
 WITH CHECK(kind='BOOKING' AND status='HELD' AND cp.waitlist_offering(tenant_id,amenity_id));
GRANT INSERT(tenant_id,event_type,aggregate_id,aggregate_version,payload) ON cp.outbox TO cp_worker;
CREATE POLICY outbox_worker_insert ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_worker
 WITH CHECK(event_type IN ('WAITLIST_OFFERED','RESERVATION_EXPIRED') AND state='PENDING' AND attempts=0 AND lease_until IS NULL
  AND processed_at IS NULL AND last_error IS NULL AND jsonb_typeof(payload->'amenityId')='string'
  AND cp.waitlist_offering(tenant_id,(payload->>'amenityId')::uuid));
