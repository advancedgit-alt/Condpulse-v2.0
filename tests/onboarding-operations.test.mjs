import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname, join, resolve} from 'node:path';
import {execute, TARGET, validateRequest} from '../infra/scripts/onboarding-operations/execute.mjs';

const SOURCE = 'a'.repeat(40), FINGERPRINT = 'b'.repeat(64);
const PRIVATE = Buffer.from('{"private":"SYNTHETIC_PRIVATE_MANIFEST_MARKER"}\n', 'utf8');
const PASSWORD = 'SYNTHETIC_ONBOARDING_PASSWORD';
const SECRET = 'sb_secret_SYNTHETIC_ONBOARDING_ADMIN_SECRET';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
function report(operation = 'inspect', extra = {}) {
  return {eligible: true, applied: operation === 'apply', alreadyApplied: false, fingerprint: FINGERPRINT,
    writeOutcome: operation === 'apply' ? 'COMMITTED' : 'NOT_ATTEMPTED',
    counts: {accountsToCreate: 1, units: 1, memberships: 2, amenities: 1}, codes: [], ...extra};
}
function child(value = report(), extra = {}) { return {status: 0, stdout: JSON.stringify(value) + '\n', stderr: '', ...extra}; }
function fixture(t, extra = {}) {
  const base = mkdtempSync(join(tmpdir(), 'condpulse-onboarding-test-'));
  const root = join(base, 'condpulse-onboarding-operations');
  mkdirSync(join(root, 'classes', 'BOOT-INF'), {recursive: true});
  t.after(() => {
    // Only the unique, explicitly created temporary fixture is recursively removed.
    assert.equal(dirname(resolve(base)), resolve(tmpdir()));
    assert.match(base.split(/[\\/]/).at(-1), /^condpulse-onboarding-test-/);
    rmSync(base, {recursive: true, force: true});
  });
  const env = {GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_REPOSITORY: TARGET.repository, GITHUB_REF: TARGET.ref,
    GITHUB_SHA: SOURCE, CP_ONBOARDING_SOURCE_COMMIT: SOURCE, CP_ONBOARDING_EXPECTED_COMMIT: SOURCE,
    CP_ONBOARDING_OPERATION: 'inspect', CP_ONBOARDING_MANIFEST_SHA256: hash(PRIVATE),
    CP_ONBOARDING_MANIFEST_BASE64: PRIVATE.toString('base64'),
    CP_ONBOARDING_DATABASE_USER: `cp_onboarding_fixture.${TARGET.project}`,
    CP_ONBOARDING_DATABASE_PASSWORD: PASSWORD, CP_ONBOARDING_AUTH_SECRET_KEY: SECRET,
    RUNNER_TEMP: base, JAVA_HOME: join(base, 'java-home'), ...extra};
  return {base, root, env};
}
function apply(env) {
  return {...env, CP_ONBOARDING_OPERATION: 'apply', MAINTENANCE_CONFIRMED: 'true',
    CP_ONBOARDING_ACK: `${TARGET.project}:${SOURCE}:${env.CP_ONBOARDING_MANIFEST_SHA256}:${FINGERPRINT}`};
}
function assertClean(fixture) {
  assert.deepEqual(readdirSync(fixture.root).sort(), ['classes']);
}

test('pre-Environment gate reads only the public request and binds the exact reviewed source', t => {
  const f = fixture(t), publicEnv = Object.fromEntries(Object.entries(f.env).filter(([key]) =>
    key !== 'CP_ONBOARDING_MANIFEST_BASE64' && !key.includes('PASSWORD') && !key.includes('SECRET_KEY')));
  for (const key of ['CP_ONBOARDING_MANIFEST_BASE64', 'CP_ONBOARDING_DATABASE_PASSWORD', 'CP_ONBOARDING_AUTH_SECRET_KEY'])
    Object.defineProperty(f.env, key, {get() { throw Error('private field must not be read'); }});
  assert.equal(validateRequest(f.env).operation, 'inspect');
  for (const extra of [{GITHUB_EVENT_NAME: 'push'}, {GITHUB_REPOSITORY: 'other/repo'}, {GITHUB_REF: 'refs/tags/release'},
    {CP_ONBOARDING_EXPECTED_COMMIT: 'A'.repeat(40)}, {GITHUB_SHA: 'c'.repeat(40)}, {CP_ONBOARDING_SOURCE_COMMIT: 'c'.repeat(40)},
    {CP_ONBOARDING_MANIFEST_SHA256: 'not-a-hash'}, {CP_ONBOARDING_OPERATION: 'deploy'}, {CP_ONBOARDING_OPERATION: undefined}])
    assert.throws(() => validateRequest({...publicEnv, ...extra}));
});

test('apply requires maintenance and ACK bound to both source and exact manifest hash', t => {
  const f = fixture(t), approved = apply(f.env);
  const request = validateRequest(approved);
  assert.equal(request.javaAck, `${TARGET.project}:${FINGERPRINT}`);
  for (const extra of [{MAINTENANCE_CONFIRMED: 'false'}, {CP_ONBOARDING_ACK: ''},
    {CP_ONBOARDING_ACK: approved.CP_ONBOARDING_ACK.replace(SOURCE, 'c'.repeat(40))},
    {CP_ONBOARDING_ACK: approved.CP_ONBOARDING_ACK.replace(hash(PRIVATE), 'c'.repeat(64))},
    {CP_ONBOARDING_ACK: approved.CP_ONBOARDING_ACK + '\n'}])
    assert.throws(() => validateRequest({...approved, ...extra}));
  assert.throws(() => validateRequest({...f.env, CP_ONBOARDING_ACK: approved.CP_ONBOARDING_ACK}), /ACK_NOT_APPLICABLE/);
});

test('inspect writes exact private bytes, uses exclusive private files, and cleans them after the child', t => {
  const f = fixture(t); let observed;
  const result = execute(f.env, {spawn(executable, args, options) {
    const path = args.at(-1); observed = path;
    assert.deepEqual(readFileSync(path), PRIVATE);
    assert.equal(lstatSync(path).isSymbolicLink(), false);
    if (process.platform !== 'win32') {
      assert.equal(lstatSync(path).mode & 0o777, 0o600);
      assert.equal(lstatSync(dirname(path)).mode & 0o777, 0o700);
    }
    assert.equal(args.at(-2), 'inspect');
    assert.equal(executable, join(f.env.JAVA_HOME, 'bin', 'java'));
    assert.deepEqual(options.stdio, ['ignore', 'pipe', 'pipe']);
    assert.equal(options.maxBuffer, 16384);
    assert.equal(options.killSignal, 'SIGKILL');
    return child();
  }});
  assert.equal(result.exitCode, 0); assert.equal(result.writeOutcome, 'NOT_ATTEMPTED');
  assert.equal(existsSync(observed), false); assertClean(f);
});

test('child receives only restricted operational credentials, fixed JDBC target and translated ACK', t => {
  const f = fixture(t); const env = apply({...f.env, DATABASE_PASSWORD: 'OWNER_PASSWORD_MUST_NOT_PASS',
    CP_VERCEL_TOKEN: 'VERCEL_TOKEN_MUST_NOT_PASS', JAVA_TOOL_OPTIONS: '-javaagent:poison', JDK_JAVA_OPTIONS: 'poison',
    NODE_OPTIONS: '--inspect', HTTPS_PROXY: 'https://poison', SPRING_DATASOURCE_URL: 'poison', PATH: 'poison'});
  const result = execute(env, {spawn(executable, args, options) {
    assert.deepEqual(Object.keys(options.env).sort(), ['CP_ONBOARDING_ACK', 'CP_ONBOARDING_AUTH_SECRET_KEY',
      'CP_ONBOARDING_PROJECT_REF', 'DATABASE_PASSWORD', 'DATABASE_URL', 'DATABASE_USER', 'JAVA_HOME'].sort());
    assert.equal(options.env.DATABASE_USER, `cp_onboarding_fixture.${TARGET.project}`);
    assert.equal(options.env.DATABASE_PASSWORD, PASSWORD);
    assert.equal(options.env.CP_ONBOARDING_AUTH_SECRET_KEY, SECRET);
    assert.equal(options.env.CP_ONBOARDING_ACK, `${TARGET.project}:${FINGERPRINT}`);
    assert.equal(options.env.DATABASE_URL, `jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt`);
    for (const sensitive of [PASSWORD, SECRET, PRIVATE.toString('utf8')]) assert.equal(args.join(' ').includes(sensitive), false);
    return child(report('apply'));
  }});
  assert.equal(result.exitCode, 0); assert.equal(result.applied, true); assert.equal(result.writeOutcome, 'COMMITTED');
  assertClean(f);
});

test('invalid credentials and request are rejected before writing private files or spawning Java', t => {
  const f = fixture(t); let calls = 0;
  for (const extra of [{CP_ONBOARDING_DATABASE_USER: `postgres.${TARGET.project}`},
    {CP_ONBOARDING_DATABASE_USER: 'cp_onboarding_fixture.otherproject'},
    {CP_ONBOARDING_DATABASE_PASSWORD: 'short'}, {CP_ONBOARDING_DATABASE_PASSWORD: PASSWORD + '\n'},
    {CP_ONBOARDING_AUTH_SECRET_KEY: 'sb_publishable_NOT_AN_ADMIN_KEY'}, {JAVA_HOME: 'relative/java'},
    {CP_ONBOARDING_SOURCE_COMMIT: 'c'.repeat(40)}]) {
    const result = execute({...f.env, ...extra}, {spawn() { calls++; return child(); }});
    assert.equal(result.exitCode, 1); assert.equal(result.writeOutcome, 'NOT_ATTEMPTED'); assertClean(f);
  }
  assert.equal(calls, 0);
});

test('manifest encoding is canonical, bounded UTF8 Base64 and its exact bytes must match the reviewed hash', t => {
  const f = fixture(t); let calls = 0;
  const encodings = ['', 'YR==', 'e30=\n', ' e30=', '/w==', Buffer.alloc(32769, 97).toString('base64')];
  for (const encoded of encodings) {
    const result = execute({...f.env, CP_ONBOARDING_MANIFEST_BASE64: encoded}, {spawn() { calls++; }});
    assert.equal(result.code, 'ONBOARDING_MANIFEST_ENCODING_INVALID'); assertClean(f);
  }
  const altered = Buffer.from(PRIVATE.toString('utf8').trim());
  const result = execute({...f.env, CP_ONBOARDING_MANIFEST_BASE64: altered.toString('base64')}, {spawn() { calls++; }});
  assert.equal(result.code, 'ONBOARDING_MANIFEST_HASH_MISMATCH'); assert.equal(calls, 0); assertClean(f);
});

test('a symlinked classes directory is rejected without writing private input or following its target',
  {skip: process.platform === 'win32' ? 'Windows sandbox denies symlink creation; required on Linux CI' : false}, t => {
  const f = fixture(t), classes = join(f.root, 'classes'), elsewhere = join(f.base, 'elsewhere');
  rmSync(classes, {recursive: true}); mkdirSync(elsewhere);
  symlinkSync(elsewhere, classes, 'junction');
  let calls = 0;
  const result = execute(f.env, {spawn() { calls++; }});
  assert.equal(result.code, 'ONBOARDING_RUNNER_PATH_DENIED'); assert.equal(calls, 0);
  assert.deepEqual(readdirSync(elsewhere), []); assertClean(f);
});

test('raw child stderr and unknown stdout fields never reach the sanitized result', t => {
  const f = fixture(t), poison = `${SECRET}:${PASSWORD}:SYNTHETIC_PRIVATE_MANIFEST_MARKER`;
  for (const response of [child(report(), {stderr: poison}), child({...report(), privateData: poison}),
    child(report(), {stdout: poison}), child(report(), {stdout: JSON.stringify(report()) + '\n' + poison})]) {
    const result = execute(f.env, {spawn() { return response; }});
    assert.equal(result.exitCode, 1); assert.equal(result.code, 'ONBOARDING_PROCESS_FAILED_OR_RESULT_UNKNOWN');
    for (const privateValue of [SECRET, PASSWORD, 'SYNTHETIC_PRIVATE_MANIFEST_MARKER']) assert.equal(JSON.stringify(result).includes(privateValue), false);
    assertClean(f);
  }
});

test('duplicate report fields, unknown codes, invalid counts and contradictory outcomes fail closed', t => {
  const f = fixture(t);
  const invalid = [child(report(), {stdout: JSON.stringify(report()).replace('"eligible":true', '"eligible":false,"eligible":true')}),
    child(report(), {stdout: JSON.stringify(report()).replace('"units":1', '"units":0,"units":1')}),
    child(report('inspect', {codes: ['PRIVATE_CODE_MUST_NOT_APPEAR']})),
    child(report('inspect', {counts: {...report().counts, units: 251}})),
    child(report('inspect', {counts: {...report().counts, memberships: 1}})),
    child(report('inspect', {counts: {...report().counts, extra: 'private'}})),
    child(report('inspect', {applied: true, writeOutcome: 'COMMITTED'})),
    child(report('inspect', {eligible: false})), child(report(), {status: 1})];
  for (const response of invalid) {
    const result = execute(f.env, {spawn() { return response; }});
    assert.equal(result.exitCode, 1); assert.equal(result.code, 'ONBOARDING_PROCESS_FAILED_OR_RESULT_UNKNOWN'); assertClean(f);
  }
});

test('apply success must match the reviewed Java fingerprint', t => {
  const f = fixture(t);
  const result = execute(apply(f.env), {spawn() { return child(report('apply', {fingerprint: 'c'.repeat(64)})); }});
  assert.equal(result.exitCode, 1); assert.equal(result.writeOutcome, 'UNKNOWN'); assertClean(f);
});

test('escaped duplicate keys and noncanonical serializations cannot masquerade as a Java report', t => {
  const f = fixture(t);
  const valid = JSON.stringify(report());
  for (const stdout of [
    valid.replace('"eligible":true', '"eligible":false,"elig\\u0069ble":true'),
    valid.replace('"units":1', '"units":0,"un\\u0069ts":1'),
    valid.replace('"eligible":true', '"elig\\u0069ble":true'),
    valid.replace('"eligible":true', '"eligible": true'),
  ]) {
    const result = execute(f.env, {spawn() { return child(report(), {stdout}); }});
    assert.equal(result.exitCode, 1); assert.equal(result.code, 'ONBOARDING_PROCESS_FAILED_OR_RESULT_UNKNOWN');
    assert.equal(result.writeOutcome, 'NOT_ATTEMPTED'); assertClean(f);
  }
});

test('valid controlled Java failures retain NOT_ATTEMPTED or UNKNOWN without rewriting their meaning', t => {
  const f = fixture(t);
  for (const state of ['NOT_ATTEMPTED', 'UNKNOWN']) {
    const failure = {eligible: false, applied: false, alreadyApplied: false, fingerprint: FINGERPRINT,
      writeOutcome: state, counts: null, codes: ['ONBOARDING_DATABASE_OPERATION_FAILED']};
    const result = execute(apply(f.env), {spawn() { return child(failure, {status: 1}); }});
    assert.equal(result.writeOutcome, state); assert.equal(result.code, ''); assert.equal(result.exitCode, 1); assertClean(f);
  }
});

test('timeout, signal, buffer failure, nonzero process or spawn error after apply are UNKNOWN', t => {
  const f = fixture(t);
  const invalid = [{status: null, stdout: '', stderr: '', error: Error('PRIVATE_TIMEOUT')},
    {status: null, stdout: '', stderr: '', signal: 'SIGKILL'}, child(report('apply'), {error: Error('PRIVATE_BUFFER')}),
    child(report('apply'), {status: 137}), child(report('apply'), {stdout: 'x'.repeat(16385)})];
  for (const response of invalid) {
    const result = execute(apply(f.env), {spawn() { return response; }});
    assert.equal(result.writeOutcome, 'UNKNOWN'); assert.equal(result.exitCode, 1); assertClean(f);
  }
  const thrown = execute(apply(f.env), {spawn() { throw Error(SECRET); }});
  assert.equal(thrown.writeOutcome, 'UNKNOWN'); assert.equal(JSON.stringify(thrown).includes(SECRET), false); assertClean(f);
});

test('the same process failures during inspect cannot imply a business write', t => {
  const f = fixture(t);
  const result = execute(f.env, {spawn() { return {error: Error('PRIVATE'), status: null, stdout: '', stderr: ''}; }});
  assert.equal(result.writeOutcome, 'NOT_ATTEMPTED'); assert.equal(result.exitCode, 1); assertClean(f);
});

test('cleanup cannot recursively delete unexpected files or erase a known committed result', t => {
  const f = fixture(t); let preserved;
  const result = execute(apply(f.env), {spawn(executable, args) {
    preserved = join(dirname(args.at(-1)), 'unexpected-file'); writeFileSync(preserved, 'synthetic');
    return child(report('apply'));
  }});
  assert.equal(result.writeOutcome, 'COMMITTED'); assert.equal(result.applied, true);
  assert.equal(result.code, 'ONBOARDING_PRIVATE_CLEANUP_FAILED'); assert.equal(result.exitCode, 1);
  assert.equal(existsSync(preserved), true); assert.equal(existsSync(join(dirname(preserved), 'onboarding.json')), false);
});
