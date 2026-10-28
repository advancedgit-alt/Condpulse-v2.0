// CI only: real Spring/JDBC/session/cipher/JWT chain, disposable PostgreSQL and an
// HTTPS Auth protocol sandbox. No remote Supabase connection or production data.
import { createServer as httpsServer, request as httpsRequest } from 'node:https';
import { request as httpRequest } from 'node:http';
import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync, realpathSync, lstatSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join, extname, sep, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { minimalEnvironment } from './https-bff-environment.mjs';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const ensure = (condition, code) => { if (!condition) throw new Error(code); };
const pause = (ms) => new Promise((done) => setTimeout(done, ms));
const account = randomUUID(), tenant = randomUUID(), unit = randomUUID(), amenity = randomUUID();
let temporary, api, sandbox, web, seeded = false;
let stage = 'guard', startupError = false;
let passwordCalls = 0, refreshCalls = 0, logoutCalls = 0, jwksCalls = 0;
const accessTokens = new Set(), refreshTokens = new Set();
// Diagnostics may expose only these known backend codes, never arbitrary bodies.
const problemCodes = new Set(['AUTHENTICATION_REQUIRED', 'ACTION_FORBIDDEN', 'ACCOUNT_INACTIVE',
  'SESSION_EXPIRED', 'SESSION_REFRESH_REQUIRED', 'TENANT_ACCESS_DENIED', 'UNIT_ACCESS_DENIED',
  'RESERVATION_ACCESS_DENIED', 'RESERVATION_RESOURCE_UNAVAILABLE', 'RESERVATION_DEPENDENCY_UNAVAILABLE',
  'IDEMPOTENCY_KEY_INVALID', 'IDEMPOTENCY_KEY_EXPIRED', 'IDEMPOTENCY_KEY_REUSED', 'REQUEST_IN_PROGRESS',
  'DEPENDENCY_UNAVAILABLE', 'RESERVATION_CONFLICT', 'UNIT_RESERVATION_QUOTA_REACHED',
  'AMENITY_POLICY_CHANGED', 'AMENITY_POLICY_NOT_CONFIGURED', 'RESERVATION_WINDOW_INVALID']);

function responseDiagnostic(response) {
  const status = Number.isInteger(response.status) && response.status >= 100 && response.status <= 599
    ? String(response.status) : 'INVALID';
  const code = problemCodes.has(response.body?.code) ? response.body.code : 'NO_ALLOWLISTED_PROBLEM';
  return `RESPONSE_STATUS_${status}_${code}`;
}

function guard() {
  ensure(process.env.CP_DB_TEST_OK === 'DISPOSABLE', 'DISPOSABLE_ACK_REQUIRED');
  ensure(['localhost', '127.0.0.1'].includes(process.env.PGHOST), 'LOCAL_DB_HOST_REQUIRED');
  ensure(process.env.PGDATABASE === 'condpulse_ci', 'DISPOSABLE_DATABASE_REQUIRED');
  ensure(process.env.PGUSER === 'postgres' && process.env.PGPASSWORD === 'condpulse_ci_only', 'SYNTHETIC_ADMIN_REQUIRED');
  ensure(/^\d{1,5}$/.test(process.env.PGPORT ?? '') && Number(process.env.PGPORT) < 65536, 'LOCAL_DB_PORT_REQUIRED');
  // Do not inherit alternate libpq routing or arbitrary JVM options from an operator.
  ensure(!process.env.PGSERVICE && !process.env.PGSERVICEFILE && !process.env.PGHOSTADDR, 'ALTERNATE_DB_ROUTING_FORBIDDEN');
  ensure(!process.env.JAVA_TOOL_OPTIONS && !process.env.JDK_JAVA_OPTIONS && !process.env._JAVA_OPTIONS, 'EXTERNAL_JVM_OPTIONS_FORBIDDEN');
  ensure(existsSync(join(root, 'apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar')), 'BUILD_API_JAR_FIRST');
  ensure(existsSync(join(root, 'apps/web/dist/index.html')), 'BUILD_WEB_FIRST');
}

function command(program, args, input, environment = minimalEnvironment()) {
  const result = spawnSync(program, args, { input, env: environment, encoding: 'utf8', timeout: 30000, windowsHide: true });
  ensure(result.status === 0, `${program.toUpperCase()}_COMMAND_FAILED`);
  return result.stdout.trim();
}

function sql(query) {
  return command('psql', ['-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1'], query,
    { ...minimalEnvironment(), PGHOST: process.env.PGHOST, PGPORT: process.env.PGPORT,
      PGDATABASE: 'condpulse_ci', PGUSER: 'postgres', PGPASSWORD: 'condpulse_ci_only' });
}

function seed() {
  ensure(sql("select current_database() || ':' || current_user") === 'condpulse_ci:postgres', 'DATABASE_IDENTITY_MISMATCH');
  ensure(sql("select count(*) from pg_roles where rolname='cp_ci_login' and rolcanlogin and not rolsuper and not rolbypassrls and pg_has_role(rolname,'cp_api','MEMBER')") === '1', 'RUNTIME_ROLE_NOT_READY');
  const policy = JSON.stringify({ schemaVersion: 1, weekDays: [1, 2, 3, 4, 5, 6, 7],
    opensAt: '00:00', closesAt: '23:59', minimumLeadMinutes: 0,
    maximumAdvanceDays: 366, maximumDurationMinutes: 600,
    maximumActivePerUnit: 2, cancellationLeadMinutes: 0 });
  sql(`begin;
    insert into platform.accounts(id,status) values('${account}','ACTIVE');
    insert into platform.tenants(id,name,time_zone,active) values('${tenant}','CONDOMINIO SINTETICO HTTPS','UTC',true);
    insert into cp.units(id,tenant_id,label) values('${unit}','${tenant}','UNIDADE SINTETICA HTTPS');
    insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
      values('${tenant}','${account}','${unit}','MORADOR SINTETICO HTTPS','RESIDENT','ACTIVE');
    insert into cp.amenities(id,tenant_id,name,capacity,approval_required,policy)
      values('${amenity}','${tenant}','AREA SINTETICA HTTPS',10,false,'${policy}'::jsonb);
    commit;`);
  seeded = true;
}

function cleanupFixtures() {
  if (!seeded) return;
  sql(`begin;
    delete from platform.web_sessions where account_id='${account}';
    delete from cp.idempotency_keys where tenant_id='${tenant}';
    delete from cp.outbox where tenant_id='${tenant}';
    delete from cp.audit_log where tenant_id='${tenant}';
    delete from cp.reservations where tenant_id='${tenant}';
    delete from cp.memberships where tenant_id='${tenant}';
    delete from cp.amenities where tenant_id='${tenant}';
    delete from cp.units where tenant_id='${tenant}';
    delete from platform.tenants where id='${tenant}';
    delete from platform.accounts where id='${account}'; commit;`);
  ensure(sql(`select count(*) from platform.accounts where id='${account}'`) === '0', 'FIXTURE_CLEANUP_FAILED');
}

function tlsMaterial() {
  temporary = resolve(mkdtempSync(join(tmpdir(), 'condpulse-https-ci-')));
  const config = join(temporary, 'openssl.cnf');
  writeFileSync(config, `[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,keyEncipherment,keyCertSign\nextendedKeyUsage=serverAuth\n`, { mode: 0o600 });
  command('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
    '-config', config, '-keyout', join(temporary, 'tls.key'), '-out', join(temporary, 'tls.crt')]);
  command('keytool', ['-importcert', '-noprompt', '-alias', 'synthetic-localhost',
    '-file', join(temporary, 'tls.crt'), '-keystore', join(temporary, 'truststore.p12'),
    '-storetype', 'PKCS12', '-storepass', 'synthetic_ci_trust_only']);
  return { key: readFileSync(join(temporary, 'tls.key')), cert: readFileSync(join(temporary, 'tls.crt')) };
}

async function listen(server) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return `https://127.0.0.1:${server.address().port}`;
}

function json(response, status, value) {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
}

async function bodyOf(request) {
  let result = '';
  for await (const part of request) {
    result += part;
    ensure(result.length <= 16384, 'SANDBOX_REQUEST_TOO_LARGE');
  }
  return JSON.parse(result);
}

async function authSandbox(tls) {
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'synthetic-ci-rsa', alg: 'RS256', use: 'sig' };
  let issuer;
  const mint = () => {
    const now = Math.floor(Date.now() / 1000);
    const head = Buffer.from(JSON.stringify({ alg: 'RS256', kid: jwk.kid, typ: 'JWT' })).toString('base64url');
    const body = Buffer.from(JSON.stringify({ iss: issuer, sub: account, aud: ['authenticated'],
      iat: now, exp: now + 300, role: 'authenticated', jti: randomUUID() })).toString('base64url');
    const payload = `${head}.${body}`;
    const access = `${payload}.${sign('RSA-SHA256', Buffer.from(payload), privateKey).toString('base64url')}`;
    const refresh = randomBytes(32).toString('base64url');
    accessTokens.add(access); refreshTokens.add(refresh);
    return { access_token: access, refresh_token: refresh, expires_in: 300 };
  };
  sandbox = httpsServer(tls, async (request, response) => {
    try {
      const url = new URL(request.url, issuer);
      if (request.method === 'GET' && url.pathname === '/auth/v1/.well-known/jwks.json') {
        jwksCalls++;
        return json(response, 200, { keys: [jwk] });
      }
      if (request.headers.apikey !== 'sb_publishable_synthetic_ci_only') return json(response, 401, { code: 'BAD_APIKEY' });
      if (request.method === 'POST' && url.pathname === '/auth/v1/token') {
        if (!request.headers['content-type']?.startsWith('application/json')) return json(response, 400, { code: 'JSON_REQUIRED' });
        const body = await bodyOf(request);
        if (url.search === '?grant_type=password') {
          passwordCalls++;
          if (Object.keys(body).length !== 2 || body.email !== 'resident@synthetic.invalid' || body.password !== 'synthetic_https_password_only')
            return json(response, 400, { code: 'INVALID_CREDENTIALS' });
          return json(response, 200, mint());
        }
        if (url.search === '?grant_type=refresh_token') {
          refreshCalls++;
          if (Object.keys(body).length !== 1 || !refreshTokens.delete(body.refresh_token)) return json(response, 400, { code: 'REFRESH_REUSED' });
          return json(response, 200, mint());
        }
      }
      if (request.method === 'POST' && url.pathname === '/auth/v1/logout' && url.search === '?scope=local') {
        if (!accessTokens.has(request.headers.authorization?.replace(/^Bearer /, ''))) return json(response, 401, { code: 'INVALID_ACCESS' });
        logoutCalls++;
        response.writeHead(204); response.end(); return;
      }
      json(response, 404, { code: 'UNKNOWN_SANDBOX_PROTOCOL' });
    } catch { json(response, 400, { code: 'SANDBOX_PROTOCOL_ERROR' }); }
  });
  const origin = await listen(sandbox);
  issuer = `${origin}/auth/v1`;
  return issuer;
}

async function webOrigin(tls, apiPort) {
  const dist = join(root, 'apps/web/dist');
  web = httpsServer(tls, (request, response) => {
    if (request.url.startsWith('/auth/') || request.url.startsWith('/v1/')) {
      const upstream = httpRequest({ hostname: '127.0.0.1', port: apiPort, method: request.method,
        path: request.url, headers: { ...request.headers, host: 'localhost' } }, (result) => {
        response.writeHead(result.statusCode, result.headers); result.pipe(response);
      });
      upstream.setTimeout(15000, () => upstream.destroy());
      upstream.on('error', () => { if (!response.headersSent) json(response, 502, { code: 'API_UNAVAILABLE' }); else response.destroy(); });
      request.pipe(upstream); return;
    }
    const path = new URL(request.url, 'https://localhost').pathname;
    const asset = path.startsWith('/assets/') ? resolve(dist, `.${path}`) : join(dist, 'index.html');
    if (!asset.startsWith(`${dist}${sep}`) || !existsSync(asset)) { response.writeHead(404); response.end(); return; }
    const contentType = ({ '.js': 'application/javascript', '.css': 'text/css', '.html': 'text/html', '.woff2': 'font/woff2' })[extname(asset)] ?? 'application/octet-stream';
    response.writeHead(200, { 'Content-Type': contentType, 'Cache-Control': 'no-store' }); response.end(readFileSync(asset));
  });
  return listen(web);
}

async function startApi(issuer, origin, port) {
  api = spawn('java', [`-Djavax.net.ssl.trustStore=${join(temporary, 'truststore.p12')}`,
    '-Djavax.net.ssl.trustStorePassword=synthetic_ci_trust_only', '-Xmx512m', '-jar',
    join(root, 'apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar')], {
    cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...minimalEnvironment(), SPRING_PROFILES_ACTIVE: 'api', PORT: String(port),
      DATABASE_URL: `jdbc:postgresql://${process.env.PGHOST}:${process.env.PGPORT}/condpulse_ci`,
      DATABASE_USER: 'cp_ci_login', DATABASE_PASSWORD: 'synthetic_ci_runtime_only', DB_POOL_SIZE: '2',
      CP_JWT_ISSUER: issuer, CP_JWT_JWK_SET_URI: `${issuer}/.well-known/jwks.json`,
      CP_BFF_ENABLED: 'true', CP_RESERVATIONS_ENABLED: 'true', CP_BFF_ORIGIN: origin,
      CP_SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_synthetic_ci_only', CP_BFF_ENCRYPTION_KEY: randomBytes(32).toString('base64'),
      CP_BFF_SESSION_HOURS: '1' },
  });
  // Drain logs without printing provider/cookie/token material or persisting an artifact.
  api.stdout.resume(); api.stderr.resume(); api.once('error', () => { startupError = true; });
  for (let attempt = 0; attempt < 120; attempt++) {
    ensure(!startupError && api.exitCode === null, 'API_PROCESS_EXITED');
    try {
      const result = await new Promise((done, reject) => {
        const request = httpRequest({ hostname: '127.0.0.1', port, path: '/actuator/health/readiness', timeout: 1000 }, (response) => { response.resume(); done(response.statusCode); });
        request.on('error', reject); request.on('timeout', () => request.destroy()); request.end();
      });
      if (result === 200) return;
    } catch { /* startup can take time; no remote retries or mutations */ }
    await pause(500);
  }
  throw new Error('API_READINESS_TIMEOUT');
}

class Client {
  constructor(origin, ca, cookies = new Map()) { this.origin = origin; this.ca = ca; this.cookies = cookies; }
  async call(path, { method = 'GET', body, headers = {}, csrf, origin = this.origin } = {}) {
    const data = body === undefined ? undefined : JSON.stringify(body);
    const result = await new Promise((done, reject) => {
      const request = httpsRequest(`${this.origin}${path}`, { ca: this.ca, rejectUnauthorized: true, method,
        headers: { ...Object.fromEntries(this.cookies.size ? [['Cookie', [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ')]] : []),
          ...(method !== 'GET' ? { Origin: origin } : {}), ...(data ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } : {}),
          ...(csrf ? { [csrf.headerName]: csrf.token } : {}), ...headers }, timeout: 10000 }, (response) => {
        const chunks = []; response.on('data', (chunk) => chunks.push(chunk));
        response.on('end', () => done({ status: response.statusCode, headers: response.headers, text: Buffer.concat(chunks).toString('utf8') }));
      });
      request.on('error', () => reject(new Error('HTTPS_REQUEST_FAILED')));
      request.on('timeout', () => request.destroy()); request.end(data);
    });
    for (const cookie of result.headers['set-cookie'] ?? []) {
      ensure(/;\s*Secure(?:;|$)/i.test(cookie) && /;\s*HttpOnly(?:;|$)/i.test(cookie) && /;\s*SameSite=Lax(?:;|$)/i.test(cookie) && /;\s*Path=\/(?:;|$)/i.test(cookie), 'COOKIE_SECURITY_ATTRIBUTES_MISSING');
      ensure(!/;\s*Domain=/i.test(cookie), 'HOST_COOKIE_DOMAIN_FORBIDDEN');
      const first = cookie.split(';')[0], split = first.indexOf('='), name = first.slice(0, split), value = first.slice(split + 1);
      ensure(name.startsWith('__Host-'), 'COOKIE_HOST_PREFIX_MISSING');
      if (/Max-Age=0/i.test(cookie) || !value) this.cookies.delete(name); else this.cookies.set(name, value);
    }
    if (result.headers['content-type']?.includes('json') && result.text) {
      try { result.body = JSON.parse(result.text); } catch { throw new Error('INVALID_API_JSON'); }
    }
    return result;
  }
  async csrf() {
    const result = await this.call('/auth/csrf');
    ensure(result.status === 200 && result.body?.headerName === 'X-CSRF-Token' && result.body.token, 'CSRF_FETCH_FAILED');
    ensure(result.headers['cache-control'] === 'no-store', 'AUTH_CACHE_CONTROL_MISSING');
    return result.body;
  }
}

async function exercise(origin, tls) {
  const client = new Client(origin, tls.cert);
  const html = await client.call('/entrar');
  ensure(html.status === 200 && html.text.includes('<div id="root">'), 'REAL_WEB_BUNDLE_MISSING');
  const asset = html.text.match(/src="([^\"]+\/assets\/[^\"]+\.js)"/)?.[1] ?? html.text.match(/src="(\/assets\/[^\"]+\.js)"/)?.[1];
  ensure(asset && (await client.call(asset)).status === 200, 'REAL_WEB_ASSET_MISSING');
  ensure((await client.call('/v1/me')).status === 401, 'ANONYMOUS_ME_ALLOWED');
  const loginBody = { email: 'resident@synthetic.invalid', password: 'synthetic_https_password_only' };
  let csrf = await client.csrf();
  ensure((await client.call('/auth/login', { method: 'POST', body: loginBody, csrf, origin: 'https://hostile.invalid' })).status === 403, 'HOSTILE_ORIGIN_ALLOWED');
  ensure((await client.call('/auth/login', { method: 'POST', body: loginBody })).status === 403, 'MISSING_CSRF_ALLOWED');
  ensure(passwordCalls === 0, 'DENIED_LOGIN_CONTACTED_PROVIDER');
  const login = await client.call('/auth/login', { method: 'POST', body: loginBody, csrf });
  ensure(login.status === 200 && login.body?.accountId === account, 'REAL_LOGIN_FAILED');
  ensure(Object.keys(login.body).sort().join(',') === 'accountId,expiresAt', 'LOGIN_EXPOSED_PROVIDER_TOKENS');
  const opaque = client.cookies.get('__Host-cp_session');
  ensure(opaque && !accessTokens.has(opaque) && !refreshTokens.has(opaque), 'SESSION_COOKIE_NOT_OPAQUE');
  const hash = createHash('sha256').update(opaque).digest('hex');
  ensure(sql(`select count(*) from platform.web_sessions where account_id='${account}' and token_hash='${hash}' and encrypted_access_token not like 'eyJ%' and encrypted_refresh_token not in (${[...refreshTokens].map((token) => `'${token}'`).join(',')})`) === '1', 'DURABLE_ENCRYPTED_SESSION_MISSING');
  const me = await client.call('/v1/me');
  ensure(me.status === 200 && me.body?.accountId === account && me.body.memberships?.some((membership) => membership.tenantId === tenant && membership.unitId === unit), 'REAL_CONTEXT_FAILED');
  const base = `/v1/tenants/${tenant}`;
  ensure((await client.call(`${base}/units`)).status === 200, 'UNIT_QUERY_FAILED');
  const areas = await client.call(`${base}/amenities`);
  ensure(areas.status === 200 && areas.body.some((area) => area.amenityId === amenity), 'AMENITY_QUERY_FAILED');
  csrf = await client.csrf();
  const start = new Date(); start.setUTCDate(start.getUTCDate() + 2); start.setUTCHours(12, 0, 0, 0);
  const booking = { unitId: unit, amenityId: amenity, startsAt: start.toISOString(), endsAt: new Date(start.getTime() + 3600000).toISOString(), acceptedPolicyVersion: 1 };
  const bookingKey = `https-ci-booking:${randomUUID()}`;
  ensure((await client.call(`${base}/reservations`, { method: 'POST', body: booking, headers: { 'Idempotency-Key': bookingKey } })).status === 403, 'RESERVATION_WITHOUT_CSRF_ALLOWED');
  const create = await client.call(`${base}/reservations`, { method: 'POST', body: booking, csrf, headers: { 'Idempotency-Key': bookingKey } });
  ensure(create.status === 201 && create.body?.status === 'CONFIRMED', 'REAL_RESERVATION_FAILED');
  const replay = await client.call(`${base}/reservations`, { method: 'POST', body: booking, csrf, headers: { 'Idempotency-Key': bookingKey } });
  ensure(replay.status === 201, `RESERVATION_REPLAY_${responseDiagnostic(replay)}`);
  ensure(typeof replay.body?.reservationId === 'string', 'RESERVATION_REPLAY_BODY_ID_MISSING');
  ensure(replay.body.reservationId === create.body.reservationId, 'RESERVATION_REPLAY_BODY_ID_MISMATCH');
  ensure(Object.hasOwn(replay.headers, 'idempotency-replayed'), 'RESERVATION_REPLAY_HEADER_MISSING');
  ensure(replay.headers['idempotency-replayed'] === 'true', 'RESERVATION_REPLAY_HEADER_MISMATCH');
  const cancel = await client.call(`${base}/reservations/${create.body.reservationId}/cancel`, { method: 'POST', body: { unitId: unit, expectedVersion: create.body.version }, csrf, headers: { 'Idempotency-Key': `https-ci-cancel:${randomUUID()}` } });
  ensure(cancel.status === 200 && cancel.body?.status === 'CANCELLED', 'REAL_CANCELLATION_FAILED');
  ensure(sql(`select (select count(*) from cp.reservations where tenant_id='${tenant}' and status='CANCELLED') || ':' || (select count(*) from cp.audit_log where tenant_id='${tenant}') || ':' || (select count(*) from cp.outbox where tenant_id='${tenant}')`) === '1:2:2', 'RESERVATION_COMMIT_COUNTS_WRONG');
  const beforeRefresh = sql(`select encrypted_refresh_token from platform.web_sessions where token_hash='${hash}'`);
  const refresh = await client.call('/auth/refresh', { method: 'POST', csrf });
  ensure(refresh.status === 200 && refreshCalls === 1, 'REAL_REFRESH_FAILED');
  ensure(sql(`select version || ':' || (refresh_lease_id is null)::text from platform.web_sessions where token_hash='${hash}'`) === '1:true', 'REFRESH_COMMIT_MISSING');
  ensure(sql(`select encrypted_refresh_token from platform.web_sessions where token_hash='${hash}'`) !== beforeRefresh, 'REFRESH_TOKEN_NOT_ROTATED');
  ensure((await client.call('/v1/me')).status === 200, 'REFRESHED_ME_FAILED');
  sql(`update cp.memberships set status='REVOKED' where tenant_id='${tenant}' and account_id='${account}'`);
  ensure((await client.call(`${base}/units`)).status === 403, 'REVOKED_MEMBERSHIP_ALLOWED');
  csrf = await client.csrf();
  const replayClient = new Client(origin, tls.cert, new Map(client.cookies));
  const logout = await client.call('/auth/logout', { method: 'POST', csrf });
  ensure(logout.status === 204 && logoutCalls === 1, 'REAL_LOGOUT_FAILED');
  ensure(sql(`select count(*) from platform.web_sessions where account_id='${account}'`) === '0', 'LOGOUT_NOT_DURABLE');
  ensure((await replayClient.call('/v1/me')).status === 401, 'LOGGED_OUT_COOKIE_REPLAY_ALLOWED');
  ensure(!client.cookies.has('__Host-cp_session') && !client.cookies.has('__Host-cp_csrf'), 'LOGOUT_COOKIES_NOT_CLEARED');
  ensure(passwordCalls === 1 && jwksCalls >= 1, 'AUTH_SANDBOX_PROTOCOL_NOT_EXERCISED');
}

async function stop() {
  if (api && api.exitCode === null) {
    api.kill('SIGTERM');
    await Promise.race([once(api, 'exit').catch(() => {}), pause(5000)]);
    if (api.exitCode === null) api.kill('SIGKILL');
  }
  for (const server of [web, sandbox]) if (server) {
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
  }
  try { cleanupFixtures(); }
  finally {
    if (temporary) {
      // Verify the actual absolute target before recursive removal, including Windows.
      const target = realpathSync(temporary), base = realpathSync(tmpdir());
      ensure(!lstatSync(temporary).isSymbolicLink() && dirname(target) === base
        && basename(target).startsWith('condpulse-https-ci-'), 'UNSAFE_TEMP_CLEANUP_TARGET');
      rmSync(target, { recursive: true, force: true });
    }
  }
}

let failed = false;
try {
  guard(); stage = 'fixtures'; seed(); stage = 'tls';
  const tls = tlsMaterial(); stage = 'sandbox'; const issuer = await authSandbox(tls);
  const apiPort = 4192;
  const origin = await webOrigin(tls, apiPort); stage = 'api'; await startApi(issuer, origin, apiPort);
  stage = 'journey'; await exercise(origin, tls);
} catch (error) {
  failed = true;
  // Codes contain no HTTP bodies, cookies, provider tokens or database credentials.
  console.error(`HTTPS BFF integration failed at ${stage}: ${/^[A-Z0-9_]+$/.test(error.message) ? error.message : 'UNEXPECTED_ERROR'}`);
} finally {
  try { await stop(); } catch { failed = true; console.error('HTTPS BFF integration cleanup failed.'); }
}
if (failed) process.exitCode = 1;
else console.log('HTTPS BFF integration passed: real web assets, Spring Security/JWT/cipher/JDBC, context, reservation/replay/cancellation, Origin/CSRF, refresh/logout and revoked membership; synthetic local Auth only.');
