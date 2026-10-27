-- Somente em banco exclusivo CondPulse. Flyway controla a aplicação.
CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS cp;
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA extensions;
SET search_path=cp,platform,extensions,pg_catalog;
CREATE TABLE platform.accounts (
 id uuid PRIMARY KEY,
 status text NOT NULL CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','CLOSED')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform.tenants (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 160),
 time_zone text NOT NULL DEFAULT 'America/Sao_Paulo',
 active boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE cp.blocks (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 name text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,name)
);
CREATE TABLE cp.units (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 block_id uuid, label text NOT NULL CHECK(length(btrim(label)) BETWEEN 1 AND 40),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id), UNIQUE NULLS NOT DISTINCT(tenant_id,block_id,label),
 FOREIGN KEY(tenant_id,block_id) REFERENCES cp.blocks(tenant_id,id)
);
CREATE TABLE cp.memberships (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 account_id uuid NOT NULL REFERENCES platform.accounts(id),unit_id uuid,
 display_name text NOT NULL CHECK(length(btrim(display_name)) BETWEEN 1 AND 160),
 role text NOT NULL CHECK(role IN ('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER','CONCIERGE','MANAGER','SUPPORT')),
 permissions jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(permissions)='object'),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACTIVE','SUSPENDED','REVOKED')),
 valid_from timestamptz NOT NULL DEFAULT now(), valid_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(tenant_id,id),UNIQUE NULLS NOT DISTINCT(tenant_id,account_id,unit_id,role),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK(valid_until IS NULL OR valid_until>valid_from),
 CHECK(role NOT IN ('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER') OR unit_id IS NOT NULL)
);
CREATE INDEX memberships_account_current ON cp.memberships(account_id,status,tenant_id);
CREATE TABLE cp.amenities (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 name text NOT NULL,capacity integer NOT NULL CHECK(capacity>0),
 approval_required boolean NOT NULL DEFAULT true,fee_cents bigint NOT NULL DEFAULT 0 CHECK(fee_cents>=0),
 buffer_minutes integer NOT NULL DEFAULT 0 CHECK(buffer_minutes BETWEEN 0 AND 1440),
 policy_version integer NOT NULL DEFAULT 1 CHECK(policy_version>0),
 policy jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(policy)='object'),
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),UNIQUE(tenant_id,id)
);
CREATE TABLE cp.reservations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),amenity_id uuid NOT NULL,unit_id uuid,
 created_by uuid NOT NULL REFERENCES platform.accounts(id),
 kind text NOT NULL CHECK(kind IN ('BOOKING','BLOCK')),
 status text NOT NULL CHECK(status IN ('HELD','PENDING_APPROVAL','CONFIRMED','REJECTED','CANCELLED','EXPIRED')),
 starts_at timestamptz NOT NULL,ends_at timestamptz NOT NULL,
 occupied_from timestamptz NOT NULL,occupied_until timestamptz NOT NULL,expires_at timestamptz,
 rule_snapshot jsonb NOT NULL CHECK(jsonb_typeof(rule_snapshot)='object'),
 fee_cents bigint NOT NULL DEFAULT 0 CHECK(fee_cents>=0),decision_reason text,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,amenity_id) REFERENCES cp.amenities(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),
 CHECK((kind='BOOKING' AND unit_id IS NOT NULL) OR (kind='BLOCK' AND unit_id IS NULL)),
 CHECK(isfinite(starts_at) AND isfinite(ends_at) AND isfinite(occupied_from) AND isfinite(occupied_until)),
 CHECK(starts_at<ends_at AND occupied_from<=starts_at AND occupied_until>=ends_at),
 CHECK(status NOT IN ('HELD','PENDING_APPROVAL') OR (expires_at IS NOT NULL AND expires_at<=starts_at)),
 CONSTRAINT reservations_no_overlap EXCLUDE USING gist (
  tenant_id WITH =,amenity_id WITH =,tstzrange(occupied_from,occupied_until,'[)') WITH &&
 ) WHERE(status IN ('HELD','PENDING_APPROVAL','CONFIRMED'))
);
CREATE INDEX reservations_by_unit ON cp.reservations(tenant_id,unit_id,starts_at);
CREATE INDEX reservations_expiry ON cp.reservations(tenant_id,expires_at) WHERE status IN ('HELD','PENDING_APPROVAL');
CREATE TABLE cp.parcels (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid NOT NULL,quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 1000),
 description text NOT NULL CHECK(length(description)<=500),
 storage_location text NOT NULL CHECK(length(btrim(storage_location)) BETWEEN 1 AND 160),
 status text NOT NULL DEFAULT 'RECEIVED' CHECK(status IN ('RECEIVED','DELIVERED')),
 received_by uuid NOT NULL REFERENCES platform.accounts(id),received_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id)
);
CREATE INDEX parcels_pending ON cp.parcels(tenant_id,unit_id,received_at) WHERE status='RECEIVED';
CREATE TABLE cp.parcel_handoffs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 parcel_id uuid NOT NULL,operator_id uuid NOT NULL REFERENCES platform.accounts(id),
 recipient_name text NOT NULL CHECK(length(btrim(recipient_name)) BETWEEN 1 AND 160),
 evidence_kind text NOT NULL CHECK(evidence_kind IN ('ASSISTED','CODE','DELEGATION')),
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,parcel_id),
 FOREIGN KEY(tenant_id,parcel_id) REFERENCES cp.parcels(tenant_id,id)
);
CREATE TABLE cp.arrival_requests (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 unit_id uuid NOT NULL,state text NOT NULL DEFAULT 'WAITING' CHECK(state IN ('WAITING','APPROVED','DENIED','EXPIRED')),
 expires_at timestamptz NOT NULL,decided_by uuid REFERENCES platform.accounts(id),decided_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES cp.units(tenant_id,id),CHECK(expires_at>created_at),
 CHECK((state IN ('APPROVED','DENIED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
  OR (state IN ('WAITING','EXPIRED') AND decided_by IS NULL AND decided_at IS NULL))
);
CREATE TABLE cp.audit_log (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 actor_id uuid NOT NULL REFERENCES platform.accounts(id),action text NOT NULL,
 resource_type text NOT NULL,resource_id uuid NOT NULL,trace_id uuid NOT NULL,reason text,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id)
);
CREATE INDEX audit_by_resource ON cp.audit_log(tenant_id,resource_type,resource_id,created_at);
CREATE TABLE cp.outbox (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 event_type text NOT NULL,aggregate_id uuid NOT NULL,aggregate_version bigint NOT NULL,
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
 state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','DELIVERED','FAILED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),available_at timestamptz NOT NULL DEFAULT now(),
 lease_until timestamptz,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,event_type,aggregate_id,aggregate_version)
);
CREATE INDEX outbox_ready ON cp.outbox(tenant_id,state,available_at);
CREATE TABLE cp.idempotency_keys (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 actor_id uuid NOT NULL REFERENCES platform.accounts(id),
 request_key text NOT NULL CHECK(length(request_key) BETWEEN 16 AND 200),method text NOT NULL,route text NOT NULL,
 body_hash text NOT NULL CHECK(body_hash ~ '^[0-9a-f]{64}$'),
 response_status integer CHECK(response_status BETWEEN 200 AND 599),response_body jsonb,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,actor_id,method,route,request_key),CHECK(expires_at>created_at)
);
RESET search_path;
