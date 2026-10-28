import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { validateRelease } from '../infra/validate-release.mjs';

const clone = value => structuredClone(value);
const ca = { type: 'bind', source: '/etc/condpulse/ca/postgres.crt', target: '/run/secrets/db-ca.crt', read_only: true, bind: { create_host_path: false } };
const db = {
  DATABASE_URL: 'jdbc:postgresql://db.abcdefghijklmnopqrst.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt',
  DATABASE_USER: 'cp_runtime_app', DATABASE_PASSWORD: 'SYNTHETIC_TEST_ONLY_password',
};
const hardened = {
  image: `ghcr.io/condpulse/api@sha256:${'a'.repeat(64)}`, user: '10001:10001', read_only: true,
  cap_drop: ['ALL'], security_opt: ['no-new-privileges:true'], tmpfs: ['/tmp:rw,noexec,nosuid,size=64m,mode=1777'],
  mem_limit: '2147483648', cpus: 2, pids_limit: 256, restart: 'unless-stopped',
  logging: { driver: 'json-file', options: { 'max-size': '10m', 'max-file': '3' } },
  networks: { backend: null }, volumes: [ca],
};
const app = {
  name: 'condpulse-staging',
  networks: { backend: { name: 'condpulse-staging_backend', driver: 'bridge' }, proxy: { name: 'condpulse-proxy', external: true } },
  services: {
    api: {
      ...hardened, expose: ['8080'],
      environment: { ...db, SPRING_PROFILES_ACTIVE: 'api', DB_POOL_SIZE: '8', PORT: '8080', CP_BFF_ENABLED: 'false', CP_RESERVATIONS_ENABLED: 'false', CP_BFF_ORIGIN: 'https://condpulse.example.com', CP_JWT_ISSUER: 'https://abcdefghijklmnopqrst.supabase.co/auth/v1', CP_JWT_JWK_SET_URI: 'https://abcdefghijklmnopqrst.supabase.co/auth/v1/.well-known/jwks.json' },
      healthcheck: { test: ['CMD', 'java', '-Xmx16m', '-XX:+UseSerialGC', '-cp', '/app/health', 'Healthcheck'], interval: '15s', timeout: '3s', retries: 5, start_period: '1m0s' },
    },
    web: {
      ...hardened, image: `ghcr.io/condpulse/web@sha256:${'b'.repeat(64)}`, user: '101:101', mem_limit: '268435456', cpus: 0.5, pids_limit: 128,
      volumes: [], expose: ['8080'], networks: { backend: null, proxy: null },
      depends_on: { api: { condition: 'service_healthy', required: true } },
      healthcheck: { test: ['CMD', 'wget', '-q', '-O', '/dev/null', 'http://127.0.0.1:8080/healthz'], interval: '30s', timeout: '3s', retries: 3, start_period: '5s' },
    },
  },
};
const migrate = {
  name: 'condpulse-migrate', networks: { backend: { name: 'condpulse-migrate_backend', driver: 'bridge' } },
  services: { migrate: { ...hardened, restart: 'no', environment: { ...db, DATABASE_USER: 'postgres', SPRING_PROFILES_ACTIVE: 'migrate', DB_POOL_SIZE: '2' }, healthcheck: { disable: true } } },
};

test('app e migrador isolado aceitam somente a configuracao endurecida', () => {
  assert.deepEqual(validateRelease(app, 'app'), []);
  assert.deepEqual(validateRelease(migrate, 'migrate'), []);
  const pooler = clone(app);
  pooler.services.api.environment.DATABASE_USER += '.abcdefghijklmnopqrst';
  pooler.services.api.environment.DATABASE_URL = 'jdbc:postgresql://aws-0-sa-east-1.pooler.supabase.com:5432/postgres?sslrootcert=%2Frun%2Fsecrets%2Fdb-ca.crt&sslmode=verify-full';
  assert.deepEqual(validateRelease(pooler, 'app'), []);
});

function rejected(edit, code, base = app, mode = 'app') {
  const candidate = clone(base); edit(candidate);
  const errors = validateRelease(candidate, mode);
  assert.ok(errors.some(error => error.code === code), `Expected controlled code ${code}; received ${JSON.stringify(errors)}`);
}

test('porta publica, rede proxy na API e privilegios adicionais falham', () => {
  rejected(c => { c.name = 'advanced-core'; }, 'DEDICATED_PROJECT_NAME_REQUIRED');
  rejected(c => { c.networks.backend.name = 'advanced-core'; }, 'DEDICATED_BACKEND_NETWORK_REQUIRED');
  rejected(c => { c.services.api.ports = ['8080:8080']; }, 'SERVICE_SETTING_UNEXPECTED');
  rejected(c => { c.services.api.networks.proxy = null; }, 'SERVICE_NETWORK_ISOLATION');
  rejected(c => { c.services.api.privileged = true; }, 'SERVICE_SETTING_UNEXPECTED');
  rejected(c => { c.services.api.cap_drop = []; }, 'DROP_ALL_CAPABILITIES');
  rejected(c => { c.services.api.user = '0:0'; }, 'NON_ROOT_USER_REQUIRED');
  rejected(c => { c.networks.backend.external = true; }, 'BACKEND_PRIVATE_BRIDGE_WITH_EGRESS');
  rejected(c => { c.networks.proxy.name = 'advanced-core'; }, 'DEDICATED_PROXY_NETWORK_REQUIRED');
  rejected(c => { c.services.worker = clone(c.services.api); }, 'SERVICES_EXACT');
});

test('normalizacao Compose aceita apenas null/empty e preserva o projeto Supabase', () => {
  const normalized = clone(app);
  normalized.networks.proxy.ipam = {};
  for (const service of Object.values(normalized.services)) { service.command = null; service.entrypoint = null; }
  assert.deepEqual(validateRelease(normalized, 'app'), []);
  rejected(c => { c.services.api.command = []; }, 'SERVICE_SETTING_UNEXPECTED');
  rejected(c => { c.networks.proxy.ipam = { config: [{ subnet: '10.0.0.0/24' }] }; }, 'DEDICATED_PROXY_NETWORK_REQUIRED');
  rejected(c => { c.services.api.environment.DATABASE_URL = db.DATABASE_URL.replace('abcdefghijklmnopqrst', 'bbbbbbbbbbbbbbbbbbbb'); }, 'SUPABASE_PROJECT_MISMATCH');
  rejected(c => { c.services.api.environment.DATABASE_URL = db.DATABASE_URL.replace(':5432', ':6543'); }, 'DATABASE_TLS_CONFIGURATION');
  rejected(c => { c.services.api.environment.DATABASE_URL = db.DATABASE_URL.replace('db.abcdefghijklmnopqrst.supabase.co', 'other.example.com'); }, 'SUPABASE_DATABASE_TARGET_REQUIRED');
  const pooled = clone(app);
  pooled.services.api.environment.DATABASE_URL = db.DATABASE_URL.replace('db.abcdefghijklmnopqrst.supabase.co', 'aws-0-sa-east-1.pooler.supabase.com');
  pooled.services.api.environment.DATABASE_USER += '.bbbbbbbbbbbbbbbbbbbb';
  assert.ok(validateRelease(pooled, 'app').some(e => e.code === 'SUPABASE_PROJECT_MISMATCH'));
  const migrationPooler = clone(migrate);
  migrationPooler.services.migrate.environment.DATABASE_URL = pooled.services.api.environment.DATABASE_URL;
  migrationPooler.services.migrate.environment.DATABASE_USER = 'postgres.abcdefghijklmnopqrst';
  assert.deepEqual(validateRelease(migrationPooler, 'migrate'), []);
});

test('imagem mutavel, build no host, entrypoint e recursos sem teto falham', () => {
  for (const image of ['ghcr.io/condpulse/api:latest', `ghcr.io/condpulse/api:release@sha256:${'a'.repeat(64)}`, `ghcr.io/condpulse/api@sha256:${'0'.repeat(64)}`]) {
    rejected(c => { c.services.api.image = image; }, 'IMAGE_DIGEST_REQUIRED');
  }
  rejected(c => { c.services.api.build = '.'; }, 'SERVICE_SETTING_UNEXPECTED');
  rejected(c => { c.services.api.entrypoint = ['sh']; }, 'SERVICE_SETTING_UNEXPECTED');
  rejected(c => { delete c.services.api.mem_limit; }, 'MEMORY_LIMIT_REQUIRED');
  rejected(c => { c.services.web.cpus = 8; }, 'CPU_LIMIT_REQUIRED');
  rejected(c => { c.services.api.pids_limit = -1; }, 'PID_LIMIT_REQUIRED');
  rejected(c => { c.services.web.logging.options['max-size'] = '500m'; }, 'ROTATED_LOGS_REQUIRED');
  rejected(c => { c.services.api.tmpfs = ['/etc:rw,size=64m']; }, 'BOUNDED_PRIVATE_TMP_REQUIRED');
});

test('TLS, CA, credenciais em URL e parametros duplicados falham fechados', () => {
  const valid = db.DATABASE_URL;
  for (const value of [valid.replace('verify-full', 'require'), valid + '&sslmode=disable', valid + '&user=postgres', valid.replace('db.abcdefghijklmnopqrst.supabase.co', 'localhost'), valid.replace('db.abcdefghijklmnopqrst.supabase.co', '127.0.0.1'), valid.replace('db.abcdefghijklmnopqrst.supabase.co', 'host.invalid'), valid.replace('postgresql://', 'postgresql://user:password@'), valid.replace('/run/secrets/db-ca.crt', '/tmp/ca.crt'), valid.replace('/postgres?', '/postgres,another?')]) {
    rejected(c => { c.services.api.environment.DATABASE_URL = value; }, 'DATABASE_TLS_CONFIGURATION');
  }
  rejected(c => { c.services.api.volumes[0].read_only = false; }, 'CA_READ_ONLY_MOUNT_REQUIRED');
  rejected(c => { c.services.api.volumes.push({ type: 'bind', source: '/var/run/docker.sock', target: '/var/run/docker.sock' }); }, 'CA_READ_ONLY_MOUNT_REQUIRED');
  rejected(c => { c.services.api.volumes[0].bind.create_host_path = true; }, 'CA_READ_ONLY_MOUNT_REQUIRED');
});

test('DDL nunca entra na API e o job migrador nao ganha sessao ou servidor', () => {
  for (const user of ['postgres', 'cp_api', 'cp_worker', 'supabase_admin', 'cp_migrator_owner']) rejected(c => { c.services.api.environment.DATABASE_USER = user; }, 'DATABASE_ROLE_SEPARATION');
  rejected(c => { c.services.api.environment.CP_MIGRATION_DATABASE_PASSWORD = 'SYNTHETIC_MIGRATOR_ONLY'; }, 'ENVIRONMENT_UNEXPECTED');
  rejected(c => { c.services.web.environment = { DATABASE_PASSWORD: db.DATABASE_PASSWORD }; }, 'WEB_NO_CREDENTIALS_OR_MOUNTS');
  rejected(c => { c.services.migrate.environment.DATABASE_USER = 'cp_runtime_app'; }, 'DATABASE_ROLE_SEPARATION', migrate, 'migrate');
  rejected(c => { c.services.migrate.environment.CP_BFF_ENABLED = 'false'; }, 'ENVIRONMENT_UNEXPECTED', migrate, 'migrate');
  rejected(c => { c.services.migrate.environment.SPRING_PROFILES_ACTIVE = 'api,migrate'; }, 'PROFILE_EXACT', migrate, 'migrate');
  rejected(c => { c.services.migrate.restart = 'always'; }, 'RESTART_POLICY_REQUIRED', migrate, 'migrate');
  rejected(c => { c.services.migrate.expose = ['8080']; }, 'MIGRATOR_NO_HTTP', migrate, 'migrate');
});

test('flags, identidade, origem e readiness nao podem ser liberadas por engano', () => {
  rejected(c => { c.services.api.environment.CP_BFF_ENABLED = 'true'; }, 'FEATURES_MUST_REMAIN_DISABLED');
  rejected(c => { c.services.api.environment.CP_RESERVATIONS_ENABLED = 'true'; }, 'FEATURES_MUST_REMAIN_DISABLED');
  for (const origin of ['http://condpulse.example.com', 'https://condpulse.example.com/', 'https://user:pass@condpulse.example.com', 'https://condpulse.example.com/path']) rejected(c => { c.services.api.environment.CP_BFF_ORIGIN = origin; }, 'BFF_HTTPS_ORIGIN_REQUIRED');
  rejected(c => { c.services.api.environment.CP_JWT_JWK_SET_URI = 'https://other.example.com/jwks'; }, 'SUPABASE_AUTH_ENDPOINTS_MATCH');
  rejected(c => { c.services.web.depends_on.api.condition = 'service_started'; }, 'API_READINESS_DEPENDENCY');
  rejected(c => { c.services.api.healthcheck.test = ['CMD', 'true']; }, 'HEALTHCHECK_REQUIRED');
});

test('CLI nao devolve segredos em JSON invalido, chaves hostis ou valores invalidos', () => {
  const secret = 'SENTINEL_SECRET_never_echo_1234567890';
  const candidate = clone(app);
  candidate.services.api.environment.DATABASE_PASSWORD = secret;
  candidate.services.api.environment[secret] = secret;
  candidate.services.api[secret] = secret;
  candidate.services[secret] = secret;
  candidate.networks[secret] = secret;
  const script = fileURLToPath(new URL('../infra/validate-release.mjs', import.meta.url));
  for (const input of [JSON.stringify(candidate), `{"bad":"${secret}"`, 'x'.repeat(1024 * 1024 + 1) + secret]) {
    const run = spawnSync(process.execPath, [script, '--app'], { input, encoding: 'utf8', maxBuffer: 1024 * 1024 });
    assert.equal(run.status, 1);
    const output = run.stdout + run.stderr;
    assert.doesNotMatch(output, new RegExp(secret));
    assert.match(output, /^RELEASE_INVALID \d+\n/);
    assert.doesNotMatch(output, /SyntaxError|at file:|DATABASE_PASSWORD=/);
  }
  const valid = spawnSync(process.execPath, [script, '--migrate'], { input: JSON.stringify(migrate), encoding: 'utf8' });
  assert.equal(valid.status, 0); assert.equal(valid.stdout, 'RELEASE_VALID 0\n'); assert.equal(valid.stderr, '');
});
