import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync,mkdtempSync,rmSync,realpathSync,lstatSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {join,resolve,dirname,basename,delimiter} from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('..',import.meta.url));
const jar=join(root,'apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar');
const helper=join(root,'infra/scripts/fly-api/FlyRuntimeLogin.java');
const source=readFileSync(helper,'utf8');
const minimal=Object.fromEntries(['PATH','JAVA_HOME','HOME','TMPDIR','TMP','TEMP'].filter(k=>process.env[k]!==undefined).map(k=>[k,process.env[k]]));
const compiler=spawnSync('javac',['-version'],{env:minimal,encoding:'utf8',timeout:10000});
const capable=existsSync(jar)&&compiler.status===0&&/javac (?:2[5-9]|[3-9][0-9])\b/.test(`${compiler.stdout??''}${compiler.stderr??''}`);
const required=process.env.CP_FLY_JAVA_TEST_REQUIRED==='true';
const skip=reason=>capable?false:(required?false:reason);
const disposable=process.env.CP_DB_TEST_OK==='DISPOSABLE'&&['localhost','127.0.0.1'].includes(process.env.PGHOST)
  &&process.env.PGDATABASE==='condpulse_ci'&&process.env.PGUSER==='postgres'&&process.env.PGPASSWORD==='condpulse_ci_only';

test('login da Fly mantém a política do login da Vercel e só muda teto e conferência de grants com motivo escrito',()=>{
  assert.match(source,/CONNECTION LIMIT " \+ CONNECTION_LIMIT/);assert.match(source,/static final int CONNECTION_LIMIT = 4;/);
  assert.match(source,/static final int MAX_CONNECTIONS = 60;/);assert.match(source,/static final int MARGIN = 20;/);
  assert.match(source,/NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS/);
  assert.match(source,/WITH ADMIN FALSE/);assert.match(source,/WITH INHERIT TRUE/);assert.match(source,/WITH SET FALSE/);
  assert.match(source,/interval '10 minutes'/);assert.match(source,/NOLOGIN PASSWORD NULL/);assert.match(source,/PasswordUtil\.encodeScramSha256/);
  assert.match(source,/"cp_runtime_" \+ generation/);assert.match(source,/condpulse-runtime-fly:/);
  assert.match(source,/FLY_RUNTIME_OTHER_GENERATION_EXISTS/);assert.match(source,/FLY_RUNTIME_DIRECT_GRANT_FORBIDDEN/);
  assert.match(source,/BootstrapBaseline\.productionConfig\(env\)/);assert.match(source,/BootstrapManifest\.sourceCodes/);
  assert.doesNotMatch(source,/SpringApplication|\.migrate\(|\.baseline\(|\.repair\(|\.clean\(|CREATE TABLE|DROP ROLE|System\.out\.print[^;]*password/);
  // Ordem: prova do login antes de pedir a confirmação da Fly; READY só depois dela.
  assert.ok(source.indexOf('verifyLogin(login')<source.indexOf('channel.confirmStaged()'));
  assert.ok(source.indexOf('channel.confirmStaged()')<source.indexOf('ready(owner, config)'));
});

function compiled(harness,action){
  const temporary=resolve(mkdtempSync(join(tmpdir(),'condpulse-fly-test-')));
  try{
    assert.equal(spawnSync('jar',['-xf',jar],{cwd:temporary,env:minimal,encoding:'utf8',timeout:30000}).status,0);
    const classes=join(temporary,'BOOT-INF/classes'),classpath=[classes,join(temporary,'BOOT-INF/lib/*')].join(delimiter);
    const sources=[join(root,'infra/scripts/runtime-configuration/RuntimeConfiguration.java'),join(root,'infra/scripts/runtime-configuration/RuntimeVercelClient.java'),helper];
    if(harness)sources.push(join(root,'tests/runtime',`${harness}.java`));
    const compile=spawnSync('javac',['-cp',classpath,'-d',classes,...sources],{env:minimal,encoding:'utf8',timeout:60000});
    assert.equal(compile.status,0,`Compile Fly login helper: ${compile.stderr}`);
    return action({classpath,classes});
  }finally{
    const actual=realpathSync(temporary);assert.equal(dirname(actual),realpathSync(tmpdir()));
    assert.ok(basename(actual).startsWith('condpulse-fly-test-'));assert.equal(lstatSync(temporary).isSymbolicLink(),false);
    rmSync(actual,{recursive:true,force:true});
  }
}
const run=({classpath,classes},main,env={},timeout=30000,input='')=>spawnSync('java',[`-Dlogback.configurationFile=${join(classes,'bootstrap/logback-baseline.xml')}`,
  '-cp',classpath,`br.com.condpulse.bootstrap.${main}`],{env:{...minimal,...env},encoding:'utf8',timeout,input});

test('executor Java compila e falha fechado sem projeto, antes de JDBC, sem ecoar credenciais',{skip:skip('Requer JAR revisado e JDK25; roda no CI depois do Maven.')},()=>compiled(null,c=>{
  const password='SYNTHETIC_OWNER_PASSWORD_MUST_NEVER_BE_OUTPUT',stdin='SYNTHETIC_RUNTIME_PASSWORD_NEVER_OUTPUT_000000000';
  const result=run(c,'FlyRuntimeLogin',{DATABASE_PASSWORD:password},30000,`${stdin}\n`);
  assert.equal(result.status,1);assert.equal(result.stderr.trim(),'');
  assert.deepEqual(JSON.parse(result.stdout.trim()),{phase:'RESULT',eligible:false,applied:false,alreadyApplied:false,state:'NOT_ATTEMPTED',fingerprint:'',code:'FLY_RUNTIME_PREFLIGHT_FAILED'});
  assert.ok(!result.stdout.includes(password)&&!result.stdout.includes(stdin));
}));

test('PostgreSQL descartável: SCRAM, STAGED→READY só com COMMIT, ABORT desabilita, coexistência com a Vercel, margem e drift',
  {skip:capable&&disposable?false:(required?false:'Requer JDK25, JAR revisado e PostgreSQL descartável; roda no CI.')},()=>compiled('FlyRuntimeLoginHarness',c=>{
    const result=run(c,'FlyRuntimeLoginHarness',{CP_DB_TEST_OK:'DISPOSABLE',PGHOST:process.env.PGHOST,PGPORT:process.env.PGPORT,
      PGDATABASE:'condpulse_ci',PGUSER:'postgres',PGPASSWORD:'condpulse_ci_only'},180000);
    assert.equal(result.stderr.trim(),'');
    const outcome=JSON.parse(result.stdout.trim());
    assert.equal(result.status,0,`Fly login harness: ${result.stdout}`);
    assert.deepEqual(outcome,{passed:true,logins:2,connectionLimit:4,abortDisables:true,readyReplay:true,earlierCommitReady:true,vercelCoexists:true,budgetDenied:true,driftDenied:true});
  }));
