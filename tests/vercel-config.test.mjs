import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync, realpathSync, lstatSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { minimalEnvironment } from './integration/https-bff-environment.mjs';

const read = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');
const config = JSON.parse(read('vercel.json'));

test('Vercel Services preserva origem unica e caminhos do BFF/API antes do fallback SPA', () => {
  assert.deepEqual(config.regions, ['gru1']);
  assert.deepEqual(Object.keys(config.services).sort(), ['api', 'web']);
  assert.deepEqual(config.rewrites, [
    { source: '/auth/:path*', destination: { service: 'api' } },
    { source: '/v1/:path*', destination: { service: 'api' } },
    { source: '/(.*)', destination: { service: 'web' } },
  ]);
  assert.equal(config.services.api.root, '.');
  assert.equal(config.services.api.runtime, 'container');
  assert.equal(config.services.api.entrypoint, 'Dockerfile.vercel');
  assert.equal(config.services.web.root, 'apps/web');
  assert.equal(config.services.web.framework, 'vite');
  assert.equal(config.services.web.installCommand, 'npm ci --no-fund --no-audit');
  assert.equal(config.services.web.buildCommand, 'npm run build');
  assert.equal(config.services.web.outputDirectory, 'dist');
  assert.deepEqual(config.services.web.rewrites, [{ source: '/(.*)', destination: '/index.html' }]);
  for (const forbidden of ['env', 'build', 'builds', 'functions', 'buildCommand', 'installCommand', 'outputDirectory']) {
    assert.equal(Object.hasOwn(config, forbidden), false);
  }
  assert.equal(Object.hasOwn(config.services.api, 'command'), false);
});

test('Vercel conserva CSP/no-store e nao grava credenciais ou origem de outro sistema no manifesto', () => {
  const headers = Object.fromEntries(config.headers[0].headers.map(({ key, value }) => [key.toLowerCase(), value]));
  assert.equal(config.headers[0].source, '/(.*)');
  assert.equal(headers['cache-control'], 'no-store');
  assert.equal(headers['x-content-type-options'], 'nosniff');
  assert.match(headers['content-security-policy'], /connect-src 'self'/);
  assert.match(headers['content-security-policy'], /frame-ancestors 'none'/);
  assert.doesNotMatch(JSON.stringify(config), /DATABASE_PASSWORD|ENCRYPTION_KEY|sb_secret_|service_role|sindicoadvanced/i);
});

test('Container Vercel usa o build root revisado e forca perfil API sem migracao', () => {
  const docker = read('Dockerfile.vercel');
  const existing = read('infra/Dockerfile.api');
  const bases = [...docker.matchAll(/^FROM ([^\s]+@sha256:[a-f0-9]{64})/gm)].map(match => match[1]);
  assert.equal(bases.length, 2);
  for (const base of bases) assert.ok(existing.includes(base));
  assert.match(docker, /COPY apps\/backend apps\/backend/);
  assert.match(docker, /COPY db\/migrations db\/migrations/);
  assert.match(docker, /USER 10001:10001/);
  assert.match(docker, /CP_BFF_ENABLED=false CP_RESERVATIONS_ENABLED=false/);
  assert.match(docker, /ENTRYPOINT \["\/bin\/sh", "\/app\/start-api.sh"\]/);
  assert.doesNotMatch(docker, /ARG .*PASSWORD|COPY \. \.|--build-arg|latest/);
  const launcher = read('infra/vercel/start-api.sh');
  assert.match(launcher, /--spring\.profiles\.active=api --spring\.flyway\.enabled=false --spring\.sql\.init\.mode=never/);
  const preflight = read('infra/vercel/VercelRuntimePreflight.java');
  assert.match(preflight, /"verify-full"\.equals/);
  assert.match(preflight, /\/tmp\/condpulse-db-ca\.crt/);
  assert.match(launcher, /umask 077/);
  assert.match(launcher, /unset CP_VERCEL_DB_CA_BASE64/);
  assert.match(launcher, /-cp \/app\/preflight VercelRuntimePreflight/);
  assert.doesNotMatch(launcher, /^(?:set -x|source\s|eval\s)/m);
  const env = read('infra/vercel.env.example');
  assert.match(env, /^PORT=8080$/m);
  assert.match(env, /^CP_BFF_ENABLED=false$/m);
  assert.match(env, /^CP_RESERVATIONS_ENABLED=false$/m);
  assert.doesNotMatch(env, /^(VITE_|NEXT_PUBLIC_|CP_MIGRATION_|CP_ONBOARDING_)/m);
});

test('preflight Java rejeita banco/projeto, JWT, TLS e chaves incompatíveis antes de iniciar Spring', t => {
  const detected = spawnSync('javac', ['-version'], { encoding: 'utf8', windowsHide: true });
  if (detected.error?.code === 'ENOENT') { t.skip('JDK local ausente; executado no CI Java25'); return; }
  assert.equal(detected.status, 0);
  const temp = mkdtempSync(join(tmpdir(), 'condpulse-vercel-guard-'));
  try {
    const source = fileURLToPath(new URL('../infra/vercel/VercelRuntimePreflight.java', import.meta.url));
    const compile = spawnSync('javac', ['-d', temp, source], { env: minimalEnvironment(), encoding: 'utf8', windowsHide: true });
    assert.equal(compile.status, 0, compile.stderr);
    const project = 'rpnrqfrfwywglvyykgrg';
    const issuer = `https://${project}.supabase.co/auth/v1`;
    const jdbc = `jdbc:postgresql://db.${project}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=/tmp/condpulse-db-ca.crt`;
    const env = { ...minimalEnvironment(), DATABASE_URL: jdbc, DATABASE_USER: 'cp_runtime_vercel_ci',
      DATABASE_PASSWORD: 'SYNTHETIC_TEST_ONLY_password', CP_JWT_ISSUER: issuer,
      CP_JWT_JWK_SET_URI: `${issuer}/.well-known/jwks.json`, CP_BFF_ORIGIN: 'https://condpulse.vercel.app',
      CP_BFF_ENABLED: 'true', CP_RESERVATIONS_ENABLED: 'false', DB_POOL_SIZE: '2', PORT: '8080',
      CP_SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_SYNTHETIC_TEST_ONLY_1234567890',
      CP_BFF_ENCRYPTION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => i + 1)).toString('base64'),
      CP_BFF_RATE_LIMIT_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => i + 51)).toString('base64') };
    const execute = change => spawnSync('java', ['-Xmx16m', '-cp', temp, 'VercelRuntimePreflight'], {
      env: { ...env, ...change }, encoding: 'utf8', windowsHide: true, timeout: 5000,
    });
    for (const valid of [{}, { DATABASE_URL: jdbc.replace(`db.${project}.supabase.co`, 'aws-0-sa-east-1.pooler.supabase.com'), DATABASE_USER: `cp_runtime_vercel_ci.${project}` }]) {
      const result = execute(valid); assert.equal(result.status, 0); assert.equal(result.stdout + result.stderr, '');
    }
    for (const [change, code] of [
      [{ DATABASE_URL: jdbc.replace(`db.${project}.supabase.co`, 'attacker.example.com') }, 'VERCEL_API_DATABASE_PROJECT_EXACT'],
      [{ DATABASE_URL: jdbc.replace(project, 'bbbbbbbbbbbbbbbbbbbb') }, 'VERCEL_API_DATABASE_PROJECT_EXACT'],
      [{ DATABASE_URL: jdbc.replace(':5432/', ':6543/') }, 'VERCEL_API_DATABASE_TARGET_INVALID'],
      [{ DATABASE_URL: jdbc.replace('/postgres?', '/another?') }, 'VERCEL_API_DATABASE_TARGET_INVALID'],
      [{ DATABASE_URL: jdbc.replace('postgresql://', 'postgresql://user:SENSITIVE_SENTINEL@') }, 'VERCEL_API_DATABASE_TARGET_INVALID'],
      [{ DATABASE_URL: jdbc + '#SENSITIVE_SENTINEL' }, 'VERCEL_API_DATABASE_TARGET_INVALID'],
      [{ DATABASE_URL: jdbc + '&sslmode=disable' }, 'VERCEL_API_DATABASE_TLS_REQUIRED'],
      [{ DATABASE_URL: jdbc + '&options=SENSITIVE_SENTINEL' }, 'VERCEL_API_DATABASE_TLS_REQUIRED'],
      [{ DATABASE_URL: jdbc.replace('sslmode=', '%73slmode=') }, 'VERCEL_API_DATABASE_TLS_REQUIRED'],
      [{ DATABASE_URL: jdbc.replace('verify-full', 'require') }, 'VERCEL_API_DATABASE_TLS_REQUIRED'],
      [{ DATABASE_URL: jdbc.replace(`db.${project}.supabase.co`, 'aws-0-sa-east-1.pooler.supabase.com'), DATABASE_USER: 'cp_runtime_vercel_ci.bbbbbbbbbbbbbbbbbbbb' }, 'VERCEL_API_RUNTIME_IDENTITY_REQUIRED'],
      [{ DATABASE_USER: 'postgres' }, 'VERCEL_API_RUNTIME_IDENTITY_REQUIRED'],
      [{ CP_JWT_ISSUER: 'https://attacker.example.com/auth/v1' }, 'VERCEL_API_ISSUER_EXACT'],
      [{ CP_JWT_JWK_SET_URI: 'https://attacker.example.com/jwks' }, 'VERCEL_API_JWKS_EXACT'],
      [{ CP_BFF_ORIGIN: 'http://condpulse.vercel.app' }, 'VERCEL_API_BFF_ORIGIN_REQUIRED'],
      [{ CP_BFF_ORIGIN: 'https://user:SENSITIVE_SENTINEL@condpulse.vercel.app' }, 'VERCEL_API_BFF_ORIGIN_REQUIRED'],
      [{ CP_BFF_ORIGIN: 'https://condpulse.vercel.app/path' }, 'VERCEL_API_BFF_ORIGIN_REQUIRED'],
      [{ CP_BFF_ENCRYPTION_KEY: Buffer.alloc(32).toString('base64') }, 'VERCEL_API_ENCRYPTION_KEY_INVALID'],
      [{ CP_BFF_RATE_LIMIT_KEY: 'SENSITIVE_SENTINEL' }, 'VERCEL_API_RATE_KEY_INVALID'],
      [{ CP_BFF_RATE_LIMIT_KEY: env.CP_BFF_ENCRYPTION_KEY }, 'VERCEL_API_KEYS_MUST_DIFFER'],
      [{ CP_SUPABASE_SECRET_KEY: 'SENSITIVE_SENTINEL' }, 'VERCEL_API_PRIVILEGED_ENV_FORBIDDEN'],
      [{ CP_MIGRATION_DATABASE_PASSWORD: 'SENSITIVE_SENTINEL' }, 'VERCEL_API_PRIVILEGED_ENV_FORBIDDEN'],
      [{ DB_POOL_SIZE: '100' }, 'VERCEL_API_POOL_LIMIT'],
      [{ DB_POOL_SIZE: undefined }, 'VERCEL_API_POOL_LIMIT'],
      [{ DB_POOL_SIZE: '' }, 'VERCEL_API_POOL_LIMIT'],
      [{ PORT: undefined }, 'VERCEL_API_PORT_EXACT'],
      [{ PORT: '9090' }, 'VERCEL_API_PORT_EXACT'],
      [{ SERVER_PORT: '9090' }, 'VERCEL_API_CONFIGURATION_OVERRIDE_FORBIDDEN'],
      [{ SERVER_SERVLET_CONTEXT_PATH: '/changed' }, 'VERCEL_API_CONFIGURATION_OVERRIDE_FORBIDDEN'],
      [{ MANAGEMENT_SERVER_PORT: '9091' }, 'VERCEL_API_CONFIGURATION_OVERRIDE_FORBIDDEN'],
    ]) {
      const result = execute(change);
      assert.equal(result.status, 1);
      assert.equal(result.stdout, '');
      assert.equal(result.stderr.trim(), code);
      assert.doesNotMatch(result.stderr, /SENSITIVE_SENTINEL|Exception|jdbc:|sb_publishable_/);
    }
  } finally {
    const path = realpathSync(temp), base = realpathSync(tmpdir());
    assert.equal(dirname(path), base); assert.ok(basename(path).startsWith('condpulse-vercel-guard-'));
    assert.equal(lstatSync(temp).isSymbolicLink(), false);
    rmSync(path, { recursive: true, force: true });
  }
});
