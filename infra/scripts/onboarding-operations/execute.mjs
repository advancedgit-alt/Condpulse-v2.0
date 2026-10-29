import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {closeSync, constants, lstatSync, mkdtempSync, openSync, realpathSync, rmdirSync, unlinkSync, writeFileSync} from 'node:fs';
import {delimiter, isAbsolute, join, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

export const TARGET = Object.freeze({project: 'rpnrqfrfwywglvyykgrg', repository: 'advancedgit-alt/Condpulse-v2.0',
  ref: 'refs/heads/ccr-5e725112-3x8hyg'});
const FOLDER = 'condpulse-onboarding-operations';
const MAXIMUM_MANIFEST = 32768;
const MAIN = 'br.com.condpulse.onboarding.OperationalOnboarding';
const JAVA_CODES = new Set(['ONBOARDING_CONFIGURATION_OR_MANIFEST_INVALID', 'ONBOARDING_COMMAND_INVALID',
  'ONBOARDING_ACK_REQUIRED', 'ONBOARDING_BUSY', 'ONBOARDING_OPERATOR_NOT_AUTHORIZED',
  'ONBOARDING_AUTH_IDENTITY_INELIGIBLE', 'ONBOARDING_AUTH_UNAVAILABLE', 'ONBOARDING_AUTH_PROOF_EXPIRED',
  'ONBOARDING_MEMBERSHIP_EXPIRED', 'ONBOARDING_OPERATION_REUSED', 'ONBOARDING_ACCOUNT_NOT_ACTIVE',
  'ONBOARDING_TENANT_ALREADY_EXISTS', 'ONBOARDING_STATE_DIVERGED', 'ONBOARDING_AUDIT_NOT_WRITTEN',
  'ONBOARDING_OPERATION_FAILED', 'ONBOARDING_DATABASE_OPERATION_FAILED']);
const ERROR_CODES = new Set(['ONBOARDING_REQUEST_CONTEXT_DENIED', 'ONBOARDING_REVIEWED_SOURCE_REQUIRED',
  'ONBOARDING_MANIFEST_HASH_REQUIRED', 'ONBOARDING_OPERATION_DENIED', 'ONBOARDING_MAINTENANCE_REQUIRED',
  'ONBOARDING_ACK_REQUIRED', 'ONBOARDING_ACK_NOT_APPLICABLE', 'ONBOARDING_PRIVATE_CONFIGURATION_INVALID',
  'ONBOARDING_MANIFEST_ENCODING_INVALID', 'ONBOARDING_MANIFEST_HASH_MISMATCH', 'ONBOARDING_RUNNER_PATH_DENIED']);

/** Pre-Environment gate. This function does not read credentials, the private manifest, or files. */
export function validateRequest(source) {
  if (source.GITHUB_EVENT_NAME !== 'workflow_dispatch' || source.GITHUB_REPOSITORY !== TARGET.repository
      || source.GITHUB_REF !== TARGET.ref) throw Error('ONBOARDING_REQUEST_CONTEXT_DENIED');
  const commit = source.CP_ONBOARDING_EXPECTED_COMMIT;
  if (!/^[a-f0-9]{40}$/.test(commit ?? '') || commit !== source.GITHUB_SHA
      || commit !== source.CP_ONBOARDING_SOURCE_COMMIT) throw Error('ONBOARDING_REVIEWED_SOURCE_REQUIRED');
  const manifestHash = source.CP_ONBOARDING_MANIFEST_SHA256;
  if (!/^[a-f0-9]{64}$/.test(manifestHash ?? '')) throw Error('ONBOARDING_MANIFEST_HASH_REQUIRED');
  const operation = source.CP_ONBOARDING_OPERATION;
  if (!['inspect', 'apply'].includes(operation)) throw Error('ONBOARDING_OPERATION_DENIED');
  const ack = source.CP_ONBOARDING_ACK ?? '';
  let fingerprint = '';
  if (operation === 'apply') {
    if (source.MAINTENANCE_CONFIRMED !== 'true') throw Error('ONBOARDING_MAINTENANCE_REQUIRED');
    const prefix = `${TARGET.project}:${commit}:${manifestHash}:`;
    if (typeof ack !== 'string' || !ack.startsWith(prefix) || !/^[a-f0-9]{64}$/.test(ack.slice(prefix.length)))
      throw Error('ONBOARDING_ACK_REQUIRED');
    fingerprint = ack.slice(prefix.length);
  } else if (ack !== '') throw Error('ONBOARDING_ACK_NOT_APPLICABLE');
  return Object.freeze({operation, commit, manifestHash, fingerprint,
    javaAck: operation === 'apply' ? `${TARGET.project}:${fingerprint}` : ''});
}

function safePath(value) {
  return typeof value === 'string' && isAbsolute(value) && !/[\x00-\x1f\x7f]/.test(value) && !value.includes(delimiter);
}
function configuration(source, request) {
  const user = source.CP_ONBOARDING_DATABASE_USER, password = source.CP_ONBOARDING_DATABASE_PASSWORD;
  const secret = source.CP_ONBOARDING_AUTH_SECRET_KEY;
  if (typeof user !== 'string' || !new RegExp(`^cp_onboarding_[a-z0-9_]{1,40}\\.${TARGET.project}$`).test(user)
      || typeof password !== 'string' || password.length < 16 || password.length > 4096 || /[\x00-\x1f\x7f]/.test(password)
      || typeof secret !== 'string' || !/^sb_secret_[A-Za-z0-9_-]{24,4096}$/.test(secret))
    throw Error('ONBOARDING_PRIVATE_CONFIGURATION_INVALID');
  if (!safePath(source.RUNNER_TEMP) || !safePath(source.JAVA_HOME)) throw Error('ONBOARDING_RUNNER_PATH_DENIED');
  const encoded = source.CP_ONBOARDING_MANIFEST_BASE64;
  if (typeof encoded !== 'string' || encoded.length < 4 || encoded.length > 43692 || encoded.length % 4 !== 0
      || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded))
    throw Error('ONBOARDING_MANIFEST_ENCODING_INVALID');
  const bytes = Buffer.from(encoded, 'base64');
  try {
    if (bytes.length === 0 || bytes.length > MAXIMUM_MANIFEST || bytes.toString('base64') !== encoded)
      throw Error('ONBOARDING_MANIFEST_ENCODING_INVALID');
    try { new TextDecoder('utf-8', {fatal: true}).decode(bytes); }
    catch { throw Error('ONBOARDING_MANIFEST_ENCODING_INVALID'); }
    if (createHash('sha256').update(bytes).digest('hex') !== request.manifestHash)
      throw Error('ONBOARDING_MANIFEST_HASH_MISMATCH');
    return {user, password, secret, bytes};
  } catch (error) { bytes.fill(0); throw error; }
}

function directory(path) {
  const info = lstatSync(path);
  if (info.isSymbolicLink() || !info.isDirectory() || realpathSync(path) !== resolve(path))
    throw Error('ONBOARDING_RUNNER_PATH_DENIED');
  return path;
}
function blank(code, attempted = false) {
  return {eligible: false, applied: false, alreadyApplied: false, fingerprint: '',
    writeOutcome: attempted ? 'UNKNOWN' : 'NOT_ATTEMPTED', counts: null, codes: [], code, exitCode: 1};
}
function exactKeys(value, keys) {
  return value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
}
function normalize(result, request) {
  const operation = request.operation;
  if (!result || result.error || result.signal || ![0, 1].includes(result.status)
      || typeof result.stdout !== 'string' || Buffer.byteLength(result.stdout, 'utf8') > 16384
      || (typeof result.stderr !== 'string' && result.stderr !== undefined) || (result.stderr ?? '') !== '')
    throw Error('untrusted');
  const value = JSON.parse(result.stdout);
  // The reviewed Java writer emits compact JSON with ASCII field names. Requiring its
  // canonical serialization rejects duplicate keys, including JSON-escaped aliases.
  if (result.stdout.trim() !== JSON.stringify(value)) throw Error('untrusted');
  if (!exactKeys(value, ['eligible', 'applied', 'alreadyApplied', 'fingerprint', 'writeOutcome', 'counts', 'codes'])
      || !['eligible', 'applied', 'alreadyApplied'].every(key => typeof value[key] === 'boolean')
      || typeof value.fingerprint !== 'string' || !/^(?:[a-f0-9]{64})?$/.test(value.fingerprint)
      || !['NOT_ATTEMPTED', 'COMMITTED', 'UNKNOWN'].includes(value.writeOutcome)
      || !Array.isArray(value.codes) || value.codes.length > 1 || !value.codes.every(code => JAVA_CODES.has(code)))
    throw Error('untrusted');
  const success = value.codes.length === 0;
  if (success) {
    const counts = value.counts;
    if (result.status !== 0 || !value.eligible || !/^[a-f0-9]{64}$/.test(value.fingerprint)
        || !exactKeys(counts, ['accountsToCreate', 'units', 'memberships', 'amenities'])
        || !Object.values(counts).every(Number.isSafeInteger)
        || counts.accountsToCreate < 0 || counts.accountsToCreate > 250
        || counts.units < 1 || counts.units > 250 || counts.memberships < 2 || counts.memberships > 500
        || counts.amenities < 1 || counts.amenities > 100
        || (operation === 'inspect' && (value.applied || value.writeOutcome !== 'NOT_ATTEMPTED'))
        || (operation === 'apply' && (!value.applied || value.writeOutcome !== 'COMMITTED'
          || value.fingerprint !== request.fingerprint))) throw Error('untrusted');
  } else if (result.status !== 1 || value.eligible || value.applied || value.alreadyApplied || value.counts !== null
      || value.writeOutcome === 'COMMITTED' || (operation === 'inspect' && value.writeOutcome !== 'NOT_ATTEMPTED'))
    throw Error('untrusted');
  // Reconstruct every field. Raw stdout/stderr, thrown messages and provider data are never re-emitted.
  return {eligible: value.eligible, applied: value.applied, alreadyApplied: value.alreadyApplied,
    fingerprint: value.fingerprint, writeOutcome: value.writeOutcome,
    counts: value.counts === null ? null : {accountsToCreate: value.counts.accountsToCreate,
      units: value.counts.units, memberships: value.counts.memberships, amenities: value.counts.amenities},
    codes: [...value.codes], code: '', exitCode: success ? 0 : 1};
}

/** Fixed target, private ephemeral input, allowlisted child environment and bounded sanitized result. */
export function execute(source, {spawn = spawnSync} = {}) {
  let outcome, request, config, privateDirectory, manifestPath, attempted = false;
  try {
    request = validateRequest(source);
    config = configuration(source, request);
    const temp = directory(resolve(source.RUNNER_TEMP));
    const root = directory(join(temp, FOLDER));
    const classes = directory(join(root, 'classes'));
    directory(join(classes, 'BOOT-INF'));
    privateDirectory = mkdtempSync(join(root, 'private-'), {encoding: 'utf8'});
    // mkdir mode is affected by umask; explicitly reject accidental permissive Unix modes.
    if (process.platform !== 'win32' && (lstatSync(privateDirectory).mode & 0o777) !== 0o700)
      throw Error('ONBOARDING_RUNNER_PATH_DENIED');
    manifestPath = join(privateDirectory, 'onboarding.json');
    let fd;
    try {
      fd = openSync(manifestPath, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | (constants.O_NOFOLLOW ?? 0), 0o600);
      writeFileSync(fd, config.bytes);
    } finally { if (fd !== undefined) closeSync(fd); config.bytes.fill(0); }
    const env = {JAVA_HOME: source.JAVA_HOME,
      DATABASE_URL: `jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt`,
      DATABASE_USER: config.user, DATABASE_PASSWORD: config.password, CP_ONBOARDING_AUTH_SECRET_KEY: config.secret,
      CP_ONBOARDING_PROJECT_REF: TARGET.project, CP_ONBOARDING_ACK: request.javaAck};
    const args = ['-Xmx768m', `-Dlogback.configurationFile=${join(classes, 'BOOT-INF/classes/bootstrap/logback-baseline.xml')}`,
      '-cp', [join(classes, 'BOOT-INF/classes'), join(classes, 'BOOT-INF/lib/*')].join(delimiter), MAIN, request.operation, manifestPath];
    attempted = request.operation === 'apply';
    const result = spawn(join(source.JAVA_HOME, 'bin', 'java'), args, {env, stdio: ['ignore', 'pipe', 'pipe'],
      encoding: 'utf8', timeout: 10 * 60 * 1000, maxBuffer: 16384, killSignal: 'SIGKILL', windowsHide: true});
    try { outcome = normalize(result, request); }
    catch { outcome = blank('ONBOARDING_PROCESS_FAILED_OR_RESULT_UNKNOWN', attempted); }
  } catch (error) {
    outcome = blank(ERROR_CODES.has(error?.message) ? error.message : 'ONBOARDING_PROCESS_FAILED_OR_RESULT_UNKNOWN', attempted);
  } finally {
    config?.bytes.fill(0);
    if (privateDirectory) {
      try {
        directory(privateDirectory);
        if (manifestPath) {
          try { unlinkSync(manifestPath); } catch (error) { if (error?.code !== 'ENOENT') throw error; }
        }
        rmdirSync(privateDirectory);
      } catch {
        // A known COMMITTED result remains known even if local cleanup fails; promotion still stops.
        outcome = {...(outcome ?? blank('ONBOARDING_PRIVATE_CLEANUP_FAILED', attempted)),
          code: 'ONBOARDING_PRIVATE_CLEANUP_FAILED', exitCode: 1};
      }
    }
  }
  return outcome;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const result = process.argv.length === 2 ? execute(process.env) : blank('ONBOARDING_ARGUMENTS_DENIED');
  const {exitCode, ...report} = result;
  console.log(JSON.stringify(report));
  process.exitCode = exitCode;
}
