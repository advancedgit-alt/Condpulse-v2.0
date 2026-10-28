import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

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
  assert.match(launcher, /sslmode=verify-full&sslrootcert=\/tmp\/condpulse-db-ca\.crt/);
  assert.match(launcher, /umask 077/);
  assert.match(launcher, /unset CP_VERCEL_DB_CA_BASE64/);
  assert.doesNotMatch(launcher, /^(?:set -x|source\s|eval\s)/m);
  const env = read('infra/vercel.env.example');
  assert.match(env, /^PORT=8080$/m);
  assert.match(env, /^CP_BFF_ENABLED=false$/m);
  assert.match(env, /^CP_RESERVATIONS_ENABLED=false$/m);
  assert.doesNotMatch(env, /^(VITE_|NEXT_PUBLIC_|CP_MIGRATION_|CP_ONBOARDING_)/m);
});
