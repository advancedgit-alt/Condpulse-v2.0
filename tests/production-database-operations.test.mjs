import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync,mkdtempSync,writeFileSync,rmSync,realpathSync,lstatSync,readdirSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {resolve,dirname,basename,join,delimiter} from 'node:path';
import {fileURLToPath} from 'node:url';
const workflow=readFileSync(new URL('../.github/workflows/production-database-operations.yml',import.meta.url),'utf8');
function section(name) {
  const begin=`// BEGIN AUDITABLE_${name}`,end=`// END AUDITABLE_${name}`;
  assert.equal(workflow.split(begin).length,2);assert.equal(workflow.split(end).length,2);
  return workflow.split(begin)[1].split(end)[0].split('\n').map(line=>line.replace(/^          /,'')).join('\n');
}
const request=await import(`data:text/javascript;base64,${Buffer.from(section('REQUEST_GATE')).toString('base64')}`);
const launcher=await import(`data:text/javascript;base64,${Buffer.from(section('OPERATION_LAUNCHER')).toString('base64')}`);
const sha='a'.repeat(40),project='tkxcujckpsuawnzkvpjg';
const inspect=()=>({GITHUB_EVENT_NAME:'workflow_dispatch',GITHUB_REPOSITORY:'advancedgit-alt/Condpulse-v2.0',
  GITHUB_REF:'refs/heads/ccr-5e725112-3x8hyg',GITHUB_SHA:sha,EXPECTED_COMMIT:sha,REQUEST_OPERATION:'inspect',MIGRATION_ACK:'',MAINTENANCE_CONFIRMED:'false'});

test('inspect is inert and migrate needs maintenance plus an explicit range bound to the commit and production project',()=>{
  assert.doesNotThrow(()=>request.validateRequest(inspect()));
  assert.throws(()=>request.validateRequest({...inspect(),REQUEST_OPERATION:'migrate'}),/MAINTENANCE_ACK_REQUIRED/);
  const migrate={...inspect(),REQUEST_OPERATION:'migrate',MAINTENANCE_CONFIRMED:'true',MIGRATION_ACK:`${project}:001-015:${sha}`};
  assert.doesNotThrow(()=>request.validateRequest(migrate));
  for(const ack of ['',`${project}:001-015:${'b'.repeat(40)}`,`${project}:000-015:${sha}`,`${project}:015-001:${sha}`,`${project}:1-15:${sha}`,
    `rpnrqfrfwywglvyykgrg:001-015:${sha}`,`${project}:001-015:${sha} `,`${project}:001-015`])
    assert.throws(()=>request.validateRequest({...migrate,MIGRATION_ACK:ack}),/EXPLICIT_MIGRATION_ACK_REQUIRED/);
  assert.throws(()=>request.validateRequest({...inspect(),MIGRATION_ACK:`${project}:001-015:${sha}`}),/MIGRATION_ACK_NOT_APPLICABLE/);
});

test('repository, branch, event, operation and reviewed exact commit cannot be selected around the gate',()=>{
  for(const override of [{GITHUB_EVENT_NAME:'push'},{GITHUB_EVENT_NAME:'pull_request'},{GITHUB_REPOSITORY:'other/Condpulse-v2.0'},
    {GITHUB_REF:'refs/heads/feature'},{GITHUB_REF:'refs/tags/ccr-5e725112-3x8hyg'}])
    assert.throws(()=>request.validateRequest({...inspect(),...override}),/REQUEST_CONTEXT_DENIED/);
  for(const commit of ['main','a'.repeat(7),'A'.repeat(40),'b'.repeat(40)])
    assert.throws(()=>request.validateRequest({...inspect(),EXPECTED_COMMIT:commit}),/EXACT_REVIEWED_COMMIT_REQUIRED/);
  for(const operation of ['baseline','clean','repair','onboarding','__proto__'])
    assert.throws(()=>request.validateRequest({...inspect(),REQUEST_OPERATION:operation}),/OPERATION_DENIED/);
});

test('operation child has the fixed production target, secret only in environment and no inherited overrides',()=>{
  const secret='SYNTHETIC_PASSWORD_NOT_A_REAL_CREDENTIAL';
  const source={RUNNER_TEMP:'/tmp/synthetic',JAVA_HOME:'/synthetic/java',PATH:'/synthetic/bin',DATABASE_PASSWORD:secret,REQUEST_OPERATION:'inspect',
    SPRING_APPLICATION_JSON:'untrusted',JAVA_TOOL_OPTIONS:'untrusted',DATABASE_URL:'untrusted',HTTPS_PROXY:'untrusted'};
  const invocation=launcher.operationInvocation(source);
  assert.equal(invocation.env.DATABASE_PASSWORD,secret);assert.ok(!invocation.args.join(' ').includes(secret));
  assert.equal(invocation.env.DATABASE_URL,'jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt');
  assert.equal(invocation.env.DATABASE_USER,`postgres.${project}`);
  assert.deepEqual(Object.keys(invocation.env).sort(),['PATH','JAVA_HOME','DATABASE_URL','DATABASE_USER','DATABASE_PASSWORD','CP_MIGRATION_ACK','CP_EXPECTED_COMMIT'].sort());
  assert.deepEqual(invocation.args.slice(-2),['br.com.condpulse.bootstrap.ProductionDatabaseGuard','inspect']);
  assert.equal(launcher.operationInvocation({...source,REQUEST_OPERATION:'migrate',CP_MIGRATION_ACK:`${project}:001-015:${sha}`}).env.CP_MIGRATION_ACK,`${project}:001-015:${sha}`);
  for(const operation of ['baseline','repair']) assert.throws(()=>launcher.operationInvocation({...source,REQUEST_OPERATION:operation}),/OPERATION_CONFIGURATION_INVALID/);
  assert.throws(()=>launcher.operationInvocation({...source,DATABASE_PASSWORD:''}),/OPERATION_CONFIGURATION_INVALID/);
});

test('workflow is manual, serialized per project and grants no secret access to builds or PRs',()=>{
  assert.match(workflow,/^on:\n  workflow_dispatch:/m);
  assert.doesNotMatch(workflow,/^  (?:push|pull_request|pull_request_target|schedule|workflow_call):/m);
  assert.match(workflow,/permissions:\n  contents: read\n/);
  assert.doesNotMatch(workflow,/(?:contents|packages|id-token): write/);
  assert.match(workflow,/group: condpulse-database-operations-tkxcujckpsuawnzkvpjg\n  cancel-in-progress: false/);
  assert.match(workflow,/needs: gate/);assert.match(workflow,/name: condpulse-production-database-operations/);
  assert.match(workflow,/ref: \$\{\{ github.sha \}\}/);assert.match(workflow,/persist-credentials: false/);
  assert.equal((workflow.match(/secrets\./g)??[]).length,1);
  assert.ok(workflow.indexOf('secrets.CP_PRODUCTION_DATABASE_PASSWORD')>workflow.indexOf('name: Execute only the manually selected'));
  assert.doesNotMatch(workflow,/upload-artifact|actions\/cache|set -x|rpnrqfrfwywglvyykgrg|\.repair\(|\.clean\(|\.baseline\(|create role|CREATE ROLE/);
  for(const action of workflow.matchAll(/uses: ([^\n]+)/g)) assert.match(action[1],/@[a-f0-9]{40}$/);
});

test('production guard reads before writing, refuses baseline history and validates through the official Flyway API',()=>{
  const java=section('PRODUCTION_GUARD');
  assert.match(java,/static final String PROJECT = "tkxcujckpsuawnzkvpjg"/);
  assert.match(java,/require\(from >= 1 && to >= from\)/);
  assert.match(java,/version >= 1 && version <= to && "SQL"\.equals/);
  assert.match(java,/"SCHEMA"\.equals\(r\.getString\("type"\)\)/);
  assert.match(java,/require\(last >= from - 1 && last <= to\)/);assert.match(java,/require\(latest == to\)/);
  assert.match(java,/migration\.getState\(\)\.isResolved\(\)/);assert.match(java,/ack\.endsWith\(":" \+ commit\)/);
  assert.match(java,/skipDefaultCallbacks\(true\)/);
  assert.match(java,/pg_get_userbyid\(c\.relowner\)<>'postgres'/);assert.match(java,/current_user,pg_try_advisory_lock/);
  assert.match(java,/baselineOnMigrate\(false\)\.cleanDisabled\(true\)/);
  assert.match(java,/validateOnMigrate\(true\)\.outOfOrder\(false\)\.target\(String\.valueOf\(to\)\)/);
  assert.ok(java.indexOf('validateWithResult()')<java.indexOf('flyway.migrate()'));
  assert.doesNotMatch(java,/\.baseline\(|\.repair\(|\.clean\(|SpringApplication|UPDATE |INSERT |DELETE |CREATE /);
});

const root=fileURLToPath(new URL('..',import.meta.url));
const jar=join(root,'apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar');
const minimal=Object.fromEntries(['PATH','JAVA_HOME','HOME','TMPDIR','TMP','TEMP','SystemRoot','COMSPEC','WINDIR','USERPROFILE']
  .filter(key=>process.env[key]!==undefined).map(key=>[key,process.env[key]]));
const javac=spawnSync('javac',['-version'],{encoding:'utf8',env:minimal,timeout:10000,windowsHide:true});
const hasCompiler=javac.status===0 && /javac (?:2[5-9]|[3-9][0-9])\b/.test(`${javac.stdout??''}${javac.stderr??''}`);
function compiled(prefix) {
  const temporary=resolve(mkdtempSync(join(tmpdir(),prefix)));
  const unpack=spawnSync('jar',['-xf',jar],{cwd:temporary,env:minimal,encoding:'utf8',timeout:30000,windowsHide:true});
  assert.equal(unpack.status,0,'Extract reviewed backend JAR');
  const source=join(temporary,'ProductionDatabaseGuard.java');writeFileSync(source,section('PRODUCTION_GUARD'),{mode:0o600});
  const classes=join(temporary,'BOOT-INF/classes'),classpath=[classes,join(temporary,'BOOT-INF/lib/*')].join(delimiter);
  return {temporary,source,classes,classpath};
}
function cleanup(temporary,prefix) {
  const actual=realpathSync(temporary),base=realpathSync(tmpdir());
  assert.equal(dirname(actual),base);assert.ok(basename(actual).startsWith(prefix));
  assert.equal(lstatSync(temporary).isSymbolicLink(),false);rmSync(actual,{recursive:true,force:true});
}
test('exact embedded production guard compiles and fails closed without target before any connection',
  {skip:!existsSync(jar)||!hasCompiler?'Requires built backend JAR and Java25 JDK; runs after Maven in CI.':false},()=>{
  const {temporary,source,classes,classpath}=compiled('condpulse-prodops-compile-');
  try {
    const compile=spawnSync('javac',['-cp',classpath,'-d',classes,source],{env:minimal,encoding:'utf8',timeout:30000,windowsHide:true});
    assert.equal(compile.status,0,`Compile exact audited guard: ${compile.stderr}`);
    const sentinel='SYNTHETIC_DATABASE_PASSWORD_MUST_NEVER_BE_OUTPUT';
    for(const operation of ['inspect','migrate']) {
      const closed=spawnSync('java',[`-Dlogback.configurationFile=${join(classes,'bootstrap/logback-baseline.xml')}`,'-cp',classpath,
        'br.com.condpulse.bootstrap.ProductionDatabaseGuard',operation],{env:{...minimal,DATABASE_PASSWORD:sentinel},encoding:'utf8',timeout:10000,windowsHide:true});
      assert.equal(closed.status,1);
      assert.deepEqual(JSON.parse(closed.stdout.trim()),{ok:false,code:'PRODUCTION_GUARD_FAILED_OR_RESULT_UNKNOWN',sqlState:null});
      assert.equal(closed.stderr.trim(),'');assert.ok(!closed.stdout.includes(sentinel));
    }
  } finally { cleanup(temporary,'condpulse-prodops-compile-'); }
});

const disposable=process.env.CP_DB_TEST_OK==='DISPOSABLE' && ['localhost','127.0.0.1'].includes(process.env.PGHOST)
  && process.env.PGDATABASE==='condpulse_ci' && process.env.PGUSER==='postgres' && process.env.PGPASSWORD==='condpulse_ci_only'
  && /^[0-9]{1,5}$/.test(process.env.PGPORT??'');
test('exact production guard migrates a fresh database from V001, replays and blocks short range, gap, legacy baseline, checksum and history above the JAR',
  {skip:!existsSync(jar)||!hasCompiler||!disposable?'Requires JDK25, built JAR and explicitly disposable loopback PostgreSQL; runs after Maven in foundation CI.':false},()=>{
  const {temporary,source,classes,classpath}=compiled('condpulse-prodops-jdbc-');
  try {
    const harness=join(root,'tests/database/ProductionOperationsHarness.java');
    const compile=spawnSync('javac',['-cp',classpath,'-d',classes,source,harness],{env:minimal,encoding:'utf8',timeout:30000,windowsHide:true});
    assert.equal(compile.status,0,`Compile exact workflow guard and isolated harness: ${compile.stderr}`);
    const run=spawnSync('java',[`-Dlogback.configurationFile=${join(classes,'bootstrap/logback-baseline.xml')}`,'-cp',classpath,
      'br.com.condpulse.bootstrap.ProductionOperationsHarness',temporary],
      {env:{...minimal,CP_DB_TEST_OK:'DISPOSABLE',PGHOST:process.env.PGHOST,PGPORT:process.env.PGPORT,PGDATABASE:'condpulse_ci',PGUSER:'postgres',
        PGPASSWORD:'condpulse_ci_only'},encoding:'utf8',timeout:180000,windowsHide:true});
    assert.equal(run.status,0,`Disposable production guard scenarios: ${run.stderr}`);
    const versions=readdirSync(join(root,'db/migrations')).filter(name=>/^V[0-9]{3}__.+\.sql$/.test(name)).length;
    assert.deepEqual(JSON.parse(run.stdout.trim()),{passed:true,fresh:versions,replay:0,shortRangeDenied:true,gapDenied:true,legacyBaselineDenied:true,
      checksumDriftDenied:true,aboveHeadDenied:true});
    assert.equal(run.stderr.trim(),'');
  } finally { cleanup(temporary,'condpulse-prodops-jdbc-'); }
});
