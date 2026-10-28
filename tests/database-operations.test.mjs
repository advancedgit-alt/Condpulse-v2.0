import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync,mkdtempSync,writeFileSync,rmSync,realpathSync,lstatSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {resolve,dirname,basename,join,delimiter} from 'node:path';
import {fileURLToPath} from 'node:url';
const workflow=readFileSync(new URL('../.github/workflows/database-operations.yml',import.meta.url),'utf8');
function section(name) {
  const begin=`// BEGIN AUDITABLE_${name}`,end=`// END AUDITABLE_${name}`;
  assert.equal(workflow.split(begin).length,2);assert.equal(workflow.split(end).length,2);
  return workflow.split(begin)[1].split(end)[0].split('\n').map(line=>line.replace(/^          /,'')).join('\n');
}
const request=await import(`data:text/javascript;base64,${Buffer.from(section('REQUEST_GATE')).toString('base64')}`);
const launcher=await import(`data:text/javascript;base64,${Buffer.from(section('OPERATION_LAUNCHER')).toString('base64')}`);
const sha='a'.repeat(40),project='rpnrqfrfwywglvyykgrg';
const inspect=()=>({GITHUB_EVENT_NAME:'workflow_dispatch',GITHUB_REPOSITORY:'advancedgit-alt/Condpulse-v2.0',
  GITHUB_REF:'refs/heads/ccr-5e725112-3x8hyg',GITHUB_SHA:sha,EXPECTED_COMMIT:sha,
  REQUEST_OPERATION:'inspect',BASELINE_ACK:'',MIGRATION_ACK:'',MAINTENANCE_CONFIRMED:'false'});

test('inspect is inert and write choices require independent explicit acknowledgements',()=>{
  assert.doesNotThrow(()=>request.validateRequest(inspect()));
  for(const operation of ['baseline','migrate']) assert.throws(()=>request.validateRequest({...inspect(),REQUEST_OPERATION:operation}),/MAINTENANCE_ACK_REQUIRED/);
  const baseline={...inspect(),REQUEST_OPERATION:'baseline',MAINTENANCE_CONFIRMED:'true',BASELINE_ACK:`${project}:002:${'b'.repeat(64)}`};
  assert.doesNotThrow(()=>request.validateRequest(baseline));
  for(const ack of ['',`${project}:003:${'b'.repeat(64)}`,`other:002:${'b'.repeat(64)}`])
    assert.throws(()=>request.validateRequest({...baseline,BASELINE_ACK:ack}),/EXPLICIT_BASELINE_ACK_REQUIRED/);
  assert.throws(()=>request.validateRequest({...baseline,REQUEST_OPERATION:'inspect'}),/BASELINE_ACK_NOT_APPLICABLE/);
});

test('repository, branch, event and reviewed exact commit cannot be selected around the gate',()=>{
  for(const override of [{GITHUB_EVENT_NAME:'push'},{GITHUB_EVENT_NAME:'pull_request'},{GITHUB_EVENT_NAME:'schedule'},
    {GITHUB_REPOSITORY:'other/Condpulse-v2.0'},{GITHUB_REF:'refs/heads/feature'},{GITHUB_REF:'refs/tags/ccr-5e725112-3x8hyg'}])
    assert.throws(()=>request.validateRequest({...inspect(),...override}),/REQUEST_CONTEXT_DENIED/);
  for(const commit of ['main','a'.repeat(7),'A'.repeat(40),'b'.repeat(40),'$(touch /tmp/untrusted)'])
    assert.throws(()=>request.validateRequest({...inspect(),EXPECTED_COMMIT:commit}),/EXACT_REVIEWED_COMMIT_REQUIRED/);
  for(const operation of ['clean','repair','onboarding','migrate; echo unsafe','__proto__'])
    assert.throws(()=>request.validateRequest({...inspect(),REQUEST_OPERATION:operation}),/OPERATION_DENIED/);
});

test('migrate binds its acknowledgement to V003-V005 and this reviewed commit',()=>{
  const migrate={...inspect(),REQUEST_OPERATION:'migrate',MAINTENANCE_CONFIRMED:'true',MIGRATION_ACK:`${project}:003-005:${sha}`};
  assert.doesNotThrow(()=>request.validateRequest(migrate));
  for(const ack of ['',`${project}:003-005:${'b'.repeat(40)}`,`${project}:003-006:${sha}`])
    assert.throws(()=>request.validateRequest({...migrate,MIGRATION_ACK:ack}),/EXPLICIT_MIGRATION_ACK_REQUIRED/);
  assert.throws(()=>request.validateRequest({...migrate,BASELINE_ACK:`${project}:002:${'c'.repeat(64)}`}),/BASELINE_ACK_NOT_APPLICABLE/);
});

test('operation child has fixed target, secret only in environment and no inherited overrides',()=>{
  const secret='SYNTHETIC_PASSWORD_NOT_A_REAL_CREDENTIAL';
  const source={RUNNER_TEMP:'/tmp/synthetic',JAVA_HOME:'/synthetic/java',PATH:'/synthetic/bin',DATABASE_PASSWORD:secret,
    REQUEST_OPERATION:'inspect',SPRING_APPLICATION_JSON:'untrusted',JAVA_TOOL_OPTIONS:'untrusted',DATABASE_URL:'untrusted',HTTPS_PROXY:'untrusted'};
  const invocation=launcher.operationInvocation(source);
  assert.equal(invocation.env.DATABASE_PASSWORD,secret);assert.ok(!invocation.args.join(' ').includes(secret));
  assert.match(invocation.env.DATABASE_URL,/^jdbc:postgresql:\/\/aws-1-sa-east-1\.pooler\.supabase\.com:5432\/postgres\?sslmode=verify-full&sslrootcert=\/run\/secrets\/db-ca\.crt$/);
  assert.equal(invocation.env.DATABASE_USER,`postgres.${project}`);
  assert.deepEqual(Object.keys(invocation.env).sort(),['PATH','JAVA_HOME','DATABASE_URL','DATABASE_USER','DATABASE_PASSWORD','CP_BASELINE_PROJECT_REF','CP_BASELINE_ACK'].sort());
  assert.deepEqual(invocation.args.slice(-2),['br.com.condpulse.bootstrap.BootstrapBaseline','inspect']);
  assert.equal(launcher.operationInvocation({...source,REQUEST_OPERATION:'migrate'}).args.at(-1),'br.com.condpulse.bootstrap.DatabaseOperationsMigrateGuard');
  assert.throws(()=>launcher.operationInvocation({...source,REQUEST_OPERATION:'repair'}),/OPERATION_CONFIGURATION_INVALID/);
});

test('workflow is manual, serialized and grants no secret access to builds or PRs',()=>{
  assert.match(workflow,/^on:\n  workflow_dispatch:/m);
  assert.doesNotMatch(workflow,/^  (?:push|pull_request|pull_request_target|schedule|workflow_call):/m);
  assert.match(workflow,/permissions:\n  contents: read\n/);
  assert.doesNotMatch(workflow,/(?:contents|packages|id-token): write/);
  assert.match(workflow,/group: condpulse-database-operations-rpnrqfrfwywglvyykgrg\n  cancel-in-progress: false/);
  assert.match(workflow,/needs: gate/);assert.match(workflow,/name: condpulse-database-operations/);
  assert.match(workflow,/ref: \$\{\{ github.sha \}\}/);assert.match(workflow,/persist-credentials: false/);
  assert.equal((workflow.match(/secrets\./g)??[]).length,1);
  assert.ok(workflow.indexOf('secrets.CP_DATABASE_OPERATIONS_PASSWORD')>workflow.indexOf('name: Execute only the manually selected'));
  assert.doesNotMatch(workflow,/upload-artifact|actions\/cache|set -x|baseline-on-migrate=true|\.repair\(|\.clean\(|create role|CREATE ROLE/);
  for(const action of workflow.matchAll(/uses: ([^\n]+)/g)) assert.match(action[1],/@[a-f0-9]{40}$/);
});

test('migration guard only reads history then validates source before official migrate',()=>{
  const java=section('MIGRATION_GUARD');
  assert.match(java,/BootstrapBaseline\.productionConfig\(System\.getenv\(\)\)/);
  assert.match(java,/BootstrapManifest\.sourceCodes\(BootstrapManifest\.load\(\)\)\.isEmpty\(\)/);
  assert.match(java,/version == 2 && "BASELINE"\.equals/);
  assert.match(java,/version >= 3 && version <= 5/);assert.match(java,/r\.getBoolean\("success"\)/);
  assert.match(java,/r\.getObject\("checksum"\) != null/);assert.match(java,/current_user,pg_try_advisory_lock/);
  assert.match(java,/pg_get_userbyid\(c\.relowner\)<>'postgres'/);
  assert.match(java,/baselineOnMigrate\(false\)\.cleanDisabled\(true\)/);
  assert.match(java,/validateOnMigrate\(true\)\.outOfOrder\(false\)\.target\("005"\)/);
  assert.ok(java.indexOf('validateWithResult()')<java.indexOf('flyway.migrate()'));
  assert.match(java,/RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED/);
  assert.doesNotMatch(java,/\.baseline\(|\.repair\(|\.clean\(|SpringApplication|UPDATE |INSERT |DELETE |CREATE /);
});

test('CA uses one fixed HTTPS source, strict verification and read-only mount path',()=>{
  assert.match(workflow,/curl --proto '=https' --tlsv1.2 --fail/);
  assert.match(workflow,/--connect-timeout 5 --max-time 20 --max-filesize 65536/);
  assert.match(workflow,/https:\/\/supabase-downloads\.s3-ap-southeast-1\.amazonaws\.com\/prod\/ssl\/prod-ca-2021\.crt/);
  assert.match(workflow,/openssl verify -check_ss_sig -CAfile/);assert.match(workflow,/openssl x509 -in "\$ca" -noout -checkend 86400/);
  assert.match(workflow,/sudo install -m 0444 "\$ca" \/run\/secrets\/db-ca.crt/);
  assert.doesNotMatch(workflow,/curl .*--insecure|NODE_TLS_REJECT_UNAUTHORIZED|sslmode=require|sslmode=disable/);
});

const root=fileURLToPath(new URL('..',import.meta.url));
const jar=join(root,'apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar');
const minimal=Object.fromEntries(['PATH','JAVA_HOME','HOME','TMPDIR','TMP','TEMP','SystemRoot','COMSPEC','WINDIR','USERPROFILE']
  .filter(key=>process.env[key]!==undefined).map(key=>[key,process.env[key]]));
const javac=spawnSync('javac',['-version'],{encoding:'utf8',env:minimal,timeout:10000,windowsHide:true});
const hasCompiler=javac.status===0 && /javac (?:2[5-9]|[3-9][0-9])\b/.test(`${javac.stdout??''}${javac.stderr??''}`);
test('exact embedded Java guard compiles and fails closed without project before JDBC or Spring',
  {skip:!existsSync(jar)||!hasCompiler?'Requires built backend JAR and Java25 JDK; compile/closed-path check runs after Maven in CI.':false},()=>{
  const temporary=resolve(mkdtempSync(join(tmpdir(),'condpulse-dbops-compile-')));
  try {
    const unpack=spawnSync('jar',['-xf',jar],{cwd:temporary,env:minimal,encoding:'utf8',timeout:30000,windowsHide:true});
    assert.equal(unpack.status,0,'Extract reviewed backend JAR');
    const source=join(temporary,'DatabaseOperationsMigrateGuard.java');writeFileSync(source,section('MIGRATION_GUARD'),{mode:0o600});
    const classes=join(temporary,'BOOT-INF/classes'),libraries=join(temporary,'BOOT-INF/lib/*');
    const classpath=[classes,libraries].join(delimiter);
    const compile=spawnSync('javac',['-cp',classpath,'-d',classes,source],{env:minimal,encoding:'utf8',timeout:30000,windowsHide:true});
    assert.equal(compile.status,0,`Compile exact audited guard: ${compile.stderr}`);
    const sentinel='SYNTHETIC_DATABASE_PASSWORD_MUST_NEVER_BE_OUTPUT';
    const closed=spawnSync('java',[`-Dlogback.configurationFile=${join(classes,'bootstrap/logback-baseline.xml')}`,
      '-cp',classpath,'br.com.condpulse.bootstrap.DatabaseOperationsMigrateGuard'],
      {env:{...minimal,DATABASE_PASSWORD:sentinel},encoding:'utf8',timeout:10000,windowsHide:true});
    assert.equal(closed.status,1);assert.deepEqual(JSON.parse(closed.stdout.trim()),
      {migrated:false,code:'MIGRATION_GUARD_FAILED_OR_RESULT_UNKNOWN'});
    assert.equal(closed.stderr.trim(),'');assert.ok(!closed.stdout.includes(sentinel));
    assert.doesNotMatch(closed.stdout,/Spring|Hikari|jdbc:postgresql|password/i);
  } finally {
    const actual=realpathSync(temporary),base=realpathSync(tmpdir());
    assert.equal(dirname(actual),base);assert.ok(basename(actual).startsWith('condpulse-dbops-compile-'));
    assert.equal(lstatSync(temporary).isSymbolicLink(),false);rmSync(actual,{recursive:true,force:true});
  }
});

const disposable=process.env.CP_DB_TEST_OK==='DISPOSABLE' && ['localhost','127.0.0.1'].includes(process.env.PGHOST)
  && process.env.PGDATABASE==='condpulse_ci' && process.env.PGUSER==='postgres' && process.env.PGPASSWORD==='condpulse_ci_only'
  && /^[0-9]{1,5}$/.test(process.env.PGPORT??'');
test('exact migration guard applies official legacy baseline then replays and blocks incompatible history/checksum',
  {skip:!existsSync(jar)||!hasCompiler||!disposable?'Requires JDK25, built JAR and explicitly disposable loopback PostgreSQL; runs after Maven in foundation CI.':false},()=>{
  const temporary=resolve(mkdtempSync(join(tmpdir(),'condpulse-dbops-jdbc-')));
  try {
    const unpack=spawnSync('jar',['-xf',jar],{cwd:temporary,env:minimal,encoding:'utf8',timeout:30000,windowsHide:true});
    assert.equal(unpack.status,0);
    const source=join(temporary,'DatabaseOperationsMigrateGuard.java');writeFileSync(source,section('MIGRATION_GUARD'),{mode:0o600});
    const classes=join(temporary,'BOOT-INF/classes'),classpath=[classes,join(temporary,'BOOT-INF/lib/*')].join(delimiter);
    const harness=join(root,'tests/database/DatabaseOperationsHarness.java');
    const compile=spawnSync('javac',['-cp',classpath,'-d',classes,source,harness],{env:minimal,encoding:'utf8',timeout:30000,windowsHide:true});
    assert.equal(compile.status,0,`Compile exact workflow and isolated harness: ${compile.stderr}`);
    const run=spawnSync('java',[`-Dlogback.configurationFile=${join(classes,'bootstrap/logback-baseline.xml')}`,
      '-cp',classpath,'br.com.condpulse.bootstrap.DatabaseOperationsHarness',temporary],
      {env:{...minimal,CP_DB_TEST_OK:'DISPOSABLE',PGHOST:process.env.PGHOST,PGPORT:process.env.PGPORT,
        PGDATABASE:'condpulse_ci',PGUSER:'postgres',PGPASSWORD:'condpulse_ci_only'},
      encoding:'utf8',timeout:120000,windowsHide:true});
    assert.equal(run.status,0,`Disposable guard scenarios: ${run.stderr}`);
    assert.deepEqual(JSON.parse(run.stdout.trim()),{passed:true,positive:3,replay:0,missingBaselineDenied:true,checksumDriftDenied:true,aboveHeadDenied:true});
    assert.equal(run.stderr.trim(),'');
  } finally {
    const actual=realpathSync(temporary),base=realpathSync(tmpdir());
    assert.equal(dirname(actual),base);assert.ok(basename(actual).startsWith('condpulse-dbops-jdbc-'));
    assert.equal(lstatSync(temporary).isSymbolicLink(),false);rmSync(actual,{recursive:true,force:true});
  }
});
