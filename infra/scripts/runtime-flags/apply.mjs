import {createHash, randomBytes as secureRandomBytes} from 'node:crypto';
import {pathToFileURL} from 'node:url';

/**
 * Altera apenas o perfil escolhido no projeto Vercel fixo do CondPulse (homologação).
 * demo: somente CP_DEMO_ENABLED, sem gerar segredos. access: lote histórico de credenciais/facial e chave de retirada.
 * Nunca lê nem imprime valor de segredo: o segredo é gerado aqui, enviado como "sensitive" e só os metadados voltam.
 * inspect mostra o estado e o fingerprint; apply exige o ACK com esse fingerprint (o estado não mudou entre os dois).
 */
export const TARGET=Object.freeze({project:'prj_IeUa4Zjg1IwaPqJQwv525H14I837',team:'team_6fzA029jxhDb1LpnrnphWGQ0'});
export const FLAGS=Object.freeze({CP_ACCESS_CREDENTIALS_ENABLED:'true',CP_FACIAL_ACCESS_ENABLED:'true'});
export const GENERATED=Object.freeze(['CP_PARCEL_CODE_KEY']);
const PROFILES=Object.freeze({
 access:Object.freeze({flags:FLAGS,generated:GENERATED}),
 demo:Object.freeze({flags:Object.freeze({CP_DEMO_ENABLED:'true'}),generated:Object.freeze([])})
});
function configuration(profile){need(Object.hasOwn(PROFILES,profile),'FLAGS_PROFILE_INVALID');return PROFILES[profile];}
const ENV_PATH=`/v10/projects/${TARGET.project}/env?teamId=${TARGET.team}`;
const PROJECT_PATH=`/v9/projects/${TARGET.project}?teamId=${TARGET.team}`;
const SHA=/^[a-f0-9]{40}$/;
class SafeFailure extends Error {constructor(code){super(code);this.code=code;}}
const fail=code=>{throw new SafeFailure(code);};
const need=(ok,code)=>{if(!ok)fail(code);};
const object=v=>{need(v!==null&&typeof v==='object'&&!Array.isArray(v),'FLAGS_RESPONSE_INVALID');return v;};

export function validateRequest(env){
 const operation=env.CP_FLAGS_OPERATION||'inspect',source=env.CP_FLAGS_EXPECTED_COMMIT||'';
 const profile=env.CP_FLAGS_PROFILE||'access';
 configuration(profile);
 need(['inspect','apply'].includes(operation)&&SHA.test(source)&&source===env.GITHUB_SHA&&env.GITHUB_EVENT_NAME==='workflow_dispatch'
  &&env.GITHUB_REPOSITORY==='advancedgit-alt/Condpulse-v2.0'&&env.GITHUB_REF==='refs/heads/ccr-5e725112-3x8hyg','FLAGS_REQUEST_INVALID');
 const ack=env.CP_FLAGS_ACK||'',prefix=`${TARGET.project}:flags:${profile==='access'?'':`${profile}:`}${source}:`;
 need(operation==='inspect'?ack==='':ack.startsWith(prefix)&&/^[a-f0-9]{64}$/.test(ack.slice(prefix.length)),'FLAGS_ACK_INVALID');
 need(operation!=='apply'||env.MAINTENANCE_CONFIRMED==='true','FLAGS_MAINTENANCE_REQUIRED');
 return Object.freeze({operation,source,ack,prefix,profile});
}

/** Destinos fixos da API da Vercel, sem redirecionamento, com prazo e tamanho limitados. */
export function createRequester(fetcher=globalThis.fetch){
 return async function request({method,path,token,body}){
  need(method==='GET'&&(path===PROJECT_PATH||path===`${ENV_PATH}&decrypt=false`)||method==='POST'&&path===`${ENV_PATH}&upsert=true`,'FLAGS_DESTINATION_DENIED');
  const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),10_000);
  try{
   const response=await fetcher(`https://api.vercel.com${path}`,{method,redirect:'manual',signal:controller.signal,
    headers:{Accept:'application/json',Authorization:`Bearer ${token}`,...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
   need(response.status>=200&&response.status<=299,'FLAGS_PROVIDER_HTTP_DENIED');
   const text=await response.text();need(text.length>0&&text.length<=512*1024,'FLAGS_RESPONSE_INVALID');
   return JSON.parse(text);
  }catch(error){if(error instanceof SafeFailure)throw error;fail('FLAGS_PROVIDER_UNAVAILABLE');}
  finally{clearTimeout(timer);}
 };
}

/** Estado só com metadados: valor das flags (texto simples) e presença dos segredos. */
export function state(envs,profile='access'){
 const selected=configuration(profile);
 need(Array.isArray(envs),'FLAGS_RESPONSE_INVALID');
 const production=envs.filter(e=>Array.isArray(e.target)&&e.target.includes('production'));
 const flags={},secrets={};
 for(const [key] of Object.entries(selected.flags)){
  const found=production.filter(e=>e.key===key);need(found.length<=1,'FLAGS_DUPLICATE_ENV');
  if(found.length) need(found[0].type==='plain'&&typeof found[0].value==='string','FLAGS_PLAIN_EXPECTED');
  flags[key]=found.length?found[0].value:null;
 }
 for(const key of selected.generated){
  const found=production.filter(e=>e.key===key);need(found.length<=1,'FLAGS_DUPLICATE_ENV');
  if(found.length) need(['sensitive','secret','encrypted'].includes(found[0].type),'FLAGS_SECRET_TYPE_INVALID');
  secrets[key]=found.length>0;
 }
 return {flags,secrets};
}
export const fingerprint=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');

export async function execute(env,{request=createRequester(),randomBytes=secureRandomBytes}={}){
 const req=validateRequest(env),token=env.CP_VERCEL_TOKEN,selected=configuration(req.profile);
 need(typeof token==='string'&&token.length>=20,'FLAGS_TOKEN_MISSING');
 const project=object(await request({method:'GET',path:PROJECT_PATH,token}));
 const link=object(project.link??{});
 need(project.id===TARGET.project&&project.accountId===TARGET.team&&link.type==='github'&&link.org==='advancedgit-alt'&&link.repo==='Condpulse-v2.0','FLAGS_PROJECT_MISMATCH');
 const before=state(object(await request({method:'GET',path:`${ENV_PATH}&decrypt=false`,token})).envs,req.profile);
 const print=fingerprint(before);
 if(req.operation==='inspect') return {operation:'inspect',profile:req.profile,state:before,ack:`${req.prefix}${print}`};
 need(req.ack===`${req.prefix}${print}`,'FLAGS_STATE_CHANGED');
 const changed=[];
 for(const [key,value] of Object.entries(selected.flags)) if(before.flags[key]!==value){
  await request({method:'POST',path:`${ENV_PATH}&upsert=true`,token,body:{key,value,type:'plain',target:['production']}});changed.push(key);
 }
 for(const key of selected.generated) if(!before.secrets[key]){
  const value=randomBytes(32).toString('base64');
  await request({method:'POST',path:`${ENV_PATH}&upsert=true`,token,body:{key,value,type:'sensitive',target:['production']}});changed.push(key);
 }
 const after=state(object(await request({method:'GET',path:`${ENV_PATH}&decrypt=false`,token})).envs,req.profile);
 need(Object.entries(selected.flags).every(([k,v])=>after.flags[k]===v)&&selected.generated.every(k=>after.secrets[k]),'FLAGS_READBACK_MISMATCH');
 return {operation:'apply',profile:req.profile,changed,state:after,note:'Vale a partir do próximo deployment de produção.'};
}

if(import.meta.url===pathToFileURL(process.argv[1]).href){
 try{
  const result=JSON.stringify(await execute(process.env));
  console.log(result);
  // Anotação legível pela API de checks (o log bruto pode não estar acessível); só metadados, nunca valor de segredo.
  console.log(`::notice title=runtime-flags::${result}`);
 }
 catch(error){console.error(error instanceof SafeFailure?error.code:'FLAGS_UNEXPECTED_FAILURE');process.exitCode=1;}
}
