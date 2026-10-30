import {createHash, randomBytes as secureRandomBytes} from 'node:crypto';
import {spawn as nodeSpawn, spawnSync as nodeSpawnSync} from 'node:child_process';
import {readFileSync, lstatSync} from 'node:fs';
import {isAbsolute, join, resolve, delimiter} from 'node:path';
import {pathToFileURL} from 'node:url';

/**
 * Worker contínuo de homologação na Fly.io (docs/FLY_WORKER.md). Quatro operações manuais, sempre no app e projeto fixos:
 * - inspect: existência do app, máquinas e nomes dos segredos da Fly, estado do login de banco do worker, números da
 *   margem de conexões (ADR-0006) e situação do teto da Vercel; imprime os ACKs.
 * - apply: cria o app se faltar (grátis, sem máquina), cria o login exclusivo do worker (executor Java) e grava
 *   usuário, senha gerada no runner e CA como segredos da Fly sem eco; confere só os nomes de volta. Nenhum deploy.
 * - deploy: flyctl deploy --remote-only com uma máquina em gru, sem HTTP; confere máquina estável (sem saída) e única.
 * - vercel-limit: operação separada que só reduz o CONNECTION LIMIT do login READY da Vercel de 16 para 4.
 * Nenhum valor de segredo é lido de volta ou impresso: só nomes, códigos, contagens e fingerprints.
 */
export const APP='condpulse-v2-0-worker', ORG='personal', REGION='gru';
export const PROJECT='rpnrqfrfwywglvyykgrg';
export const FLYCTL_VERSION='0.4.110';
export const CONFIG='fly.worker.toml';
const REPOSITORY='advancedgit-alt/Condpulse-v2.0', REF='refs/heads/ccr-5e725112-3x8hyg';
const OWNER_URL='jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt';
const CA_PATH='/run/secrets/db-ca.crt';
const WORK='condpulse-fly-worker';
const JAVA_MAIN='br.com.condpulse.bootstrap.FlyWorkerLogin';
const OPERATIONS=['inspect','apply','deploy','vercel-limit'];

/** Únicos segredos do app do worker. URL, pool e flags ficam em fly.worker.toml ([env]), revisados por PR. */
export const REQUIRED=Object.freeze(['CP_DB_CA_BASE64','DATABASE_PASSWORD','DATABASE_USER']);
const MANAGED=new Set(REQUIRED);
const SHA=/^[a-f0-9]{40}$/, HEX64=/^[a-f0-9]{64}$/;
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

export class SafeFailure extends Error {constructor(code){super(code);this.code=code;}}
const fail=code=>{throw new SafeFailure(code);};
const need=(ok,code)=>{if(!ok)fail(code);};
const object=(v,code='FLY_WORKER_RESPONSE_INVALID')=>{need(v!==null&&typeof v==='object'&&!Array.isArray(v),code);return v;};
export const sha256=value=>createHash('sha256').update(value).digest('hex');
export const fingerprint=value=>sha256(JSON.stringify(value));

/** Gate antes de liberar o Environment: dispatch manual da branch principal, SHA exato, geração e ACK no formato. */
export function validateRequest(env){
 const operation=env.CP_FLY_OPERATION||'inspect',source=env.CP_FLY_EXPECTED_COMMIT||'',generation=env.CP_FLY_GENERATION||'';
 need(OPERATIONS.includes(operation)&&SHA.test(source)&&source===env.GITHUB_SHA&&env.GITHUB_EVENT_NAME==='workflow_dispatch'
  &&env.GITHUB_REPOSITORY===REPOSITORY&&env.GITHUB_REF===REF,'FLY_WORKER_REQUEST_INVALID');
 const withGeneration=operation==='inspect'||operation==='apply';
 need(withGeneration?UUID.test(generation)&&generation!=='00000000-0000-0000-0000-000000000000':generation==='','FLY_WORKER_GENERATION_INVALID');
 const ack=env.CP_FLY_ACK||'';
 const applyPrefix=`${APP}:fly-worker:${generation}:${source}:`,deployPrefix=`${APP}:fly-worker-deploy:${source}:`,
  limitPrefix=`${APP}:vercel-limit:${source}:`;
 const prefix={apply:applyPrefix,deploy:deployPrefix,'vercel-limit':limitPrefix}[operation];
 need(operation==='inspect'?ack==='':ack.startsWith(prefix)&&HEX64.test(ack.slice(prefix.length)),'FLY_WORKER_ACK_INVALID');
 need(operation==='inspect'||env.MAINTENANCE_CONFIRMED==='true','FLY_WORKER_MAINTENANCE_REQUIRED');
 return Object.freeze({operation,source,generation,ack,applyPrefix,deployPrefix,limitPrefix});
}

/** flyctl fixo, verificado por checksum no workflow; ambiente mínimo e saída capturada (nunca impressa). */
export function createFly({binary,token,spawnSync=nodeSpawnSync}){
 need(typeof binary==='string'&&isAbsolute(binary),'FLY_WORKER_CLI_INVALID');
 need(typeof token==='string'&&/^[\x20-\x7e]{20,8192}$/.test(token),'FLY_WORKER_TOKEN_MISSING');
 const env={PATH:'/usr/bin:/bin',HOME:process.env.HOME||'/tmp',FLY_API_TOKEN:token,FLY_NO_UPDATE_CHECK:'1',NO_COLOR:'1'};
 function run(args,{input,timeout=60_000,code}){
  const result=spawnSync(binary,args,{env,input,encoding:'utf8',timeout,maxBuffer:4*1024*1024,windowsHide:true});
  need(!result.error&&result.status===0,code);
  return result.stdout;
 }
 const json=(text,code)=>{try{return JSON.parse(text);}catch{fail(code);}};
 return {
  apps:()=>json(run(['apps','list','--org',ORG,'--json'],{code:'FLY_WORKER_APPS_LIST_FAILED'}),'FLY_WORKER_APPS_LIST_FAILED'),
  /** App sem máquina e sem IP: criar não gera cobrança; a máquina só nasce no deploy. */
  createApp:()=>{run(['apps','create',APP,'--org',ORG],{code:'FLY_WORKER_APP_CREATE_FAILED'});},
  status:()=>json(run(['status','--app',APP,'--json'],{code:'FLY_WORKER_STATUS_FAILED'}),'FLY_WORKER_STATUS_FAILED'),
  secrets:()=>json(run(['secrets','list','--app',APP,'--json'],{code:'FLY_WORKER_SECRETS_LIST_FAILED'}),'FLY_WORKER_SECRETS_LIST_FAILED'),
  /** Valores só no stdin, nunca em argumento; --stage não reinicia máquinas. */
  importSecrets:lines=>{run(['secrets','import','--app',APP,'--stage'],{input:lines,code:'FLY_WORKER_SECRETS_IMPORT_FAILED'});},
  deploy:(spawn=spawnSync)=>{
   const result=spawn(binary,['deploy','--app',APP,'--config',CONFIG,'--remote-only','--ha=false','--yes','--wait-timeout','10m'],
    {env,stdio:'inherit',timeout:30*60_000,windowsHide:true});
   need(!result.error&&result.status===0,'FLY_WORKER_DEPLOY_FAILED');
  }};
}

const appName=a=>object(a,'FLY_WORKER_APPS_LIST_FAILED').Name??a.name;
const orgSlug=o=>{const org=object(o??{},'FLY_WORKER_STATUS_FAILED');return org.Slug??org.slug;};

/** Estado da Fly só com metadados: existência do app, org, máquinas (id/região/estado/saídas) e nomes dos segredos. */
export function flyState(fly){
 const apps=fly.apps();need(Array.isArray(apps)&&apps.length<=500,'FLY_WORKER_APPS_LIST_FAILED');
 const listed=apps.filter(a=>appName(a)===APP);
 need(listed.length<=1,'FLY_WORKER_APPS_LIST_FAILED');
 if(listed.length===0)return {app:APP,org:ORG,exists:false,machines:[],secrets:[]};
 need(orgSlug(listed[0].Organization??listed[0].organization)===ORG,'FLY_WORKER_APP_MISMATCH');
 const status=object(fly.status(),'FLY_WORKER_STATUS_FAILED');
 need(status.Name===APP&&orgSlug(status.Organization)===ORG,'FLY_WORKER_APP_MISMATCH');
 const machines=(Array.isArray(status.Machines)?status.Machines:[]).map(m=>{object(m,'FLY_WORKER_STATUS_FAILED');
  return {id:String(m.id??''),region:String(m.region??''),state:String(m.state??'')};}).sort((a,b)=>a.id<b.id?-1:1);
 const names=fly.secrets();need(Array.isArray(names)&&names.length<=200,'FLY_WORKER_SECRETS_LIST_FAILED');
 const secrets=names.map(s=>{object(s,'FLY_WORKER_SECRETS_LIST_FAILED');need(typeof s.name==='string'&&/^[A-Z][A-Z0-9_]{0,127}$/.test(s.name),'FLY_WORKER_SECRETS_LIST_FAILED');
  return {name:s.name,digest:typeof s.digest==='string'?s.digest:''};}).sort((a,b)=>a.name<b.name?-1:1);
 need(new Set(secrets.map(s=>s.name)).size===secrets.length,'FLY_WORKER_SECRETS_LIST_FAILED');
 return {app:APP,org:ORG,exists:true,machines,secrets};
}
/** Saídas de processo (eventos exit) de cada máquina a partir de um instante, lidas do status; sem logs. */
export function exitsSince(fly,since){
 const status=object(fly.status(),'FLY_WORKER_STATUS_FAILED');
 let exits=0;
 for(const m of Array.isArray(status.Machines)?status.Machines:[])
  for(const e of Array.isArray(m?.events)?m.events:[])
   if(e&&e.type==='exit'&&Number(e.timestamp)>=since)exits++;
 return exits;
}

/** Linhas NAME=VALUE aceitas sem ambiguidade pelo parser do flyctl (sem aspas, #, espaço ou quebra de linha). */
export function secretLines(values){
 const lines=[];
 for(const [key,value] of Object.entries(values)){
  need(MANAGED.has(key)&&typeof value==='string'&&/^[A-Za-z0-9+/=_.:?&%-]{1,8192}$/.test(value),'FLY_WORKER_SECRET_VALUE_FORMAT_INVALID');
  lines.push(`${key}=${value}`);
 }
 return lines.join('\n')+'\n';
}

/** Executor Java: inspect/vercel-limit síncronos e apply interativo (senha e confirmação pelo stdin). */
export function javaInvocation(env){
 need(typeof env.JAVA_HOME==='string'&&isAbsolute(env.JAVA_HOME)&&typeof env.RUNNER_TEMP==='string'&&isAbsolute(env.RUNNER_TEMP),'FLY_WORKER_JAVA_ENV_INVALID');
 const password=env.DATABASE_PASSWORD;
 need(typeof password==='string'&&password.length>=16&&password.length<=4096&&!/[\x00-\x1f\x7f]/.test(password),'FLY_WORKER_OWNER_PASSWORD_INVALID');
 const classes=join(resolve(env.RUNNER_TEMP),WORK,'classes');
 return {executable:join(env.JAVA_HOME,'bin','java'),args:['-Xmx768m',`-Dlogback.configurationFile=${join(classes,'BOOT-INF/classes/bootstrap/logback-baseline.xml')}`,
  '-cp',[classes,join(classes,'BOOT-INF/classes'),join(classes,'BOOT-INF/lib/*')].join(delimiter),JAVA_MAIN],
  env:{PATH:env.PATH||'/usr/bin:/bin',JAVA_HOME:env.JAVA_HOME,HOME:env.HOME||'/tmp',CP_BASELINE_PROJECT_REF:PROJECT,
   DATABASE_URL:OWNER_URL,DATABASE_USER:`postgres.${PROJECT}`,DATABASE_PASSWORD:password,CP_FLY_CA_PATH:CA_PATH}};
}
const parseResult=line=>{try{const v=JSON.parse(line);return v&&v.phase==='RESULT'&&typeof v.code==='string'?v:null;}catch{return null;}};
const unknown=code=>({phase:'RESULT',eligible:false,applied:false,alreadyApplied:false,state:'UNKNOWN',fingerprint:'',code,budget:null,vercel:null});

export function createLogin(invocation,{spawn=nodeSpawn,spawnSync=nodeSpawnSync}={}){
 const childEnv=(request,operation,dbFingerprint='')=>({...invocation.env,CP_FLY_GENERATION:request.generation,CP_FLY_EXPECTED_COMMIT:request.source,
  GITHUB_SHA:request.source,CP_FLY_WORKER_OPERATION:operation,CP_FLY_DB_FINGERPRINT:dbFingerprint});
 const sync=(request,operation,dbFingerprint)=>{
  const result=spawnSync(invocation.executable,invocation.args,{env:childEnv(request,operation,dbFingerprint),input:'',encoding:'utf8',timeout:5*60_000,windowsHide:true});
  const outcome=parseResult((result.stdout||'').trim().split('\n').pop());
  return outcome??unknown('FLY_WORKER_RESULT_UNKNOWN');
 };
 return {
  inspect:request=>sync(request,'inspect',''),
  vercelLimit:(request,dbFingerprint)=>sync(request,'vercel-limit',dbFingerprint),
  /** Senha na primeira linha do stdin; quando o Java avisa STAGED, onStaged grava e confere na Fly e decide COMMIT. */
  apply(request,dbFingerprint,password,onStaged){
   return new Promise(done=>{
    const child=spawn(invocation.executable,invocation.args,{env:childEnv(request,'apply',dbFingerprint),stdio:['pipe','pipe','ignore'],windowsHide:true});
    let buffer='',outcome=null,staged=false,stagedError=null;
    const timer=setTimeout(()=>child.kill('SIGKILL'),15*60_000);
    child.stdin.on('error',()=>{});
    child.stdin.write(`${password}\n`);
    child.stdout.on('data',chunk=>{
     buffer+=chunk;let index;
     while((index=buffer.indexOf('\n'))>=0){
      const line=buffer.slice(0,index).trim();buffer=buffer.slice(index+1);
      if(line==='{"phase":"STAGED"}'&&!staged){
       staged=true;
       Promise.resolve().then(onStaged).then(ok=>child.stdin.end(ok===true?'COMMIT\n':'ABORT\n'),error=>{stagedError=error;child.stdin.end('ABORT\n');});
      } else outcome=parseResult(line)??outcome;
     }
    });
    child.on('close',()=>{clearTimeout(timer);done({outcome:outcome??unknown('FLY_WORKER_RESULT_UNKNOWN'),staged,stagedError});});
    child.on('error',()=>{});
   });
  }};
}

/** CA pública já validada pelo workflow (openssl); aqui só formato e tamanho. */
export function readCa(path=CA_PATH,read=readFileSync,stat=lstatSync){
 need(path===CA_PATH&&stat(path).isFile()&&!stat(path).isSymbolicLink(),'FLY_WORKER_CA_INVALID');
 const pem=read(path,'utf8');
 need(pem.length<=65536&&(pem.match(/-----BEGIN CERTIFICATE-----/g)||[]).length===1&&/-----END CERTIFICATE-----\s*$/.test(pem),'FLY_WORKER_CA_INVALID');
 return Buffer.from(pem,'utf8').toString('base64');
}
/** Mesmo formato do executor da API: 36 bytes em Base64 URL sem padding (48 caracteres). */
export const generatePassword=(randomBytes=secureRandomBytes)=>randomBytes(36).toString('base64url');

const budgetView=b=>b&&typeof b==='object'?{maxConnections:b.maxConnections,otherBackends:b.otherBackends,runtimeLimits:b.runtimeLimits,
 workerLimit:b.workerLimit,margin:b.margin,total:b.otherBackends+b.runtimeLimits+b.workerLimit+b.margin,fits:b.fits}:null;
const vercelView=v=>v&&typeof v==='object'?{state:v.state,limit:v.limit,backends:v.backends,code:v.code}:null;

async function inspectAll(req,deps){
 const fly=flyState(deps.fly);
 const db=deps.login.inspect(req);
 const database={state:db.state,eligible:db.eligible,code:db.code,fingerprint:db.fingerprint};
 const state={fly,database};
 const vercel=db.vercel&&typeof db.vercel==='object'?db.vercel:null;
 return {state,budget:budgetView(db.budget),vercel,
  applyAck:`${req.applyPrefix}${fingerprint(state)}`,
  deployAck:fly.exists?`${req.deployPrefix}${fingerprint({fly})}`:null,
  vercelLimitAck:vercel&&vercel.state==='ELIGIBLE'&&HEX64.test(vercel.fingerprint)?`${req.limitPrefix}${vercel.fingerprint}`:null};
}

export async function execute(env,deps){
 const req=validateRequest(env);
 if(req.operation==='vercel-limit'){
  const result=deps.login.vercelLimit(req,req.ack.slice(req.limitPrefix.length));
  need(result.code==='',result.code||'FLY_WORKER_RESULT_UNKNOWN');
  need(result.state==='REDUCED','FLY_WORKER_VERCEL_READBACK_FAILED');
  return {operation:'vercel-limit',applied:result.applied===true,alreadyApplied:result.alreadyApplied===true,
   vercel:vercelView(result.vercel),budget:budgetView(result.budget)};
 }
 if(req.operation==='deploy'){
  const fly=flyState(deps.fly);
  need(fly.exists,'FLY_WORKER_APP_ABSENT');
  need(req.ack===`${req.deployPrefix}${fingerprint({fly})}`,'FLY_WORKER_STATE_CHANGED');
  const names=fly.secrets.map(s=>s.name);
  need(fly.secrets.every(s=>MANAGED.has(s.name)),'FLY_WORKER_UNMANAGED_SECRET');
  need(JSON.stringify(names)===JSON.stringify(REQUIRED),'FLY_WORKER_SECRETS_INCOMPLETE');
  const since=deps.now();
  deps.fly.deploy();
  const stable=await deps.waitStable(since);
  const after=flyState(deps.fly);
  need(after.machines.length===1&&after.machines.every(m=>m.region===REGION&&m.state==='started'),'FLY_WORKER_MACHINE_LAYOUT_UNEXPECTED');
  return {operation:'deploy',stable,machines:after.machines.map(m=>`${m.region}:${m.state}`),secretNames:after.secrets.map(s=>s.name)};
 }
 const inspected=await inspectAll(req,deps);
 if(req.operation==='inspect')return {operation:'inspect',app:APP,org:ORG,appExists:inspected.state.fly.exists,
  machines:inspected.state.fly.machines.map(m=>`${m.region}:${m.state}`),secretNames:inspected.state.fly.secrets.map(s=>s.name),
  database:{state:inspected.state.database.state,eligible:inspected.state.database.eligible,code:inspected.state.database.code},
  budget:inspected.budget,vercel:vercelView(inspected.vercel),
  applyAck:inspected.applyAck,deployAck:inspected.deployAck,vercelLimitAck:inspected.vercelLimitAck};
 need(req.ack===inspected.applyAck,'FLY_WORKER_STATE_CHANGED');
 const {database}=inspected.state;let {fly}=inspected.state;
 need(database.eligible&&database.code==='','FLY_WORKER_NOT_ELIGIBLE');
 need(fly.secrets.every(s=>MANAGED.has(s.name)),'FLY_WORKER_UNMANAGED_SECRET');
 const names=new Set(fly.secrets.map(s=>s.name));
 if(database.state==='READY'){
  need(REQUIRED.every(k=>names.has(k)),'FLY_WORKER_SECRETS_INCOMPLETE');
  return {operation:'apply',alreadyApplied:true,changed:[],secretNames:[...names].sort()};
 }
 // Login novo com credencial já gravada na Fly seria troca silenciosa de geração.
 need(database.state!=='ABSENT'||(!names.has('DATABASE_USER')&&!names.has('DATABASE_PASSWORD')),'FLY_WORKER_EXISTING_CREDENTIAL_CONFLICT');
 let createdApp=false;
 if(!fly.exists){
  deps.fly.createApp();createdApp=true;
  fly=flyState(deps.fly);
  need(fly.exists&&fly.machines.length===0&&fly.secrets.length===0,'FLY_WORKER_APP_CREATE_READBACK_FAILED');
 }
 const password=generatePassword(deps.randomBytes);
 const values={CP_DB_CA_BASE64:deps.readCa(),DATABASE_PASSWORD:password,DATABASE_USER:`cp_worker_${req.generation.replaceAll('-','')}.${PROJECT}`};
 const lines=secretLines(values);
 const result=await deps.login.apply(req,database.fingerprint,password,async()=>{
  deps.fly.importSecrets(lines);
  const after=flyState(deps.fly);
  need(JSON.stringify(after.secrets.map(s=>s.name))===JSON.stringify(REQUIRED),'FLY_WORKER_SECRETS_READBACK_MISMATCH');
  return true;
 });
 // Falha na Fly depois do STAGED: o Java já desabilitou o login; os dois códigos vão para o registro.
 if(result.stagedError)fail(`${result.stagedError instanceof SafeFailure?result.stagedError.code:'FLY_WORKER_SECRETS_STEP_FAILED'};${result.outcome.code||'FLY_WORKER_RESULT_UNKNOWN'}`);
 need(result.staged&&result.outcome.code===''&&result.outcome.state==='READY'&&result.outcome.applied===true,result.outcome.code||'FLY_WORKER_RESULT_UNKNOWN');
 return {operation:'apply',alreadyApplied:false,createdApp,changed:[...REQUIRED],databaseLogin:'READY',budget:budgetView(result.outcome.budget),
  note:'Segredos em stage na Fly: valem no próximo deploy (operação deploy).'};
}

/**
 * Sem HTTP no worker: estável = uma máquina em gru com estado started em todas as leituras da janela final e nenhum
 * evento exit da máquina desde o início do deploy (falha da guarda de role encerra o processo e a Fly reinicia).
 */
export async function waitStable(since,{fly,attempts=18,required=6,delay=10_000,sleep=ms=>new Promise(r=>setTimeout(r,ms))}){
 let streak=0;
 for(let attempt=1;attempt<=attempts;attempt++){
  if(attempt>1)await sleep(delay);
  need(exitsSince(fly,since)===0,'FLY_WORKER_PROCESS_EXITED');
  const state=flyState(fly);
  const ok=state.machines.length===1&&state.machines[0].region===REGION&&state.machines[0].state==='started';
  streak=ok?streak+1:0;
  if(streak>=required)return {polls:attempt,startedStreak:streak,exits:0};
 }
 fail('FLY_WORKER_NOT_STABLE');
}

/** Dependências reais, só montadas na execução do workflow. */
export function realDeps(env){
 const binary=join(resolve(env.RUNNER_TEMP||'/nonexistent'),WORK,'flyctl');
 const operation=env.CP_FLY_OPERATION||'inspect';
 const fly=operation==='vercel-limit'?null:createFly({binary,token:env.FLY_API_TOKEN});
 return {fly,now:()=>Date.now(),waitStable:since=>waitStable(since,{fly}),randomBytes:secureRandomBytes,readCa:()=>readCa(),
  login:operation==='deploy'?null:createLogin(javaInvocation(env))};
}

if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
 const mode=process.argv[2];
 if(mode==='--gate'){
  try{validateRequest(process.env);console.log('REQUEST_APPROVED_FOR_ENVIRONMENT_GATE');}
  catch(error){console.error(error instanceof SafeFailure?error.code:'FLY_WORKER_REQUEST_INVALID');process.exitCode=1;}
 } else {
  try{
   const result=JSON.stringify(await execute(process.env,realDeps(process.env)));
   console.log(result);
   console.log(`::notice title=fly-worker::${result}`);
  }catch(error){
   const code=error instanceof SafeFailure?error.code:'FLY_WORKER_UNEXPECTED_FAILURE';
   console.error(code);console.log(`::error title=fly-worker::${code}`);process.exitCode=1;
  }
 }
}
