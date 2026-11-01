import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,mkdtempSync,writeFileSync,chmodSync,rmSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {APP,ORG,REQUIRED,CONFIG,validateRequest,createFly,flyState,exitsSince,secretLines,createLogin,execute,waitStable,
  generatePassword,fingerprint} from '../infra/scripts/fly-worker/fly-worker.mjs';

const SHA='a'.repeat(40),GEN='22000000-0000-4000-8000-000000000002',FP='f'.repeat(64);
const base={GITHUB_SHA:SHA,GITHUB_EVENT_NAME:'workflow_dispatch',GITHUB_REPOSITORY:'advancedgit-alt/Condpulse-v2.0',GITHUB_REF:'refs/heads/ccr-5e725112-3x8hyg',
  CP_FLY_EXPECTED_COMMIT:SHA,CP_FLY_GENERATION:GEN};
const read=path=>readFileSync(new URL(`../${path}`,import.meta.url),'utf8');
const applyEnv=ack=>({...base,CP_FLY_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLY_ACK:ack});
const deployEnv=ack=>({...base,CP_FLY_OPERATION:'deploy',CP_FLY_GENERATION:'',MAINTENANCE_CONFIRMED:'true',CP_FLY_ACK:ack});
const limitEnv=ack=>({...base,CP_FLY_OPERATION:'vercel-limit',CP_FLY_GENERATION:'',MAINTENANCE_CONFIRMED:'true',CP_FLY_ACK:ack});

test('pedido só pela branch principal; geração só em inspect/apply; ACK com prefixo próprio por operação e janela confirmada',()=>{
  assert.equal(validateRequest(base).operation,'inspect');
  for(const o of [{GITHUB_REF:'refs/heads/outra'},{GITHUB_EVENT_NAME:'push'},{GITHUB_REPOSITORY:'x/Condpulse-v2.0'},{CP_FLY_EXPECTED_COMMIT:'b'.repeat(40)},{CP_FLY_OPERATION:'destroy'}])
    assert.throws(()=>validateRequest({...base,...o}),/FLY_WORKER_REQUEST_INVALID/);
  for(const g of ['','00000000-0000-0000-0000-000000000000','not-a-uuid',`${GEN}:x`])
    assert.throws(()=>validateRequest({...base,CP_FLY_GENERATION:g}),/FLY_WORKER_GENERATION_INVALID/);
  const applyAck=`${APP}:fly-worker:${GEN}:${SHA}:${'c'.repeat(64)}`,deployAck=`${APP}:fly-worker-deploy:${SHA}:${'c'.repeat(64)}`,
    limitAck=`${APP}:vercel-limit:${SHA}:${'c'.repeat(64)}`;
  assert.throws(()=>validateRequest({...base,CP_FLY_ACK:applyAck}),/FLY_WORKER_ACK_INVALID/,'inspect não aceita ACK');
  assert.equal(validateRequest(applyEnv(applyAck)).operation,'apply');
  assert.throws(()=>validateRequest(applyEnv(deployAck)),/FLY_WORKER_ACK_INVALID/);
  assert.throws(()=>validateRequest(applyEnv(limitAck)),/FLY_WORKER_ACK_INVALID/);
  assert.throws(()=>validateRequest({...applyEnv(applyAck),MAINTENANCE_CONFIRMED:'false'}),/FLY_WORKER_MAINTENANCE_REQUIRED/);
  assert.equal(validateRequest(deployEnv(deployAck)).operation,'deploy');
  assert.throws(()=>validateRequest({...deployEnv(deployAck),CP_FLY_GENERATION:GEN}),/FLY_WORKER_GENERATION_INVALID/);
  assert.throws(()=>validateRequest(deployEnv(limitAck)),/FLY_WORKER_ACK_INVALID/);
  assert.equal(validateRequest(limitEnv(limitAck)).operation,'vercel-limit');
  assert.throws(()=>validateRequest(limitEnv(applyAck)),/FLY_WORKER_ACK_INVALID/);
  assert.throws(()=>validateRequest({...limitEnv(limitAck),CP_FLY_GENERATION:GEN}),/FLY_WORKER_GENERATION_INVALID/);
  assert.throws(()=>validateRequest({...limitEnv(limitAck),MAINTENANCE_CONFIRMED:''}),/FLY_WORKER_MAINTENANCE_REQUIRED/);
});

test('flyctl: app/org fixos, valores só no stdin, ambiente mínimo, criação do app e deploy com fly.worker.toml',()=>{
  const calls=[];const token='SYNTHETIC_FLY_TOKEN_NOT_REAL_000000';
  const fly=createFly({binary:'/runner/condpulse-fly-worker/flyctl',token,spawnSync:(bin,args,opts)=>{calls.push({bin,args,opts});
    if(args[0]==='status')return {status:0,stdout:JSON.stringify({Name:APP,Organization:{Slug:ORG},Machines:[]})};
    return {status:0,stdout:'[]'};}});
  fly.apps();fly.createApp();fly.status();fly.secrets();fly.importSecrets('DATABASE_USER=x\n');fly.deploy();
  assert.deepEqual(calls.map(c=>c.args),[['apps','list','--org',ORG,'--json'],['apps','create',APP,'--org',ORG],['status','--app',APP,'--json'],
    ['secrets','list','--app',APP,'--json'],['secrets','import','--app',APP,'--stage'],
    ['deploy','--app',APP,'--config',CONFIG,'--remote-only','--ha=false','--yes','--wait-timeout','10m']]);
  assert.equal(CONFIG,'fly.worker.toml');assert.equal(APP,'condpulse-v2-0-worker');
  assert.equal(calls[4].opts.input,'DATABASE_USER=x\n');
  for(const c of calls){assert.deepEqual(Object.keys(c.opts.env).sort(),['FLY_API_TOKEN','FLY_NO_UPDATE_CHECK','HOME','NO_COLOR','PATH']);assert.ok(!c.args.join(' ').includes(token));}
  assert.throws(()=>createFly({binary:'flyctl',token}),/FLY_WORKER_CLI_INVALID/);
  const failing=createFly({binary:'/x/flyctl',token,spawnSync:()=>({status:1,stdout:'',stderr:'boom'})});
  assert.throws(()=>failing.createApp(),/FLY_WORKER_APP_CREATE_FAILED/);
});

test('linhas para o flyctl: só os três nomes do worker e nada que o parser reinterprete',()=>{
  assert.deepEqual(REQUIRED,['CP_DB_CA_BASE64','DATABASE_PASSWORD','DATABASE_USER']);
  assert.equal(secretLines({DATABASE_USER:'cp_worker_x.p'}),'DATABASE_USER=cp_worker_x.p\n');
  for(const value of ['a#b','"x"','a b','a\nb',''])assert.throws(()=>secretLines({DATABASE_USER:value}),/FLY_WORKER_SECRET_VALUE_FORMAT_INVALID/);
  for(const key of ['DATABASE_URL','DB_POOL_SIZE','CP_WORKER_OUTBOX_ENABLED','CP_BFF_ENCRYPTION_KEY'])
    assert.throws(()=>secretLines({[key]:'x'}),/FLY_WORKER_SECRET_VALUE_FORMAT_INVALID/,key);
});

/** Fly falsa em memória: app opcional, nomes e digest de segredos, máquinas com eventos; nunca devolve valor. */
function flyFake({exists=false,names=[],org=ORG}={}){
  const secrets=new Map(names.map(n=>[n,'d0']));const imports=[];const machines=[];let created=0,deploys=0;let present=exists;
  return {imports,machines,get created(){return created;},get deploys(){return deploys;},
    apps:()=>present?[{Name:APP,Organization:{Slug:org}},{Name:'condpulse-v2-0',Organization:{Slug:ORG}}]:[{Name:'condpulse-v2-0',Organization:{Slug:ORG}}],
    createApp:()=>{created++;present=true;},
    status:()=>{assert.ok(present,'status só com app existente');return {Name:APP,Organization:{Slug:org},Machines:machines};},
    secrets:()=>[...secrets.keys()].map(name=>({name,digest:secrets.get(name)})),
    importSecrets:lines=>{imports.push(lines);for(const line of lines.trim().split('\n')){const [k,...v]=line.split('=');secrets.set(k,fingerprint(v.join('=')).slice(0,16));}},
    deploy:()=>{deploys++;machines.splice(0,machines.length,{id:'m1',region:'gru',state:'started',events:[{type:'launch',timestamp:Date.now()}]});}};
}
const budget={maxConnections:60,otherBackends:18,runtimeLimits:20,workerLimit:2,margin:20,fits:true};
function loginFake(state='ABSENT',{code='',vercel={state:'ELIGIBLE',limit:16,backends:0,fingerprint:'e'.repeat(64),code:''},limitResult}={}){
  const calls=[],limits=[];
  return {calls,limits,inspect:()=>({phase:'RESULT',eligible:code==='',applied:false,alreadyApplied:state==='READY',state,fingerprint:FP,code,budget,vercel}),
    vercelLimit:(req,fp)=>{limits.push(fp);return limitResult??{phase:'RESULT',eligible:true,applied:true,alreadyApplied:false,state:'REDUCED',fingerprint:'d'.repeat(64),code:'',
      budget:{...budget,runtimeLimits:8},vercel:{state:'REDUCED',limit:4,backends:0,fingerprint:'d'.repeat(64),code:''}};},
    apply:async(req,db,password,onStaged)=>{calls.push({db,password});let stagedError=null,ok=false;
      try{ok=await onStaged();}catch(e){stagedError=e;}
      return {staged:true,stagedError,outcome:ok?{code:'',state:'READY',applied:true,budget}:{code:'FLY_WORKER_PARTIAL_RECONCILE_REQUIRED',state:'DISABLED',applied:false}};}};
}
const sequence=()=>{let call=0;return n=>{call++;return Buffer.from(Array.from({length:n},(_,i)=>(i*31+call*17)%256));};};
const deps=(fly,login,extra={})=>({fly,login,readCa:()=>'U1lOVEhFVElDX0NB',randomBytes:sequence(),now:()=>1000,
  waitStable:async()=>({polls:6,startedStreak:6,exits:0}),...extra});

test('inspect sem app não escreve; imprime margem, teto da Vercel e ACKs; apply cria o app, o login e grava só os três nomes',async()=>{
  const fly=flyFake(),login=loginFake();
  const inspected=await execute(base,deps(fly,login));
  assert.equal(fly.created+fly.imports.length+login.calls.length,0);
  assert.equal(inspected.appExists,false);assert.equal(inspected.deployAck,null,'sem app não há deploy');
  assert.deepEqual(inspected.budget,{maxConnections:60,otherBackends:18,runtimeLimits:20,workerLimit:2,margin:20,total:60,fits:true});
  assert.deepEqual(inspected.vercel,{state:'ELIGIBLE',limit:16,backends:0,code:''});
  assert.equal(inspected.vercelLimitAck,`${APP}:vercel-limit:${SHA}:${'e'.repeat(64)}`);
  assert.match(inspected.applyAck,new RegExp(`^${APP}:fly-worker:${GEN}:${SHA}:[a-f0-9]{64}$`));
  const applied=await execute(applyEnv(inspected.applyAck),deps(fly,login));
  assert.equal(fly.created,1);assert.equal(fly.imports.length,1);assert.equal(login.calls.length,1);assert.equal(login.calls[0].db,FP);
  assert.equal(applied.createdApp,true);assert.deepEqual(applied.changed,REQUIRED);
  const lines=fly.imports[0];
  assert.deepEqual(lines.trim().split('\n').map(l=>l.split('=')[0]),REQUIRED);
  assert.match(lines,new RegExp(`^DATABASE_USER=cp_worker_${GEN.replaceAll('-','')}\\.rpnrqfrfwywglvyykgrg$`,'m'));
  const password=generatePassword(sequence());
  assert.match(password,/^[A-Za-z0-9_-]{48}$/);assert.equal(login.calls[0].password,password);
  assert.ok(lines.includes(`DATABASE_PASSWORD=${password}`));
  const printed=JSON.stringify([inspected,applied]);
  for(const secret of [password,'U1lOVEhFVElDX0NB'])assert.equal(printed.includes(secret),false,'nenhum valor de segredo no resultado');
  assert.equal(fly.deploys,0,'apply não faz deploy');
});

test('app existente de outra org, segredo desconhecido, credencial já gravada, margem insuficiente e ACK velho: recusados sem escrita',async()=>{
  await assert.rejects(execute(base,deps(flyFake({exists:true,org:'outra'}),loginFake())),/FLY_WORKER_APP_MISMATCH/);
  for(const [names,code] of [[['SOMETHING_ELSE'],/FLY_WORKER_UNMANAGED_SECRET/],[['DATABASE_PASSWORD'],/FLY_WORKER_EXISTING_CREDENTIAL_CONFLICT/]]){
    const f=flyFake({exists:true,names}),l=loginFake();const {applyAck}=await execute(base,deps(f,l));
    await assert.rejects(execute(applyEnv(applyAck),deps(f,l)),code);
    assert.equal(f.imports.length+l.calls.length+f.created,0);
  }
  const f=flyFake(),l=loginFake('ABSENT',{code:'FLY_WORKER_BUDGET_INSUFFICIENT'});const {applyAck}=await execute(base,deps(f,l));
  await assert.rejects(execute(applyEnv(applyAck),deps(f,l)),/FLY_WORKER_NOT_ELIGIBLE/);
  assert.equal(f.created+f.imports.length+l.calls.length,0,'nem o app é criado sem margem');
  await assert.rejects(execute(applyEnv(`${APP}:fly-worker:${GEN}:${SHA}:${'0'.repeat(64)}`),deps(flyFake(),loginFake())),/FLY_WORKER_STATE_CHANGED/);
});

test('login READY repete sem escrita; falha de leitura na Fly aborta o login e devolve os dois códigos',async()=>{
  const ready=flyFake({exists:true,names:REQUIRED}),readyLogin=loginFake('READY');
  const {applyAck}=await execute(base,deps(ready,readyLogin));
  const repeat=await execute(applyEnv(applyAck),deps(ready,readyLogin));
  assert.equal(repeat.alreadyApplied,true);assert.equal(ready.imports.length+readyLogin.calls.length+ready.created,0);
  const broken=flyFake({exists:true}),brokenLogin=loginFake();
  const inspected=await execute(base,deps(broken,brokenLogin));
  broken.importSecrets=()=>{};
  await assert.rejects(execute(applyEnv(inspected.applyAck),deps(broken,brokenLogin)),/FLY_WORKER_SECRETS_READBACK_MISMATCH;FLY_WORKER_PARTIAL_RECONCILE_REQUIRED/);
});

test('deploy exige app, ACK do estado da Fly e exatamente os três segredos; termina com uma máquina estável em gru',async()=>{
  const absent=flyFake();const a=await execute(base,deps(absent,loginFake()));
  assert.equal(a.deployAck,null);
  await assert.rejects(execute(deployEnv(`${APP}:fly-worker-deploy:${SHA}:${'0'.repeat(64)}`),deps(absent,null)),/FLY_WORKER_APP_ABSENT/);
  const incomplete=flyFake({exists:true,names:['DATABASE_USER']});const i=await execute(base,deps(incomplete,loginFake()));
  await assert.rejects(execute(deployEnv(i.deployAck),deps(incomplete,null)),/FLY_WORKER_SECRETS_INCOMPLETE/);
  assert.equal(incomplete.deploys,0);
  const fly=flyFake({exists:true,names:REQUIRED});const {deployAck}=await execute(base,deps(fly,loginFake('READY')));
  await assert.rejects(execute(deployEnv(`${APP}:fly-worker-deploy:${SHA}:${'0'.repeat(64)}`),deps(fly,null)),/FLY_WORKER_STATE_CHANGED/);
  let since=null;
  const result=await execute(deployEnv(deployAck),deps(fly,null,{now:()=>4242,waitStable:async s=>{since=s;return {polls:6,startedStreak:6,exits:0};}}));
  assert.equal(fly.deploys,1);assert.equal(since,4242);assert.deepEqual(result.machines,['gru:started']);assert.deepEqual(result.secretNames,REQUIRED);
});

test('estabilidade sem HTTP: seis leituras started seguidas; saída do processo depois do deploy reprova',async()=>{
  const fly=flyFake({exists:true});fly.deploy();
  const ok=await waitStable(0,{fly,attempts:8,required:6,delay:0,sleep:async()=>{}});
  assert.deepEqual(ok,{polls:6,startedStreak:6,exits:0});
  const crash=flyFake({exists:true});crash.deploy();crash.machines[0].events.push({type:'exit',timestamp:5000});
  assert.equal(exitsSince(crash,4000),1);assert.equal(exitsSince(crash,6000),0,'saída anterior ao deploy não conta');
  await assert.rejects(waitStable(4000,{fly:crash,attempts:3,required:2,delay:0,sleep:async()=>{}}),/FLY_WORKER_PROCESS_EXITED/);
  const stopped=flyFake({exists:true});stopped.deploy();stopped.machines[0].state='stopped';
  await assert.rejects(waitStable(0,{fly:stopped,attempts:3,required:2,delay:0,sleep:async()=>{}}),/FLY_WORKER_NOT_STABLE/);
  const two=flyFake({exists:true});two.deploy();two.machines.push({id:'m2',region:'gru',state:'started',events:[]});
  await assert.rejects(waitStable(0,{fly:two,attempts:3,required:2,delay:0,sleep:async()=>{}}),/FLY_WORKER_NOT_STABLE/);
});

test('vercel-limit: só com o ACK impresso, passa o fingerprint ao Java e não usa a Fly; noop e recusa ficam explícitos',async()=>{
  const login=loginFake();
  const result=await execute(limitEnv(`${APP}:vercel-limit:${SHA}:${'e'.repeat(64)}`),deps(null,login));
  assert.deepEqual(login.limits,['e'.repeat(64)]);
  assert.equal(result.applied,true);assert.deepEqual(result.vercel,{state:'REDUCED',limit:4,backends:0,code:''});assert.equal(result.budget.runtimeLimits,8);
  const noop=loginFake('ABSENT',{limitResult:{phase:'RESULT',eligible:true,applied:false,alreadyApplied:true,state:'REDUCED',fingerprint:'d'.repeat(64),code:'',budget,vercel:{state:'REDUCED',limit:4,backends:0,code:''}}});
  assert.equal((await execute(limitEnv(`${APP}:vercel-limit:${SHA}:${'d'.repeat(64)}`),deps(null,noop))).alreadyApplied,true);
  const busy=loginFake('ABSENT',{limitResult:{phase:'RESULT',eligible:false,applied:false,alreadyApplied:false,state:'NOT_ATTEMPTED',fingerprint:'',code:'FLY_WORKER_VERCEL_BACKENDS_EXCEED',budget:null,vercel:null}});
  await assert.rejects(execute(limitEnv(`${APP}:vercel-limit:${SHA}:${'e'.repeat(64)}`),deps(null,busy)),/FLY_WORKER_VERCEL_BACKENDS_EXCEED/);
  // Sem teto elegível, a inspeção não imprime ACK de redução.
  const blocked=await execute(base,deps(flyFake(),loginFake('ABSENT',{vercel:{state:'REDUCED',limit:4,backends:1,fingerprint:'d'.repeat(64),code:''}})));
  assert.equal(blocked.vercelLimitAck,null);
});

test('protocolo com o Java: senha só na primeira linha do stdin, COMMIT só depois da conferência na Fly',async()=>{
  const written=[];
  const login=createLogin({executable:'/jdk/bin/java',args:['Main'],env:{DATABASE_PASSWORD:'owner'}},{spawn:(bin,args,opts)=>{
    const child=new EventEmitter();child.stdin=new PassThrough();child.stdout=new PassThrough();
    child.stdin.on('data',d=>{written.push(String(d));
      if(written.length===1)setImmediate(()=>child.stdout.write('{"phase":"STAGED"}\n'));
      else setImmediate(()=>{child.stdout.write('{"phase":"RESULT","eligible":true,"applied":true,"alreadyApplied":false,"state":"READY","fingerprint":"x","code":"","budget":null,"vercel":null}\n');child.emit('close',0);});});
    assert.equal(opts.env.CP_FLY_WORKER_OPERATION,'apply');assert.equal(opts.env.CP_FLY_DB_FINGERPRINT,FP);
    assert.deepEqual(opts.stdio,['pipe','pipe','ignore']);assert.ok(!args.join(' ').includes('SYNTHETIC_PASSWORD'));
    return child;}});
  const result=await login.apply({generation:GEN,source:SHA},FP,'SYNTHETIC_PASSWORD',async()=>{assert.deepEqual(written,['SYNTHETIC_PASSWORD\n']);return true;});
  assert.deepEqual(written,['SYNTHETIC_PASSWORD\n','COMMIT\n']);assert.equal(result.outcome.state,'READY');
  const seen=[];
  const sync=createLogin({executable:'/jdk/bin/java',args:['Main'],env:{}},{spawnSync:(bin,args,opts)=>{seen.push(opts.env);return {stdout:'ruído\n{"phase":"RESULT","eligible":true,"applied":true,"alreadyApplied":false,"state":"REDUCED","fingerprint":"y","code":""}\n'};}});
  assert.equal(sync.vercelLimit({generation:'',source:SHA},'e'.repeat(64)).state,'REDUCED');
  assert.equal(seen[0].CP_FLY_WORKER_OPERATION,'vercel-limit');assert.equal(seen[0].CP_FLY_GENERATION,'');assert.equal(seen[0].CP_FLY_DB_FINGERPRINT,'e'.repeat(64));
});

test('workflow manual: gate sem segredos, Environment protegido, flyctl com checksum, deploy sem banco e vercel-limit sem Fly',()=>{
  const workflow=read('.github/workflows/fly-worker.yml');
  assert.match(workflow,/^on:\n  workflow_dispatch:/m);assert.doesNotMatch(workflow,/^\s+(push|pull_request|schedule):/m);
  assert.match(workflow,/options: \[inspect, apply, deploy, vercel-limit\]/);
  const [gate,job]=workflow.split(/\n  fly-worker:\n/);
  assert.doesNotMatch(gate,/secrets\./);assert.match(gate,/fly-worker\.mjs --gate/);
  assert.match(job,/needs: gate/);assert.match(job,/name: condpulse-database-operations/);
  assert.equal((workflow.match(/github\.ref == 'refs\/heads\/ccr-5e725112-3x8hyg'/g)||[]).length,2);
  for(const uses of workflow.match(/uses: [^\n]+/g))assert.match(uses,/@[a-f0-9]{40}$/);
  assert.match(job,/flyctl_0\.4\.110_Linux_x86_64\.tar\.gz/);assert.match(job,/62b48471d4d560831ec27a3c316c402750d34a3f5f8b956459d7cc5f6125ec97 .*sha256sum --check --strict/);
  assert.match(job,/FLY_API_TOKEN: \$\{\{ inputs\.operation != 'vercel-limit' && secrets\.FLY_API_TOKEN \|\| '' \}\}/);
  assert.match(job,/DATABASE_PASSWORD: \$\{\{ inputs\.operation != 'deploy' && secrets\.CP_DATABASE_OPERATIONS_PASSWORD \|\| '' \}\}/);
  // O worker não recebe nada da Vercel, do BFF nem do Storage.
  assert.deepEqual([...new Set(workflow.match(/secrets\.[A-Z_]+/g))].sort(),['secrets.CP_DATABASE_OPERATIONS_PASSWORD','secrets.FLY_API_TOKEN']);
  assert.match(job,/infra\/scripts\/fly-worker\/FlyWorkerLogin\.java/);
  assert.match(workflow,/group: condpulse-database-operations-rpnrqfrfwywglvyykgrg/);
  assert.doesNotMatch(workflow,/set -x|echo "\$\{?(FLY_API_TOKEN|DATABASE_PASSWORD)/);
});

test('fly.worker.toml: app/região fixas, imagem do worker, sem HTTP, pool 1, outbox/fila/permanências ligados na HML e reinício só em falha',()=>{
  const toml=read('fly.worker.toml');
  for(const line of ['app = "condpulse-v2-0-worker"','primary_region = "gru"','dockerfile = "infra/fly-worker/Dockerfile"',
    'SPRING_PROFILES_ACTIVE = "worker"','DB_POOL_SIZE = "1"','CP_WORKER_OUTBOX_ENABLED = "true"','CP_WORKER_POLL_MILLIS = "2000"',
    'CP_WORKER_WAITLIST_ENABLED = "true"','CP_WORKER_WAITLIST_MILLIS = "60000"','CP_WORKER_ACCESS_SYNC_ENABLED = "false"','CP_SECURITY_LOG_ENABLED = "true"',
    'CP_WORKER_STAYS_ENABLED = "true"','CP_WORKER_STAYS_MILLIS = "60000"',
    'policy = "on-failure"','size = "shared-cpu-1x"','memory = "1gb"',
    'DATABASE_URL = "jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/tmp/condpulse-db-ca.crt"'])
    assert.ok(toml.includes(line),line);
  assert.doesNotMatch(toml,/http_service|\[\[services\]\]|internal_port|\[\[checks\]\]|PASSWORD|TOKEN|sb_|_KEY|DATABASE_USER/i);
  // O toml da API continua o mesmo arquivo e app.
  assert.match(read('fly.toml'),/^app = "condpulse-v2-0"$/m);
  const docker=read('infra/fly-worker/Dockerfile');
  assert.match(docker,/ENTRYPOINT \["\/bin\/sh", "\/app\/start-worker\.sh"\]/);assert.doesNotMatch(docker,/EXPOSE|HEALTHCHECK|migrate/i);
  for(const digest of docker.match(/^FROM [^\n]+/gm))assert.match(digest,/@sha256:[a-f0-9]{64}/);
  assert.match(docker,/USER 10001:10001/);
});

const CA_PEM='-----BEGIN CERTIFICATE-----\nU1lOVEhFVElDX1BVQkxJQ19DQV9PTkxZ\n-----END CERTIFICATE-----\n';
const launcherEnv={SPRING_PROFILES_ACTIVE:'worker',
  DATABASE_URL:'jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/tmp/condpulse-db-ca.crt',
  DATABASE_USER:`cp_worker_${'1'.repeat(32)}.rpnrqfrfwywglvyykgrg`,DATABASE_PASSWORD:'SYNTHETIC_WORKER_PASSWORD_NEVER_OUTPUT',
  CP_DB_CA_BASE64:Buffer.from(CA_PEM).toString('base64'),DB_POOL_SIZE:'1',CP_WORKER_OUTBOX_ENABLED:'false',CP_WORKER_WAITLIST_ENABLED:'true',
  CP_WORKER_ACCESS_SYNC_ENABLED:'false',CP_WORKER_POLL_MILLIS:'2000',CP_WORKER_WAITLIST_MILLIS:'60000',CP_WORKER_STAYS_ENABLED:'false',
  CP_WORKER_STAYS_MILLIS:'60000'};
function launch(overrides={},remove=[]){
  const dir=mkdtempSync(join(tmpdir(),'condpulse-fly-worker-launcher-'));
  try{
    // java falso: confirma argumentos e que a CA em Base64 não chega ao processo Java.
    writeFileSync(join(dir,'java'),'#!/bin/sh\nenv | grep -q "^CP_DB_CA_BASE64=" && { echo CA_LEAKED; exit 3; }\necho "STUB_JAVA $* $SPRING_PROFILES_ACTIVE"\n');
    chmodSync(join(dir,'java'),0o755);
    const env={PATH:`${dir}:/usr/bin:/bin`,...launcherEnv,...overrides};for(const k of remove)delete env[k];
    return spawnSync('/bin/sh',[new URL('../infra/fly-worker/start-worker.sh',import.meta.url).pathname],{env,encoding:'utf8',timeout:10000});
  }finally{rmSync(dir,{recursive:true,force:true});}
}

test('launcher do worker: aceita só a configuração revisada e falha fechado sem ecoar valores',()=>{
  const ok=launch();
  assert.equal(ok.status,0,ok.stderr);
  assert.equal(ok.stdout.trim(),'STUB_JAVA -XX:MaxRAMPercentage=70 -jar /app/app.jar --spring.profiles.active=worker --spring.flyway.enabled=false --spring.sql.init.mode=never worker');
  assert.equal(readFileSync('/tmp/condpulse-db-ca.crt','utf8'),CA_PEM);
  const cases=[
    [{SPRING_PROFILES_ACTIVE:'api'},'FLY_WORKER_PROFILE_EXACT'],
    [{DATABASE_URL:launcherEnv.DATABASE_URL.replace('verify-full','require')},'FLY_WORKER_DATABASE_TARGET_EXACT'],
    [{DATABASE_URL:launcherEnv.DATABASE_URL.replace('aws-1-sa-east-1','attacker')},'FLY_WORKER_DATABASE_TARGET_EXACT'],
    [{DATABASE_USER:`cp_runtime_${'1'.repeat(32)}.rpnrqfrfwywglvyykgrg`},'FLY_WORKER_IDENTITY_REQUIRED'],
    [{DATABASE_USER:'cp_worker.rpnrqfrfwywglvyykgrg'},'FLY_WORKER_IDENTITY_REQUIRED'],
    [{DATABASE_USER:`cp_worker_${'1'.repeat(32)}.bbbbbbbbbbbbbbbbbbbb`},'FLY_WORKER_IDENTITY_REQUIRED'],
    [{DATABASE_PASSWORD:''},'FLY_WORKER_DATABASE_PASSWORD_REQUIRED'],
    [{DB_POOL_SIZE:'4'},'FLY_WORKER_POOL_LIMIT'],[{DB_POOL_SIZE:''},'FLY_WORKER_POOL_LIMIT'],
    [{CP_WORKER_OUTBOX_ENABLED:'yes'},'FLY_WORKER_FLAG_INVALID'],[{CP_WORKER_WAITLIST_ENABLED:''},'FLY_WORKER_FLAG_INVALID'],
    [{CP_WORKER_ACCESS_SYNC_ENABLED:'true'},'FLY_WORKER_ACCESS_SYNC_FORBIDDEN'],
    [{CP_WORKER_POLL_MILLIS:'100'},'FLY_WORKER_POLL_INVALID'],[{CP_WORKER_POLL_MILLIS:'2000;x'},'FLY_WORKER_POLL_INVALID'],
    [{CP_WORKER_WAITLIST_MILLIS:'5000'},'FLY_WORKER_WAITLIST_INTERVAL_INVALID'],
    [{CP_WORKER_STAYS_ENABLED:'sim'},'FLY_WORKER_FLAG_INVALID'],[{CP_WORKER_STAYS_MILLIS:'5000'},'FLY_WORKER_STAYS_INTERVAL_INVALID'],
    [{CP_WORKER_STAYS_MILLIS:'900000'},'FLY_WORKER_STAYS_INTERVAL_INVALID'],
    [{CP_BFF_ENCRYPTION_KEY:'SYNTHETIC_SENTINEL'},'FLY_WORKER_PRIVILEGED_ENV_FORBIDDEN'],
    [{CP_STORAGE_SERVICE_KEY:'SYNTHETIC_SENTINEL'},'FLY_WORKER_PRIVILEGED_ENV_FORBIDDEN'],
    [{SUPABASE_SERVICE_ROLE_KEY:'SYNTHETIC_SENTINEL'},'FLY_WORKER_PRIVILEGED_ENV_FORBIDDEN'],
    [{CP_MIGRATION_PASSWORD:'SYNTHETIC_SENTINEL'},'FLY_WORKER_PRIVILEGED_ENV_FORBIDDEN'],
    [{SPRING_DATASOURCE_USERNAME:'postgres'},'FLY_WORKER_CONFIGURATION_OVERRIDE_FORBIDDEN'],
    [{SERVER_PORT:'9090'},'FLY_WORKER_CONFIGURATION_OVERRIDE_FORBIDDEN'],
    [{JAVA_TOOL_OPTIONS:'-Dx=y'},'FLY_WORKER_CONFIGURATION_OVERRIDE_FORBIDDEN'],
    [{CP_DB_CA_BASE64:'!!!not-base64!!!'},'FLY_WORKER_CA_ENCODING_INVALID'],
    [{CP_DB_CA_BASE64:Buffer.from('SYNTHETIC').toString('base64')},'FLY_WORKER_CA_FORMAT_INVALID']];
  for(const [overrides,code] of cases){
    const result=launch(overrides);
    assert.notEqual(result.status,0,code);assert.equal(result.stderr.trim(),code,JSON.stringify(overrides));assert.equal(result.stdout,'');
    assert.ok(!(result.stdout+result.stderr).includes('SYNTHETIC'),'nenhum valor ecoado');
  }
  for(const key of ['CP_DB_CA_BASE64','DB_POOL_SIZE','CP_WORKER_ACCESS_SYNC_ENABLED','CP_WORKER_STAYS_ENABLED','CP_WORKER_STAYS_MILLIS']){
    const result=launch({},[key]);assert.notEqual(result.status,0,key);
  }
});

test('CLI: pedido inválido falha no gate sem ecoar valores',()=>{
  const cli=new URL('../infra/scripts/fly-worker/fly-worker.mjs',import.meta.url).pathname;
  const child=spawnSync(process.execPath,[cli,'--gate'],{env:{...base,CP_FLY_OPERATION:'apply',CP_FLY_ACK:'SYNTHETIC_MUST_NOT_APPEAR',PATH:process.env.PATH},encoding:'utf8'});
  assert.notEqual(child.status,0);assert.equal(child.stderr.trim(),'FLY_WORKER_ACK_INVALID');
  assert.equal((child.stdout+child.stderr).includes('SYNTHETIC_MUST_NOT_APPEAR'),false);
});
