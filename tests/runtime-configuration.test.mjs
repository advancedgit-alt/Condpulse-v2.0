import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync,mkdtempSync,rmSync,realpathSync,lstatSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {join,resolve,dirname,basename,delimiter} from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('..',import.meta.url));
const directory=join(root,'infra/scripts/runtime-configuration');
const jar=join(root,'apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar');
const source=readFileSync(join(directory,'RuntimeConfiguration.java'),'utf8');
const client=readFileSync(join(directory,'RuntimeVercelClient.java'),'utf8');
const minimal=Object.fromEntries(['PATH','JAVA_HOME','HOME','TMPDIR','TMP','TEMP','SystemRoot','COMSPEC','WINDIR','USERPROFILE']
  .filter(key=>process.env[key]!==undefined).map(key=>[key,process.env[key]]));
const compiler=spawnSync('javac',['-version'],{env:minimal,encoding:'utf8',timeout:10000,windowsHide:true});
const capable=existsSync(jar)&&compiler.status===0&&/javac (?:2[5-9]|[3-9][0-9])\b/.test(`${compiler.stdout??''}${compiler.stderr??''}`);
const unavailable='Requires the reviewed backend JAR and Java25 JDK; runs after Maven in CI.';

test('standalone production entry fixes target and validates source TLS and state without Spring or schema mutation',()=>{
  assert.match(source,/BootstrapBaseline\.productionConfig\(env\)/);
  assert.match(source,/BootstrapManifest\.sourceCodes\(BootstrapManifest\.load\(\)\)\.isEmpty\(\)/);
  assert.match(source,/requireTls\(owner\)/);assert.match(source,/requireTls\(connection\)/);
  assert.match(source,/PasswordUtil\.encodeScramSha256\(password\.toCharArray\(\)\)/);
  assert.match(source,/new byte\[36\]/);assert.match(source,/interval '10 minutes'/);
  assert.match(source,/NOLOGIN PASSWORD NULL/);assert.match(source,/cp_runtime_/);
  assert.match(source,/WITH ADMIN FALSE/);assert.match(source,/WITH SET FALSE/);
  assert.doesNotMatch(source,/SpringApplication|\.migrate\(|\.baseline\(|\.repair\(|\.clean\(|CREATE TABLE|DROP ROLE/);
  assert.ok(source.indexOf('foundation(owner,flyway)')<source.indexOf('stage(owner,config'));
  assert.ok(source.indexOf('verifyLogin(login')<source.indexOf('api.put('));
  assert.ok(source.indexOf('api.inspect(); reconcile(')<source.indexOf('ready(owner,config)'));
  assert.doesNotMatch(client,/[?&]decrypt=true/);
  assert.match(client,/PUBLIC_LEGACY_KEYS/);assert.match(client,/Boolean\.TRUE\.equals\(clear\.get\("decrypted"\)\)/);
  assert.match(client,/"https:\/\/api\.vercel\.com"/);assert.match(client,/Redirect\.NEVER/);
  assert.match(client,/response\.get\("failed"\) instanceof List<\?> failed && failed\.isEmpty\(\)/);
});

function compiled(harness,action) {
  const temporary=resolve(mkdtempSync(join(tmpdir(),'condpulse-runtime-test-')));
  try {
    const unpack=spawnSync('jar',['-xf',jar],{cwd:temporary,env:minimal,encoding:'utf8',timeout:30000,windowsHide:true});
    assert.equal(unpack.status,0,'Extract reviewed JAR');
    const classes=join(temporary,'BOOT-INF/classes'),classpath=[classes,join(temporary,'BOOT-INF/lib/*')].join(delimiter);
    const sources=[join(directory,'RuntimeConfiguration.java'),join(directory,'RuntimeVercelClient.java')];
    if(harness)sources.push(join(root,'tests/runtime',`${harness}.java`));
    const compile=spawnSync('javac',['-cp',classpath,'-d',classes,...sources],{env:minimal,encoding:'utf8',timeout:30000,windowsHide:true});
    assert.equal(compile.status,0,`Compile exact runtime sources and fixture: ${compile.stderr}`);
    return action({classpath,classes,temporary});
  } finally {
    const actual=realpathSync(temporary),base=realpathSync(tmpdir());
    assert.equal(dirname(actual),base);assert.ok(basename(actual).startsWith('condpulse-runtime-test-'));
    assert.equal(lstatSync(temporary).isSymbolicLink(),false);rmSync(actual,{recursive:true,force:true});
  }
}
function run({classpath,classes},main,env={},timeout=30000) {
  return spawnSync('java',[`-Dlogback.configurationFile=${join(classes,'bootstrap/logback-baseline.xml')}`,
    '-cp',classpath,`br.com.condpulse.bootstrap.${main}`],{env:{...minimal,...env},encoding:'utf8',timeout,windowsHide:true});
}

test('exact runtime helper compiles and fails closed without a project before JDBC or HTTP',
  {skip:capable?false:unavailable},()=>compiled(null,compiled=>{
    const password='SYNTHETIC_OWNER_PASSWORD_MUST_NEVER_BE_OUTPUT',token='SYNTHETIC_VERCEL_TOKEN_MUST_NEVER_BE_OUTPUT';
    const result=run(compiled,'RuntimeConfiguration',{DATABASE_PASSWORD:password,CP_VERCEL_TOKEN:token});
    assert.equal(result.status,1);assert.equal(result.stderr.trim(),'');
    assert.deepEqual(JSON.parse(result.stdout.trim()),{eligible:false,applied:false,alreadyApplied:false,state:'NOT_ATTEMPTED',fingerprint:'',code:'RUNTIME_PREFLIGHT_FAILED'});
    assert.ok(!result.stdout.includes(password)&&!result.stdout.includes(token));
    assert.doesNotMatch(result.stdout,/Spring|Hikari|jdbc:postgresql/);
  }));

test('official HTTP transport restricts targets, never retrieves secrets and rejects partial writes or redirects',
  {skip:capable?false:unavailable},()=>compiled('RuntimeVercelHttpHarness',compiled=>{
    const result=run(compiled,'RuntimeVercelHttpHarness');
    assert.equal(result.status,0,`Synthetic HTTP protocol fixture: ${result.stderr}`);assert.equal(result.stderr.trim(),'');
    assert.deepEqual(JSON.parse(result.stdout.trim()),{passed:true,httpRequests:32,writeRequests:5,legacyPublicConfigs:7,
      secretReadbackBlocked:true,fixedTargets:true,partialWriteDenied:true,closeOutcomePreserved:true});
    assert.doesNotMatch(result.stdout,/SYNTHETIC_|Bearer|jdbc:postgresql/);
  }));

const disposable=process.env.CP_DB_TEST_OK==='DISPOSABLE'&&['localhost','127.0.0.1'].includes(process.env.PGHOST)
  &&process.env.PGDATABASE==='condpulse_ci'&&process.env.PGUSER==='postgres'&&process.env.PGPASSWORD==='condpulse_ci_only'
  &&/^[0-9]{1,5}$/.test(process.env.PGPORT??'');
test('exact provisioning core proves SCRAM login, role isolation, replay, partial recovery and drift on disposable PostgreSQL',
  {skip:capable&&disposable?false:'Requires JDK25, reviewed JAR and explicitly disposable loopback PostgreSQL; runs after Maven in foundation CI.'},()=>
    compiled('RuntimeConfigurationHarness',compiled=>{
      const result=run(compiled,'RuntimeConfigurationHarness',{CP_DB_TEST_OK:'DISPOSABLE',PGHOST:process.env.PGHOST,
        PGPORT:process.env.PGPORT,PGDATABASE:'condpulse_ci',PGUSER:'postgres',PGPASSWORD:'condpulse_ci_only'},120000);
      assert.equal(result.status,0,`Disposable role provisioning and recovery: ${result.stderr}`);assert.equal(result.stderr.trim(),'');
      const outcome=JSON.parse(result.stdout.trim());assert.equal(outcome.passed,true);
      assert.equal(outcome.runtimeLogins,3);
      for(const field of ['readyReplay','partialRecovery','rlsIsolation','membershipDriftDenied','grantDriftDenied','nonTlsDenied'])
        assert.equal(outcome[field],true,field);
      assert.doesNotMatch(result.stdout,/SYNTHETIC_|Bearer|jdbc:postgresql|password/i);
    }));
