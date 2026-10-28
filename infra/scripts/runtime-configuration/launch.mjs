import {spawnSync} from 'node:child_process';
import {isAbsolute, resolve, join, delimiter} from 'node:path';
import {pathToFileURL} from 'node:url';

const PROJECT = 'rpnrqfrfwywglvyykgrg';
const REPOSITORY = 'advancedgit-alt/Condpulse-v2.0';
const REF = 'refs/heads/ccr-5e725112-3x8hyg';
const ROOT_FOLDER = 'condpulse-runtime-configuration';
const MAIN = 'br.com.condpulse.bootstrap.RuntimeConfiguration';
const CA = '/run/secrets/db-ca.crt';
const CONTEXT_CODES = new Set(['REQUEST_CONTEXT_DENIED', 'EXACT_REVIEWED_COMMIT_REQUIRED',
  'RUNTIME_GENERATION_REQUIRED', 'OPERATION_DENIED', 'MAINTENANCE_ACK_REQUIRED',
  'EXPLICIT_RUNTIME_ACK_REQUIRED', 'RUNTIME_ACK_NOT_APPLICABLE',
  'RUNTIME_OWNER_PASSWORD_INVALID', 'RUNTIME_VERCEL_TOKEN_INVALID', 'RUNTIME_JAVA_HOME_INVALID',
  'RUNTIME_RUNNER_TEMP_INVALID', 'RUNTIME_PATH_INVALID', 'RUNTIME_CLASSES_PATH_DENIED', 'RUNTIME_ARGUMENTS_DENIED']);

/** Pure guard shared by the pre-Environment job and the launcher. It never opens a connection. */
export function validateRequest(source) {
  if (source.GITHUB_EVENT_NAME !== 'workflow_dispatch' || source.GITHUB_REPOSITORY !== REPOSITORY
      || source.GITHUB_REF !== REF) throw Error('REQUEST_CONTEXT_DENIED');
  const commit = source.CP_RUNTIME_EXPECTED_COMMIT;
  if (!/^[a-f0-9]{40}$/.test(commit ?? '') || commit !== source.GITHUB_SHA)
    throw Error('EXACT_REVIEWED_COMMIT_REQUIRED');
  const generation = source.CP_RUNTIME_GENERATION;
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(generation ?? '')
      || generation === '00000000-0000-0000-0000-000000000000') throw Error('RUNTIME_GENERATION_REQUIRED');
  const operation = source.CP_RUNTIME_OPERATION ?? 'inspect';
  if (!['inspect', 'apply'].includes(operation)) throw Error('OPERATION_DENIED');
  const ack = source.CP_RUNTIME_ACK ?? '';
  if (operation === 'apply') {
    if (source.MAINTENANCE_CONFIRMED !== 'true') throw Error('MAINTENANCE_ACK_REQUIRED');
    if (!new RegExp(`^${PROJECT}:runtime:${generation}:${commit}:[a-f0-9]{64}$`).test(ack))
      throw Error('EXPLICIT_RUNTIME_ACK_REQUIRED');
  } else if (ack !== '') throw Error('RUNTIME_ACK_NOT_APPLICABLE');
  return Object.freeze({operation, generation, commit, ack});
}

function ownerPassword(value) {
  return typeof value === 'string' && value.length >= 16 && value.length <= 4096 && !/[\x00-\x1f\x7f]/.test(value);
}
function vercelToken(value) { return typeof value === 'string' && /^[\x21-\x7e]{16,4096}$/.test(value); }

/** Constructs a fixed executable/target and an explicit child environment, without side effects. */
export function runtimeInvocation(source, classes) {
  const request = validateRequest(source);
  if (!ownerPassword(source.DATABASE_PASSWORD)) throw Error('RUNTIME_OWNER_PASSWORD_INVALID');
  if (!vercelToken(source.CP_VERCEL_TOKEN)) throw Error('RUNTIME_VERCEL_TOKEN_INVALID');
  if (typeof source.JAVA_HOME !== 'string' || !isAbsolute(source.JAVA_HOME)) throw Error('RUNTIME_JAVA_HOME_INVALID');
  if (typeof source.RUNNER_TEMP !== 'string' || !isAbsolute(source.RUNNER_TEMP)) throw Error('RUNTIME_RUNNER_TEMP_INVALID');
  if (typeof source.PATH !== 'string' || !source.PATH) throw Error('RUNTIME_PATH_INVALID');
  const expected = join(resolve(source.RUNNER_TEMP), ROOT_FOLDER, 'classes');
  if (typeof classes !== 'string' || !isAbsolute(classes) || resolve(classes) !== expected
      || classes.includes(delimiter) || /[\x00\r\n]/.test(classes)) throw Error('RUNTIME_CLASSES_PATH_DENIED');
  const host = Object.fromEntries(['PATH', 'JAVA_HOME', 'HOME', 'TMPDIR', 'TMP', 'TEMP',
    'SystemRoot', 'COMSPEC', 'WINDIR', 'USERPROFILE'].filter(key => typeof source[key] === 'string')
    .map(key => [key, source[key]]));
  const env = {...host,
    CP_RUNTIME_OPERATION: request.operation, CP_RUNTIME_GENERATION: request.generation,
    CP_RUNTIME_EXPECTED_COMMIT: request.commit, CP_RUNTIME_ACK: request.ack, GITHUB_SHA: request.commit,
    CP_BASELINE_PROJECT_REF: PROJECT,
    DATABASE_URL: `jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=${CA}`,
    DATABASE_USER: `postgres.${PROJECT}`, DATABASE_PASSWORD: source.DATABASE_PASSWORD,
    CP_VERCEL_TOKEN: source.CP_VERCEL_TOKEN, CP_RUNTIME_CA_PATH: CA};
  return {executable: join(source.JAVA_HOME, 'bin', 'java'), args: ['-Xmx768m',
    `-Dlogback.configurationFile=${join(expected, 'BOOT-INF/classes/bootstrap/logback-baseline.xml')}`,
    '-cp', [expected, join(expected, 'BOOT-INF/classes'), join(expected, 'BOOT-INF/lib/*')].join(delimiter), MAIN], env};
}

export function launchRuntime(source, args, spawn = spawnSync) {
  if (!Array.isArray(args) || args.length !== 2 || args[0] !== '--classes') throw Error('RUNTIME_ARGUMENTS_DENIED');
  const invocation = runtimeInvocation(source, args[1]);
  // Only the reviewed Java helper emits sanitized results. Never print invocation/env or error objects.
  const result = spawn(invocation.executable, invocation.args,
    {env: invocation.env, stdio: 'inherit', timeout: 15 * 60 * 1000, windowsHide: true});
  if (result.error || result.signal || result.status === null) return {exitCode: 1, unknown: true};
  return {exitCode: result.status === 0 ? 0 : 1, unknown: false};
}

// Importing this file from the gate or tests is inert.
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const result = launchRuntime(process.env, process.argv.slice(2));
    if (result.unknown) console.error('RUNTIME_PROCESS_FAILED_OR_RESULT_UNKNOWN');
    process.exitCode = result.exitCode;
  } catch (error) {
    console.error(CONTEXT_CODES.has(error?.message) ? error.message : 'RUNTIME_PROCESS_FAILED_OR_RESULT_UNKNOWN');
    process.exitCode = 1;
  }
}
