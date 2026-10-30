// CP-052 — carga sintética da API real (Spring/Security/JWT/JDBC) contra PostgreSQL DESCARTÁVEL do runner.
// Mede latência (p50/p95/p99), erros e saturação local com mistura de leituras e gravações de reserva. O número é do
// runner do CI (API, banco e gerador na mesma máquina): serve para comparar versões e achar regressão, NUNCA para
// declarar capacidade da homologação ou da produção. Sem Supabase, Vercel, segredo real ou dado real.
import { createServer as httpsServer } from 'node:https';
import { Agent, request as httpRequest } from 'node:http';
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync, realpathSync, lstatSync, appendFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { minimalEnvironment } from '../integration/https-bff-environment.mjs';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const ensure = (condition, code) => { if (!condition) throw new Error(code); };
const pause = (ms) => new Promise((done) => setTimeout(done, ms));
const integer = (name, fallback, min, max) => {
  const raw = process.env[name] ?? String(fallback);
  ensure(/^\d{1,6}$/.test(raw) && Number(raw) >= min && Number(raw) <= max, `${name}_OUT_OF_RANGE`);
  return Number(raw);
};
// Meta inicial do PRD: 100 rps e 1.000 sessões. Limites altos só para não derrubar o runner.
const RPS = integer('CP_LOAD_RPS', 100, 1, 300);
const DURATION = integer('CP_LOAD_SECONDS', 60, 10, 600);
const SESSIONS = integer('CP_LOAD_SESSIONS', 1000, 4, 2000);
const POOL = integer('CP_LOAD_DB_POOL', 2, 1, 16);
const MAX_IN_FLIGHT = 256;
const tenant = randomUUID();
const UNITS = Math.ceil(SESSIONS / 4);
let temporary, api, sandbox, seeded = false, stage = 'guard', startupError = false;

function guard() {
  ensure(process.env.CP_DB_TEST_OK === 'DISPOSABLE', 'DISPOSABLE_ACK_REQUIRED');
  ensure(['localhost', '127.0.0.1'].includes(process.env.PGHOST), 'LOCAL_DB_HOST_REQUIRED');
  ensure(process.env.PGDATABASE === 'condpulse_ci', 'DISPOSABLE_DATABASE_REQUIRED');
  ensure(process.env.PGUSER === 'postgres' && process.env.PGPASSWORD === 'condpulse_ci_only', 'SYNTHETIC_ADMIN_REQUIRED');
  ensure(/^\d{1,5}$/.test(process.env.PGPORT ?? ''), 'LOCAL_DB_PORT_REQUIRED');
  ensure(!process.env.PGSERVICE && !process.env.PGSERVICEFILE && !process.env.PGHOSTADDR, 'ALTERNATE_DB_ROUTING_FORBIDDEN');
  ensure(existsSync(join(root, 'apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar')), 'BUILD_API_JAR_FIRST');
}
function command(program, args, input, environment = minimalEnvironment()) {
  const result = spawnSync(program, args, { input, env: environment, encoding: 'utf8', timeout: 120000, windowsHide: true });
  ensure(result.status === 0, `${program.toUpperCase()}_COMMAND_FAILED`);
  return result.stdout.trim();
}
const sql = (query) => command('psql', ['-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1'], query,
  { ...minimalEnvironment(), PGHOST: process.env.PGHOST, PGPORT: process.env.PGPORT, PGDATABASE: 'condpulse_ci', PGUSER: 'postgres', PGPASSWORD: 'condpulse_ci_only' });

const amenities = Array.from({ length: 5 }, () => randomUUID());
let residents = [];
function seed() {
  ensure(sql("select count(*) from pg_roles where rolname='cp_ci_login' and rolcanlogin and not rolsuper and not rolbypassrls and pg_has_role(rolname,'cp_api','MEMBER')") === '1', 'RUNTIME_ROLE_NOT_READY');
  const policy = JSON.stringify({ schemaVersion: 1, weekDays: [1, 2, 3, 4, 5, 6, 7], opensAt: '00:00', closesAt: '23:59', minimumLeadMinutes: 0,
    maximumAdvanceDays: 366, maximumDurationMinutes: 600, maximumActivePerUnit: 999, cancellationLeadMinutes: 0 });
  // Unidades com 4 moradores cada; uma conta por sessão.
  sql(`begin;
    insert into platform.tenants(id,name,time_zone,active) values('${tenant}','CONDOMINIO SINTETICO CARGA','America/Sao_Paulo',true);
    create temporary table load_units on commit drop as select gen_random_uuid() as id, n from generate_series(1,${UNITS}) n;
    insert into cp.units(id,tenant_id,label) select id,'${tenant}','CARGA '||n from load_units;
    create temporary table load_accounts on commit drop as select gen_random_uuid() as id, u.id as unit_id, k
      from load_units u cross join generate_series(1,4) k;
    insert into platform.accounts(id,status) select id,'ACTIVE' from load_accounts;
    insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
      select '${tenant}',id,unit_id,'MORADOR CARGA '||k,'RESIDENT','ACTIVE' from load_accounts;
    insert into cp.amenities(id,tenant_id,name,capacity,approval_required,policy)
      select a::uuid,'${tenant}','AREA CARGA '||row_number() over (),10,false,'${policy}'::jsonb from unnest(array['${amenities.join("','")}']) a;
    commit;`);
  seeded = true;
  residents = sql(`select m.account_id||' '||m.unit_id from cp.memberships m where m.tenant_id='${tenant}' order by m.account_id limit ${SESSIONS}`)
    .split('\n').map((line) => { const [account, unit] = line.split(' '); return { account, unit, token: null, minted: 0 }; });
  ensure(residents.length === SESSIONS, 'SEED_SESSIONS_MISMATCH');
}
function cleanupFixtures() {
  if (!seeded) return;
  sql(`begin;
    delete from cp.idempotency_keys where tenant_id='${tenant}';
    delete from cp.outbox where tenant_id='${tenant}';
    delete from cp.audit_log where tenant_id='${tenant}';
    delete from cp.notifications where tenant_id='${tenant}';
    delete from cp.reservations where tenant_id='${tenant}';
    create temporary table gone on commit drop as select account_id from cp.memberships where tenant_id='${tenant}';
    delete from cp.memberships where tenant_id='${tenant}';
    delete from cp.amenities where tenant_id='${tenant}';
    delete from cp.units where tenant_id='${tenant}';
    delete from platform.tenants where id='${tenant}';
    delete from platform.accounts where id in (select account_id from gone); commit;`);
  ensure(sql(`select count(*) from platform.tenants where id='${tenant}'`) === '0', 'FIXTURE_CLEANUP_FAILED');
}

function tlsMaterial() {
  temporary = resolve(mkdtempSync(join(tmpdir(), 'condpulse-load-ci-')));
  const config = join(temporary, 'openssl.cnf');
  writeFileSync(config, `[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,keyEncipherment,keyCertSign\nextendedKeyUsage=serverAuth\n`, { mode: 0o600 });
  command('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-config', config,
    '-keyout', join(temporary, 'tls.key'), '-out', join(temporary, 'tls.crt')]);
  command('keytool', ['-importcert', '-noprompt', '-alias', 'synthetic-localhost', '-file', join(temporary, 'tls.crt'),
    '-keystore', join(temporary, 'truststore.p12'), '-storetype', 'PKCS12', '-storepass', 'synthetic_ci_trust_only']);
  return { key: readFileSync(join(temporary, 'tls.key')), cert: readFileSync(join(temporary, 'tls.crt')) };
}
// Só o JWKS: os tokens são emitidos aqui mesmo, com a chave sintética, para as contas semeadas.
let mintToken;
async function jwks(tls) {
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'synthetic-load-rsa', alg: 'RS256', use: 'sig' };
  let issuer;
  sandbox = httpsServer(tls, (request, response) => {
    const ok = request.method === 'GET' && request.url === '/auth/v1/.well-known/jwks.json';
    response.writeHead(ok ? 200 : 404, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify(ok ? { keys: [jwk] } : { code: 'UNKNOWN' }));
  });
  sandbox.listen(0, '127.0.0.1'); await once(sandbox, 'listening');
  issuer = `https://127.0.0.1:${sandbox.address().port}/auth/v1`;
  mintToken = (account) => {
    const now = Math.floor(Date.now() / 1000);
    const head = Buffer.from(JSON.stringify({ alg: 'RS256', kid: jwk.kid, typ: 'JWT' })).toString('base64url');
    const body = Buffer.from(JSON.stringify({ iss: issuer, sub: account, aud: ['authenticated'], iat: now, exp: now + 300, role: 'authenticated',
      aal: 'aal1', jti: randomUUID() })).toString('base64url');
    const payload = `${head}.${body}`;
    return `${payload}.${sign('RSA-SHA256', Buffer.from(payload), privateKey).toString('base64url')}`;
  };
  return issuer;
}
async function startApi(issuer, port) {
  api = spawn('java', [`-Djavax.net.ssl.trustStore=${join(temporary, 'truststore.p12')}`, '-Djavax.net.ssl.trustStorePassword=synthetic_ci_trust_only',
    '-Xmx512m', '-jar', join(root, 'apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar')], {
    cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...minimalEnvironment(), SPRING_PROFILES_ACTIVE: 'api', PORT: String(port),
      DATABASE_URL: `jdbc:postgresql://${process.env.PGHOST}:${process.env.PGPORT}/condpulse_ci`,
      DATABASE_USER: 'cp_ci_login', DATABASE_PASSWORD: 'synthetic_ci_runtime_only', DB_POOL_SIZE: String(POOL),
      CP_JWT_ISSUER: issuer, CP_JWT_JWK_SET_URI: `${issuer}/.well-known/jwks.json`, CP_RESERVATIONS_ENABLED: 'true' },
  });
  api.stdout.resume(); api.stderr.resume(); api.once('error', () => { startupError = true; });
  for (let attempt = 0; attempt < 120; attempt++) {
    ensure(!startupError && api.exitCode === null, 'API_PROCESS_EXITED');
    const status = await call(port, '/actuator/health/readiness', {}).then((r) => r.status, () => 0);
    if (status === 200) return;
    await pause(500);
  }
  throw new Error('API_READINESS_TIMEOUT');
}

const agent = new Agent({ keepAlive: true, maxSockets: 128 });
function call(port, path, { method = 'GET', token, body, key } = {}) {
  const data = body === undefined ? undefined : JSON.stringify(body);
  return new Promise((done, reject) => {
    const request = httpRequest({ hostname: '127.0.0.1', port, path, method, agent, timeout: 10000, headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(key ? { 'Idempotency-Key': key } : {}),
      ...(data ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } : {}) } }, (response) => {
      const chunks = []; response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        let json; try { json = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { json = undefined; }
        done({ status: response.statusCode, body: json });
      });
    });
    request.on('error', () => reject(new Error('NETWORK'))); request.on('timeout', () => request.destroy(new Error('TIMEOUT')));
    request.end(data);
  });
}

// Mistura: 70% leituras, 20% disponibilidade, 10% gravações (reserva; metade das criadas é cancelada depois).
const classes = ['me', 'units', 'amenities', 'reservations', 'availability', 'create', 'cancel'];
const stats = Object.fromEntries(classes.map((name) => [name, { latencies: [], ok: 0, expected4xx: 0, errors: 0, codes: {} }]));
const cancellable = [];
let dropped = 0, inFlight = 0, maxInFlight = 0;
function token(resident) {
  if (!resident.token || Date.now() - resident.minted > 200_000) { resident.token = mintToken(resident.account); resident.minted = Date.now(); }
  return resident.token;
}
const pick = (list) => list[Math.floor(Math.random() * list.length)];
function operation() {
  const resident = pick(residents), base = `/v1/tenants/${tenant}`, roll = Math.random();
  const range = () => { const from = new Date(Date.now() + 86_400_000); return { from: from.toISOString(), until: new Date(from.getTime() + 7 * 86_400_000).toISOString() }; };
  if (roll < 0.15) return ['me', resident, '/v1/me', {}];
  if (roll < 0.30) return ['units', resident, `${base}/units`, {}];
  if (roll < 0.45) return ['amenities', resident, `${base}/amenities`, {}];
  if (roll < 0.70) { const r = range(); return ['reservations', resident, `${base}/reservations?unitId=${resident.unit}&from=${encodeURIComponent(r.from)}&until=${encodeURIComponent(r.until)}`, {}]; }
  if (roll < 0.90) { const r = range(); return ['availability', resident, `${base}/amenities/${pick(amenities)}/availability?unitId=${resident.unit}&from=${encodeURIComponent(r.from)}&until=${encodeURIComponent(r.until)}`, {}]; }
  const target = cancellable.length > 0 && roll >= 0.97 ? cancellable.shift() : null;
  if (target) return ['cancel', target.resident, `${base}/reservations/${target.id}/cancel`,
    { method: 'POST', body: { unitId: target.resident.unit, expectedVersion: target.version }, key: `load-cancel:${randomUUID()}` }];
  const start = new Date(Date.now() + (2 + Math.floor(Math.random() * 300)) * 86_400_000);
  start.setUTCHours(Math.floor(Math.random() * 20), 0, 0, 0);
  return ['create', resident, `${base}/reservations`, { method: 'POST', key: `load-create:${randomUUID()}`, body: { unitId: resident.unit,
    amenityId: pick(amenities), startsAt: start.toISOString(), endsAt: new Date(start.getTime() + 3_600_000).toISOString(), acceptedPolicyVersion: 1 } }];
}
async function fire(port) {
  if (inFlight >= MAX_IN_FLIGHT) { dropped++; return; }
  const [name, resident, path, options] = operation();
  const stat = stats[name];
  inFlight++; maxInFlight = Math.max(maxInFlight, inFlight);
  const started = process.hrtime.bigint();
  try {
    const result = await call(port, path, { ...options, token: token(resident) });
    stat.latencies.push(Number(process.hrtime.bigint() - started) / 1e6);
    // Só códigos de problema conhecidos no formato da API (nada de corpo livre).
    const code = result.status >= 400 && typeof result.body?.code === 'string' && /^[A-Z0-9_]{1,60}$/.test(result.body.code) ? `${result.status}:${result.body.code}` : String(result.status);
    stat.codes[code] = (stat.codes[code] ?? 0) + 1;
    if (result.status >= 200 && result.status < 300) {
      stat.ok++;
      if (name === 'create' && result.body?.reservationId && Math.random() < 0.5) cancellable.push({ id: result.body.reservationId, version: result.body.version, resident });
    } else if (result.status >= 400 && result.status < 500 && ![401, 403, 404].includes(result.status)) stat.expected4xx++; // conflito, cota etc.
    else stat.errors++;
  } catch (error) {
    stat.errors++; stat.codes[error.message] = (stat.codes[error.message] ?? 0) + 1;
  } finally { inFlight--; }
}
function percentile(sorted, p) { return sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(p / 100 * sorted.length) - 1)] : null; }
function procSample() {
  try {
    const stat = readFileSync(`/proc/${api.pid}/stat`, 'utf8').split(') ')[1].split(' ');
    const status = readFileSync(`/proc/${api.pid}/status`, 'utf8');
    return { cpuTicks: Number(stat[11]) + Number(stat[12]), rssKb: Number(/VmRSS:\s+(\d+)/.exec(status)?.[1] ?? 0) };
  } catch { return null; }
}

async function run(port) {
  // Aquecimento curto (JIT e pool) fora das medidas.
  for (let i = 0; i < 50; i++) await call(port, '/v1/me', { token: token(pick(residents)) }).catch(() => {});
  const samples = [], cpuStart = procSample(), began = Date.now();
  const sampler = setInterval(() => {
    try { samples.push({ dbConnections: Number(sql("select count(*) from pg_stat_activity where usename='cp_ci_login'")), inFlight, rssKb: procSample()?.rssKb ?? null }); }
    catch { /* amostra perdida não interrompe a medida */ }
  }, 2000);
  const pending = new Set();
  let sent = 0;
  while (Date.now() - began < DURATION * 1000) {
    const due = Math.floor((Date.now() - began) / 1000 * RPS);
    while (sent < due) { const task = fire(port); pending.add(task); task.finally(() => pending.delete(task)); sent++; }
    await pause(5);
  }
  const elapsed = (Date.now() - began) / 1000;
  await Promise.allSettled([...pending]);
  clearInterval(sampler);
  const cpuEnd = procSample();
  const ticks = cpuStart && cpuEnd ? cpuEnd.cpuTicks - cpuStart.cpuTicks : null;
  const report = { generatedAt: new Date().toISOString(), environment: 'GitHub Actions runner: API, PostgreSQL 17 e gerador na mesma máquina; sem rede real, sem Supabase/Vercel',
    capacityClaim: 'NENHUMA — número do runner, só para comparar versões', commit: process.env.GITHUB_SHA ?? null,
    target: { rps: RPS, seconds: DURATION, sessions: SESSIONS, dbPool: POOL, maxInFlight: MAX_IN_FLIGHT },
    achieved: { requests: sent, dropped, rps: Number(((sent - dropped) / elapsed).toFixed(1)), maxInFlight },
    saturation: { maxDbConnections: Math.max(0, ...samples.map((s) => s.dbConnections)), apiCpuSeconds: ticks === null ? null : ticks / 100,
      apiCpuShare: ticks === null ? null : Number((ticks / 100 / elapsed).toFixed(2)), maxRssMb: Math.round(Math.max(0, ...samples.map((s) => s.rssKb ?? 0)) / 1024) },
    cost: 'Não medido: o runner não representa o custo da homologação ou da produção.', classes: {} };
  let total = 0, errors = 0;
  for (const [name, stat] of Object.entries(stats)) {
    const sorted = stat.latencies.sort((a, b) => a - b);
    const count = stat.ok + stat.expected4xx + stat.errors;
    total += count; errors += stat.errors;
    report.classes[name] = { count, ok: stat.ok, expected4xx: stat.expected4xx, errors: stat.errors, codes: stat.codes,
      p50: percentile(sorted, 50), p95: percentile(sorted, 95), p99: percentile(sorted, 99), max: sorted.at(-1) ?? null };
  }
  const all = Object.values(stats).flatMap((s) => s.latencies).sort((a, b) => a - b);
  report.overall = { count: total, errors, errorRate: total ? Number((errors / total).toFixed(4)) : null,
    p50: percentile(all, 50), p95: percentile(all, 95), p99: percentile(all, 99) };
  report.reservationsCommitted = Number(sql(`select count(*) from cp.reservations where tenant_id='${tenant}'`));
  return report;
}
function publish(report) {
  const output = process.env.CP_LOAD_REPORT ?? join(process.env.RUNNER_TEMP ?? tmpdir(), 'condpulse-load-report.json');
  writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
  const ms = (value) => value === null ? '—' : value.toFixed(1);
  const lines = ['## CP-052 — carga sintética no runner (sem afirmação de capacidade)', '',
    `Alvo ${report.target.rps} rps por ${report.target.seconds} s, ${report.target.sessions} sessões, pool ${report.target.dbPool}. Obtido ${report.achieved.rps} rps; descartadas por saturação do gerador: ${report.achieved.dropped}.`,
    `Erros: ${report.overall.errors} de ${report.overall.count} (${(report.overall.errorRate * 100).toFixed(2)}%). Conexões máx. no banco: ${report.saturation.maxDbConnections}; CPU da API: ${report.saturation.apiCpuShare ?? '—'} núcleo; memória máx. ${report.saturation.maxRssMb} MB.`, '',
    '| Classe | Pedidos | 2xx | 4xx de regra | Erros | p50 ms | p95 ms | p99 ms |', '|---|---:|---:|---:|---:|---:|---:|---:|',
    ...Object.entries(report.classes).map(([name, c]) => `| ${name} | ${c.count} | ${c.ok} | ${c.expected4xx} | ${c.errors} | ${ms(c.p50)} | ${ms(c.p95)} | ${ms(c.p99)} |`),
    `| **total** | ${report.overall.count} | | | ${report.overall.errors} | ${ms(report.overall.p50)} | ${ms(report.overall.p95)} | ${ms(report.overall.p99)} |`, '',
    report.capacityClaim];
  console.log(lines.join('\n'));
  if (process.env.GITHUB_ACTIONS) {
    console.log(`::notice title=Carga sintética (runner, sem afirmação de capacidade)::${report.achieved.rps} rps obtidos; p50 ${ms(report.overall.p50)} ms, p95 ${ms(report.overall.p95)} ms, p99 ${ms(report.overall.p99)} ms; erros ${report.overall.errors}/${report.overall.count}; descartados ${report.achieved.dropped}; conexões máx. ${report.saturation.maxDbConnections}; CPU ${report.saturation.apiCpuShare} núcleo; memória ${report.saturation.maxRssMb} MB`);
    for (const [name, c] of Object.entries(report.classes))
      console.log(`::notice title=Carga ${name}::${c.count} pedidos; 2xx ${c.ok}; 4xx de regra ${c.expected4xx}; erros ${c.errors} ${JSON.stringify(c.codes)}; p50 ${ms(c.p50)} p95 ${ms(c.p95)} p99 ${ms(c.p99)} ms`);
  }
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${lines.join('\n')}\n`);
}

async function stop() {
  agent.destroy();
  if (api && api.exitCode === null) {
    api.kill('SIGTERM'); await Promise.race([once(api, 'exit').catch(() => {}), pause(5000)]);
    if (api.exitCode === null) api.kill('SIGKILL');
  }
  if (sandbox) { sandbox.closeAllConnections(); await new Promise((done) => sandbox.close(done)); }
  try { cleanupFixtures(); }
  finally {
    if (temporary) {
      const target = realpathSync(temporary), base = realpathSync(tmpdir());
      ensure(!lstatSync(temporary).isSymbolicLink() && dirname(target) === base && basename(target).startsWith('condpulse-load-ci-'), 'UNSAFE_TEMP_CLEANUP_TARGET');
      rmSync(target, { recursive: true, force: true });
    }
  }
}

let failed = false;
try {
  guard(); stage = 'fixtures'; seed(); stage = 'tls';
  const tls = tlsMaterial(); stage = 'jwks'; const issuer = await jwks(tls);
  const port = 4193; stage = 'api'; await startApi(issuer, port);
  stage = 'load'; const report = await run(port);
  stage = 'report'; publish(report);
  // O ensaio falha só se o próprio harness não exercitou a API (nenhuma leitura aceita) ou se nada foi gravado.
  ensure(report.classes.me.ok > 0 && report.classes.reservations.ok > 0, 'NO_SUCCESSFUL_READS');
  ensure(report.classes.create.ok > 0 && report.reservationsCommitted > 0, 'NO_SUCCESSFUL_WRITES');
} catch (error) {
  failed = true;
  const code = /^[A-Z0-9_]+$/.test(error.message) ? error.message : 'UNEXPECTED_ERROR';
  console.error(`Carga sintética falhou em ${stage}: ${code}`);
  // Anotação do Actions: o log completo do job pode não estar acessível a quem acompanha o PR.
  if (process.env.GITHUB_ACTIONS) console.log(`::error title=Carga sintética::${stage}: ${code}`);
} finally {
  try { await stop(); } catch { failed = true; console.error('Limpeza da carga sintética falhou.'); }
}
if (failed) process.exitCode = 1;
