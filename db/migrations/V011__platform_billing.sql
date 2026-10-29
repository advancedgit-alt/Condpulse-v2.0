-- Financeiro da plataforma (fase 5): assinatura CondPulse de cada condomínio. Planos e valores são cadastrados pela
-- administração com motivo e auditoria; nenhum preço é presumido aqui. Não há gateway, cobrança automática nem emissão
-- fiscal: pagamento só é registrado manualmente, com referência do comprovante, responsável e auditoria. Atraso não
-- bloqueia acesso de moradores nem da gestão. Somente capacidades de plataforma publicadas após AAL2 leem ou alteram.
CREATE TABLE platform.billing_plans (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
 -- Valor maior que zero: isenção não existe neste recorte (cobrança de R$ 0 não teria como ser paga).
 price_cents bigint NOT NULL CHECK (price_cents BETWEEN 1 AND 100000000),
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
 price_cents bigint NOT NULL CHECK (price_cents BETWEEN 1 AND 100000000),
 period text NOT NULL CHECK (period IN ('MONTHLY','YEARLY')),
 starts_on date NOT NULL CHECK (starts_on BETWEEN DATE '2000-01-01' AND DATE '2100-12-28'),
 ends_on date CHECK (ends_on IS NULL OR ends_on <= DATE '2101-12-31'),
 due_day smallint NOT NULL CHECK (due_day BETWEEN 1 AND 28),
 status text NOT NULL CHECK (status IN ('ACTIVE','ENDED')),
 version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 ended_at timestamptz,
 UNIQUE (id, tenant_id),
 CHECK (ends_on IS NULL OR ends_on > starts_on),
 -- Início até o dia 28: os períodos mensais seguintes caem sempre no mesmo dia.
 CHECK (extract(day FROM starts_on) <= 28),
 CHECK ((status = 'ENDED') = (ended_at IS NOT NULL AND ends_on IS NOT NULL))
);
CREATE UNIQUE INDEX billing_subscription_active ON platform.billing_subscriptions(tenant_id) WHERE status = 'ACTIVE';

-- Cobrança de um período [period_start, period_end) da assinatura, do mesmo condomínio (chave composta). PAID só existe
-- com pagamento registrado do mesmo valor (gatilho diferido abaixo); cancelada não recebe pagamento. Períodos não
-- cancelados do mesmo condomínio não se sobrepõem (o mesmo período duas vezes inclusive), nem entre assinaturas
-- diferentes; período cancelado pode ser cobrado de novo.
CREATE TABLE platform.billing_charges (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 subscription_id uuid NOT NULL,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 period_start date NOT NULL CHECK (period_start BETWEEN DATE '2000-01-01' AND DATE '2100-12-31'),
 period_end date NOT NULL,
 amount_cents bigint NOT NULL CHECK (amount_cents BETWEEN 1 AND 100000000),
 due_on date NOT NULL,
 status text NOT NULL CHECK (status IN ('OPEN','PAID','CANCELLED')),
 cancel_reason text CHECK (cancel_reason IS NULL OR length(btrim(cancel_reason)) BETWEEN 1 AND 500),
 version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY (subscription_id, tenant_id) REFERENCES platform.billing_subscriptions(id, tenant_id),
 CHECK (period_end > period_start AND due_on >= period_start AND due_on < period_end),
 CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL)),
 CONSTRAINT billing_charges_no_overlap EXCLUDE USING gist (tenant_id WITH =, daterange(period_start, period_end) WITH &&)
  WHERE (status <> 'CANCELLED')
);
CREATE INDEX billing_charges_due ON platform.billing_charges(status, due_on);

-- Pagamento registrado manualmente: referência do comprovante (identificador da transação ou recibo), responsável e
-- data. Estorno de registro errado mantém a linha (VOIDED) com motivo e responsável; nada é apagado. Um comprovante
-- registrado quita uma única cobrança.
CREATE TABLE platform.billing_payments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 charge_id uuid NOT NULL REFERENCES platform.billing_charges(id),
 amount_cents bigint NOT NULL CHECK (amount_cents BETWEEN 1 AND 100000000),
 paid_on date NOT NULL CHECK (paid_on >= DATE '2000-01-01'),
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
CREATE UNIQUE INDEX billing_payment_reference ON platform.billing_payments(method, lower(btrim(reference))) WHERE status = 'RECORDED';

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

-- Regras que não dependem só do Java, conferidas antes da gravação (executa como quem grava; registro de referência
-- invisível falha fechado): assinatura copia o plano ativo e não começa antes do fim de outra do mesmo condomínio;
-- cobrança alinhada ao início da assinatura, com o valor contratado, o vencimento da regra e dentro do encerramento;
-- pagamento com data até hoje no fuso de operação.
CREATE FUNCTION platform.billing_insert_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
 plan_row record;
 sub record;
 latest date;
 aligned boolean;
 expected_end date;
 expected_due date;
 next_month date;
BEGIN
 IF TG_TABLE_NAME = 'billing_subscriptions' THEN
  SELECT p.price_cents, p.period, p.active INTO plan_row FROM platform.billing_plans p WHERE p.id = NEW.plan_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'Plano não visível.' USING ERRCODE = '42501'; END IF;
  IF NOT plan_row.active OR plan_row.price_cents <> NEW.price_cents OR plan_row.period <> NEW.period
     OR NEW.ends_on IS NOT NULL OR NEW.ended_at IS NOT NULL THEN
   RAISE EXCEPTION 'Assinatura diverge do plano vigente.' USING ERRCODE = '23514';
  END IF;
  SELECT max(s.ends_on) INTO latest FROM platform.billing_subscriptions s WHERE s.tenant_id = NEW.tenant_id;
  IF latest IS NOT NULL AND NEW.starts_on < latest THEN
   RAISE EXCEPTION 'Assinatura sobrepõe contrato anterior do condomínio.' USING ERRCODE = '23P01';
  END IF;
 ELSIF TG_TABLE_NAME = 'billing_charges' THEN
  SELECT s.tenant_id, s.price_cents, s.period, s.starts_on, s.ends_on, s.due_day INTO sub
   FROM platform.billing_subscriptions s WHERE s.id = NEW.subscription_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'Assinatura não visível.' USING ERRCODE = '42501'; END IF;
  IF sub.period = 'MONTHLY' THEN
   expected_end := (NEW.period_start + interval '1 month')::date;
   aligned := extract(day FROM NEW.period_start) = extract(day FROM sub.starts_on);
  ELSE
   expected_end := (NEW.period_start + interval '1 year')::date;
   aligned := extract(month FROM NEW.period_start) = extract(month FROM sub.starts_on)
    AND extract(day FROM NEW.period_start) = extract(day FROM sub.starts_on);
  END IF;
  -- Mesma regra do Java: dia de vencimento no próprio mês se ainda não passou do início; senão, no mês seguinte.
  next_month := (NEW.period_start + interval '1 month')::date;
  expected_due := CASE WHEN sub.due_day >= extract(day FROM NEW.period_start)
   THEN make_date(extract(year FROM NEW.period_start)::int, extract(month FROM NEW.period_start)::int, sub.due_day)
   ELSE make_date(extract(year FROM next_month)::int, extract(month FROM next_month)::int, sub.due_day) END;
  IF NOT aligned OR NEW.period_start < sub.starts_on OR NEW.period_end <> expected_end OR NEW.amount_cents <> sub.price_cents
     OR NEW.due_on <> expected_due OR (sub.ends_on IS NOT NULL AND NEW.period_end > sub.ends_on) OR NEW.tenant_id <> sub.tenant_id THEN
   RAISE EXCEPTION 'Cobrança diverge da assinatura.' USING ERRCODE = '23514';
  END IF;
 ELSE
  IF NEW.paid_on > (clock_timestamp() AT TIME ZONE 'America/Sao_Paulo')::date
     OR NEW.voided_by IS NOT NULL OR NEW.voided_at IS NOT NULL OR NEW.void_reason IS NOT NULL THEN
   RAISE EXCEPTION 'Pagamento com data futura ou estorno na inclusão.' USING ERRCODE = '23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.billing_insert_guard() FROM PUBLIC;
CREATE TRIGGER billing_subscription_insert BEFORE INSERT ON platform.billing_subscriptions
 FOR EACH ROW EXECUTE FUNCTION platform.billing_insert_guard();
CREATE TRIGGER billing_charge_insert BEFORE INSERT ON platform.billing_charges
 FOR EACH ROW EXECUTE FUNCTION platform.billing_insert_guard();
CREATE TRIGGER billing_payment_insert BEFORE INSERT ON platform.billing_payments
 FOR EACH ROW EXECUTE FUNCTION platform.billing_insert_guard();

-- Toda alteração é uma transição permitida com a versão avançando uma unidade e horário do banco: plano por versão;
-- assinatura ACTIVE -> ENDED; cobrança OPEN -> PAID/CANCELLED e PAID -> OPEN (estorno, conferido pelo gatilho
-- diferido); pagamento RECORDED -> VOIDED. Estados finais (ENDED, CANCELLED, VOIDED) não mudam mais.
CREATE FUNCTION platform.billing_update_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_TABLE_NAME = 'billing_plans' THEN
  IF NEW.version <> OLD.version + 1 THEN
   RAISE EXCEPTION 'Versão do plano deve avançar uma unidade.' USING ERRCODE = '23514';
  END IF;
  NEW.updated_at := clock_timestamp();
 ELSIF TG_TABLE_NAME = 'billing_subscriptions' THEN
  IF OLD.status <> 'ACTIVE' OR NEW.status <> 'ENDED' OR NEW.version <> OLD.version + 1 THEN
   RAISE EXCEPTION 'Transição de assinatura não permitida.' USING ERRCODE = '23514';
  END IF;
  IF EXISTS (SELECT 1 FROM platform.billing_charges c WHERE c.subscription_id = NEW.id AND c.status <> 'CANCELLED'
     AND c.period_end > NEW.ends_on) THEN
   RAISE EXCEPTION 'Há cobrança depois do encerramento.' USING ERRCODE = '23514';
  END IF;
  NEW.ended_at := clock_timestamp();
 ELSIF TG_TABLE_NAME = 'billing_charges' THEN
  IF NEW.version <> OLD.version + 1 OR NOT ((OLD.status = 'OPEN' AND NEW.status IN ('PAID','CANCELLED'))
     OR (OLD.status = 'PAID' AND NEW.status = 'OPEN')) THEN
   RAISE EXCEPTION 'Transição de cobrança não permitida.' USING ERRCODE = '23514';
  END IF;
 ELSE
  IF OLD.status <> 'RECORDED' OR NEW.status <> 'VOIDED' THEN
   RAISE EXCEPTION 'Transição de pagamento não permitida.' USING ERRCODE = '23514';
  END IF;
  NEW.voided_at := clock_timestamp();
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION platform.billing_update_guard() FROM PUBLIC;
CREATE TRIGGER billing_plan_update BEFORE UPDATE ON platform.billing_plans
 FOR EACH ROW EXECUTE FUNCTION platform.billing_update_guard();
CREATE TRIGGER billing_subscription_update BEFORE UPDATE ON platform.billing_subscriptions
 FOR EACH ROW EXECUTE FUNCTION platform.billing_update_guard();
CREATE TRIGGER billing_charge_update BEFORE UPDATE ON platform.billing_charges
 FOR EACH ROW EXECUTE FUNCTION platform.billing_update_guard();
CREATE TRIGGER billing_payment_update BEFORE UPDATE ON platform.billing_payments
 FOR EACH ROW EXECUTE FUNCTION platform.billing_update_guard();

ALTER TABLE platform.billing_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_plans FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_subscriptions FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_charges ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_charges FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_payments FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.billing_plans,platform.billing_subscriptions,platform.billing_charges,platform.billing_payments FROM PUBLIC;
-- UPDATE só alcança linhas com billing.read (UPDATE sem WHERE não varre a tabela) e grava só com billing.manage.

CREATE POLICY billing_plans_read ON platform.billing_plans FOR SELECT TO cp_api
 USING(platform.has_platform_capability('billing.read'));
CREATE POLICY billing_plans_insert ON platform.billing_plans FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('billing.manage') AND created_by = platform.current_actor_id());
CREATE POLICY billing_plans_update ON platform.billing_plans FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('billing.read')) WITH CHECK(platform.has_platform_capability('billing.manage'));
GRANT SELECT ON platform.billing_plans TO cp_api;
GRANT INSERT(id,name,price_cents,period,created_by) ON platform.billing_plans TO cp_api;
GRANT UPDATE(name,price_cents,active,version,updated_at) ON platform.billing_plans TO cp_api;

CREATE POLICY billing_subscriptions_read ON platform.billing_subscriptions FOR SELECT TO cp_api
 USING(platform.has_platform_capability('billing.read'));
CREATE POLICY billing_subscriptions_insert ON platform.billing_subscriptions FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('billing.manage') AND created_by = platform.current_actor_id() AND status = 'ACTIVE');
CREATE POLICY billing_subscriptions_update ON platform.billing_subscriptions FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('billing.read')) WITH CHECK(platform.has_platform_capability('billing.manage'));
GRANT SELECT ON platform.billing_subscriptions TO cp_api;
GRANT INSERT(id,tenant_id,plan_id,price_cents,period,starts_on,due_day,status,created_by) ON platform.billing_subscriptions TO cp_api;
GRANT UPDATE(ends_on,status,version,ended_at) ON platform.billing_subscriptions TO cp_api;

CREATE POLICY billing_charges_read ON platform.billing_charges FOR SELECT TO cp_api
 USING(platform.has_platform_capability('billing.read'));
CREATE POLICY billing_charges_insert ON platform.billing_charges FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('billing.manage') AND created_by = platform.current_actor_id() AND status = 'OPEN');
CREATE POLICY billing_charges_update ON platform.billing_charges FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('billing.read')) WITH CHECK(platform.has_platform_capability('billing.manage'));
GRANT SELECT ON platform.billing_charges TO cp_api;
GRANT INSERT(id,subscription_id,tenant_id,period_start,period_end,amount_cents,due_on,status,created_by) ON platform.billing_charges TO cp_api;
GRANT UPDATE(status,cancel_reason,version) ON platform.billing_charges TO cp_api;

CREATE POLICY billing_payments_read ON platform.billing_payments FOR SELECT TO cp_api
 USING(platform.has_platform_capability('billing.read'));
CREATE POLICY billing_payments_insert ON platform.billing_payments FOR INSERT TO cp_api
 WITH CHECK(platform.has_platform_capability('billing.manage') AND recorded_by = platform.current_actor_id() AND status = 'RECORDED');
CREATE POLICY billing_payments_update ON platform.billing_payments FOR UPDATE TO cp_api
 USING(platform.has_platform_capability('billing.read')) WITH CHECK(platform.has_platform_capability('billing.manage') AND voided_by = platform.current_actor_id());
GRANT SELECT ON platform.billing_payments TO cp_api;
GRANT INSERT(id,charge_id,amount_cents,paid_on,method,reference,status,recorded_by) ON platform.billing_payments TO cp_api;
GRANT UPDATE(status,voided_by,voided_at,void_reason) ON platform.billing_payments TO cp_api;

-- Trilha da plataforma por capacidade e prefixo da ação: o financeiro só registra BILLING_* e o catálogo só TENANT_*.
DROP POLICY platform_audit_insert ON platform.platform_audit_log;
CREATE POLICY platform_audit_insert ON platform.platform_audit_log FOR INSERT TO cp_api
 WITH CHECK(actor_id = platform.current_actor_id() AND (
  (platform.has_platform_capability('tenants.manage') AND action LIKE 'TENANT\_%')
  OR (platform.has_platform_capability('billing.manage') AND action LIKE 'BILLING\_%')));
