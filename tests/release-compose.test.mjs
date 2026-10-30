import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateRelease } from '../infra/validate-release.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

test('cinco manifestos Compose separam aplicacao, DDL e operacao sem daemon ou conexao remota', () => {
  const version = execFileSync('docker', ['compose', 'version', '--short'], {
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
  }).trim().replace(/^v/, '');
  assert.equal(version, '5.2.0', 'Preflight exige a versao revisada Compose5.2.0');
  const temporary = mkdtempSync(join(tmpdir(), 'condpulse-compose-'));
  try {
    const emptyEnv = join(temporary, 'empty.env');
    const ca = join(temporary, 'db-ca.crt');
    const manifest = join(temporary, 'onboarding.json');
    writeFileSync(emptyEnv, '');
    writeFileSync(ca, 'SYNTHETIC CA FILE FOR CONFIG PARSING ONLY\n');
    writeFileSync(manifest, '{}\n');
    // Não há conexão, pull ou credencial real neste teste de renderização.
    const jdbc = 'jdbc:postgresql://db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432/postgres'
      + '?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt';
    const env = {
      ...process.env,
      CP_API_IMAGE: 'ghcr.io/condpulse/api@sha256:' + 'a'.repeat(64),
      CP_WEB_IMAGE: 'ghcr.io/condpulse/web@sha256:' + 'b'.repeat(64),
      CP_BASELINE_IMAGE: 'ghcr.io/condpulse/baseline@sha256:' + 'c'.repeat(64),
      CP_BASELINE_ACK: '',
      CP_ONBOARDING_IMAGE: 'ghcr.io/condpulse/onboarding@sha256:' + 'd'.repeat(64),
      CP_ONBOARDING_DATABASE_URL: jdbc.replace('aaaaaaaaaaaaaaaaaaaa', 'rpnrqfrfwywglvyykgrg'),
      CP_ONBOARDING_DATABASE_USER: 'cp_onboarding_ci',
      CP_ONBOARDING_DATABASE_PASSWORD: 'OPERATOR_SENTINEL_CONFIG_TEST_ONLY',
      CP_ONBOARDING_AUTH_SECRET_KEY: 'sb_secret_SYNTHETIC_OPERATIONAL_TEST_ONLY_1234567890',
      CP_ONBOARDING_MANIFEST_FILE: manifest,
      CP_ONBOARDING_ACK: '',
      CP_API_DATABASE_URL: jdbc,
      CP_API_DATABASE_USER: 'cp_runtime_ci',
      CP_API_DATABASE_PASSWORD: 'ConfigTestRuntime_42!',
      CP_WORKER_DATABASE_URL: jdbc,
      CP_WORKER_DATABASE_USER: 'cp_worker_ci',
      CP_WORKER_DATABASE_PASSWORD: 'ConfigTestWorker_42!',
      CP_MIGRATION_DATABASE_URL: jdbc,
      CP_MIGRATION_DATABASE_USER: 'postgres',
      CP_MIGRATION_DATABASE_PASSWORD: 'DDL_SENTINEL_CONFIG_TEST_ONLY',
      CP_DB_CA_FILE: ca,
      CP_SUPABASE_URL: 'https://aaaaaaaaaaaaaaaaaaaa.supabase.co',
      CP_BFF_ORIGIN: 'https://condpulse.example.org',
      CP_PROXY_NETWORK: 'condpulse_ci_proxy',
      CP_SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_SYNTHETIC_TEST_ONLY_1234567890',
      CP_BFF_ENCRYPTION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => i + 1)).toString('base64'),
      CP_BFF_RATE_LIMIT_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => 255 - i)).toString('base64'),
    };
    const render = (file, environment = env, project) => {
      const files = Array.isArray(file) ? file : [file];
      const name = project ?? (files[0].includes('.app.') ? 'condpulse-app' : files[0].includes('.baseline.') ? 'condpulse-baseline' : files[0].includes('.onboarding.') ? 'condpulse-onboarding' : 'condpulse-migrate');
      return JSON.parse(execFileSync('docker', [
        'compose', '--project-name', name, '--env-file', emptyEnv,
        ...files.flatMap(path => ['-f', path]), 'config', '--format', 'json',
      ], { cwd: root, env: environment, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true }));
    };
    const app = render('infra/compose.app.yaml');
    const migration = render('infra/compose.migrate.yaml');
    const baseline = render('infra/compose.baseline.yaml', {
      ...env, CP_MIGRATION_DATABASE_URL: jdbc.replace('aaaaaaaaaaaaaaaaaaaa', 'rpnrqfrfwywglvyykgrg'),
    });
    assert.deepEqual(validateRelease(app, 'app'), []);
    assert.deepEqual(validateRelease(migration, 'migrate'), []);
    assert.deepEqual(validateRelease(baseline, 'baseline'), []);
    assert.equal(app.services.api.image, migration.services.migrate.image);
    assert.equal(JSON.stringify(app).includes('DDL_SENTINEL_CONFIG_TEST_ONLY'), false);
    assert.equal(JSON.stringify(migration).includes('ConfigTestRuntime_42!'), false);
    assert.equal(JSON.stringify(baseline).includes('ConfigTestRuntime_42!'), false);
    assert.deepEqual(baseline.services.baseline.command, ['inspect']);
    assert.equal(baseline.services.baseline.environment.CP_BASELINE_ACK, '');
    assert.equal(baseline.services.baseline.environment.CP_BASELINE_PROJECT_REF, 'rpnrqfrfwywglvyykgrg');
    assert.notEqual(baseline.services.baseline.image, app.services.api.image);
    assert.equal(Object.keys(baseline.services).length, 1);
    assert.throws(() => render('infra/compose.app.yaml', { ...env, CP_API_IMAGE: '' }));
    const hmlFiles = ['infra/compose.app.yaml', 'infra/compose.homologation.yaml'];
    const hmlEnv = { ...env, CP_PROXY_NETWORK: 'condpulse-hml-ci-proxy' };
    const hml = render(hmlFiles, hmlEnv, 'condpulse-hml-ci');
    assert.deepEqual(validateRelease(hml, 'homologation'), []);
    assert.ok(validateRelease(hml, 'app').some(error => error.code === 'FEATURES_MUST_REMAIN_DISABLED'));
    assert.equal(hml.services.api.environment.CP_BFF_ENABLED, 'true');
    assert.equal(hml.services.api.environment.CP_RESERVATIONS_ENABLED, 'true');
    assert.equal(hml.services.api.environment.CP_BFF_ENCRYPTION_KEY, env.CP_BFF_ENCRYPTION_KEY);
    assert.equal(hml.services.web.environment, undefined);
    assert.equal(JSON.stringify(hml).includes('DDL_SENTINEL_CONFIG_TEST_ONLY'), false);
    assert.equal(JSON.stringify(migration).includes(env.CP_BFF_ENCRYPTION_KEY), false);
    assert.equal(JSON.stringify(baseline).includes(env.CP_BFF_ENCRYPTION_KEY), false);
    assert.equal(app.services.api.environment.CP_BFF_ENABLED, 'false');
    assert.equal(app.services.api.environment.CP_BFF_ENCRYPTION_KEY, undefined);
    assert.throws(() => render(hmlFiles, { ...hmlEnv, CP_BFF_ENCRYPTION_KEY: '' }, 'condpulse-hml-ci'));
    assert.throws(() => render(hmlFiles, { ...hmlEnv, CP_BFF_RATE_LIMIT_KEY: '' }, 'condpulse-hml-ci'));
    assert.throws(() => render(hmlFiles, { ...hmlEnv, CP_SUPABASE_PUBLISHABLE_KEY: '' }, 'condpulse-hml-ci'));
    const onboarding = render('infra/compose.onboarding.yaml');
    assert.deepEqual(validateRelease(onboarding, 'onboarding'), []);
    assert.deepEqual(Object.keys(onboarding.services), ['onboarding']);
    assert.deepEqual(onboarding.services.onboarding.command, ['inspect', '/run/secrets/onboarding.json']);
    assert.equal(onboarding.services.onboarding.environment.SPRING_PROFILES_ACTIVE, undefined);
    assert.equal(onboarding.services.onboarding.environment.DB_POOL_SIZE, undefined);
    assert.equal(onboarding.services.onboarding.environment.CP_ONBOARDING_AUTH_SECRET_KEY, env.CP_ONBOARDING_AUTH_SECRET_KEY);
    assert.equal(onboarding.services.onboarding.volumes.length, 2);
    for (const config of [app, migration, baseline, hml]) {
      assert.equal(JSON.stringify(config).includes(env.CP_ONBOARDING_AUTH_SECRET_KEY), false);
      assert.equal(JSON.stringify(config).includes('OPERATOR_SENTINEL_CONFIG_TEST_ONLY'), false);
    }
    for (const secret of ['ConfigTestRuntime_42!', 'DDL_SENTINEL_CONFIG_TEST_ONLY', env.CP_BFF_ENCRYPTION_KEY, env.CP_BFF_RATE_LIMIT_KEY]) {
      assert.equal(JSON.stringify(onboarding).includes(secret), false);
    }
    assert.throws(() => render('infra/compose.onboarding.yaml', { ...env, CP_ONBOARDING_AUTH_SECRET_KEY: '' }));
    assert.throws(() => render('infra/compose.onboarding.yaml', { ...env, CP_ONBOARDING_MANIFEST_FILE: '' }));
    const worker = render('infra/compose.worker.yaml', env, 'condpulse-worker');
    assert.deepEqual(validateRelease(worker, 'worker'), []);
    assert.deepEqual(Object.keys(worker.services), ['worker']);
    assert.equal(worker.services.worker.image, app.services.api.image, 'worker usa a mesma imagem aprovada da API');
    assert.equal(worker.services.worker.environment.SPRING_PROFILES_ACTIVE, 'worker');
    assert.equal(worker.services.worker.environment.CP_WORKER_OUTBOX_ENABLED, 'false');
    assert.equal(worker.services.worker.environment.CP_WORKER_WAITLIST_ENABLED, 'false');
    assert.equal(worker.services.worker.expose, undefined);
    for (const secret of ['ConfigTestRuntime_42!', 'DDL_SENTINEL_CONFIG_TEST_ONLY', env.CP_BFF_ENCRYPTION_KEY, env.CP_ONBOARDING_AUTH_SECRET_KEY]) {
      assert.equal(JSON.stringify(worker).includes(secret), false);
    }
    for (const config of [app, migration, baseline, hml, onboarding]) assert.equal(JSON.stringify(config).includes('ConfigTestWorker_42!'), false);
    assert.ok(validateRelease(worker, 'app').some(error => error.code === 'SERVICES_EXACT'));
    const enabled = structuredClone(worker); enabled.services.worker.environment.CP_WORKER_OUTBOX_ENABLED = 'true';
    assert.ok(validateRelease(enabled, 'worker').some(error => error.code === 'FEATURES_MUST_REMAIN_DISABLED'));
    const apiLogin = structuredClone(worker); apiLogin.services.worker.environment.DATABASE_USER = 'cp_runtime_ci';
    assert.ok(validateRelease(apiLogin, 'worker').some(error => error.code === 'DATABASE_ROLE_SEPARATION'));
    const groupLogin = structuredClone(worker); groupLogin.services.worker.environment.DATABASE_USER = 'cp_worker';
    assert.ok(validateRelease(groupLogin, 'worker').some(error => error.code === 'DATABASE_ROLE_SEPARATION'));
    const port = structuredClone(worker); port.services.worker.expose = ['8080'];
    assert.ok(validateRelease(port, 'worker').some(error => error.code === 'WORKER_NO_HTTP'));
    assert.throws(() => render('infra/compose.worker.yaml', { ...env, CP_WORKER_DATABASE_PASSWORD: '' }, 'condpulse-worker'));
  } finally {
    assert.ok(resolve(temporary).startsWith(resolve(tmpdir()) + sep));
    rmSync(temporary, { recursive: true, force: true });
  }
});
