-- Financeiro da plataforma (fase 5): assinatura CondPulse de cada condomínio. Planos e valores são cadastrados pela
-- administração com motivo e auditoria; nenhum preço é presumido aqui. Não há gateway, cobrança automática nem emissão
-- fiscal: pagamento só é registrado manualmente, com referência do comprovante, responsável e auditoria. Atraso não
-- bloqueia acesso de moradores nem da gestão. Somente capacidades de plataforma publicadas após AAL2 leem ou alteram.
CREATE TABLE platform.billing_plans (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
 price_cents bigint NOT NULL CHECK (price_cents BETWEEN 0 AND 100000000),
 period text NOT NULL CHECK (period IN ('MONTHLY','YEARLY')),
 active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX billing_plans_name ON platform.billing_plans(lower(btrim(name)));

-- Valor e período contratados são copiados do plano na contratação: mudar o plano não reescreve assinaturas.
CREATE TABLE platform.billing_subscriptions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 plan_id uuid NOT NULL REFERENCES platform.billing_plans(id),
 price_cents bigint NOT NULL CHECK (price_cents BETWEEN 0 AND 100000000),
 period text NOT NULL CHECK (period IN ('MONTHLY','YEARLY')),
 starts_on date NOT NULL,
 ends_on date,
 due_day smallint NOT NULL CHECK (due_day BETWEEN 1 AND 28),
 status text NOT NULL CHECK (status IN ('ACTIVE','ENDED')),
 version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 ended_at timestamptz,
 CHECK (ends_on IS NULL OR ends_on > starts_on),
 -- Início até o dia 28: os períodos mensais seguintes caem sempre no mesmo dia.
 CHECK (extract(day FROM starts_on) <= 28),
 CHECK ((status = 'ENDED') = (ended_at IS NOT NULL AND ends_on IS NOT NULL))
);
CREATE UNIQUE INDEX billing_subscription_active ON platform.billing_subscriptions(tenant_id) WHERE status = 'ACTIVE';

-- Cobrança de um período [period_start, period_end) da assinatura. PAID só existe com pagamento registrado do mesmo
-- valor (gatilho diferido abaixo); cancelada não recebe pagamento.
CREATE TABLE platform.billing_charges (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 subscription_id uuid NOT NULL REFERENCES platform.billing_subscriptions(id),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 period_start date NOT NULL,
 period_end date NOT NULL,
 amount_cents bigint NOT NULL CHECK (amount_cents BETWEEN 0 AND 100000000),
 due_on date NOT NULL,
 status text NOT NULL CHECK (status IN ('OPEN','PAID','CANCELLED')),
 cancel_reason text CHECK (cancel_reason IS NULL OR length(btrim(cancel_reason)) BETWEEN 1 AND 500),
 version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (subscription_id, period_start),
 CHECK (period_end > period_start AND due_on >= period_start),
 CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL))
);
CREATE INDEX billing_charges_due ON platform.billing_charges(status, due_on);

-- Pagamento registrado manualmente: referência do comprovante (identificador da transação ou recibo), responsável e
-- data. Estorno de registro errado mantém a linha (VOIDED) com motivo e responsável; nada é apagado.
CREATE TABLE platform.billing_payments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 charge_id uuid NOT NULL REFERENCES platform.billing_charges(id),
 amount_cents bigint NOT NULL CHECK (amount_cents BETWEEN 0 AND 100000000),
 paid_on date NOT NULL,
 method text NOT NULL CHECK (method IN ('PIX','TRANSFERENCIA','BOLETO','OUTRO')),
 reference text NOT NULL CHECK (length(btrim(reference)) BETWEEN 3 AND 200),
 status text NOT NULL CHECK (status IN ('RECORDED','VOIDED')),
 recorded_by uuid NOT NULL REFERENCES platform.accounts(id),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 voided_by uuid REFERENCES platform.accounts(id),
 voided_at timestamptz,
 void_reason text CHECK (void_reason IS NULL OR length(btrim(void_reason)) BETWEEN 1 AND 500),
 CHECK ((status = 'VOIDED') = (voided_by IS NOT NULL AND voided_at IS NOT NULL AND void_reason IS NOT NULL))
);
CREATE UNIQUE INDEX billing_payment_recorded ON platform.billing_payments(charge_id) WHERE status = 'RECORDED';

-- Consistência financeira conferida no commit, para qualquer papel: cobrança PAID tem exatamente um pagamento
-- registrado do mesmo valor; cobrança OPEN ou CANCELLED não tem pagamento registrado.
CREATE FUNCTION platform.billing_charge_consistency() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 target uuid;
 charge_status text;
 charge_amount bigint;
 recorded bigint;
 matching bigint;
BEGIN
 IF TG_TABLE_NAME = 'billing_charges' THEN target := NEW.id; ELSE target := NEW.charge_id; END IF;
 SELECT c.status, c.amount_cents INTO charge_status, charge_amount FROM platform.billing_charges c WHERE c.id = target;
 -- Sem visibilidade da cobrança (capacidade de leitura ausente) a escrita falha fechada.
 IF charge_status IS NULL THEN
  RAISE EXCEPTION 'Cobrança não visível para conferir a consistência.' USING ERRCODE = '42501';
 END IF;
 SELECT count(*), count(*) FILTER (WHERE p.amount_cents = charge_amount) INTO recorded, matching
  FROM platform.billing_payments p WHERE p.charge_id = target AND p.status = 'RECORDED';
 IF (charge_status = 'PAID' AND NOT (recorded = 1 AND matching = 1)) OR (charge_status <> 'PAID' AND recorded > 0) THEN
  RAISE EXCEPTION 'Cobrança e pagamento inconsistentes.' USING ERRCODE = '23514';
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION platform.billing_charge_consistency() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER billing_charge_consistency AFTER INSERT OR UPDATE ON platform.billing_charges
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.billing_charge_consistency();
CREATE CONSTRAINT TRIGGER billing_payment_consistency AFTER INSERT OR UPDATE ON platform.billing_payments
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.billing_charge_consistency();

-- Estados encerrados não voltam: assinatura ENDED, cobrança CANCELLED e pagamento VOIDED são finais; cobrança PAID
-- só volta a OPEN quando o pagamento é estornado (conferido pelo gatilho diferido).
CREATE FUNCTION platform.billing_transition_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.status IS DISTINCT FROM OLD.status AND (
     (TG_TABLE_NAME = 'billing_subscriptions' AND OLD.status = 'ENDED')
  OR (TG_TABLE_NAME = 'billing_charges' AND (OLD.status = 'CANCELLED' OR (OLD.status = 'PAID' AND NEW.status = 'CANCELLED')))
  OR (TG_TABLE_NAME = 'billing_payments' AND OLD.status = 'VOIDED')) THEN
  RAISE EXCEPTION 'Transição financeira não permitida.' USING ERRCODE = '23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.billing_transition_guard() FROM PUBLIC;
CREATE TRIGGER billing_subscription_transition BEFORE UPDATE OF status ON platform.billing_subscriptions
 FOR EACH ROW EXECUTE FUNCTION platform.billing_transition_guard();
CREATE TRIGGER billing_charge_transition BEFORE UPDATE OF status ON platform.billing_charges
 FOR EACH ROW EXECUTE FUNCTION platform.billing_transition_guard();
CREATE TRIGGER billing_payment_transition BEFORE UPDATE OF status ON platform.billing_payments
 FOR EACH ROW EXECUTE FUNCTION platform.billing_transition_guard();

ALTER TABLE platform.billing_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_plans FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_subscriptions FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_charges ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_charges FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_payments FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.billing_plans,platform.billing_subscriptions,platform.billing_charges,platform.billing_payments FROM PUBLIC;

CREATE POLICY billing_plans_read ON platform.billing_plans FOR SELECT TO cp_api
 USING(platform.has_platform_capability('billing.read'));
CREATE POLICY billing_plans_insert ON platform.billing_plans FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('billing.manage') AND created_by = platform.current_actor_id());
CREATE POLICY billing_plans_update ON platform.billing_plans FOR UPDATE TO cp_api
 USING(true) WITH CHECK(platform.has_platform_capability('billing.manage'));
GRANT SELECT ON platform.billing_plans TO cp_api;
GRANT INSERT(id,name,price_cents,period,created_by) ON platform.billing_plans TO cp_api;
GRANT UPDATE(name,price_cents,active,version,updated_at) ON platform.billing_plans TO cp_api;

CREATE POLICY billing_subscriptions_read ON platform.billing_subscriptions FOR SELECT TO cp_api
 USING(platform.has_platform_capability('billing.read'));
CREATE POLICY billing_subscriptions_insert ON platform.billing_subscriptions FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('billing.manage') AND created_by = platform.current_actor_id() AND status = 'ACTIVE');
CREATE POLICY billing_subscriptions_update ON platform.billing_subscriptions FOR UPDATE TO cp_api
 USING(true) WITH CHECK(platform.has_platform_capability('billing.manage'));
GRANT SELECT ON platform.billing_subscriptions TO cp_api;
GRANT INSERT(id,tenant_id,plan_id,price_cents,period,starts_on,due_day,status,created_by) ON platform.billing_subscriptions TO cp_api;
GRANT UPDATE(ends_on,status,version,ended_at) ON platform.billing_subscriptions TO cp_api;

CREATE POLICY billing_charges_read ON platform.billing_charges FOR SELECT TO cp_api
 USING(platform.has_platform_capability('billing.read'));
CREATE POLICY billing_charges_insert ON platform.billing_charges FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('billing.manage') AND created_by = platform.current_actor_id() AND status = 'OPEN');
CREATE POLICY billing_charges_update ON platform.billing_charges FOR UPDATE TO cp_api
 USING(true) WITH CHECK(platform.has_platform_capability('billing.manage'));
GRANT SELECT ON platform.billing_charges TO cp_api;
GRANT INSERT(id,subscription_id,tenant_id,period_start,period_end,amount_cents,due_on,status,created_by) ON platform.billing_charges TO cp_api;
GRANT UPDATE(status,cancel_reason,version) ON platform.billing_charges TO cp_api;

CREATE POLICY billing_payments_read ON platform.billing_payments FOR SELECT TO cp_api
 USING(platform.has_platform_capability('billing.read'));
CREATE POLICY billing_payments_insert ON platform.billing_payments FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('billing.manage') AND recorded_by = platform.current_actor_id() AND status = 'RECORDED');
CREATE POLICY billing_payments_update ON platform.billing_payments FOR UPDATE TO cp_api
 USING(true) WITH CHECK(platform.has_platform_capability('billing.manage') AND voided_by = platform.current_actor_id());
GRANT SELECT ON platform.billing_payments TO cp_api;
GRANT INSERT(id,charge_id,amount_cents,paid_on,method,reference,status,recorded_by) ON platform.billing_payments TO cp_api;
GRANT UPDATE(status,voided_by,voided_at,void_reason) ON platform.billing_payments TO cp_api;
