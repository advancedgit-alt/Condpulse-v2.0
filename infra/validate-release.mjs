import { isAbsolute, resolve, win32 } from 'node:path';
import { fileURLToPath } from 'node:url';

// Validate Compose's normalized JSON, never the text of an env file. Error paths
// are constants: neither values nor attacker-controlled object keys are echoed.
const API_HEALTH = ['CMD', 'java', '-Xmx16m', '-XX:+UseSerialGC', '-cp', '/app/health', 'Healthcheck'];
const WEB_HEALTH = ['CMD', 'wget', '-q', '-O', '/dev/null', 'http://127.0.0.1:8080/healthz'];
const SERVICE_KEYS = new Set(['image', 'user', 'read_only', 'tmpfs', 'cap_drop', 'security_opt', 'mem_limit', 'cpus', 'pids_limit', 'logging', 'restart', 'stop_grace_period', 'environment', 'volumes', 'networks', 'expose', 'healthcheck', 'depends_on', 'init', 'command', 'entrypoint']);
const API_ENV = new Set(['SPRING_PROFILES_ACTIVE', 'DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD', 'DB_POOL_SIZE', 'PORT', 'CP_JWT_ISSUER', 'CP_JWT_JWK_SET_URI', 'CP_BFF_ORIGIN', 'CP_BFF_ENABLED', 'CP_RESERVATIONS_ENABLED', 'JAVA_TOOL_OPTIONS', 'TZ']);
const HOMOLOGATION_ENV = new Set([...API_ENV, 'CP_SUPABASE_PUBLISHABLE_KEY', 'CP_BFF_ENCRYPTION_KEY', 'CP_BFF_RATE_LIMIT_KEY', 'CP_BFF_SESSION_HOURS']);
const WORKER_ENV = new Set(['SPRING_PROFILES_ACTIVE', 'DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD', 'DB_POOL_SIZE', 'CP_WORKER_OUTBOX_ENABLED', 'CP_WORKER_WAITLIST_ENABLED', 'JAVA_TOOL_OPTIONS', 'TZ']);
const MIGRATE_ENV = new Set(['SPRING_PROFILES_ACTIVE', 'DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD', 'DB_POOL_SIZE', 'JAVA_TOOL_OPTIONS', 'TZ']);
const BASELINE_PROJECT = 'rpnrqfrfwywglvyykgrg';
const BASELINE_ENV = new Set(['DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD', 'CP_BASELINE_PROJECT_REF', 'CP_BASELINE_ACK']);
const ONBOARDING_ENV = new Set(['DATABASE_URL', 'DATABASE_USER', 'DATABASE_PASSWORD', 'CP_ONBOARDING_PROJECT_REF', 'CP_ONBOARDING_AUTH_SECRET_KEY', 'CP_ONBOARDING_ACK']);
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const keys = value => record(value) ? Object.keys(value) : [];
const sameKeys = (value, expected) => record(value) && keys(value).sort().join('|') === [...expected].sort().join('|');
const equalArray = (value, expected) => Array.isArray(value) && JSON.stringify(value) === JSON.stringify(expected);
const noKeysOutside = (value, allowed) => record(value) && keys(value).every(key => allowed.has(key));
const absentOrEmpty = value => value === undefined || value === null || (Array.isArray(value) && value.length === 0) || (record(value) && keys(value).length === 0);
const text = value => typeof value === 'string' ? value : '';

function bytes(value) {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? value : NaN;
  const match = text(value).match(/^(\d+)(b|k|kb|m|mb|g|gb)?$/i);
  if (!match) return NaN;
  const scale = { b: 1, k: 1024, kb: 1024, m: 1024 ** 2, mb: 1024 ** 2, g: 1024 ** 3, gb: 1024 ** 3 };
  const result = Number(match[1]) * (scale[(match[2] || 'b').toLowerCase()] || 1);
  return Number.isSafeInteger(result) && result > 0 ? result : NaN;
}

function publicHostname(host) {
  // IPv4/IPv6 literals and local/reserved names are unsuitable for verify-full
  // in this managed Supabase release. No DNS/network access occurs here.
  return /^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}$/i.test(host)
    && !/(?:^|\.)(?:localhost|local|invalid|internal)$/i.test(host);
}

function databaseUrl(value) {
  if (typeof value !== 'string' || !value.startsWith('jdbc:postgresql://') || /[\s\\]/.test(value)) return false;
  try {
    const url = new URL(value.slice(5));
    if (url.protocol !== 'postgresql:' || !publicHostname(url.hostname) || url.username || url.password || url.hash) return false;
    // Pacote atual admite somente conexao direta ou pooler de sessao.
    if (url.port && url.port !== '5432') return false;
    if (!/^\/[a-zA-Z0-9_-]+$/.test(url.pathname)) return false;
    const entries = [...url.searchParams];
    return entries.length === 2
      && url.searchParams.getAll('sslmode').length === 1
      && url.searchParams.get('sslmode') === 'verify-full'
      && url.searchParams.getAll('sslrootcert').length === 1
      && url.searchParams.get('sslrootcert') === '/run/secrets/db-ca.crt';
  } catch { return false; }
}

function databaseProject(value, username) {
  if (!databaseUrl(value)) return null;
  const url = new URL(value.slice(5));
  const direct = url.hostname.match(/^db\.([a-z0-9]{20})\.supabase\.co$/);
  if (direct) return username.includes('.') ? null : direct[1];
  if (!/^aws-\d+-sa-east-1\.pooler\.supabase\.com$/.test(url.hostname)) return null;
  return username.match(/\.([a-z0-9]{20})$/)?.[1] ?? null;
}

function secureOrigin(value) {
  try {
    const url = new URL(value);
    return typeof value === 'string' && value === url.origin && url.protocol === 'https:'
      && !url.username && !url.password && publicHostname(url.hostname);
  } catch { return false; }
}

function secretConfigured(value) {
  return typeof value === 'string' && value.length >= 16 && !/[\r\n\0]/.test(value)
    && !/^(?:SET_|REPLACE_|CHANGE_|PREENCHER|<)/i.test(value);
}

function encryptionKey(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9+/]{43}=$/.test(value)) return false;
  const decoded = Buffer.from(value, 'base64');
  // Forma canonica AES256; rejeita marcadores e chaves degeneradas evidentes.
  // Isto nao comprova entropia nem substitui geracao criptografica no cofre.
  return decoded.length === 32 && decoded.toString('base64') === value && new Set(decoded).size >= 16;
}

/** Return only stable error codes and controlled paths; never input values. */
export function validateRelease(config, mode) {
  const errors = [];
  const seen = new Set();
  const fail = (code, path) => {
    const id = `${code}:${path}`;
    if (!seen.has(id)) { seen.add(id); errors.push({ code, path }); }
  };
  if (!['app', 'homologation', 'migrate', 'baseline', 'onboarding', 'worker'].includes(mode)) return [{ code: 'MODE_INVALID', path: '$' }];
  const application = mode === 'app' || mode === 'homologation';
  const homologation = mode === 'homologation';
  if (!record(config)) return [{ code: 'CONFIG_OBJECT_REQUIRED', path: '$' }];
  if (!/^condpulse[-_][a-zA-Z0-9_.-]+$/.test(text(config.name))) fail('DEDICATED_PROJECT_NAME_REQUIRED', '$.name');
  if (!noKeysOutside(config, new Set(['name', 'services', 'networks']))) fail('TOP_LEVEL_UNEXPECTED', '$');
  if (homologation && !/^condpulse-hml[-_][a-zA-Z0-9_.-]+$/.test(text(config.name))) fail('HOMOLOGATION_PROJECT_NAME_REQUIRED', '$.name');
  const serviceNames = application ? ['api', 'web'] : [mode];
  if (!sameKeys(config.services, serviceNames)) fail('SERVICES_EXACT', '$.services');
  const networkNames = application ? ['backend', 'proxy'] : ['backend'];
  if (!sameKeys(config.networks, networkNames)) fail('NETWORKS_EXACT', '$.networks');
  const backend = config.networks?.backend;
  if (!record(backend) || !noKeysOutside(backend, new Set(['name', 'driver', 'internal', 'external', 'ipam']))
      || (backend.ipam !== undefined && !sameKeys(backend.ipam, []))
      || (backend.driver !== undefined && backend.driver !== 'bridge') || backend.internal === true || backend.external === true) {
    fail('BACKEND_PRIVATE_BRIDGE_WITH_EGRESS', '$.networks.backend');
  }
  if (!/^condpulse[-_][a-zA-Z0-9_.-]+$/.test(text(backend?.name))) fail('DEDICATED_BACKEND_NETWORK_REQUIRED', '$.networks.backend');
  if (homologation && !/^condpulse-hml[-_][a-zA-Z0-9_.-]+$/.test(text(backend?.name))) fail('HOMOLOGATION_NETWORK_REQUIRED', '$.networks.backend');
  if (application) {
    const proxy = config.networks?.proxy;
    if (!record(proxy) || !noKeysOutside(proxy, new Set(['name', 'external', 'ipam']))
        || (proxy.ipam !== undefined && !sameKeys(proxy.ipam, [])) || proxy.external !== true
        || !/^condpulse[-_][a-zA-Z0-9_.-]+$/.test(text(proxy.name))) fail('DEDICATED_PROXY_NETWORK_REQUIRED', '$.networks.proxy');
    if (homologation && !/^condpulse-hml[-_][a-zA-Z0-9_.-]+$/.test(text(proxy?.name))) fail('HOMOLOGATION_NETWORK_REQUIRED', '$.networks.proxy');
  }
  for (const name of serviceNames) {
    const path = `$.services.${name}`; // name belongs to the fixed list above.
    const service = config.services?.[name];
    if (!record(service)) { fail('SERVICE_OBJECT_REQUIRED', path); continue; }
    if (!noKeysOutside(service, SERVICE_KEYS)) fail('SERVICE_SETTING_UNEXPECTED', path);
    // Os jobs operacionais iniciam somente em inspect; a escrita e manual.
    // Compose5 normaliza os campos ausentes para null. Nenhum entrypoint e substituido.
    const expectedCommand = name === 'baseline' ? ['inspect'] : name === 'onboarding' ? ['inspect', '/run/secrets/onboarding.json'] : null;
    if (service.entrypoint != null || (expectedCommand
      ? !equalArray(service.command, expectedCommand) : service.command != null)) fail('SERVICE_SETTING_UNEXPECTED', path);
    const image = text(service.image);
    if (!/^[a-z0-9][a-z0-9.-]*(?::\d{1,5})?\/(?:[a-z0-9]+(?:[._-][a-z0-9]+)*\/)*[a-z0-9]+(?:[._-][a-z0-9]+)*@sha256:[a-f0-9]{64}$/.test(image)
        || /@sha256:0{64}$/.test(image)) fail('IMAGE_DIGEST_REQUIRED', `${path}.image`);
    if (service.user !== (name === 'web' ? '101:101' : '10001:10001')) fail('NON_ROOT_USER_REQUIRED', `${path}.user`);
    if (service.read_only !== true) fail('READ_ONLY_REQUIRED', `${path}.read_only`);
    if (!equalArray(service.cap_drop, ['ALL'])) fail('DROP_ALL_CAPABILITIES', `${path}.cap_drop`);
    if (!Array.isArray(service.security_opt) || service.security_opt.length !== 1
        || !['no-new-privileges:true', 'no-new-privileges'].includes(service.security_opt[0])) fail('NO_NEW_PRIVILEGES_REQUIRED', `${path}.security_opt`);
    const worker = name === 'worker';
    const limit = name === 'web' ? 256 * 1024 ** 2 : worker ? 1024 ** 3 : 2 * 1024 ** 3;
    if (!(bytes(service.mem_limit) <= limit)) fail('MEMORY_LIMIT_REQUIRED', `${path}.mem_limit`);
    if (!(Number(service.cpus) > 0 && Number(service.cpus) <= (name === 'web' ? 0.5 : worker ? 1 : 2))) fail('CPU_LIMIT_REQUIRED', `${path}.cpus`);
    if (!(Number.isSafeInteger(Number(service.pids_limit)) && Number(service.pids_limit) > 0
        && Number(service.pids_limit) <= (name === 'web' || worker ? 128 : 256))) fail('PID_LIMIT_REQUIRED', `${path}.pids_limit`);
    const tmpfs = service.tmpfs;
    const tmp = Array.isArray(tmpfs) && tmpfs.length === 1 ? text(tmpfs[0]) : '';
    const opts = tmp.startsWith('/tmp:') ? tmp.slice(5).split(',') : [];
    const tmpSize = opts.find(option => option.startsWith('size='));
    if (!opts.includes('noexec') || !opts.includes('nosuid') || !opts.includes('mode=1777')
        || !(bytes(tmpSize?.slice(5)) <= 128 * 1024 ** 2)
        || new Set(opts).size !== opts.length || opts.filter(option => option.startsWith('size=')).length !== 1
        || opts.some(option => !/^(?:rw|noexec|nosuid|nodev|mode=1777|size=\d+(?:[kmg]b?)?)$/i.test(option))) fail('BOUNDED_PRIVATE_TMP_REQUIRED', `${path}.tmpfs`);
    const logging = service.logging;
    if (!record(logging) || !sameKeys(logging, ['driver', 'options']) || logging.driver !== 'json-file'
        || !sameKeys(logging.options, ['max-size', 'max-file']) || !(bytes(logging.options['max-size']) <= 10 * 1024 ** 2)
        || !(Number.isSafeInteger(Number(logging.options['max-file'])) && Number(logging.options['max-file']) >= 1 && Number(logging.options['max-file']) <= 3)) fail('ROTATED_LOGS_REQUIRED', `${path}.logging`);
    const job = name === 'migrate' || name === 'baseline' || name === 'onboarding';
    if (service.restart !== (job ? 'no' : 'unless-stopped')) fail('RESTART_POLICY_REQUIRED', `${path}.restart`);
    if (worker) {
      // Perfil worker nao abre porta nem HTTP: sem expose e com healthcheck desligado de forma explicita.
      if (!absentOrEmpty(service.expose) || !sameKeys(service.healthcheck, ['disable']) || service.healthcheck.disable !== true) fail('WORKER_NO_HTTP', path);
      if (!absentOrEmpty(service.depends_on)) fail('WORKER_SEPARATE_SERVICE', `${path}.depends_on`);
    } else if (job) {
      const jobCode = name === 'onboarding' ? 'ONBOARDING' : name === 'baseline' ? 'BASELINE' : 'MIGRATOR';
      if (!absentOrEmpty(service.expose) || !sameKeys(service.healthcheck, ['disable']) || service.healthcheck.disable !== true) fail(`${jobCode}_NO_HTTP`, path);
      if (!absentOrEmpty(service.depends_on)) fail(`${jobCode}_SEPARATE_JOB`, `${path}.depends_on`);
    } else {
      if (!equalArray(service.expose?.map(String), ['8080'])) fail('INTERNAL_PORT_ONLY', `${path}.expose`);
      if (!equalArray(service.healthcheck?.test, name === 'api' ? API_HEALTH : WEB_HEALTH)
          || service.healthcheck.disable === true || service.healthcheck.retries < 1
          || service.healthcheck.retries > 10 || !service.healthcheck.interval || !service.healthcheck.timeout) fail('HEALTHCHECK_REQUIRED', `${path}.healthcheck`);
      if (name === 'web') {
        if (!sameKeys(service.depends_on, ['api']) || service.depends_on.api.condition !== 'service_healthy') fail('API_READINESS_DEPENDENCY', `${path}.depends_on`);
      } else if (!absentOrEmpty(service.depends_on)) fail('API_DEPENDENCY_UNEXPECTED', `${path}.depends_on`);
    }
    if (!sameKeys(service.networks, name === 'web' ? ['backend', 'proxy'] : ['backend'])) fail('SERVICE_NETWORK_ISOLATION', `${path}.networks`);
    for (const network of (name === 'web' ? ['backend', 'proxy'] : ['backend'])) {
      if (service.networks?.[network] != null && !sameKeys(service.networks[network], [])) fail('NETWORK_OPTIONS_UNEXPECTED', `${path}.networks`);
    }
    const environment = service.environment;
    if (name === 'web') {
      if (!absentOrEmpty(environment) || !absentOrEmpty(service.volumes)) fail('WEB_NO_CREDENTIALS_OR_MOUNTS', path);
      continue;
    }
    if (!noKeysOutside(environment, name === 'api' ? (homologation ? HOMOLOGATION_ENV : API_ENV) : worker ? WORKER_ENV : name === 'baseline' ? BASELINE_ENV : name === 'onboarding' ? ONBOARDING_ENV : MIGRATE_ENV)) fail('ENVIRONMENT_UNEXPECTED', `${path}.environment`);
    const env = record(environment) ? environment : {};
    if (name !== 'baseline' && name !== 'onboarding') {
      if (env.SPRING_PROFILES_ACTIVE !== (name === 'api' ? 'api' : worker ? 'worker' : 'migrate')) fail('PROFILE_EXACT', `${path}.environment.SPRING_PROFILES_ACTIVE`);
      if (String(env.DB_POOL_SIZE) !== (name === 'api' ? '8' : worker ? '4' : '2')) fail('POOL_SIZE_EXACT', `${path}.environment.DB_POOL_SIZE`);
    }
    if (!databaseUrl(env.DATABASE_URL)) fail('DATABASE_TLS_CONFIGURATION', `${path}.environment.DATABASE_URL`);
    if (!secretConfigured(env.DATABASE_PASSWORD)) fail('DATABASE_PASSWORD_REQUIRED', `${path}.environment.DATABASE_PASSWORD`);
    const dbUser = text(env.DATABASE_USER);
    if (name === 'api' ? !/^cp_runtime_[a-z0-9_]+(?:\.[a-z0-9]+)?$/.test(dbUser)
        : worker ? !/^cp_worker_[a-z0-9_]+(?:\.[a-z0-9]{20})?$/.test(dbUser)
        : name === 'onboarding' ? !new RegExp(`^cp_onboarding_[a-z0-9_]{1,40}(?:\\.${BASELINE_PROJECT})?$`).test(dbUser)
        : !(/^(?:postgres|cp_migrator_[a-z0-9_]+)(?:\.[a-z0-9]{20})?$/.test(dbUser))) fail('DATABASE_ROLE_SEPARATION', `${path}.environment.DATABASE_USER`);
    const dbProject = databaseProject(env.DATABASE_URL, dbUser);
    if (!dbProject) fail('SUPABASE_DATABASE_TARGET_REQUIRED', `${path}.environment.DATABASE_URL`);
    if (name === 'baseline') {
      if (env.CP_BASELINE_PROJECT_REF !== BASELINE_PROJECT || dbProject !== BASELINE_PROJECT
          || new URL(env.DATABASE_URL.slice(5)).pathname !== '/postgres') fail('BASELINE_PROJECT_EXACT', `${path}.environment`);
      if (typeof env.CP_BASELINE_ACK !== 'string' || (env.CP_BASELINE_ACK !== ''
          && !new RegExp(`^${BASELINE_PROJECT}:002:[a-f0-9]{64}$`).test(env.CP_BASELINE_ACK))) fail('BASELINE_ACK_FORMAT', `${path}.environment.CP_BASELINE_ACK`);
    }
    if (name === 'onboarding') {
      if (env.CP_ONBOARDING_PROJECT_REF !== BASELINE_PROJECT || dbProject !== BASELINE_PROJECT
          || (databaseUrl(env.DATABASE_URL) && new URL(env.DATABASE_URL.slice(5)).pathname !== '/postgres')) fail('ONBOARDING_PROJECT_EXACT', `${path}.environment`);
      if (!/^sb_secret_[A-Za-z0-9_-]{24,4096}$/.test(text(env.CP_ONBOARDING_AUTH_SECRET_KEY))) fail('ONBOARDING_AUTH_SECRET_REQUIRED', `${path}.environment.CP_ONBOARDING_AUTH_SECRET_KEY`);
      if (typeof env.CP_ONBOARDING_ACK !== 'string' || (env.CP_ONBOARDING_ACK !== ''
          && !new RegExp(`^${BASELINE_PROJECT}:[a-f0-9]{64}$`).test(env.CP_ONBOARDING_ACK))) fail('ONBOARDING_ACK_FORMAT', `${path}.environment.CP_ONBOARDING_ACK`);
    }
    if (env.JAVA_TOOL_OPTIONS !== undefined && env.JAVA_TOOL_OPTIONS !== '-XX:MaxRAMPercentage=70') fail('JVM_OPTIONS_UNEXPECTED', `${path}.environment.JAVA_TOOL_OPTIONS`);
    if (env.TZ !== undefined && env.TZ !== 'America/Sao_Paulo') fail('TIMEZONE_UNEXPECTED', `${path}.environment.TZ`);
    const mounts = service.volumes;
    const onboardingMounts = name === 'onboarding';
    const mount = Array.isArray(mounts) && mounts.length === (onboardingMounts ? 2 : 1)
      ? mounts.find(value => value?.target === '/run/secrets/db-ca.crt') : null;
    if (!record(mount) || !noKeysOutside(mount, new Set(['type', 'source', 'target', 'read_only', 'bind']))
        || mount.type !== 'bind' || mount.target !== '/run/secrets/db-ca.crt' || mount.read_only !== true
        || !text(mount.source) || !(isAbsolute(mount.source) || win32.isAbsolute(mount.source))
        || !sameKeys(mount.bind, ['create_host_path']) || mount.bind.create_host_path !== false) fail('CA_READ_ONLY_MOUNT_REQUIRED', `${path}.volumes`);
    if (onboardingMounts) {
      const manifest = Array.isArray(mounts) && mounts.length === 2 ? mounts.find(value => value?.target === '/run/secrets/onboarding.json') : null;
      if (!record(manifest) || !noKeysOutside(manifest, new Set(['type', 'source', 'target', 'read_only', 'bind']))
          || manifest.type !== 'bind' || manifest.read_only !== true || !text(manifest.source)
          || !(isAbsolute(manifest.source) || win32.isAbsolute(manifest.source))
          || manifest.source === mount?.source || !sameKeys(manifest.bind, ['create_host_path'])
          || manifest.bind.create_host_path !== false) fail('ONBOARDING_MANIFEST_MOUNT_REQUIRED', `${path}.volumes`);
    }
    if (worker && (String(env.CP_WORKER_OUTBOX_ENABLED) !== 'false' || String(env.CP_WORKER_WAITLIST_ENABLED) !== 'false')) fail('FEATURES_MUST_REMAIN_DISABLED', `${path}.environment`);
    if (name === 'api') {
      if (String(env.PORT) !== '8080') fail('PORT_EXACT', `${path}.environment.PORT`);
      if (homologation) {
        if (env.CP_BFF_ENABLED !== 'true' || env.CP_RESERVATIONS_ENABLED !== 'true') fail('HOMOLOGATION_FEATURES_EXACT', `${path}.environment`);
        if (!/^sb_publishable_[A-Za-z0-9_-]{16,}$/.test(text(env.CP_SUPABASE_PUBLISHABLE_KEY))) fail('PUBLISHABLE_KEY_REQUIRED', `${path}.environment.CP_SUPABASE_PUBLISHABLE_KEY`);
        if (!encryptionKey(env.CP_BFF_ENCRYPTION_KEY)) fail('ENCRYPTION_KEY_REQUIRED', `${path}.environment.CP_BFF_ENCRYPTION_KEY`);
        if (!encryptionKey(env.CP_BFF_RATE_LIMIT_KEY) || env.CP_BFF_RATE_LIMIT_KEY === env.CP_BFF_ENCRYPTION_KEY) fail('RATE_LIMIT_KEY_REQUIRED', `${path}.environment.CP_BFF_RATE_LIMIT_KEY`);
        if (env.CP_BFF_SESSION_HOURS !== '8') fail('SESSION_HOURS_EXACT', `${path}.environment.CP_BFF_SESSION_HOURS`);
      } else if (String(env.CP_BFF_ENABLED) !== 'false' || String(env.CP_RESERVATIONS_ENABLED) !== 'false') fail('FEATURES_MUST_REMAIN_DISABLED', `${path}.environment`);
      if (!secureOrigin(env.CP_BFF_ORIGIN)) fail('BFF_HTTPS_ORIGIN_REQUIRED', `${path}.environment.CP_BFF_ORIGIN`);
      if (!/^https:\/\/[a-z0-9]{20}\.supabase\.co\/auth\/v1$/.test(text(env.CP_JWT_ISSUER))
          || env.CP_JWT_JWK_SET_URI !== `${env.CP_JWT_ISSUER}/.well-known/jwks.json`) fail('SUPABASE_AUTH_ENDPOINTS_MATCH', `${path}.environment`);
      if (dbProject && env.CP_JWT_ISSUER !== `https://${dbProject}.supabase.co/auth/v1`) fail('SUPABASE_PROJECT_MISMATCH', `${path}.environment`);
    }
  }
  return errors;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length !== 1 || !['--app', '--homologation', '--migrate', '--baseline', '--onboarding', '--worker'].includes(args[0])) {
    process.stderr.write('RELEASE_INVALID 1\nMODE_INVALID $\n'); process.exitCode = 1; return;
  }
  let input = '';
  let length = 0;
  for await (const chunk of process.stdin) {
    length += chunk.length;
    if (length > 1024 * 1024) {
      process.stderr.write('RELEASE_INVALID 1\nCONFIG_TOO_LARGE $\n'); process.exitCode = 1; return;
    }
    input += chunk;
  }
  let config;
  try { config = JSON.parse(input); } catch {
    process.stderr.write('RELEASE_INVALID 1\nJSON_INVALID $\n'); process.exitCode = 1; return;
  }
  const errors = validateRelease(config, args[0].slice(2));
  if (errors.length) {
    process.stderr.write(`RELEASE_INVALID ${errors.length}\n${errors.map(error => `${error.code} ${error.path}`).join('\n')}\n`);
    process.exitCode = 1;
  } else process.stdout.write('RELEASE_VALID 0\n');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => { process.stderr.write('RELEASE_INVALID 1\nVALIDATION_FAILED $\n'); process.exitCode = 1; });
}
