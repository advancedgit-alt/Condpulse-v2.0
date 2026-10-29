-- CondPulse v1.0 | 27/10/2026
-- REFERÊNCIA INICIAL. Revisar e testar em PostgreSQL descartável antes de usar Flyway.
-- Não foi aplicada a um projeto Supabase ou a produção nesta entrega.
-- Bootstrap de roles fica em 00_roles_example.sql; funções auth/storage do provedor não são alteradas.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;
CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS cp;
SET search_path = cp, platform, public, extensions;
CREATE TABLE platform.accounts (
  id uuid PRIMARY KEY, -- mesmo subject do provedor Auth; reconciliado pelo serviço
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED','ERASURE_PENDING')),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform.tenants (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  timezone text NOT NULL DEFAULT 'America/Sao_Paulo',
  status text NOT NULL DEFAULT 'ONBOARDING' CHECK(status IN ('ONBOARDING','ACTIVE','ENDING','ARCHIVED')),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform.web_sessions (
  id_hash text PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES platform.accounts(id),
  provider_tokens_ciphertext bytea NOT NULL,
  encryption_key_version text NOT NULL,
  csrf_token_hash text NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Blocos do condomínio
CREATE TABLE cp.blocks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, name)
);
COMMENT ON TABLE cp.blocks IS 'Blocos do condomínio';

-- Unidades residenciais/comerciais
CREATE TABLE cp.units (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  block_id uuid,
  label text NOT NULL,
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, block_id) REFERENCES cp.blocks(tenant_id, id),
  UNIQUE NULLS NOT DISTINCT (tenant_id, block_id, label)
);
COMMENT ON TABLE cp.units IS 'Unidades residenciais/comerciais';

-- Vínculos e permissões por unidade ou função
CREATE TABLE cp.memberships (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  account_id uuid NOT NULL REFERENCES platform.accounts(id),
  unit_id uuid,
  display_name text NOT NULL,
  role text NOT NULL CHECK (role IN ('RESIDENT','HOUSEHOLD','OWNER_NONRESIDENT','CONCIERGE','MANAGER')),
  permissions jsonb NOT NULL DEFAULT '{}'::jsonb,
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','REVOKED')),
  valid_from timestamptz NOT NULL DEFAULT now(),
  valid_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, unit_id) REFERENCES cp.units(tenant_id, id),
  CHECK (valid_until IS NULL OR valid_until > valid_from),
  CHECK ((role IN ('RESIDENT','HOUSEHOLD','OWNER_NONRESIDENT') AND unit_id IS NOT NULL) OR (role IN ('CONCIERGE','MANAGER') AND unit_id IS NULL))
);
CREATE UNIQUE INDEX memberships_active_unique ON cp.memberships (tenant_id, account_id, COALESCE(unit_id,'00000000-0000-0000-0000-000000000000'::uuid), role) WHERE status = 'ACTIVE';
CREATE INDEX memberships_actor ON cp.memberships(account_id,status);
COMMENT ON TABLE cp.memberships IS 'Vínculos e permissões por unidade ou função';

-- Plano e recursos contratados
CREATE TABLE cp.subscriptions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  plan text NOT NULL CHECK (plan IN ('ESSENTIAL','COMPLETE')),
  state text NOT NULL DEFAULT 'TRIAL' CHECK (state IN ('TRIAL','ACTIVE','PAST_DUE','ENDING','ENDED')),
  features jsonb NOT NULL DEFAULT '{}'::jsonb,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id)
);
COMMENT ON TABLE cp.subscriptions IS 'Plano e recursos contratados';

-- Áreas reserváveis e regras
CREATE TABLE cp.amenities (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  name text NOT NULL,
  capacity integer NOT NULL CHECK (capacity > 0),
  approval_required boolean NOT NULL DEFAULT true,
  fee_cents bigint NOT NULL DEFAULT 0 CHECK (fee_cents >= 0),
  buffer_minutes integer NOT NULL DEFAULT 0 CHECK (buffer_minutes BETWEEN 0 AND 1440),
  policy_version integer NOT NULL DEFAULT 1 CHECK (policy_version > 0),
  policy jsonb NOT NULL DEFAULT '{}'::jsonb,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id,name)
);
COMMENT ON TABLE cp.amenities IS 'Áreas reserváveis e regras';

-- Reservas e bloqueios no mesmo mecanismo anti-conflito
CREATE TABLE cp.reservations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  amenity_id uuid NOT NULL,
  unit_id uuid,
  created_by uuid NOT NULL REFERENCES platform.accounts(id),
  kind text NOT NULL DEFAULT 'BOOKING' CHECK (kind IN ('BOOKING','BLOCK')),
  status text NOT NULL CHECK (status IN ('HELD','PENDING_APPROVAL','CONFIRMED','REJECTED','CANCELLED','EXPIRED')),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  occupied_from timestamptz NOT NULL,
  occupied_until timestamptz NOT NULL,
  expires_at timestamptz,
  rule_snapshot jsonb NOT NULL,
  fee_cents bigint NOT NULL DEFAULT 0 CHECK (fee_cents >= 0),
  decision_reason text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, id, unit_id),
  FOREIGN KEY (tenant_id, amenity_id) REFERENCES cp.amenities(tenant_id, id),
  FOREIGN KEY (tenant_id, unit_id) REFERENCES cp.units(tenant_id, id),
  CHECK (starts_at < ends_at AND occupied_from <= starts_at AND occupied_until >= ends_at),
  CHECK (kind <> 'BOOKING' OR unit_id IS NOT NULL),
  CHECK (status NOT IN ('HELD','PENDING_APPROVAL') OR expires_at IS NOT NULL),
  EXCLUDE USING gist (tenant_id WITH =, amenity_id WITH =, tstzrange(occupied_from, occupied_until, '[)') WITH &&) WHERE (status IN ('HELD','PENDING_APPROVAL','CONFIRMED'))
);
CREATE INDEX reservations_unit_period ON cp.reservations(tenant_id,unit_id,starts_at);
COMMENT ON TABLE cp.reservations IS 'Reservas e bloqueios no mesmo mecanismo anti-conflito';

-- Fila de espera e oferta temporária
CREATE TABLE cp.reservation_waitlist (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  amenity_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  account_id uuid NOT NULL REFERENCES platform.accounts(id),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  state text NOT NULL DEFAULT 'WAITING' CHECK (state IN ('WAITING','OFFERED','ACCEPTED','EXPIRED','CANCELLED')),
  offer_reservation_id uuid,
  offer_expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, amenity_id) REFERENCES cp.amenities(tenant_id, id),
  FOREIGN KEY (tenant_id, unit_id) REFERENCES cp.units(tenant_id, id),
  FOREIGN KEY (tenant_id, offer_reservation_id) REFERENCES cp.reservations(tenant_id, id),
  CHECK (starts_at < ends_at)
);
COMMENT ON TABLE cp.reservation_waitlist IS 'Fila de espera e oferta temporária';

-- Encaminhamento de taxa, sem presumir pagamento
CREATE TABLE cp.reservation_fee_entries (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  reservation_id uuid NOT NULL,
  amount_cents bigint NOT NULL CHECK (amount_cents >= 0),
  state text NOT NULL DEFAULT 'PENDING_EXPORT' CHECK (state IN ('PENDING_EXPORT','EXPORTED','CONFIRMED_BY_ADMIN','CANCELLED')),
  external_reference text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, reservation_id) REFERENCES cp.reservations(tenant_id, id),
  UNIQUE (tenant_id,reservation_id)
);
COMMENT ON TABLE cp.reservation_fee_entries IS 'Encaminhamento de taxa, sem presumir pagamento';

-- Identidade mínima de visitante por tenant
CREATE TABLE cp.visitors (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  full_name text NOT NULL,
  document_last4 text,
  document_fingerprint text,
  contact_ciphertext bytea,
  retention_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id)
);
COMMENT ON TABLE cp.visitors IS 'Identidade mínima de visitante por tenant';

-- Autorizações temporais e recorrentes
CREATE TABLE cp.visit_permits (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  visitor_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  reservation_id uuid,
  created_by uuid NOT NULL REFERENCES platform.accounts(id),
  state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','REVOKED','EXPIRED')),
  valid_from timestamptz NOT NULL,
  valid_until timestamptz NOT NULL,
  schedule jsonb,
  public_token_hash text,
  single_use boolean NOT NULL DEFAULT false,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, visitor_id) REFERENCES cp.visitors(tenant_id, id),
  FOREIGN KEY (tenant_id, unit_id) REFERENCES cp.units(tenant_id, id),
  FOREIGN KEY (tenant_id, reservation_id, unit_id) REFERENCES cp.reservations(tenant_id, id, unit_id),
  CHECK (valid_from < valid_until),
  UNIQUE (public_token_hash)
);
COMMENT ON TABLE cp.visit_permits IS 'Autorizações temporais e recorrentes';

-- Janelas materializadas de uma recorrência
CREATE TABLE cp.permit_windows (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  permit_id uuid NOT NULL,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','USED','REVOKED','EXPIRED')),
  used_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, permit_id) REFERENCES cp.visit_permits(tenant_id, id),
  CHECK (starts_at < ends_at),
  UNIQUE(tenant_id,permit_id,starts_at)
);
COMMENT ON TABLE cp.permit_windows IS 'Janelas materializadas de uma recorrência';

-- Pedido de decisão por chegada específica
CREATE TABLE cp.arrival_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  visitor_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  permit_id uuid,
  created_by uuid NOT NULL REFERENCES platform.accounts(id),
  state text NOT NULL DEFAULT 'WAITING' CHECK (state IN ('WAITING','APPROVED','DENIED','EXPIRED','CANCELLED')),
  expires_at timestamptz NOT NULL,
  decided_by uuid REFERENCES platform.accounts(id),
  decided_at timestamptz,
  decision_source text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, visitor_id) REFERENCES cp.visitors(tenant_id, id),
  FOREIGN KEY (tenant_id, unit_id) REFERENCES cp.units(tenant_id, id),
  FOREIGN KEY (tenant_id, permit_id) REFERENCES cp.visit_permits(tenant_id, id),
  CHECK (state NOT IN ('APPROVED','DENIED') OR (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
COMMENT ON TABLE cp.arrival_requests IS 'Pedido de decisão por chegada específica';

-- Recebimento e guarda de encomendas
CREATE TABLE cp.parcels (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  unit_id uuid NOT NULL,
  received_by uuid NOT NULL REFERENCES platform.accounts(id),
  description text NOT NULL,
  quantity integer NOT NULL DEFAULT 1 CHECK (quantity > 0),
  storage_location text NOT NULL,
  state text NOT NULL DEFAULT 'RECEIVED' CHECK (state IN ('RECEIVED','HANDED_OVER','RETURNED')),
  received_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, unit_id) REFERENCES cp.units(tenant_id, id)
);
COMMENT ON TABLE cp.parcels IS 'Recebimento e guarda de encomendas';

-- Pessoa autorizada a retirar uma encomenda
CREATE TABLE cp.parcel_delegations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  parcel_id uuid NOT NULL,
  created_by uuid NOT NULL REFERENCES platform.accounts(id),
  delegate_name text NOT NULL,
  code_hmac text NOT NULL,
  expires_at timestamptz NOT NULL,
  state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','USED','REVOKED','EXPIRED')),
  failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, id, parcel_id),
  FOREIGN KEY (tenant_id, parcel_id) REFERENCES cp.parcels(tenant_id, id)
);
COMMENT ON TABLE cp.parcel_delegations IS 'Pessoa autorizada a retirar uma encomenda';

-- Evidência única de retirada
CREATE TABLE cp.parcel_handoffs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  parcel_id uuid NOT NULL,
  delegation_id uuid,
  operator_id uuid NOT NULL REFERENCES platform.accounts(id),
  recipient_name text NOT NULL,
  handed_at timestamptz NOT NULL DEFAULT now(),
  evidence_type text NOT NULL CHECK (evidence_type IN ('IN_PERSON','DELEGATION_CODE','ASSISTED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, parcel_id) REFERENCES cp.parcels(tenant_id, id),
  FOREIGN KEY (tenant_id, delegation_id, parcel_id) REFERENCES cp.parcel_delegations(tenant_id, id, parcel_id),
  UNIQUE(tenant_id,parcel_id)
);
COMMENT ON TABLE cp.parcel_handoffs IS 'Evidência única de retirada';

-- Avisos publicados e segmentação
CREATE TABLE cp.announcements (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  title text NOT NULL,
  body text NOT NULL,
  audience jsonb NOT NULL,
  state text NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','PUBLISHED','ARCHIVED')),
  published_at timestamptz,
  author_id uuid NOT NULL REFERENCES platform.accounts(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id)
);
COMMENT ON TABLE cp.announcements IS 'Avisos publicados e segmentação';

-- Leitura autenticada de aviso
CREATE TABLE cp.announcement_reads (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  announcement_id uuid NOT NULL,
  account_id uuid NOT NULL REFERENCES platform.accounts(id),
  read_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, announcement_id) REFERENCES cp.announcements(tenant_id, id),
  UNIQUE(tenant_id,announcement_id,account_id)
);
COMMENT ON TABLE cp.announcement_reads IS 'Leitura autenticada de aviso';

-- Pedidos acompanháveis do morador
CREATE TABLE cp.service_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  unit_id uuid NOT NULL,
  requester_id uuid NOT NULL REFERENCES platform.accounts(id),
  category text NOT NULL,
  title text NOT NULL,
  description text NOT NULL,
  state text NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','TRIAGED','IN_PROGRESS','WAITING_RESIDENT','RESOLVED','CLOSED')),
  assigned_to uuid REFERENCES platform.accounts(id),
  desired_window tstzrange,
  external_ref text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, unit_id) REFERENCES cp.units(tenant_id, id)
);
COMMENT ON TABLE cp.service_requests IS 'Pedidos acompanháveis do morador';

-- Mensagens do atendimento
CREATE TABLE cp.request_messages (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  request_id uuid NOT NULL,
  author_id uuid NOT NULL REFERENCES platform.accounts(id),
  body text NOT NULL,
  visibility text NOT NULL DEFAULT 'RESIDENT' CHECK (visibility IN ('RESIDENT','INTERNAL')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, request_id) REFERENCES cp.service_requests(tenant_id, id)
);
COMMENT ON TABLE cp.request_messages IS 'Mensagens do atendimento';

-- Metadados de arquivo privado
CREATE TABLE cp.attachments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  owner_id uuid NOT NULL REFERENCES platform.accounts(id),
  unit_id uuid,
  purpose text NOT NULL,
  entity_type text NOT NULL,
  entity_id uuid,
  bucket text NOT NULL,
  object_key text NOT NULL,
  sha256 text,
  mime_type text NOT NULL,
  size_bytes bigint NOT NULL CHECK(size_bytes > 0),
  state text NOT NULL DEFAULT 'QUARANTINED' CHECK (state IN ('QUARANTINED','CLEAN','REJECTED','DELETED')),
  retention_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, unit_id) REFERENCES cp.units(tenant_id, id),
  UNIQUE(tenant_id,bucket,object_key)
);
COMMENT ON TABLE cp.attachments IS 'Metadados de arquivo privado';

-- Conexão por condomínio com parceiro
CREATE TABLE cp.connectors (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  provider_code text NOT NULL,
  mode text NOT NULL CHECK(mode IN ('SANDBOX','LIVE')),
  secret_ref text NOT NULL,
  configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
  state text NOT NULL DEFAULT 'DISABLED' CHECK(state IN ('DISABLED','ENABLING','ACTIVE','DEGRADED','DISABLING')),
  last_confirmed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id)
);
COMMENT ON TABLE cp.connectors IS 'Conexão por condomínio com parceiro';

-- Equipamentos homologados e sua condição
CREATE TABLE cp.access_devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  connector_id uuid NOT NULL,
  external_id text NOT NULL,
  label text NOT NULL,
  model text NOT NULL,
  firmware text NOT NULL,
  direction text NOT NULL CHECK(direction IN ('IN','OUT','BOTH','UNKNOWN')),
  capabilities jsonb NOT NULL,
  last_seen_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, connector_id) REFERENCES cp.connectors(tenant_id, id),
  UNIQUE(tenant_id,connector_id,external_id)
);
COMMENT ON TABLE cp.access_devices IS 'Equipamentos homologados e sua condição';

-- Prova de finalidade/base legal/aviso
CREATE TABLE cp.legal_records (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  account_id uuid REFERENCES platform.accounts(id),
  visitor_id uuid,
  purpose text NOT NULL,
  legal_basis text NOT NULL,
  notice_version text NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  evidence_ref text,
  withdrawn_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, visitor_id) REFERENCES cp.visitors(tenant_id, id),
  CHECK ((account_id IS NOT NULL)::int + (visitor_id IS NOT NULL)::int = 1)
);
COMMENT ON TABLE cp.legal_records IS 'Prova de finalidade/base legal/aviso';

-- Estado desejado de credencial, sem template facial
CREATE TABLE cp.access_credentials (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  connector_id uuid NOT NULL,
  account_id uuid REFERENCES platform.accounts(id),
  visitor_id uuid,
  permit_id uuid,
  legal_record_id uuid NOT NULL,
  provider_person_ref text,
  kind text NOT NULL CHECK(kind IN ('FACE','QR','CARD')),
  desired_state text NOT NULL CHECK(desired_state IN ('ACTIVE','REVOKED')),
  desired_version bigint NOT NULL DEFAULT 1 CHECK(desired_version > 0),
  valid_until timestamptz,
  status text NOT NULL DEFAULT 'PENDING_REVIEW' CHECK(status IN ('PENDING_REVIEW','SYNC_PENDING','PARTIAL','ACTIVE','FAILED','REVOKING','REVOKED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, connector_id) REFERENCES cp.connectors(tenant_id, id),
  FOREIGN KEY (tenant_id, visitor_id) REFERENCES cp.visitors(tenant_id, id),
  FOREIGN KEY (tenant_id, permit_id) REFERENCES cp.visit_permits(tenant_id, id),
  FOREIGN KEY (tenant_id, legal_record_id) REFERENCES cp.legal_records(tenant_id, id),
  CHECK ((account_id IS NOT NULL)::int + (visitor_id IS NOT NULL)::int = 1)
);
COMMENT ON TABLE cp.access_credentials IS 'Estado desejado de credencial, sem template facial';

-- Confirmação por credencial e equipamento
CREATE TABLE cp.credential_syncs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  credential_id uuid NOT NULL,
  device_id uuid NOT NULL,
  desired_version bigint NOT NULL,
  observed_version bigint,
  state text NOT NULL CHECK(state IN ('PENDING','SENT','CONFIRMED','FAILED','UNKNOWN','REVOKED')),
  confirmed_at timestamptz,
  provider_reference text,
  last_error_code text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, credential_id) REFERENCES cp.access_credentials(tenant_id, id),
  FOREIGN KEY (tenant_id, device_id) REFERENCES cp.access_devices(tenant_id, id),
  UNIQUE(tenant_id,credential_id,device_id),
  CHECK(observed_version IS NULL OR observed_version <= desired_version)
);
COMMENT ON TABLE cp.credential_syncs IS 'Confirmação por credencial e equipamento';

-- Comandos externos com expiração e incerteza
CREATE TABLE cp.integration_commands (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  connector_id uuid NOT NULL,
  credential_id uuid,
  device_id uuid,
  kind text NOT NULL CHECK(kind IN ('UPSERT_CREDENTIAL','REVOKE_CREDENTIAL','RECONCILE','OPEN_DOOR')),
  idempotency_key text NOT NULL,
  desired_version bigint,
  expires_at timestamptz NOT NULL,
  state text NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','SENT','CONFIRMED','FAILED','UNKNOWN','EXPIRED')),
  attempts integer NOT NULL DEFAULT 0,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, connector_id) REFERENCES cp.connectors(tenant_id, id),
  FOREIGN KEY (tenant_id, credential_id) REFERENCES cp.access_credentials(tenant_id, id),
  FOREIGN KEY (tenant_id, device_id) REFERENCES cp.access_devices(tenant_id, id),
  UNIQUE(tenant_id,connector_id,idempotency_key)
);
COMMENT ON TABLE cp.integration_commands IS 'Comandos externos com expiração e incerteza';

-- Entrada autenticada e deduplicada
CREATE TABLE cp.webhook_inbox (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  connector_id uuid NOT NULL,
  external_event_id text NOT NULL,
  payload_hash text NOT NULL,
  payload_minimized jsonb NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','PROCESSED','FAILED')),
  processed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, connector_id) REFERENCES cp.connectors(tenant_id, id),
  UNIQUE(tenant_id,connector_id,external_event_id)
);
COMMENT ON TABLE cp.webhook_inbox IS 'Entrada autenticada e deduplicada';

-- Histórico factual, inclusive operação manual
CREATE TABLE cp.access_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  unit_id uuid,
  account_id uuid REFERENCES platform.accounts(id),
  visitor_id uuid,
  permit_id uuid,
  device_id uuid,
  connector_id uuid,
  external_event_id text,
  event_type text NOT NULL CHECK(event_type IN ('AUTHORIZED','ACTUATED','PASSAGE_IN','PASSAGE_OUT','MANUAL_IN','MANUAL_OUT','DENIED','UNKNOWN','CORRECTION')),
  source text NOT NULL CHECK(source IN ('PROVIDER','CONCIERGE','SYSTEM')),
  occurred_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  recorded_by uuid REFERENCES platform.accounts(id),
  evidence jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, unit_id) REFERENCES cp.units(tenant_id, id),
  FOREIGN KEY (tenant_id, visitor_id) REFERENCES cp.visitors(tenant_id, id),
  FOREIGN KEY (tenant_id, permit_id) REFERENCES cp.visit_permits(tenant_id, id),
  FOREIGN KEY (tenant_id, device_id) REFERENCES cp.access_devices(tenant_id, id),
  FOREIGN KEY (tenant_id, connector_id) REFERENCES cp.connectors(tenant_id, id),
  CHECK(source <> 'CONCIERGE' OR recorded_by IS NOT NULL)
);
CREATE UNIQUE INDEX access_event_external_unique ON cp.access_events(tenant_id,connector_id,external_event_id) WHERE external_event_id IS NOT NULL;
CREATE INDEX access_event_unit_time ON cp.access_events(tenant_id,unit_id,occurred_at DESC);
COMMENT ON TABLE cp.access_events IS 'Histórico factual, inclusive operação manual';

-- Tarefas duráveis criadas no commit de negócio
CREATE TABLE cp.outbox_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  aggregate_type text NOT NULL,
  aggregate_id uuid NOT NULL,
  event_type text NOT NULL,
  event_version integer NOT NULL DEFAULT 1,
  payload jsonb NOT NULL,
  dedupe_key text NOT NULL,
  state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','DONE','DEAD')),
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  attempts integer NOT NULL DEFAULT 0,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  UNIQUE(tenant_id,dedupe_key)
);
CREATE INDEX outbox_poll ON cp.outbox_events(tenant_id,state,available_at);
COMMENT ON TABLE cp.outbox_events IS 'Tarefas duráveis criadas no commit de negócio';

-- Replay controlado de requisição crítica
CREATE TABLE cp.idempotency_keys (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  actor_id uuid NOT NULL REFERENCES platform.accounts(id),
  method text NOT NULL,
  route text NOT NULL,
  key text NOT NULL,
  request_hash text NOT NULL,
  response_status integer,
  result_reference jsonb,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  UNIQUE(tenant_id,actor_id,method,route,key)
);
COMMENT ON TABLE cp.idempotency_keys IS 'Replay controlado de requisição crítica';

-- Dispositivos do usuário e consentimento de notificações
CREATE TABLE cp.push_installations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  account_id uuid NOT NULL REFERENCES platform.accounts(id),
  installation_id uuid NOT NULL,
  platform text NOT NULL CHECK(platform IN ('IOS','ANDROID','WEB')),
  token_ciphertext bytea NOT NULL,
  token_hash text NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  UNIQUE(tenant_id,account_id,installation_id)
);
COMMENT ON TABLE cp.push_installations IS 'Dispositivos do usuário e consentimento de notificações';

-- Envio e evidência por destinatário e canal
CREATE TABLE cp.notifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  account_id uuid NOT NULL REFERENCES platform.accounts(id),
  event_key text NOT NULL,
  channel text NOT NULL CHECK(channel IN ('IN_APP','PUSH','EMAIL')),
  state text NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','SENT_TO_PROVIDER','PROVIDER_ACCEPTED','FAILED','READ_IN_APP')),
  provider_receipt text,
  read_at timestamptz,
  payload_minimized jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  UNIQUE(tenant_id,account_id,event_key,channel)
);
COMMENT ON TABLE cp.notifications IS 'Envio e evidência por destinatário e canal';

-- Eventos mínimos para SSE e ressincronização
CREATE TABLE cp.ui_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  seq bigint GENERATED ALWAYS AS IDENTITY,
  account_id uuid NOT NULL REFERENCES platform.accounts(id),
  event_type text NOT NULL,
  resource_id uuid,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE(seq)
);
CREATE INDEX ui_feed ON cp.ui_events(tenant_id,account_id,seq);
COMMENT ON TABLE cp.ui_events IS 'Eventos mínimos para SSE e ressincronização';

-- Auditoria mínima append-only para a aplicação
CREATE TABLE cp.audit_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  actor_id uuid REFERENCES platform.accounts(id),
  action text NOT NULL,
  resource_type text NOT NULL,
  resource_id uuid,
  trace_id text NOT NULL,
  context jsonb NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id)
);
CREATE INDEX audit_tenant_time ON cp.audit_events(tenant_id,occurred_at DESC);
COMMENT ON TABLE cp.audit_events IS 'Auditoria mínima append-only para a aplicação';

-- Exercício de direitos e ações em destinos
CREATE TABLE cp.privacy_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  account_id uuid REFERENCES platform.accounts(id),
  visitor_id uuid,
  kind text NOT NULL,
  state text NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','VERIFYING','PROCESSING','WAITING_PARTNER','COMPLETED','REJECTED_WITH_REASON')),
  decision text,
  due_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, visitor_id) REFERENCES cp.visitors(tenant_id, id)
);
COMMENT ON TABLE cp.privacy_requests IS 'Exercício de direitos e ações em destinos';

-- Importação com prévia, hash e aceite
CREATE TABLE cp.import_batches (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  source text NOT NULL,
  file_hash text NOT NULL,
  entity_type text NOT NULL,
  mapping_version integer NOT NULL,
  state text NOT NULL DEFAULT 'UPLOADED' CHECK(state IN ('UPLOADED','VALIDATED','APPROVED','RUNNING','COMPLETED','FAILED')),
  approved_by uuid REFERENCES platform.accounts(id),
  counts jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  UNIQUE(tenant_id,source,file_hash,entity_type,mapping_version)
);
COMMENT ON TABLE cp.import_batches IS 'Importação com prévia, hash e aceite';

-- Resultado por linha e referência externa
CREATE TABLE cp.import_rows (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  batch_id uuid NOT NULL,
  row_number integer NOT NULL CHECK(row_number > 0),
  external_key text,
  state text NOT NULL,
  target_id uuid,
  errors jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, batch_id) REFERENCES cp.import_batches(tenant_id, id),
  UNIQUE(tenant_id,batch_id,row_number)
);
COMMENT ON TABLE cp.import_rows IS 'Resultado por linha e referência externa';

-- Concessão temporária para suporte
CREATE TABLE cp.support_access_grants (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
  support_account_id uuid NOT NULL REFERENCES platform.accounts(id),
  approved_by uuid NOT NULL REFERENCES platform.accounts(id),
  reason text NOT NULL,
  scope jsonb NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  UNIQUE (tenant_id, id)
);
COMMENT ON TABLE cp.support_access_grants IS 'Concessão temporária para suporte';

-- Correção temporal e versão de entidades mutáveis. Updates concorrentes ainda exigem WHERE version = :expected.
CREATE OR REPLACE FUNCTION cp.touch_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at := clock_timestamp(); NEW.version := OLD.version + 1; RETURN NEW; END;
$$;
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.blocks FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.units FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.memberships FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.subscriptions FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.amenities FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.reservations FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.reservation_waitlist FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.reservation_fee_entries FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.visitors FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.visit_permits FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.permit_windows FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.arrival_requests FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.parcels FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.parcel_delegations FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.announcements FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.service_requests FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.attachments FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.connectors FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.access_devices FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.legal_records FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.access_credentials FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.credential_syncs FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.integration_commands FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.webhook_inbox FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.outbox_events FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.idempotency_keys FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.push_installations FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.notifications FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.privacy_requests FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.import_batches FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.import_rows FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
CREATE TRIGGER touch_version BEFORE UPDATE ON cp.support_access_grants FOR EACH ROW EXECUTE FUNCTION cp.touch_version();
