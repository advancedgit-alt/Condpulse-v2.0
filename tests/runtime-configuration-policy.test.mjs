import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {join, resolve, delimiter} from 'node:path';
import {validateRequest, runtimeInvocation, launchRuntime} from '../infra/scripts/runtime-configuration/launch.mjs';

const workflow = readFileSync(new URL('../.github/workflows/runtime-configuration.yml', import.meta.url), 'utf8');
const sha = 'a'.repeat(40), fingerprint = 'b'.repeat(64), project = 'rpnrqfrfwywglvyykgrg';
const generation = '11000000-0000-4000-8000-000000000001';
const request = () => ({GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_REPOSITORY: 'advancedgit-alt/Condpulse-v2.0',
  GITHUB_REF: 'refs/heads/ccr-5e725112-3x8hyg', GITHUB_SHA: sha, CP_RUNTIME_EXPECTED_COMMIT: sha,
  CP_RUNTIME_OPERATION: 'inspect', CP_RUNTIME_GENERATION: generation, CP_RUNTIME_ACK: '', MAINTENANCE_CONFIRMED: 'false'});
const apply = () => ({...request(), CP_RUNTIME_OPERATION: 'apply', MAINTENANCE_CONFIRMED: 'true',
  CP_RUNTIME_ACK: `${project}:runtime:${generation}:${sha}:${fingerprint}`});
const runner = resolve('synthetic-runtime-runner');
const classes = join(runner, 'condpulse-runtime-configuration/classes');
const source = () => ({...request(), PATH: '/synthetic/bin', JAVA_HOME: resolve('synthetic-java'), RUNNER_TEMP: runner,
  DATABASE_PASSWORD: 'SYNTHETIC_DATABASE_OPERATIONS_PASSWORD_ONLY', CP_VERCEL_TOKEN: 'SYNTHETIC_VERCEL_TOKEN_ONLY_1234567890'});

test('inspect is the default and apply requires an independently supplied exact acknowledgement', () => {
  assert.equal(validateRequest(request()).operation, 'inspect');
  const missing = request(); delete missing.CP_RUNTIME_OPERATION;
  assert.equal(validateRequest(missing).operation, 'inspect');
  assert.doesNotThrow(() => validateRequest(apply()));
  assert.throws(() => validateRequest({...apply(), MAINTENANCE_CONFIRMED: 'false'}), /MAINTENANCE_ACK_REQUIRED/);
  for (const ack of ['', `${project}:runtime:${generation}:${'c'.repeat(40)}:${fingerprint}`,
    `${project}:runtime:22000000-0000-4000-8000-000000000001:${sha}:${fingerprint}`,
    `other:runtime:${generation}:${sha}:${fingerprint}`, `${project}:runtime:${generation}:${sha}:short`])
    assert.throws(() => validateRequest({...apply(), CP_RUNTIME_ACK: ack}), /EXPLICIT_RUNTIME_ACK_REQUIRED/);
  assert.throws(() => validateRequest({...request(), CP_RUNTIME_ACK: apply().CP_RUNTIME_ACK}), /RUNTIME_ACK_NOT_APPLICABLE/);
});

test('event repository branch exact source commit generation and operation are closed to user-selected targets', () => {
  for (const override of [{GITHUB_EVENT_NAME: 'push'}, {GITHUB_EVENT_NAME: 'pull_request'}, {GITHUB_EVENT_NAME: 'workflow_call'},
    {GITHUB_REPOSITORY: 'other/Condpulse-v2.0'}, {GITHUB_REF: 'refs/heads/feature'}, {GITHUB_REF: 'refs/tags/ccr-5e725112-3x8hyg'}])
    assert.throws(() => validateRequest({...request(), ...override}), /REQUEST_CONTEXT_DENIED/);
  for (const value of ['main', 'a'.repeat(7), 'A'.repeat(40), 'c'.repeat(40), '$(touch untrusted)'])
    assert.throws(() => validateRequest({...request(), CP_RUNTIME_EXPECTED_COMMIT: value}), /EXACT_REVIEWED_COMMIT_REQUIRED/);
  for (const value of ['__proto__', 'baseline', 'migrate', 'onboarding', 'deploy', 'apply;echo unsafe', ''])
    assert.throws(() => validateRequest({...request(), CP_RUNTIME_OPERATION: value}), /OPERATION_DENIED/);
  for (const value of ['short', generation.toUpperCase().replace('11000000', 'A1000000'), '00000000-0000-0000-0000-000000000000', `${generation}:extra`])
    assert.throws(() => validateRequest({...request(), CP_RUNTIME_GENERATION: value}), /RUNTIME_GENERATION_REQUIRED/);
});

test('child credentials travel only through a minimal explicit environment with immutable destinations', () => {
  const input = {...source(), SPRING_APPLICATION_JSON: 'untrusted', SPRING_DATASOURCE_URL: 'untrusted', SPRING_CONFIG_IMPORT: 'untrusted',
    JAVA_TOOL_OPTIONS: 'untrusted', JDK_JAVA_OPTIONS: 'untrusted', NODE_OPTIONS: 'untrusted', HTTPS_PROXY: 'untrusted',
    DATABASE_URL: 'untrusted', DATABASE_USER: 'untrusted', CP_BASELINE_PROJECT_REF: 'other', CP_RUNTIME_CA_PATH: 'untrusted',
    GITHUB_TOKEN: 'untrusted', CP_ONBOARDING_AUTH_SECRET_KEY: 'untrusted', CP_MIGRATION_PASSWORD: 'untrusted',
    CP_BFF_ENCRYPTION_KEY: 'untrusted', CP_BFF_RATE_LIMIT_KEY: 'untrusted', CP_SUPABASE_PUBLISHABLE_KEY: 'untrusted'};
  const invocation = runtimeInvocation(input, classes);
  assert.equal(invocation.env.DATABASE_PASSWORD, input.DATABASE_PASSWORD);
  assert.equal(invocation.env.CP_VERCEL_TOKEN, input.CP_VERCEL_TOKEN);
  assert.ok(!invocation.args.join(' ').includes(input.DATABASE_PASSWORD));
  assert.ok(!invocation.args.join(' ').includes(input.CP_VERCEL_TOKEN));
  assert.equal(invocation.env.DATABASE_URL, 'jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt');
  assert.equal(invocation.env.DATABASE_USER, `postgres.${project}`);
  assert.equal(invocation.env.CP_BASELINE_PROJECT_REF, project);
  assert.equal(invocation.env.CP_RUNTIME_CA_PATH, '/run/secrets/db-ca.crt');
  assert.deepEqual(Object.keys(invocation.env).sort(), ['PATH', 'JAVA_HOME', 'DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD',
    'CP_VERCEL_TOKEN', 'CP_RUNTIME_CA_PATH', 'CP_RUNTIME_OPERATION', 'CP_RUNTIME_GENERATION', 'CP_RUNTIME_EXPECTED_COMMIT',
    'CP_RUNTIME_ACK', 'GITHUB_SHA', 'CP_BASELINE_PROJECT_REF'].sort());
  assert.equal(invocation.args.at(-1), 'br.com.condpulse.bootstrap.RuntimeConfiguration');
  assert.equal(invocation.args[invocation.args.indexOf('-cp') + 1], [classes, join(classes, 'BOOT-INF/classes'), join(classes, 'BOOT-INF/lib/*')].join(delimiter));
});

test('classes and invocation cannot be redirected and process failure remains an unknown outcome', () => {
  for (const value of ['relative', resolve('untrusted-classes'), `${classes}${delimiter}untrusted`])
    assert.throws(() => runtimeInvocation(source(), value), /RUNTIME_CLASSES_PATH_DENIED/);
  assert.throws(() => runtimeInvocation({...source(), DATABASE_PASSWORD: ''}, classes), /RUNTIME_LAUNCH_CONFIGURATION_INVALID/);
  assert.throws(() => runtimeInvocation({...source(), CP_VERCEL_TOKEN: 'untrusted\nsecret'}, classes), /RUNTIME_LAUNCH_CONFIGURATION_INVALID/);
  const opaque='SYNTHETIC_OPAQUE_TOKEN_~+/=:_-';
  assert.equal(runtimeInvocation({...source(),CP_VERCEL_TOKEN:opaque},classes).env.CP_VERCEL_TOKEN,opaque);
  for(const token of ['short','SYNTHETIC_TOKEN_\rINJECTED','SYNTHETIC_TOKEN_\u0000INJECTED','SYNTHETIC_TOKEN_é_UNSUPPORTED'])
    assert.throws(()=>runtimeInvocation({...source(),CP_VERCEL_TOKEN:token},classes),/RUNTIME_LAUNCH_CONFIGURATION_INVALID/);
  assert.throws(() => launchRuntime(source(), ['--classes', classes, '--apply']), /RUNTIME_ARGUMENTS_DENIED/);
  let calls = 0;
  const fake = (executable, args, options) => { calls++; assert.equal(options.env.CP_RUNTIME_OPERATION, 'inspect'); return {status: 0}; };
  assert.deepEqual(launchRuntime(source(), ['--classes', classes], fake), {exitCode: 0, unknown: false});
  assert.equal(calls, 1);
  assert.deepEqual(launchRuntime(source(), ['--classes', classes], () => ({status: null, signal: 'SIGTERM'})), {exitCode: 1, unknown: true});
});

test('workflow is manual and shares the migration lock and protected Environment without secret access during build', () => {
  assert.match(workflow, /^on:\n  workflow_dispatch:/m);
  assert.doesNotMatch(workflow, /^  (?:push|pull_request|pull_request_target|schedule|workflow_call):/m);
  assert.match(workflow, /permissions:\n  contents: read\n/);
  assert.doesNotMatch(workflow, /(?:contents|packages|id-token): write/);
  assert.match(workflow, /group: condpulse-database-operations-rpnrqfrfwywglvyykgrg\n  cancel-in-progress: false/);
  assert.match(workflow, /needs: gate/); assert.match(workflow, /name: condpulse-database-operations/);
  assert.match(workflow, /ref: \$\{\{ github.sha \}\}/); assert.match(workflow, /persist-credentials: false/);
  assert.equal((workflow.match(/secrets\./g) ?? []).length, 2);
  const operational = workflow.indexOf('name: Execute only the manually selected runtime operation');
  assert.ok(workflow.indexOf('secrets.CP_DATABASE_OPERATIONS_PASSWORD') > operational);
  assert.ok(workflow.indexOf('secrets.CP_VERCEL_TOKEN') > operational);
  assert.ok(workflow.indexOf('javac -cp') < operational);
  assert.ok(workflow.indexOf('sha256sum apps/backend/target') < operational);
  assert.match(workflow, /import \{validateRequest\} from '\.\/infra\/scripts\/runtime-configuration\/launch\.mjs'/);
  assert.doesNotMatch(workflow, /upload-artifact|actions\/cache|set -x|vercel deploy|vercel redeploy|GITHUB_OUTPUT|GITHUB_ENV|CP_BFF_ENCRYPTION_KEY|CP_BFF_RATE_LIMIT_KEY/);
  for (const action of workflow.matchAll(/uses: ([^\n]+)/g)) assert.match(action[1], /@[a-f0-9]{40}$/);
});

test('CA download and cleanup retain verified fixed sources without writing credentials to files', () => {
  assert.match(workflow, /curl --proto '=https' --tlsv1.2 --fail/);
  assert.match(workflow, /--connect-timeout 5 --max-time 20 --max-filesize 65536/);
  assert.match(workflow, /https:\/\/supabase-downloads\.s3-ap-southeast-1\.amazonaws\.com\/prod\/ssl\/prod-ca-2021\.crt/);
  assert.match(workflow, /openssl verify -check_ss_sig -CAfile/);
  assert.match(workflow, /sudo install -m 0444 "\$ca" \/run\/secrets\/db-ca.crt/);
  assert.match(workflow, /lstatSync\(target\)\.isSymbolicLink\(\)/);
  assert.match(workflow, /dirname\(actual\)!==base/);
  assert.doesNotMatch(workflow, /curl .*--insecure|NODE_TLS_REJECT_UNAUTHORIZED|sslmode=require|sslmode=disable/);
});
