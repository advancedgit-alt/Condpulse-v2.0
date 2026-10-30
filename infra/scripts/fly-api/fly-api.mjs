import {createHash, randomBytes as secureRandomBytes} from 'node:crypto';
import {spawn as nodeSpawn, spawnSync as nodeSpawnSync} from 'node:child_process';
import {readFileSync, lstatSync} from 'node:fs';
import {isAbsolute, join, resolve, delimiter} from 'node:path';
import {pathToFileURL} from 'node:url';

/**
 * API de homologação na Fly.io (docs/FLY_API.md). Três operações manuais, sempre no app e projetos fixos:
 * - inspect: nomes dos segredos da Fly, valores NÃO sensíveis da Vercel, estado do login de banco; imprime os ACKs.
 * - apply: cria o login exclusivo da Fly (executor Java), gera chaves novas no runner e grava tudo como segredo
 *   da Fly sem eco; confere só os nomes de volta. Nenhum deploy.
 * - deploy: flyctl deploy --remote-only com uma máquina em gru e espera a readiness pública.
 * Nenhum valor de segredo é lido de volta ou impresso: só nomes, códigos e fingerprints.
 */
export const APP='condpulse-v2-0', ORG='condpulse-agencia-rami', REGION='gru';
export const PROJECT='rpnrqfrfwywglvyykgrg';
export const VERCEL=Object.freeze({project:'prj_IeUa4Zjg1IwaPqJQwv525H14I837',team:'team_6fzA029jxhDb1LpnrnphWGQ0'});
export const READINESS_URL=`https://${APP}.fly.dev/actuator/health/readiness`;
export const FLYCTL_VERSION='0.4.110';
const REPOSITORY='advancedgit-alt/Condpulse-v2.0', REF='refs/heads/ccr-5e725112-3x8hyg';
const ISSUER=`https://${PROJECT}.supabase.co/auth/v1`;
const RUNTIME_URL='jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/tmp/condpulse-db-ca.crt';
const OWNER_URL='jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt';
const CA_PATH='/run/secrets/db-ca.crt';
const WORK='condpulse-fly-api';
const JAVA_MAIN='br.com.condpulse.bootstrap.FlyRuntimeLogin';

/** Configuração pública fixa: mesmos valores do executor runtime da Vercel. */
export const FIXED_PUBLIC=Object.freeze({PORT:'8080',DB_POOL_SIZE:'2',DATABASE_URL:RUNTIME_URL,CP_JWT_ISSUER:ISSUER,
 CP_JWT_JWK_SET_URI:`${ISSUER}/.well-known/jwks.json`,CP_BFF_ORIGIN:'https://project-rhvdq.vercel.app'});
/** Chaves próprias da Fly geradas no runner (32 bytes, Base64 padrão). Nunca copiadas da Vercel. */
export const GENERATED=Object.freeze(['CP_BFF_ENCRYPTION_KEY','CP_BFF_RATE_LIMIT_KEY','CP_PARCEL_CODE_KEY']);
/** Segredos que a Fly sempre recebe no apply. */
export const REQUIRED=Object.freeze(['DATABASE_USER','DATABASE_PASSWORD','CP_VERCEL_DB_CA_BASE64','CP_SUPABASE_PUBLISHABLE_KEY',
 ...Object.keys(FIXED_PUBLIC),...GENERATED].sort());
const BOOL=/^(?:true|false)$/;
/** Valores NÃO sensíveis copiados da Vercel Production quando existem; formato fechado por chave. */
export const COPIED=Object.freeze({
 CP_BFF_ENABLED:BOOL,CP_RESERVATIONS_ENABLED:BOOL,CP_MANAGEMENT_ENABLED:BOOL,CP_PLATFORM_ENABLED:BOOL,
 CP_PLATFORM_BILLING_ENABLED:BOOL,CP_PARCELS_ENABLED:BOOL,CP_VISITS_ENABLED:BOOL,CP_NOTIFICATIONS_ENABLED:BOOL,
 CP_ANNOUNCEMENTS_ENABLED:BOOL,CP_REQUESTS_ENABLED:BOOL,CP_ACCESS_CREDENTIALS_ENABLED:BOOL,CP_BFF_RECOVERY_ENABLED:BOOL,
 CP_REALTIME_ENABLED:BOOL,
 // Anexos dependem de CP_STORAGE_SERVICE_KEY, que não é copiada: só "false" pode ir para a Fly.
 CP_FILES_ENABLED:/^false$/,
 CP_BFF_SESSION_HOURS:/^[1-9][0-9]?$/,CP_REALTIME_LIFETIME_SECONDS:/^[1-9][0-9]{0,4}$/,CP_REALTIME_MAX_PER_ACCOUNT:/^[1-9][0-9]{0,2}$/,
 CP_REALTIME_MAX_STREAMS:/^[1-9][0-9]{0,3}$/,CP_REALTIME_TICK_SECONDS:/^[1-9][0-9]{0,2}$/,CP_STORAGE_BUCKET:/^[a-z0-9][a-z0-9-]{1,62}$/});
const MANAGED=new Set([...REQUIRED,...Object.keys(COPIED)]);
const SHA=/^[a-f0-9]{40}$/, HEX64=/^[a-f0-9]{64}$/;
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const PROJECT_PATH=`/v9/projects/${VERCEL.project}?teamId=${VERCEL.team}`;
const ENV_LIST_PATH=`/v10/projects/${VERCEL.project}/env?teamId=${VERCEL.team}&decrypt=false`;
const singleEnvPath=id=>`/v1/projects/${VERCEL.project}/env/${id}?teamId=${VERCEL.team}`;

export class SafeFailure extends Error {constructor(code){super(code);this.code=code;}}
const fail=code=>{throw new SafeFailure(code);};
const need=(ok,code)=>{if(!ok)fail(code);};
const object=(v,code='FLY_RESPONSE_INVALID')=>{need(v!==null&&typeof v==='object'&&!Array.isArray(v),code);return v;};
export const sha256=value=>createHash('sha256').update(value).digest('hex');
export const fingerprint=value=>sha256(JSON.stringify(value));

/** Gate antes de liberar o Environment: só dispatch manual da branch principal, SHA exato e ACK no formato. */
export function validateRequest(env){
 const operation=env.CP_FLY_OPERATION||'inspect',source=env.CP_FLY_EXPECTED_COMMIT||'',generation=env.CP_FLY_GENERATION||'';
 need(['inspect','apply','deploy'].includes(operation)&&SHA.test(source)&&source===env.GITHUB_SHA&&env.GITHUB_EVENT_NAME==='workflow_dispatch'
  &&env.GITHUB_REPOSITORY===REPOSITORY&&env.GITHUB_REF===REF,'FLY_REQUEST_INVALID');
 need(operation==='deploy'?generation==='':UUID.test(generation)&&generation!=='00000000-0000-0000-0000-000000000000','FLY_GENERATION_INVALID');
 const ack=env.CP_FLY_ACK||'';
 const applyPrefix=`${APP}:fly-api:${generation}:${source}:`,deployPrefix=`${APP}:fly-deploy:${source}:`;
 const prefix=operation==='deploy'?deployPrefix:applyPrefix;
 need(operation==='inspect'?ack==='':ack.startsWith(prefix)&&HEX64.test(ack.slice(prefix.length)),'FLY_ACK_INVALID');
 need(operation==='inspect'||env.MAINTENANCE_CONFIRMED==='true','FLY_MAINTENANCE_REQUIRED');
 return Object.freeze({operation,source,generation,ack,applyPrefix,deployPrefix});
}

/** Vercel somente leitura: projeto fixo, lista sem decrypt e leitura individual de variável NÃO sensível. */
export function createVercelRequester(fetcher=globalThis.fetch){
 return async function request(path,token){
  need(path===PROJECT_PATH||path===ENV_LIST_PATH||/^\/v1\/projects\/prj_IeUa4Zjg1IwaPqJQwv525H14I837\/env\/[A-Za-z0-9_-]{1,64}\?teamId=team_6fzA029jxhDb1LpnrnphWGQ0$/.test(path),'FLY_VERCEL_DESTINATION_DENIED');
  const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),10_000);
  try{
   const response=await fetcher(`https://api.vercel.com${path}`,{method:'GET',redirect:'manual',signal:controller.signal,
    headers:{Accept:'application/json',Authorization:`Bearer ${token}`}});
   need(response.status>=200&&response.status<=299,'FLY_VERCEL_HTTP_DENIED');
   const text=await response.text();need(text.length>0&&text.length<=512*1024,'FLY_VERCEL_RESPONSE_INVALID');
   return JSON.parse(text);
  }catch(error){if(error instanceof SafeFailure)throw error;fail('FLY_VERCEL_UNAVAILABLE');}
  finally{clearTimeout(timer);}
 };
}

/** Copia só as chaves de COPIED da Production. Tipos sensitive/secret nunca são lidos; valor fora do formato aborta. */
export async function readVercelConfig(request,token){
 const project=object(await request(PROJECT_PATH,token),'FLY_VERCEL_RESPONSE_INVALID');const link=object(project.link??{},'FLY_VERCEL_RESPONSE_INVALID');
 need(project.id===VERCEL.project&&project.accountId===VERCEL.team&&link.type==='github'&&link.org==='advancedgit-alt'&&link.repo==='Condpulse-v2.0','FLY_VERCEL_PROJECT_MISMATCH');
 const list=await request(ENV_LIST_PATH,token);const envs=Array.isArray(list)?list:object(list,'FLY_VERCEL_RESPONSE_INVALID').envs;
 need(Array.isArray(envs)&&envs.length<=1000,'FLY_VERCEL_RESPONSE_INVALID');
 const copied={};
 for(const env of envs){
  object(env,'FLY_VERCEL_RESPONSE_INVALID');
  if(!Array.isArray(env.target)||!env.target.includes('production')||!Object.hasOwn(COPIED,env.key)||env.gitBranch)continue;
  need(!Object.hasOwn(copied,env.key),'FLY_VERCEL_DUPLICATE_ENV');
  need(!['sensitive','secret'].includes(env.type)&&env.visibility!=='secret','FLY_VERCEL_FLAG_TYPE_INVALID');
  let value;
  if(env.type==='plain')value=env.value;
  else{
   need(env.type==='encrypted'&&typeof env.id==='string'&&/^[A-Za-z0-9_-]{1,64}$/.test(env.id),'FLY_VERCEL_FLAG_TYPE_INVALID');
   const single=object(await request(singleEnvPath(env.id),token),'FLY_VERCEL_RESPONSE_INVALID');
   need(single.id===env.id&&single.key===env.key&&single.type==='encrypted'&&single.decrypted===true&&single.visibility!=='secret','FLY_VERCEL_FLAG_TYPE_INVALID');
   value=single.value;
  }
  need(typeof value==='string'&&COPIED[env.key].test(value),env.key==='CP_FILES_ENABLED'?'FLY_FILES_FLAG_UNSUPPORTED':'FLY_VERCEL_VALUE_INVALID');
  copied[env.key]=value;
 }
 return Object.fromEntries(Object.entries(copied).sort(([a],[b])=>a<b?-1:1));
}

/** flyctl fixo, verificado por checksum no workflow; ambiente mínimo e saída capturada (redigida antes de qualquer log). */
export function createFly({binary,token,spawnSync=nodeSpawnSync}){
 need(typeof binary==='string'&&isAbsolute(binary),'FLY_CLI_INVALID');
 need(typeof token==='string'&&/^[\x20-\x7e]{20,8192}$/.test(token),'FLY_TOKEN_MISSING');
 const env={PATH:'/usr/bin:/bin',HOME:process.env.HOME||'/tmp',FLY_API_TOKEN:token,FLY_NO_UPDATE_CHECK:'1',NO_COLOR:'1'};
 function run(args,{input,timeout=60_000,code}){
  const result=spawnSync(binary,args,{env,input,encoding:'utf8',timeout,maxBuffer:4*1024*1024,windowsHide:true});
  need(!result.error&&result.status===0,code);
  return result.stdout;
 }
 const json=(text,code)=>{try{return JSON.parse(text);}catch{fail(code);}};
 return {
  status:()=>json(run(['status','--app',APP,'--json'],{code:'FLY_STATUS_FAILED'}),'FLY_STATUS_FAILED'),
  secrets:()=>json(run(['secrets','list','--app',APP,'--json'],{code:'FLY_SECRETS_LIST_FAILED'}),'FLY_SECRETS_LIST_FAILED'),
  /** Valores só no stdin, nunca em argumento; --stage não reinicia máquinas. */
  importSecrets:lines=>{run(['secrets','import','--app',APP,'--stage'],{input:lines,code:'FLY_SECRETS_IMPORT_FAILED'});},
  deploy:(spawn=spawnSync)=>{
   const result=spawn(binary,['deploy','--app',APP,'--config','fly.toml','--remote-only','--ha=false','--yes','--wait-timeout','10m'],
    {env,stdio:'inherit',timeout:30*60_000,windowsHide:true});
   need(!result.error&&result.status===0,'FLY_DEPLOY_FAILED');
  }};
}

/** Estado da Fly só com metadados: app/org, máquinas (id/região) e nomes+digests dos segredos. */
export function flyState(fly){
 const status=object(fly.status(),'FLY_STATUS_FAILED');const org=object(status.Organization??{},'FLY_STATUS_FAILED');
 need(status.Name===APP&&(org.Slug??org.slug)===ORG,'FLY_APP_MISMATCH');
 const machines=(Array.isArray(status.Machines)?status.Machines:[]).map(m=>({id:String(object(m,'FLY_STATUS_FAILED').id??''),region:String(m.region??'')}))
  .sort((a,b)=>a.id<b.id?-1:1);
 const listed=fly.secrets();need(Array.isArray(listed)&&listed.length<=200,'FLY_SECRETS_LIST_FAILED');
 const secrets=listed.map(s=>{object(s,'FLY_SECRETS_LIST_FAILED');need(typeof s.name==='string'&&/^[A-Z][A-Z0-9_]{0,127}$/.test(s.name),'FLY_SECRETS_LIST_FAILED');
  return {name:s.name,digest:typeof s.digest==='string'?s.digest:''};}).sort((a,b)=>a.name<b.name?-1:1);
 need(new Set(secrets.map(s=>s.name)).size===secrets.length,'FLY_SECRETS_LIST_FAILED');
 return {app:APP,org:ORG,machines,secrets};
}

/** Linhas NAME=VALUE aceitas sem ambiguidade pelo parser do flyctl (sem aspas, #, espaço ou quebra de linha). */
export function secretLines(values){
 const lines=[];
 for(const [key,value] of Object.entries(values)){
  need(MANAGED.has(key)&&typeof value==='string'&&/^[A-Za-z0-9+/=_.:?&%-]{1,8192}$/.test(value),'FLY_SECRET_VALUE_FORMAT_INVALID');
  lines.push(`${key}=${value}`);
 }
 return lines.join('\n')+'\n';
}

/** Executor Java do login de banco: inspect síncrono e apply interativo (senha e confirmação pelo stdin). */
export function javaInvocation(env){
 need(typeof env.JAVA_HOME==='string'&&isAbsolute(env.JAVA_HOME)&&typeof env.RUNNER_TEMP==='string'&&isAbsolute(env.RUNNER_TEMP),'FLY_JAVA_ENV_INVALID');
 const password=env.DATABASE_PASSWORD;
 need(typeof password==='string'&&password.length>=16&&password.length<=4096&&!/[\x00-\x1f\x7f]/.test(password),'FLY_OWNER_PASSWORD_INVALID');
 const classes=join(resolve(env.RUNNER_TEMP),WORK,'classes');
 return {executable:join(env.JAVA_HOME,'bin','java'),args:['-Xmx768m',`-Dlogback.configurationFile=${join(classes,'BOOT-INF/classes/bootstrap/logback-baseline.xml')}`,
  '-cp',[classes,join(classes,'BOOT-INF/classes'),join(classes,'BOOT-INF/lib/*')].join(delimiter),JAVA_MAIN],
  env:{PATH:env.PATH||'/usr/bin:/bin',JAVA_HOME:env.JAVA_HOME,HOME:env.HOME||'/tmp',CP_BASELINE_PROJECT_REF:PROJECT,
   DATABASE_URL:OWNER_URL,DATABASE_USER:`postgres.${PROJECT}`,DATABASE_PASSWORD:password,CP_FLY_CA_PATH:CA_PATH}};
}
const parseResult=line=>{try{const v=JSON.parse(line);return v&&v.phase==='RESULT'&&typeof v.code==='string'?v:null;}catch{return null;}};

export function createLogin(invocation,{spawn=nodeSpawn,spawnSync=nodeSpawnSync}={}){
 const childEnv=(request,operation,dbFingerprint='')=>({...invocation.env,CP_FLY_GENERATION:request.generation,CP_FLY_EXPECTED_COMMIT:request.source,
  GITHUB_SHA:request.source,CP_FLY_RUNTIME_OPERATION:operation,CP_FLY_DB_FINGERPRINT:dbFingerprint});
 return {
  inspect(request){
   const result=spawnSync(invocation.executable,invocation.args,{env:childEnv(request,'inspect'),input:'',encoding:'utf8',timeout:5*60_000,windowsHide:true});
   const outcome=parseResult((result.stdout||'').trim().split('\n').pop());
   need(outcome,'FLY_RUNTIME_RESULT_UNKNOWN');return outcome;
  },
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
    child.on('close',()=>{clearTimeout(timer);done({outcome:outcome??{phase:'RESULT',eligible:false,applied:false,alreadyApplied:false,state:'UNKNOWN',fingerprint:'',code:'FLY_RUNTIME_RESULT_UNKNOWN'},staged,stagedError});});
    child.on('error',()=>{});
   });
  }};
}

/** CA pública já validada pelo workflow (openssl); aqui só formato e tamanho. */
export function readCa(path=CA_PATH,read=readFileSync,stat=lstatSync){
 need(path===CA_PATH&&stat(path).isFile()&&!stat(path).isSymbolicLink(),'FLY_CA_INVALID');
 const pem=read(path,'utf8');
 need(pem.length<=65536&&(pem.match(/-----BEGIN CERTIFICATE-----/g)||[]).length===1&&/-----END CERTIFICATE-----\s*$/.test(pem),'FLY_CA_INVALID');
 return Buffer.from(pem,'utf8').toString('base64');
}

const key32=randomBytes=>randomBytes(32).toString('base64');
export function generateValues(randomBytes=secureRandomBytes){
 const values={};
 for(const key of GENERATED)values[key]=key32(randomBytes);
 need(new Set(GENERATED.map(k=>values[k])).size===GENERATED.length,'FLY_GENERATED_KEYS_COLLIDE');
 // Mesmo formato do executor da Vercel: 36 bytes em Base64 URL sem padding (48 caracteres).
 values.DATABASE_PASSWORD=randomBytes(36).toString('base64url');
 return values;
}

function summary(state){
 return {app:state.fly.app,org:state.fly.org,machines:state.fly.machines.map(m=>m.region),secretNames:state.fly.secrets.map(s=>s.name),
  vercelCopied:state.vercel,database:state.database?{state:state.database.state,eligible:state.database.eligible,code:state.database.code}:undefined};
}

/** Pontos de decisão sem escrita: estado da Fly, da Vercel e do login, mais os dois ACKs. */
async function inspectAll(req,env,deps){
 const fly=flyState(deps.fly);
 const vercel=await readVercelConfig(deps.vercel,env.CP_VERCEL_TOKEN);
 const database=deps.login.inspect(req);
 const state={fly,vercel,database:{state:database.state,eligible:database.eligible,code:database.code,fingerprint:database.fingerprint}};
 return {state,applyAck:`${req.applyPrefix}${fingerprint(state)}`,deployAck:`${req.deployPrefix}${fingerprint({fly})}`};
}

export async function execute(env,deps){
 const req=validateRequest(env);
 if(req.operation==='deploy'){
  const fly=flyState(deps.fly);
  need(req.ack===`${req.deployPrefix}${fingerprint({fly})}`,'FLY_STATE_CHANGED');
  const names=new Set(fly.secrets.map(s=>s.name));
  need(REQUIRED.every(k=>names.has(k)),'FLY_SECRETS_INCOMPLETE');
  need(fly.secrets.every(s=>MANAGED.has(s.name)),'FLY_UNMANAGED_SECRET');
  deps.fly.deploy();
  const readiness=await deps.waitReady();
  const after=flyState(deps.fly);
  need(after.machines.length===1&&after.machines.every(m=>m.region===REGION),'FLY_MACHINE_LAYOUT_UNEXPECTED');
  return {operation:'deploy',readiness,machines:after.machines.map(m=>m.region),secretNames:after.secrets.map(s=>s.name)};
 }
 need(typeof env.CP_VERCEL_TOKEN==='string'&&env.CP_VERCEL_TOKEN.length>=20,'FLY_VERCEL_TOKEN_MISSING');
 const inspected=await inspectAll(req,env,deps);
 if(req.operation==='inspect')return {operation:'inspect',...summary(inspected.state),applyAck:inspected.applyAck,deployAck:inspected.deployAck};
 need(req.ack===inspected.applyAck,'FLY_STATE_CHANGED');
 const {fly,vercel,database}=inspected.state;
 need(database.eligible&&database.code==='','FLY_RUNTIME_NOT_ELIGIBLE');
 const names=new Set(fly.secrets.map(s=>s.name));
 need(fly.secrets.every(s=>MANAGED.has(s.name)),'FLY_UNMANAGED_SECRET');
 // Flag que sumiu da Vercel não pode ficar com valor antigo na Fly (import não remove).
 need(Object.keys(COPIED).every(k=>!names.has(k)||Object.hasOwn(vercel,k)),'FLY_STALE_COPIED_SECRET');
 if(database.state==='READY'){
  need(REQUIRED.every(k=>names.has(k)),'FLY_SECRETS_INCOMPLETE');
  return {operation:'apply',alreadyApplied:true,changed:[],secretNames:[...names].sort()};
 }
 // Login novo (ABSENT) com credencial de banco já gravada na Fly seria troca silenciosa de geração.
 need(database.state!=='ABSENT'||(!names.has('DATABASE_USER')&&!names.has('DATABASE_PASSWORD')),'FLY_EXISTING_RUNTIME_CONFLICT');
 const publishable=env.CP_SUPABASE_PUBLISHABLE_KEY;
 need(typeof publishable==='string'&&/^sb_publishable_[A-Za-z0-9_-]{16,}$/.test(publishable),'FLY_PUBLISHABLE_KEY_INVALID');
 const generated=generateValues(deps.randomBytes);
 const values={...vercel,...FIXED_PUBLIC,CP_SUPABASE_PUBLISHABLE_KEY:publishable,CP_VERCEL_DB_CA_BASE64:deps.readCa(),
  DATABASE_USER:`cp_runtime_${req.generation.replaceAll('-','')}.${PROJECT}`,...generated};
 const lines=secretLines(values);
 const expected=[...Object.keys(values)].sort();
 const result=await deps.login.apply(req,database.fingerprint,generated.DATABASE_PASSWORD,async()=>{
  deps.fly.importSecrets(lines);
  const after=flyState(deps.fly);
  need(JSON.stringify(after.secrets.map(s=>s.name))===JSON.stringify(expected),'FLY_SECRETS_READBACK_MISMATCH');
  return true;
 });
 // Falha na Fly depois do STAGED: o Java já desabilitou o login; os dois códigos vão para o registro.
 if(result.stagedError)fail(`${result.stagedError instanceof SafeFailure?result.stagedError.code:'FLY_SECRETS_STEP_FAILED'};${result.outcome.code||'FLY_RUNTIME_RESULT_UNKNOWN'}`);
 need(result.staged&&result.outcome.code===''&&result.outcome.state==='READY'&&result.outcome.applied===true,result.outcome.code||'FLY_RUNTIME_RESULT_UNKNOWN');
 return {operation:'apply',alreadyApplied:false,changed:expected,databaseLogin:'READY',
  note:'Segredos em stage na Fly: valem no próximo deploy (operação deploy). Sessões web e códigos de retirada delegada emitidos pela Vercel não valem na Fly.'};
}

/** Readiness pública: 200, JSON e status UP; sem redirecionamento, prazo total limitado. */
export async function waitReady({fetcher=globalThis.fetch,attempts=30,delay=10_000,sleep=ms=>new Promise(r=>setTimeout(r,ms))}={}){
 for(let attempt=1;attempt<=attempts;attempt++){
  const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),10_000);
  try{
   const response=await fetcher(READINESS_URL,{method:'GET',redirect:'manual',signal:controller.signal,headers:{Accept:'application/json'}});
   const text=await response.text();
   if(response.status===200&&(response.headers.get('content-type')||'').includes('json')&&text.length<=4096&&JSON.parse(text).status==='UP')
    return {status:200,body:'UP',attempt};
  }catch{/* nova tentativa */}
  finally{clearTimeout(timer);}
  if(attempt<attempts)await sleep(delay);
 }
 fail('FLY_READINESS_TIMEOUT');
}

/** Dependências reais, só montadas na execução do workflow. */
export function realDeps(env){
 const binary=join(resolve(env.RUNNER_TEMP||'/nonexistent'),WORK,'flyctl');
 const fly=createFly({binary,token:env.FLY_API_TOKEN});
 const operation=env.CP_FLY_OPERATION||'inspect';
 return {fly,vercel:createVercelRequester(),waitReady:()=>waitReady(),randomBytes:secureRandomBytes,readCa:()=>readCa(),
  login:operation==='deploy'?null:createLogin(javaInvocation(env))};
}

if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
 const mode=process.argv[2];
 if(mode==='--gate'){
  try{validateRequest(process.env);console.log('REQUEST_APPROVED_FOR_ENVIRONMENT_GATE');}
  catch(error){console.error(error instanceof SafeFailure?error.code:'FLY_REQUEST_INVALID');process.exitCode=1;}
 } else {
  try{
   const result=JSON.stringify(await execute(process.env,realDeps(process.env)));
   console.log(result);
   // Anotação legível pela API de checks; só metadados, nunca valor de segredo.
   console.log(`::notice title=fly-api::${result}`);
  }catch(error){
   const code=error instanceof SafeFailure?error.code:'FLY_UNEXPECTED_FAILURE';
   console.error(code);console.log(`::error title=fly-api::${code}`);process.exitCode=1;
  }
 }
}
