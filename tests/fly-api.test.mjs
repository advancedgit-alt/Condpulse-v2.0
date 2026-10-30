import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {APP,ORG,VERCEL,READINESS_URL,REQUIRED,GENERATED,COPIED,FIXED_PUBLIC,validateRequest,createVercelRequester,readVercelConfig,
  createFly,flyState,secretLines,createLogin,execute,waitReady,generateValues,fingerprint} from '../infra/scripts/fly-api/fly-api.mjs';

const SHA='a'.repeat(40),GEN='11000000-0000-4000-8000-000000000001';
const base={GITHUB_SHA:SHA,GITHUB_EVENT_NAME:'workflow_dispatch',GITHUB_REPOSITORY:'advancedgit-alt/Condpulse-v2.0',GITHUB_REF:'refs/heads/ccr-5e725112-3x8hyg',
  CP_FLY_EXPECTED_COMMIT:SHA,CP_FLY_GENERATION:GEN,CP_VERCEL_TOKEN:'SYNTHETIC_VERCEL_TOKEN_NOT_REAL_0000',
  CP_SUPABASE_PUBLISHABLE_KEY:'sb_publishable_SYNTHETICxxxxxxxxxxxx'};
const read=path=>readFileSync(new URL(`../${path}`,import.meta.url),'utf8');

test('pedido só pela branch principal, geração por operação, ACK com prefixo próprio e janela confirmada',()=>{
  assert.equal(validateRequest(base).operation,'inspect');
  for(const o of [{GITHUB_REF:'refs/heads/outra'},{GITHUB_EVENT_NAME:'push'},{GITHUB_REPOSITORY:'x/Condpulse-v2.0'},{CP_FLY_EXPECTED_COMMIT:'b'.repeat(40)},{CP_FLY_OPERATION:'destroy'}])
    assert.throws(()=>validateRequest({...base,...o}),/FLY_REQUEST_INVALID/);
  for(const g of ['','00000000-0000-0000-0000-000000000000','not-a-uuid','A1000000-0000-4000-8000-000000000001',`${GEN}:x`])
    assert.throws(()=>validateRequest({...base,CP_FLY_GENERATION:g}),/FLY_GENERATION_INVALID/);
  const applyAck=`${APP}:fly-api:${GEN}:${SHA}:${'c'.repeat(64)}`,deployAck=`${APP}:fly-deploy:${SHA}:${'c'.repeat(64)}`;
  assert.throws(()=>validateRequest({...base,CP_FLY_ACK:applyAck}),/FLY_ACK_INVALID/,'inspect não aceita ACK');
  assert.throws(()=>validateRequest({...base,CP_FLY_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true'}),/FLY_ACK_INVALID/);
  assert.throws(()=>validateRequest({...base,CP_FLY_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLY_ACK:deployAck}),/FLY_ACK_INVALID/);
  assert.throws(()=>validateRequest({...base,CP_FLY_OPERATION:'apply',CP_FLY_ACK:applyAck}),/FLY_MAINTENANCE_REQUIRED/);
  assert.equal(validateRequest({...base,CP_FLY_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLY_ACK:applyAck}).operation,'apply');
  assert.throws(()=>validateRequest({...base,CP_FLY_OPERATION:'deploy',MAINTENANCE_CONFIRMED:'true',CP_FLY_ACK:deployAck}),/FLY_GENERATION_INVALID/);
  const deploy={...base,CP_FLY_OPERATION:'deploy',CP_FLY_GENERATION:'',MAINTENANCE_CONFIRMED:'true'};
  assert.equal(validateRequest({...deploy,CP_FLY_ACK:deployAck}).operation,'deploy');
  assert.throws(()=>validateRequest({...deploy,CP_FLY_ACK:applyAck}),/FLY_ACK_INVALID/);
});

test('Vercel só por GET em destinos fixos, sem decrypt da lista e sem seguir redirecionamento',async()=>{
  const calls=[];const request=createVercelRequester(async(url,init)=>{calls.push({url,init});return {status:200,text:async()=>'{}'};});
  await request(`/v9/projects/${VERCEL.project}?teamId=${VERCEL.team}`,'t');
  await request(`/v10/projects/${VERCEL.project}/env?teamId=${VERCEL.team}&decrypt=false`,'t');
  await request(`/v1/projects/${VERCEL.project}/env/env_abc?teamId=${VERCEL.team}`,'t');
  for(const path of [`/v10/projects/${VERCEL.project}/env?teamId=${VERCEL.team}&decrypt=true`,`/v1/projects/outro/env/x?teamId=${VERCEL.team}`,
    `/v10/projects/${VERCEL.project}/env?teamId=${VERCEL.team}&upsert=true`,`/v1/projects/${VERCEL.project}/env/../x?teamId=${VERCEL.team}`])
    await assert.rejects(request(path,'t'),/FLY_VERCEL_DESTINATION_DENIED/);
  assert.ok(calls.every(c=>c.init.method==='GET'&&c.init.redirect==='manual'&&c.url.startsWith('https://api.vercel.com/')));
  const redirected=createVercelRequester(async()=>({status:302,text:async()=>''}));
  await assert.rejects(redirected(`/v9/projects/${VERCEL.project}?teamId=${VERCEL.team}`,'t'),/FLY_VERCEL_HTTP_DENIED/);
});

const project={id:VERCEL.project,accountId:VERCEL.team,link:{type:'github',org:'advancedgit-alt',repo:'Condpulse-v2.0'}};
function vercelFake(envs,singles={}){
  const paths=[];
  return {paths,request:async path=>{paths.push(path);
    if(path.startsWith('/v9/'))return project;
    if(path.startsWith('/v10/'))return {envs};
    const id=path.split('/env/')[1].split('?')[0];assert.ok(singles[id],'leitura individual só de variável conhecida');return singles[id];}};
}
const vercelEnvs=()=>[
  {key:'CP_BFF_ENABLED',type:'plain',value:'true',target:['production']},
  {key:'CP_PARCELS_ENABLED',type:'encrypted',id:'env_parcels',value:'CIPHERTEXT',target:['production','preview']},
  {key:'CP_BFF_ENCRYPTION_KEY',type:'sensitive',id:'env_secret',target:['production']},
  {key:'CP_PARCEL_CODE_KEY',type:'sensitive',id:'env_parcel_key',target:['production']},
  {key:'DATABASE_PASSWORD',type:'sensitive',id:'env_db',target:['production']},
  {key:'CP_VISITS_ENABLED',type:'plain',value:'false',target:['preview']}];
const singles={env_parcels:{id:'env_parcels',key:'CP_PARCELS_ENABLED',type:'encrypted',decrypted:true,value:'true'}};

test('copia da Vercel só valores não sensíveis no formato fechado; segredo nunca é lido',async()=>{
  const fake=vercelFake(vercelEnvs(),singles);
  assert.deepEqual(await readVercelConfig(fake.request,'t'),{CP_BFF_ENABLED:'true',CP_PARCELS_ENABLED:'true'});
  assert.ok(!fake.paths.some(p=>/env_secret|env_parcel_key|env_db|decrypt=true/.test(p)));
  await assert.rejects(readVercelConfig(vercelFake([{key:'CP_BFF_ENABLED',type:'sensitive',target:['production']}]).request,'t'),/FLY_VERCEL_FLAG_TYPE_INVALID/);
  await assert.rejects(readVercelConfig(vercelFake([{key:'CP_BFF_ENABLED',type:'plain',value:'yes',target:['production']}]).request,'t'),/FLY_VERCEL_VALUE_INVALID/);
  await assert.rejects(readVercelConfig(vercelFake([{key:'CP_FILES_ENABLED',type:'plain',value:'true',target:['production']}]).request,'t'),/FLY_FILES_FLAG_UNSUPPORTED/);
  await assert.rejects(readVercelConfig(vercelFake([{key:'CP_BFF_ENABLED',type:'plain',value:'true',target:['production']},{key:'CP_BFF_ENABLED',type:'plain',value:'false',target:['production']}]).request,'t'),/FLY_VERCEL_DUPLICATE_ENV/);
  await assert.rejects(readVercelConfig(async p=>p.startsWith('/v9/')?{...project,id:'prj_outro'}:{envs:[]},'t'),/FLY_VERCEL_PROJECT_MISMATCH/);
});

test('linhas para o flyctl não admitem nada que o parser dele reinterprete',()=>{
  assert.equal(secretLines({PORT:'8080',CP_BFF_ENCRYPTION_KEY:'ab+/cd=='}),'PORT=8080\nCP_BFF_ENCRYPTION_KEY=ab+/cd==\n');
  for(const value of ['a#b','"x"',"'x'",'a b','a\nb','','"""'])assert.throws(()=>secretLines({PORT:value}),/FLY_SECRET_VALUE_FORMAT_INVALID/);
  assert.throws(()=>secretLines({OTHER_NAME:'x'}),/FLY_SECRET_VALUE_FORMAT_INVALID/);
});

test('flyctl: app/org fixos, valores só no stdin, ambiente mínimo e deploy com uma máquina remota',()=>{
  const calls=[];const token='SYNTHETIC_FLY_TOKEN_NOT_REAL_000000';
  const fly=createFly({binary:'/runner/condpulse-fly-api/flyctl',token,spawnSync:(bin,args,opts)=>{calls.push({bin,args,opts});
    if(args[0]==='status')return {status:0,stdout:JSON.stringify({Name:APP,Organization:{Slug:ORG},Machines:[]})};
    return {status:0,stdout:'[]'};}});
  fly.status();fly.secrets();fly.importSecrets('PORT=8080\n');fly.deploy();
  assert.deepEqual(calls.map(c=>c.args),[['status','--app',APP,'--json'],['secrets','list','--app',APP,'--json'],['secrets','import','--app',APP,'--stage'],
    ['deploy','--app',APP,'--config','fly.toml','--remote-only','--ha=false','--yes','--wait-timeout','10m']]);
  assert.equal(calls[2].opts.input,'PORT=8080\n');
  for(const c of calls){assert.deepEqual(Object.keys(c.opts.env).sort(),['FLY_API_TOKEN','FLY_NO_UPDATE_CHECK','HOME','NO_COLOR','PATH']);assert.ok(!c.args.join(' ').includes(token));}
  assert.throws(()=>createFly({binary:'flyctl',token}),/FLY_CLI_INVALID/);
  const failing=createFly({binary:'/x/flyctl',token,spawnSync:()=>({status:1,stdout:'',stderr:'boom'})});
  assert.throws(()=>failing.importSecrets('PORT=8080\n'),/FLY_SECRETS_IMPORT_FAILED/);
  assert.throws(()=>flyState({status:()=>({Name:APP,Organization:{Slug:'outra-org'}}),secrets:()=>[]}),/FLY_APP_MISMATCH/);
});

/** Fly falsa em memória: guarda nomes e um digest; nunca devolve valor. */
function flyFake(names=[]){
  const secrets=new Map(names.map(n=>[n,'d0']));const imports=[];let deploys=0;
  const machines=[];
  return {imports,get deploys(){return deploys;},machines,
    status:()=>({Name:APP,Organization:{Slug:ORG},Machines:machines}),
    secrets:()=>[...secrets.keys()].map(name=>({name,digest:secrets.get(name)})),
    importSecrets:lines=>{imports.push(lines);for(const line of lines.trim().split('\n')){const [k,...v]=line.split('=');secrets.set(k,fingerprint(v.join('=')).slice(0,16));}},
    deploy:()=>{deploys++;machines.push({id:'m1',region:'gru'});}};
}
function loginFake(state='ABSENT',{outcome}={}){
  const calls=[];
  return {calls,inspect:()=>({phase:'RESULT',eligible:true,applied:false,alreadyApplied:state==='READY',state,fingerprint:'f'.repeat(64),code:''}),
    apply:async(req,db,password,onStaged)=>{calls.push({db,password});let staged=true,stagedError=null,ok=false;
      try{ok=await onStaged();}catch(e){stagedError=e;}
      return {staged,stagedError,outcome:outcome??(ok?{code:'',state:'READY',applied:true}:{code:'FLY_RUNTIME_PARTIAL_RECONCILE_REQUIRED',state:'DISABLED',applied:false})};}};
}
/** Bytes determinísticos e distintos a cada chamada (só para teste). */
const sequence=()=>{let call=0;return n=>{call++;return Buffer.from(Array.from({length:n},(_,i)=>(i*31+call*17)%256));};};
const deps=(fly,login,extra={})=>({fly,login,vercel:vercelFake(vercelEnvs(),singles).request,readCa:()=>'U1lOVEhFVElDX0NB',
  randomBytes:sequence(),waitReady:async()=>({status:200,body:'UP',attempt:1}),...extra});

test('inspect não escreve e imprime os dois ACKs; apply grava tudo de uma vez, confere nomes e nunca devolve valor',async()=>{
  const fly=flyFake(),login=loginFake();
  const inspected=await execute(base,deps(fly,login));
  assert.equal(fly.imports.length,0);assert.equal(login.calls.length,0);
  assert.match(inspected.applyAck,new RegExp(`^${APP}:fly-api:${GEN}:${SHA}:[a-f0-9]{64}$`));
  assert.match(inspected.deployAck,new RegExp(`^${APP}:fly-deploy:${SHA}:[a-f0-9]{64}$`));
  assert.deepEqual(inspected.vercelCopied,{CP_BFF_ENABLED:'true',CP_PARCELS_ENABLED:'true'});
  const applyEnv={...base,CP_FLY_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLY_ACK:inspected.applyAck};
  const applied=await execute(applyEnv,deps(fly,login));
  assert.equal(fly.imports.length,1);assert.equal(login.calls.length,1);assert.equal(login.calls[0].db,'f'.repeat(64));
  const expected=[...REQUIRED,'CP_BFF_ENABLED','CP_PARCELS_ENABLED'].sort();
  assert.deepEqual(applied.changed,expected);
  const lines=fly.imports[0];
  assert.match(lines,new RegExp(`^DATABASE_USER=cp_runtime_${GEN.replaceAll('-','')}\\.rpnrqfrfwywglvyykgrg$`,'m'));
  assert.match(lines,/^DATABASE_URL=jdbc:postgresql:\/\/aws-1-sa-east-1\.pooler\.supabase\.com:5432\/postgres\?sslmode=verify-full&sslrootcert=\/tmp\/condpulse-db-ca\.crt$/m);
  assert.match(lines,/^CP_BFF_ORIGIN=https:\/\/project-rhvdq\.vercel\.app$/m);assert.match(lines,/^DB_POOL_SIZE=2$/m);
  const generated=generateValues(sequence());
  for(const key of GENERATED){assert.match(generated[key],/^[A-Za-z0-9+/]{43}=$/);assert.ok(lines.includes(`${key}=${generated[key]}`));}
  assert.equal(new Set(GENERATED.map(k=>generated[k])).size,3,'chaves BFF/encomendas diferentes entre si');
  assert.equal(login.calls[0].password,generated.DATABASE_PASSWORD);assert.match(generated.DATABASE_PASSWORD,/^[A-Za-z0-9_-]{48}$/);
  const printed=JSON.stringify(applied)+JSON.stringify(inspected);
  for(const secret of [...GENERATED.map(k=>generated[k]),generated.DATABASE_PASSWORD,base.CP_SUPABASE_PUBLISHABLE_KEY,base.CP_VERCEL_TOKEN,'U1lOVEhFVElDX0NB'])
    assert.equal(printed.includes(secret),false,'nenhum valor de segredo no resultado');
  assert.equal(fly.deploys,0,'apply não faz deploy');
});

test('ACK de outro estado, segredo desconhecido, credencial já gravada e flag antiga são recusados sem escrita',async()=>{
  const ack=`${APP}:fly-api:${GEN}:${SHA}:${'c'.repeat(64)}`;const apply={...base,CP_FLY_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true'};
  const fly=flyFake(),login=loginFake();
  await assert.rejects(execute({...apply,CP_FLY_ACK:ack},deps(fly,login)),/FLY_STATE_CHANGED/);
  for(const [names,code] of [[['SOMETHING_ELSE'],/FLY_UNMANAGED_SECRET/],[['DATABASE_PASSWORD'],/FLY_EXISTING_RUNTIME_CONFLICT/],[['CP_VISITS_ENABLED'],/FLY_STALE_COPIED_SECRET/]]){
    const f=flyFake(names),l=loginFake();const {applyAck}=await execute(base,deps(f,l));
    await assert.rejects(execute({...apply,CP_FLY_ACK:applyAck},deps(f,l)),code);
    assert.equal(f.imports.length+l.calls.length,0);
  }
  const f=flyFake(),l=loginFake();const {applyAck}=await execute(base,deps(f,l));
  await assert.rejects(execute({...apply,CP_FLY_ACK:applyAck,CP_SUPABASE_PUBLISHABLE_KEY:'sb_secret_xxxxxxxxxxxxxxxxxxxx'},deps(f,l)),/FLY_PUBLISHABLE_KEY_INVALID/);
  assert.equal(f.imports.length,0);
});

test('login READY repete sem escrita; falha de leitura na Fly aborta o login e devolve os dois códigos',async()=>{
  const apply={...base,CP_FLY_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true'};
  const ready=flyFake(REQUIRED),readyLogin=loginFake('READY');
  const {applyAck}=await execute(base,deps(ready,readyLogin));
  const repeat=await execute({...apply,CP_FLY_ACK:applyAck},deps(ready,readyLogin));
  assert.equal(repeat.alreadyApplied,true);assert.equal(ready.imports.length+readyLogin.calls.length,0);
  const broken=flyFake(),brokenLogin=loginFake();
  const inspected=await execute(base,deps(broken,brokenLogin));
  broken.importSecrets=()=>{};// importação "silenciosa" sem efeito: a leitura de nomes não confere
  await assert.rejects(execute({...apply,CP_FLY_ACK:inspected.applyAck},deps(broken,brokenLogin)),/FLY_SECRETS_READBACK_MISMATCH;FLY_RUNTIME_PARTIAL_RECONCILE_REQUIRED/);
});

test('deploy exige ACK do estado da Fly, segredos completos e termina com uma máquina em gru e readiness UP',async()=>{
  const deploy={...base,CP_FLY_OPERATION:'deploy',CP_FLY_GENERATION:'',MAINTENANCE_CONFIRMED:'true',CP_VERCEL_TOKEN:'',CP_SUPABASE_PUBLISHABLE_KEY:''};
  const incomplete=flyFake(['PORT']);const i=await execute(base,deps(incomplete,loginFake()));
  await assert.rejects(execute({...deploy,CP_FLY_ACK:i.deployAck},deps(incomplete,null)),/FLY_SECRETS_INCOMPLETE/);
  assert.equal(incomplete.deploys,0);
  const fly=flyFake(REQUIRED);const {deployAck}=await execute(base,deps(fly,loginFake('READY')));
  await assert.rejects(execute({...deploy,CP_FLY_ACK:`${APP}:fly-deploy:${SHA}:${'0'.repeat(64)}`},deps(fly,null)),/FLY_STATE_CHANGED/);
  const result=await execute({...deploy,CP_FLY_ACK:deployAck},deps(fly,null));
  assert.equal(fly.deploys,1);assert.deepEqual(result.machines,['gru']);assert.equal(result.readiness.body,'UP');
});

test('readiness só no endereço fixo da Fly, sem redirecionamento, até 200/JSON/UP',async()=>{
  const seen=[];let n=0;
  const result=await waitReady({attempts:3,delay:0,sleep:async()=>{},fetcher:async(url,init)=>{seen.push([url,init.redirect]);n++;
    return n<2?{status:503,headers:new Headers({'content-type':'application/json'}),text:async()=>'{"status":"OUT_OF_SERVICE"}'}
      :{status:200,headers:new Headers({'content-type':'application/vnd.spring-boot.actuator.v3+json'}),text:async()=>'{"status":"UP"}'};}});
  assert.deepEqual(result,{status:200,body:'UP',attempt:2});
  assert.ok(seen.every(([url,redirect])=>url===READINESS_URL&&redirect==='manual'));
  await assert.rejects(waitReady({attempts:2,delay:0,sleep:async()=>{},fetcher:async()=>({status:302,headers:new Headers(),text:async()=>''})}),/FLY_READINESS_TIMEOUT/);
});

test('protocolo com o Java: senha só na primeira linha do stdin, COMMIT só depois da conferência na Fly',async()=>{
  const written=[];let child;
  const login=createLogin({executable:'/jdk/bin/java',args:['Main'],env:{DATABASE_PASSWORD:'owner'}},{spawn:(bin,args,opts)=>{
    child=new EventEmitter();child.stdin=new PassThrough();child.stdout=new PassThrough();
    child.stdin.on('data',d=>{written.push(String(d));
      if(written.length===1)setImmediate(()=>child.stdout.write('{"phase":"STAGED"}\n'));
      else setImmediate(()=>{child.stdout.write('{"phase":"RESULT","eligible":true,"applied":true,"alreadyApplied":false,"state":"READY","fingerprint":"x","code":""}\n');child.emit('close',0);});});
    assert.equal(opts.env.CP_FLY_RUNTIME_OPERATION,'apply');assert.equal(opts.env.CP_FLY_DB_FINGERPRINT,'f'.repeat(64));
    assert.deepEqual(opts.stdio,['pipe','pipe','ignore']);assert.ok(!args.join(' ').includes('SYNTHETIC_PASSWORD'));
    return child;}});
  let checked=false;
  const result=await login.apply({generation:GEN,source:SHA},'f'.repeat(64),'SYNTHETIC_PASSWORD',async()=>{assert.deepEqual(written,['SYNTHETIC_PASSWORD\n']);checked=true;return true;});
  assert.ok(checked);assert.deepEqual(written,['SYNTHETIC_PASSWORD\n','COMMIT\n']);assert.equal(result.outcome.state,'READY');
});

test('workflow manual: gate sem segredos, Environment protegido, flyctl com checksum e nenhum segredo no deploy',()=>{
  const workflow=read('.github/workflows/fly-api.yml');
  assert.match(workflow,/^on:\n  workflow_dispatch:/m);assert.doesNotMatch(workflow,/^\s+(push|pull_request|schedule):/m);
  const [gate,job]=workflow.split(/\n  fly-api:\n/);
  assert.doesNotMatch(gate,/secrets\./,'gate valida antes de liberar segredos');assert.match(gate,/fly-api\.mjs --gate/);
  assert.match(job,/needs: gate/);assert.match(job,/name: condpulse-database-operations/);
  assert.equal((workflow.match(/github\.ref == 'refs\/heads\/ccr-5e725112-3x8hyg'/g)||[]).length,2);
  for(const uses of workflow.match(/uses: [^\n]+/g))assert.match(uses,/@[a-f0-9]{40}$/);
  assert.match(job,/flyctl_0\.4\.110_Linux_x86_64\.tar\.gz/);assert.match(job,/62b48471d4d560831ec27a3c316c402750d34a3f5f8b956459d7cc5f6125ec97 .*sha256sum --check --strict/);
  assert.match(job,/DATABASE_PASSWORD: \$\{\{ inputs\.operation != 'deploy' && secrets\.CP_DATABASE_OPERATIONS_PASSWORD \|\| '' \}\}/);
  assert.match(job,/CP_SUPABASE_PUBLISHABLE_KEY: \$\{\{ inputs\.operation == 'apply' && secrets\.CP_SUPABASE_PUBLISHABLE_KEY \|\| '' \}\}/);
  assert.match(workflow,/group: condpulse-database-operations-rpnrqfrfwywglvyykgrg/);
  assert.doesNotMatch(workflow,/set -x|echo "\$\{?(FLY_API_TOKEN|DATABASE_PASSWORD|CP_VERCEL_TOKEN)/);
});

test('fly.toml: app e região fixas, imagem revisada, sempre ligada, readiness e VM pequena, sem segredo',()=>{
  const toml=read('fly.toml');
  for(const line of ['app = "condpulse-v2-0"','primary_region = "gru"','dockerfile = "Dockerfile.vercel"','internal_port = 8080','force_https = true',
    'auto_stop_machines = "off"','min_machines_running = 1','path = "/actuator/health/readiness"','size = "shared-cpu-1x"','memory = "1gb"'])
    assert.ok(toml.includes(line),line);
  assert.doesNotMatch(toml,/\[env\]|PASSWORD|KEY|TOKEN|sb_|jdbc:/i);
  // Tráfego trocado: /auth, /v1 e a readiness vão para a Fly; o resto continua no serviço web da Vercel.
  const vercel=JSON.parse(read('vercel.json'));
  const fly=vercel.rewrites.filter(r=>typeof r.destination==='string');
  assert.deepEqual(fly.map(r=>r.destination),['https://condpulse-v2-0.fly.dev/auth/:path*','https://condpulse-v2-0.fly.dev/v1/:path*','https://condpulse-v2-0.fly.dev/actuator/health/readiness']);
  assert.ok(vercel.rewrites.filter(r=>typeof r.destination!=='string').every(r=>r.destination.service==='web'));
});

test('conjuntos de segredos: obrigatórios, gerados e copiados não se sobrepõem e cobrem a imagem',()=>{
  assert.ok(GENERATED.every(k=>REQUIRED.includes(k)));
  assert.ok(Object.keys(COPIED).every(k=>!REQUIRED.includes(k)));
  for(const key of ['DATABASE_USER','DATABASE_PASSWORD','DATABASE_URL','CP_VERCEL_DB_CA_BASE64','PORT','DB_POOL_SIZE','CP_JWT_ISSUER','CP_JWT_JWK_SET_URI','CP_BFF_ORIGIN'])
    assert.ok(REQUIRED.includes(key),key);
  assert.equal(FIXED_PUBLIC.DB_POOL_SIZE,'2');
  const yaml=read('apps/backend/src/main/resources/application.yaml');
  for(const key of Object.keys(COPIED))assert.match(yaml,new RegExp(`\\$\\{${key}:`),key);
});
