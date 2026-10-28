import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateRelease } from '../infra/validate-release.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

test('Compose real separa runtime e DDL e passa preflight sem daemon ou conexão remota', () => {
  const version = execFileSync('docker', ['compose', 'version', '--short'], {
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
  }).trim().replace(/^v/, '');
  assert.equal(version, '5.2.0', 'Preflight exige a versao revisada Compose5.2.0');
  const temporary = mkdtempSync(join(tmpdir(), 'condpulse-compose-'));
  try {
    const emptyEnv = join(temporary, 'empty.env');
    const ca = join(temporary, 'db-ca.crt');
    writeFileSync(emptyEnv, '');
    writeFileSync(ca, 'SYNTHETIC CA FILE FOR CONFIG PARSING ONLY\n');
    // Não há conexão, pull ou credencial real neste teste de renderização.
    const jdbc = 'jdbc:postgresql://db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432/postgres'
      + '?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt';
    const env = {
      ...process.env,
      CP_API_IMAGE: 'ghcr.io/condpulse/api@sha256:' + 'a'.repeat(64),
      CP_WEB_IMAGE: 'ghcr.io/condpulse/web@sha256:' + 'b'.repeat(64),
      CP_BASELINE_IMAGE: 'ghcr.io/condpulse/baseline@sha256:' + 'c'.repeat(64),
      CP_BASELINE_ACK: '',
      CP_API_DATABASE_URL: jdbc,
      CP_API_DATABASE_USER: 'cp_runtime_ci',
      CP_API_DATABASE_PASSWORD: 'ConfigTestRuntime_42!',
      CP_MIGRATION_DATABASE_URL: jdbc,
      CP_MIGRATION_DATABASE_USER: 'postgres',
      CP_MIGRATION_DATABASE_PASSWORD: 'DDL_SENTINEL_CONFIG_TEST_ONLY',
      CP_DB_CA_FILE: ca,
      CP_SUPABASE_URL: 'https://aaaaaaaaaaaaaaaaaaaa.supabase.co',
      CP_BFF_ORIGIN: 'https://condpulse.example.org',
      CP_PROXY_NETWORK: 'condpulse_ci_proxy',
    };
    const render = (file, environment = env) => JSON.parse(execFileSync('docker', [
      'compose', '--project-name', file.includes('.app.') ? 'condpulse-app' : file.includes('.baseline.') ? 'condpulse-baseline' : 'condpulse-migrate',
      '--env-file', emptyEnv, '-f', file, 'config', '--format', 'json',
    ], { cwd: root, env: environment, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true }));
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
  } finally {
    assert.ok(resolve(temporary).startsWith(resolve(tmpdir()) + sep));
    rmSync(temporary, { recursive: true, force: true });
  }
});
