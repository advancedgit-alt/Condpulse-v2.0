-- ADR-0008: financeiro por condomínio com o Asaas (primeira entrega, somente sandbox). A gestão (finance.manage, AAL2)
-- escolhe o pagador de cada unidade (proprietário ou responsável ativo da unidade), cadastra taxas recorrentes e cobranças
-- avulsas (taxa condominial, reserva, multa) por boleto ou PIX, cancela e consulta inadimplência. A unidade (morador,
-- responsável ou proprietário com vínculo vigente) vê as cobranças emitidas da própria unidade; proprietário de unidade
-- alugada não vê nada da unidade (barreira rental_owner_hidden da V034). O webhook do Asaas, já autenticado na API pelo
-- token do cabeçalho, só sincroniza a situação de uma cobrança identificada (app.finance_webhook_charge), sem ator, sem
-- capacidade e sem alterar conteúdo. Nada é apagado; a chave do Asaas nunca chega ao banco.

-- Contexto do webhook: vale só sem ator e sem capacidade publicada (transação dedicada da API).
CREATE FUNCTION cp.finance_webhook_charge() RETURNS uuid
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT CASE WHEN platform.current_actor_id() IS NULL AND coalesce(current_setting('app.capabilities',true),'')=''
  THEN nullif(current_setting('app.finance_webhook_charge',true),'')::uuid END $$;
REVOKE ALL ON FUNCTION cp.finance_webhook_charge() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.finance_webhook_charge() TO cp_api;

-- Quem vê o financeiro da unidade: morador, responsável ou proprietário com vínculo vigente (dependente não vê).
CREATE FUNCTION cp.finance_unit_member(target_tenant uuid,target_unit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT EXISTS(SELECT 1 FROM cp.memberships m
  JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id AND u.status='ACTIVE'
  WHERE m.tenant_id=target_tenant AND m.unit_id=target_unit AND m.account_id=platform.current_actor_id() AND m.status='ACTIVE'
   AND m.role IN ('RESIDENT','RESPONSIBLE','OWNER') AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now())) $$;
REVOKE ALL ON FUNCTION cp.finance_unit_member(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.finance_unit_member(uuid,uuid) TO cp_api;

-- Pagador da unidade: um ativo por unidade; trocar substitui (REPLACED) e cria outro. CPF/CNPJ só em dígitos, exigido
-- pelo Asaas para boleto/PIX; a API devolve sempre mascarado. O cliente do Asaas é gravado uma única vez.
CREATE TABLE cp.finance_payers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid NOT NULL,
 membership_id uuid NOT NULL,
 legal_name text NOT NULL CHECK(length(btrim(legal_name)) BETWEEN 3 AND 120),
 document text NOT NULL CHECK(document ~ '^([0-9]{11}|[0-9]{14})$'),
 email text NOT NULL CHECK(length(email) BETWEEN 6 AND 254 AND email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
 asaas_customer_id text CHECK(asaas_customer_id IS NULL OR asaas_customer_id ~ '^cus_[A-Za-z0-9]{1,64}$'),
 state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','REPLACED')),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 replaced_by uuid REFERENCES platform.accounts(id),
 replaced_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 FOREIGN KEY(tenant_id,membership_id) REFERENCES cp.memberships(tenant_id,id),
 CHECK((state='REPLACED')=(replaced_by IS NOT NULL AND replaced_at IS NOT NULL))
);
CREATE UNIQUE INDEX finance_payers_one_active ON cp.finance_payers(tenant_id,unit_id) WHERE state='ACTIVE';
CREATE UNIQUE INDEX finance_payers_customer ON cp.finance_payers(asaas_customer_id) WHERE asaas_customer_id IS NOT NULL;

CREATE FUNCTION cp.finance_payers_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog
AS $$
DECLARE member record;
BEGIN
 IF TG_OP='INSERT' THEN
  SELECT m.unit_id,m.role,m.status INTO member FROM cp.memberships m WHERE m.tenant_id=NEW.tenant_id AND m.id=NEW.membership_id;
  IF NOT FOUND OR member.unit_id IS DISTINCT FROM NEW.unit_id OR member.role NOT IN ('OWNER','RESPONSIBLE') OR member.status<>'ACTIVE' THEN
   RAISE EXCEPTION 'pagador precisa ser proprietário ou responsável ativo da unidade' USING ERRCODE='23514';
  END IF;
  NEW.created_by:=platform.current_actor_id(); NEW.created_at:=clock_timestamp(); NEW.state:='ACTIVE';
  NEW.asaas_customer_id:=NULL; NEW.replaced_by:=NULL; NEW.replaced_at:=NULL; NEW.version:=0;
  RETURN NEW;
 END IF;
 IF (NEW.tenant_id,NEW.unit_id,NEW.membership_id,NEW.legal_name,NEW.document,NEW.email,NEW.created_by,NEW.created_at)
    IS DISTINCT FROM (OLD.tenant_id,OLD.unit_id,OLD.membership_id,OLD.legal_name,OLD.document,OLD.email,OLD.created_by,OLD.created_at)
    OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'pagador registrado não muda de conteúdo' USING ERRCODE='23514';
 END IF;
 IF NEW.asaas_customer_id IS DISTINCT FROM OLD.asaas_customer_id AND (OLD.asaas_customer_id IS NOT NULL OR NEW.asaas_customer_id IS NULL) THEN
  RAISE EXCEPTION 'cliente do Asaas do pagador é gravado uma única vez' USING ERRCODE='23514';
 END IF;
 IF NEW.state IS DISTINCT FROM OLD.state THEN
  IF OLD.state<>'ACTIVE' OR NEW.state<>'REPLACED' THEN
   RAISE EXCEPTION 'pagador substituído é final' USING ERRCODE='23514';
  END IF;
  NEW.replaced_by:=platform.current_actor_id(); NEW.replaced_at:=clock_timestamp();
 ELSIF (NEW.replaced_by,NEW.replaced_at) IS DISTINCT FROM (OLD.replaced_by,OLD.replaced_at) THEN
  RAISE EXCEPTION 'substituição do pagador vem do banco' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.finance_payers_guard() FROM PUBLIC;
CREATE TRIGGER finance_payers_guard BEFORE INSERT OR UPDATE ON cp.finance_payers FOR EACH ROW EXECUTE FUNCTION cp.finance_payers_guard();

-- Taxa recorrente (taxa condominial mensal): uma unidade ou, sem unidade, todas as unidades com pagador ativo. A gestão
-- gera as cobranças de uma competência; a mesma taxa não gera duas cobranças da mesma unidade na mesma competência.
CREATE TABLE cp.finance_recurring_fees (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid,
 kind text NOT NULL DEFAULT 'CONDO_FEE' CHECK(kind IN ('CONDO_FEE','OTHER')),
 description text NOT NULL CHECK(length(btrim(description)) BETWEEN 3 AND 200),
 amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 500 AND 100000000),
 due_day smallint NOT NULL CHECK(due_day BETWEEN 1 AND 28),
 billing_type text NOT NULL CHECK(billing_type IN ('BOLETO','PIX')),
 state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','ENDED')),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 ended_by uuid REFERENCES platform.accounts(id),
 ended_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK((state='ENDED')=(ended_by IS NOT NULL AND ended_at IS NOT NULL))
);
CREATE INDEX finance_recurring_fees_active ON cp.finance_recurring_fees(tenant_id,state,created_at);

CREATE FUNCTION cp.finance_recurring_fees_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog
AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  NEW.created_by:=platform.current_actor_id(); NEW.created_at:=clock_timestamp(); NEW.state:='ACTIVE';
  NEW.ended_by:=NULL; NEW.ended_at:=NULL; NEW.version:=0;
  RETURN NEW;
 END IF;
 IF (NEW.tenant_id,NEW.unit_id,NEW.kind,NEW.description,NEW.amount_cents,NEW.due_day,NEW.billing_type,NEW.created_by,NEW.created_at)
    IS DISTINCT FROM (OLD.tenant_id,OLD.unit_id,OLD.kind,OLD.description,OLD.amount_cents,OLD.due_day,OLD.billing_type,OLD.created_by,OLD.created_at)
    OR OLD.state<>'ACTIVE' OR NEW.state<>'ENDED' OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'taxa recorrente só pode ser encerrada uma vez' USING ERRCODE='23514';
 END IF;
 NEW.ended_by:=platform.current_actor_id(); NEW.ended_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.finance_recurring_fees_guard() FROM PUBLIC;
CREATE TRIGGER finance_recurring_fees_guard BEFORE INSERT OR UPDATE ON cp.finance_recurring_fees
 FOR EACH ROW EXECUTE FUNCTION cp.finance_recurring_fees_guard();

-- Cobrança: nasce PENDING_ISSUE (só no CondPulse); a emissão no Asaas (fora da transação) grava o pagamento e a abre.
-- Situações: OPEN, OVERDUE, PAID, CANCELLED e REFUNDED, sincronizadas pelo webhook ou pela gestão; CANCELLED e REFUNDED
-- são finais. Aberta vai direto a REFUNDED quando o Asaas já mostra o estorno (recebimento não processado antes). Valor de R$ 5,00 a R$ 1.000.000,00 (mínimo do boleto no Asaas).
CREATE TABLE cp.finance_charges (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid NOT NULL,
 payer_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('CONDO_FEE','RESERVATION','FINE','OTHER')),
 description text NOT NULL CHECK(length(btrim(description)) BETWEEN 3 AND 200),
 amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 500 AND 100000000),
 due_date date NOT NULL CHECK(due_date>=date '2026-01-01'),
 billing_type text NOT NULL CHECK(billing_type IN ('BOLETO','PIX')),
 recurring_fee_id uuid,
 competence date CHECK(competence IS NULL OR extract(day FROM competence)=1),
 state text NOT NULL DEFAULT 'PENDING_ISSUE' CHECK(state IN ('PENDING_ISSUE','OPEN','OVERDUE','PAID','CANCELLED','REFUNDED')),
 asaas_payment_id text CHECK(asaas_payment_id IS NULL OR asaas_payment_id ~ '^pay_[A-Za-z0-9]{1,64}$'),
 asaas_status text CHECK(asaas_status IS NULL OR asaas_status ~ '^[A-Z][A-Z_]{2,39}$'),
 invoice_url text CHECK(invoice_url IS NULL OR invoice_url ~ '^https://([a-z]+\.)?asaas\.com/[A-Za-z0-9/_.-]{1,200}$'),
 bank_slip_url text CHECK(bank_slip_url IS NULL OR bank_slip_url ~ '^https://([a-z]+\.)?asaas\.com/[A-Za-z0-9/_.-]{1,200}$'),
 paid_at date,
 paid_cents bigint CHECK(paid_cents IS NULL OR paid_cents BETWEEN 0 AND 200000000),
 issue_error text CHECK(issue_error IS NULL OR issue_error ~ '^[A-Z][A-Z0-9_]{2,63}$'),
 issue_lease_until timestamptz,
 -- Falhas de emissão: tentativas e próxima tentativa do lote "emitir pendentes" (recuo exponencial); emitir uma
 -- cobrança específica ignora a espera.
 issue_attempts integer NOT NULL DEFAULT 0 CHECK(issue_attempts>=0),
 next_issue_at timestamptz,
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 cancelled_by uuid REFERENCES platform.accounts(id),
 cancelled_at timestamptz,
 cancel_reason text CHECK(cancel_reason IS NULL OR length(btrim(cancel_reason)) BETWEEN 3 AND 500),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 FOREIGN KEY(tenant_id,payer_id) REFERENCES cp.finance_payers(tenant_id,id),
 FOREIGN KEY(tenant_id,recurring_fee_id) REFERENCES cp.finance_recurring_fees(tenant_id,id),
 CHECK((recurring_fee_id IS NULL)=(competence IS NULL)),
 CHECK(state<>'PENDING_ISSUE' OR asaas_payment_id IS NULL),
 CHECK(state IN ('PENDING_ISSUE','CANCELLED') OR asaas_payment_id IS NOT NULL),
 CHECK(state NOT IN ('PAID','REFUNDED') OR paid_at IS NOT NULL),
 CHECK((state='CANCELLED')=(cancelled_at IS NOT NULL)),
 CHECK(cancelled_by IS NULL OR cancel_reason IS NOT NULL),
 CHECK(state='PENDING_ISSUE' OR issue_lease_until IS NULL)
);
CREATE UNIQUE INDEX finance_charges_payment ON cp.finance_charges(asaas_payment_id) WHERE asaas_payment_id IS NOT NULL;
CREATE UNIQUE INDEX finance_charges_competence ON cp.finance_charges(tenant_id,recurring_fee_id,unit_id,competence)
 WHERE recurring_fee_id IS NOT NULL;
CREATE INDEX finance_charges_unit ON cp.finance_charges(tenant_id,unit_id,due_date DESC,id);
CREATE INDEX finance_charges_state ON cp.finance_charges(tenant_id,state,due_date,id);
CREATE INDEX finance_charges_issue_queue ON cp.finance_charges(tenant_id,next_issue_at NULLS FIRST,created_at,id) WHERE state='PENDING_ISSUE';

CREATE FUNCTION cp.finance_charges_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog
AS $$
DECLARE payer record; fee record; webhook boolean:=cp.finance_webhook_charge() IS NOT NULL;
BEGIN
 IF TG_OP='INSERT' THEN
  SELECT p.unit_id,p.state INTO payer FROM cp.finance_payers p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.payer_id;
  IF NOT FOUND OR payer.state<>'ACTIVE' OR payer.unit_id<>NEW.unit_id THEN
   RAISE EXCEPTION 'cobrança exige o pagador ativo da unidade' USING ERRCODE='23514';
  END IF;
  IF NEW.recurring_fee_id IS NOT NULL THEN
   SELECT f.unit_id,f.state,f.amount_cents,f.billing_type,f.kind INTO fee FROM cp.finance_recurring_fees f
    WHERE f.tenant_id=NEW.tenant_id AND f.id=NEW.recurring_fee_id;
   IF NOT FOUND OR fee.state<>'ACTIVE' OR (fee.unit_id IS NOT NULL AND fee.unit_id<>NEW.unit_id) OR fee.amount_cents<>NEW.amount_cents
      OR fee.billing_type<>NEW.billing_type OR fee.kind<>NEW.kind THEN
    RAISE EXCEPTION 'cobrança recorrente diverge da taxa ativa' USING ERRCODE='23514';
   END IF;
  END IF;
  NEW.state:='PENDING_ISSUE'; NEW.asaas_payment_id:=NULL; NEW.asaas_status:=NULL; NEW.invoice_url:=NULL; NEW.bank_slip_url:=NULL;
  NEW.paid_at:=NULL; NEW.paid_cents:=NULL; NEW.issue_error:=NULL; NEW.issue_lease_until:=NULL;
  NEW.issue_attempts:=0; NEW.next_issue_at:=NULL;
  NEW.cancelled_by:=NULL; NEW.cancelled_at:=NULL; NEW.cancel_reason:=NULL;
  NEW.created_by:=platform.current_actor_id(); NEW.created_at:=clock_timestamp(); NEW.updated_at:=NEW.created_at; NEW.version:=0;
  RETURN NEW;
 END IF;
 IF (NEW.tenant_id,NEW.unit_id,NEW.payer_id,NEW.kind,NEW.description,NEW.amount_cents,NEW.due_date,NEW.billing_type,NEW.recurring_fee_id,
     NEW.competence,NEW.created_by,NEW.created_at)
    IS DISTINCT FROM (OLD.tenant_id,OLD.unit_id,OLD.payer_id,OLD.kind,OLD.description,OLD.amount_cents,OLD.due_date,OLD.billing_type,
     OLD.recurring_fee_id,OLD.competence,OLD.created_by,OLD.created_at)
    OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'cobrança registrada não muda de conteúdo' USING ERRCODE='23514';
 END IF;
 IF OLD.state IN ('CANCELLED','REFUNDED') THEN
  RAISE EXCEPTION 'cobrança encerrada não muda' USING ERRCODE='23514';
 END IF;
 IF OLD.asaas_payment_id IS NOT NULL AND NEW.asaas_payment_id IS DISTINCT FROM OLD.asaas_payment_id THEN
  RAISE EXCEPTION 'pagamento do Asaas da cobrança é gravado uma única vez' USING ERRCODE='23514';
 END IF;
 IF NEW.issue_lease_until IS DISTINCT FROM OLD.issue_lease_until AND NEW.issue_lease_until>clock_timestamp()+interval '2 minutes' THEN
  RAISE EXCEPTION 'reserva de emissão longa demais' USING ERRCODE='23514';
 END IF;
 IF NEW.issue_attempts<OLD.issue_attempts OR NEW.issue_attempts>OLD.issue_attempts+1
    OR (NEW.next_issue_at IS DISTINCT FROM OLD.next_issue_at AND NEW.next_issue_at>clock_timestamp()+interval '6 hours 1 minute') THEN
  RAISE EXCEPTION 'tentativas de emissão só avançam uma a uma, com espera de até 6 horas' USING ERRCODE='23514';
 END IF;
 IF webhook AND ((NEW.asaas_payment_id,NEW.invoice_url,NEW.bank_slip_url,NEW.issue_error,NEW.issue_lease_until,NEW.cancelled_by,NEW.cancel_reason,
     NEW.issue_attempts,NEW.next_issue_at)
    IS DISTINCT FROM (OLD.asaas_payment_id,OLD.invoice_url,OLD.bank_slip_url,OLD.issue_error,OLD.issue_lease_until,OLD.cancelled_by,OLD.cancel_reason,
     OLD.issue_attempts,OLD.next_issue_at)) THEN
  RAISE EXCEPTION 'webhook só sincroniza a situação da cobrança' USING ERRCODE='42501';
 END IF;
 IF NEW.state<>OLD.state THEN
  IF OLD.state='PENDING_ISSUE' AND NEW.state IN ('OPEN','OVERDUE','PAID','REFUNDED') AND NOT webhook AND NEW.asaas_payment_id IS NOT NULL THEN
   NULL; -- emissão (ou adoção, depois de falha, do pagamento já criado com a mesma referência externa)
  ELSIF NEW.state='CANCELLED' AND OLD.state IN ('PENDING_ISSUE','OPEN','OVERDUE') THEN
   IF NOT webhook AND (NEW.cancelled_by IS DISTINCT FROM platform.current_actor_id() OR NEW.cancel_reason IS NULL) THEN
    RAISE EXCEPTION 'cancelamento exige autoria e motivo' USING ERRCODE='23514';
   END IF;
   NEW.cancelled_at:=clock_timestamp();
  ELSIF OLD.state IN ('OPEN','OVERDUE') AND NEW.state IN ('OPEN','OVERDUE','PAID','REFUNDED') THEN
   NULL;
  ELSIF OLD.state='PAID' AND NEW.state IN ('REFUNDED','OPEN','OVERDUE') THEN
   NULL;
  ELSE
   RAISE EXCEPTION 'transição de cobrança não permitida' USING ERRCODE='23514';
  END IF;
 ELSIF (NEW.cancelled_by,NEW.cancel_reason) IS DISTINCT FROM (OLD.cancelled_by,OLD.cancel_reason) THEN
  RAISE EXCEPTION 'cancelamento só com a transição' USING ERRCODE='23514';
 END IF;
 IF NEW.state IN ('OPEN','OVERDUE') THEN NEW.paid_at:=NULL; NEW.paid_cents:=NULL; END IF;
 IF NEW.state<>'PENDING_ISSUE' THEN NEW.issue_lease_until:=NULL; NEW.next_issue_at:=NULL; END IF;
 NEW.cancelled_at:=CASE WHEN NEW.state='CANCELLED' THEN coalesce(NEW.cancelled_at,OLD.cancelled_at,clock_timestamp()) END;
 NEW.updated_at:=clock_timestamp();
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.finance_charges_guard() FROM PUBLIC;
CREATE TRIGGER finance_charges_guard BEFORE INSERT OR UPDATE ON cp.finance_charges FOR EACH ROW EXECUTE FUNCTION cp.finance_charges_guard();

-- Eventos do webhook já processados: o identificador do evento do Asaas é único (entrega "pelo menos uma vez").
CREATE TABLE cp.finance_webhook_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 event_id text NOT NULL UNIQUE CHECK(event_id ~ '^[A-Za-z0-9_-]{4,128}$'),
 event_type text NOT NULL CHECK(event_type ~ '^[A-Z][A-Z_]{2,63}$'),
 charge_id uuid NOT NULL,
 asaas_status text CHECK(asaas_status IS NULL OR asaas_status ~ '^[A-Z][A-Z_]{2,39}$'),
 resulting_state text NOT NULL CHECK(resulting_state IN ('PENDING_ISSUE','OPEN','OVERDUE','PAID','CANCELLED','REFUNDED')),
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,charge_id) REFERENCES cp.finance_charges(tenant_id,id)
);
CREATE INDEX finance_webhook_events_charge ON cp.finance_webhook_events(tenant_id,charge_id,received_at);
CREATE FUNCTION cp.finance_webhook_events_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog
AS $$ BEGIN NEW.received_at:=clock_timestamp(); RETURN NEW; END $$;
REVOKE ALL ON FUNCTION cp.finance_webhook_events_guard() FROM PUBLIC;
CREATE TRIGGER finance_webhook_events_guard BEFORE INSERT ON cp.finance_webhook_events
 FOR EACH ROW EXECUTE FUNCTION cp.finance_webhook_events_guard();

-- Grants: a API lê e grava só as colunas de entrada e de transição; autoria, horários e conteúdo vêm do banco; sem
-- DELETE. Worker e implantação não acessam o financeiro.
REVOKE ALL ON cp.finance_payers,cp.finance_recurring_fees,cp.finance_charges,cp.finance_webhook_events FROM PUBLIC;
GRANT SELECT ON cp.finance_payers,cp.finance_recurring_fees,cp.finance_charges,cp.finance_webhook_events TO cp_api;
GRANT INSERT(id,tenant_id,unit_id,membership_id,legal_name,document,email) ON cp.finance_payers TO cp_api;
GRANT UPDATE(asaas_customer_id,state,version) ON cp.finance_payers TO cp_api;
GRANT INSERT(id,tenant_id,unit_id,kind,description,amount_cents,due_day,billing_type) ON cp.finance_recurring_fees TO cp_api;
GRANT UPDATE(state,version) ON cp.finance_recurring_fees TO cp_api;
GRANT INSERT(id,tenant_id,unit_id,payer_id,kind,description,amount_cents,due_date,billing_type,recurring_fee_id,competence)
 ON cp.finance_charges TO cp_api;
GRANT UPDATE(state,asaas_payment_id,asaas_status,invoice_url,bank_slip_url,paid_at,paid_cents,issue_error,issue_lease_until,
 issue_attempts,next_issue_at,cancelled_by,cancel_reason,version) ON cp.finance_charges TO cp_api;
GRANT INSERT(tenant_id,event_id,event_type,charge_id,asaas_status,resulting_state) ON cp.finance_webhook_events TO cp_api;

DO $$
DECLARE table_name text;
BEGIN
 FOREACH table_name IN ARRAY ARRAY['finance_payers','finance_recurring_fees','finance_charges','finance_webhook_events'] LOOP
  EXECUTE format('ALTER TABLE cp.%I ENABLE ROW LEVEL SECURITY',table_name);
  EXECUTE format('ALTER TABLE cp.%I FORCE ROW LEVEL SECURITY',table_name);
  EXECUTE format('CREATE POLICY tenant_boundary ON cp.%I TO cp_api USING(tenant_id=platform.current_tenant_id()) '
   'WITH CHECK(tenant_id=platform.current_tenant_id())',table_name);
 END LOOP;
 -- Barreira de locação (V034) em toda tabela nova com unit_id.
 FOREACH table_name IN ARRAY ARRAY['finance_payers','finance_recurring_fees','finance_charges'] LOOP
  EXECUTE format('CREATE POLICY rental_owner_hidden ON cp.%I AS RESTRICTIVE FOR ALL TO cp_api '
   'USING(NOT cp.rental_owner_hidden(tenant_id,unit_id)) WITH CHECK(NOT cp.rental_owner_hidden(tenant_id,unit_id))',table_name);
 END LOOP;
END $$;

-- Pagadores e taxas: somente a gestão financeira (AAL2).
CREATE POLICY finance_payers_staff ON cp.finance_payers AS RESTRICTIVE FOR ALL TO cp_api
 USING(platform.has_capability('finance.manage')) WITH CHECK(platform.has_capability('finance.manage'));
CREATE POLICY finance_recurring_fees_staff ON cp.finance_recurring_fees AS RESTRICTIVE FOR ALL TO cp_api
 USING(platform.has_capability('finance.manage')) WITH CHECK(platform.has_capability('finance.manage'));

-- Cobranças: gestão; unidade (vínculo vigente, só emitidas); webhook só a cobrança identificada, antes de saber o
-- condomínio (permissiva de leitura por id) e depois dentro dele.
CREATE POLICY finance_charges_webhook_lookup ON cp.finance_charges FOR SELECT TO cp_api USING(id=cp.finance_webhook_charge());
CREATE POLICY finance_charges_read ON cp.finance_charges AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('finance.manage') OR id=cp.finance_webhook_charge()
  OR (state<>'PENDING_ISSUE' AND cp.finance_unit_member(tenant_id,unit_id)));
CREATE POLICY finance_charges_insert ON cp.finance_charges AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(platform.has_capability('finance.manage'));
CREATE POLICY finance_charges_update ON cp.finance_charges AS RESTRICTIVE FOR UPDATE TO cp_api
 USING(platform.has_capability('finance.manage') OR id=cp.finance_webhook_charge())
 WITH CHECK(platform.has_capability('finance.manage') OR id=cp.finance_webhook_charge());

CREATE POLICY finance_webhook_events_read ON cp.finance_webhook_events AS RESTRICTIVE FOR SELECT TO cp_api
 USING(platform.has_capability('finance.manage') OR charge_id=cp.finance_webhook_charge());
CREATE POLICY finance_webhook_events_insert ON cp.finance_webhook_events AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(charge_id=cp.finance_webhook_charge());

-- Eventos e trilha: só sobre cobrança visível a quem grava; a trilha FINANCE_* é da gestão, em nome da própria conta.
CREATE POLICY outbox_finance_events ON cp.outbox AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(event_type NOT LIKE 'FINANCE\_%' OR EXISTS(SELECT 1 FROM cp.finance_charges c
  WHERE c.tenant_id=cp.outbox.tenant_id AND c.id=cp.outbox.aggregate_id));
CREATE POLICY audit_finance_actions ON cp.audit_log AS RESTRICTIVE FOR INSERT TO cp_api
 WITH CHECK(action NOT LIKE 'FINANCE\_%' OR (platform.has_capability('finance.manage') AND actor_id=platform.current_actor_id()
  AND resource_type IN ('FINANCE_CHARGE','FINANCE_PAYER','FINANCE_RECURRING_FEE')));
