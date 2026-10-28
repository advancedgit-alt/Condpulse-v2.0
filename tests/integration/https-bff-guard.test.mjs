import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { minimalEnvironment } from './https-bff-environment.mjs';

const runner = fileURLToPath(new URL('./https-bff-smoke.mjs', import.meta.url));
const synthetic = { CP_DB_TEST_OK: 'DISPOSABLE', PGHOST: '127.0.0.1', PGPORT: '5432',
  PGDATABASE: 'condpulse_ci', PGUSER: 'postgres', PGPASSWORD: 'condpulse_ci_only',
  PGSERVICE: '', PGSERVICEFILE: '', PGHOSTADDR: '', JAVA_TOOL_OPTIONS: '', JDK_JAVA_OPTIONS: '', _JAVA_OPTIONS: '' };

for (const [label, override, code] of [
  ['missing disposable acknowledgement', { CP_DB_TEST_OK: '' }, 'DISPOSABLE_ACK_REQUIRED'],
  ['remote database host', { PGHOST: 'db.remote.invalid' }, 'LOCAL_DB_HOST_REQUIRED'],
  ['another database', { PGDATABASE: 'postgres' }, 'DISPOSABLE_DATABASE_REQUIRED'],
  ['nonfixture administrator', { PGPASSWORD: 'untrusted-sensitive-sentinel' }, 'SYNTHETIC_ADMIN_REQUIRED'],
  ['libpq routing override', { PGHOSTADDR: '203.0.113.1' }, 'ALTERNATE_DB_ROUTING_FORBIDDEN'],
  ['JVM routing override', { JAVA_TOOL_OPTIONS: '-Dhttps.proxyHost=remote.invalid' }, 'EXTERNAL_JVM_OPTIONS_FORBIDDEN'],
]) {
  test(`HTTPS integration refuses ${label} before any side effect`, () => {
    const result = spawnSync(process.execPath, [runner], { env: { ...process.env, ...synthetic, ...override },
      encoding: 'utf8', timeout: 5000, windowsHide: true });
    assert.equal(result.status, 1);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr.trim(), `HTTPS BFF integration failed at guard: ${code}`);
  });
}

test('child tools cannot inherit Spring datasource/configuration, libpq, proxy or JVM overrides', () => {
  const source = { PATH: '/synthetic/bin', HOME: '/synthetic/home', JAVA_HOME: '/synthetic/java',
    SPRING_APPLICATION_JSON: '{"spring":{"datasource":{"url":"jdbc:postgresql://remote.invalid/prod"}}}',
    SPRING_DATASOURCE_URL: 'jdbc:postgresql://remote.invalid/prod', SPRING_CONFIG_IMPORT: 'https://remote.invalid/config',
    SPRING_CONFIG_ADDITIONAL_LOCATION: '/untrusted/', DATABASE_URL: 'jdbc:postgresql://remote.invalid/prod',
    JAVA_TOOL_OPTIONS: '-Dhttps.proxyHost=remote.invalid', JDK_JAVA_OPTIONS: '-Dhttps.proxyHost=remote.invalid',
    _JAVA_OPTIONS: '-Dhttps.proxyHost=remote.invalid', PGHOST: 'remote.invalid', PGOPTIONS: '-c role=other',
    PGSERVICE: 'remote', PGHOSTADDR: '203.0.113.1', HTTPS_PROXY: 'https://remote.invalid', LD_PRELOAD: '/untrusted.so' };
  assert.deepEqual(minimalEnvironment(source), { PATH: source.PATH, JAVA_HOME: source.JAVA_HOME, HOME: source.HOME });
});
