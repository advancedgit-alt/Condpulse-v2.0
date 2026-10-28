import {createHash, randomBytes as secureRandomBytes} from 'node:crypto';
import {pathToFileURL} from 'node:url';

export const TARGET=Object.freeze({project:'prj_IeUa4Zjg1IwaPqJQwv525H14I837',team:'team_6fzA029jxhDb1LpnrnphWGQ0',supabase:'rpnrqfrfwywglvyykgrg',origin:'https://project-rhvdq.vercel.app'});
const ISSUER=`https://${TARGET.supabase}.supabase.co/auth/v1`;
const ENV_PATH=`/v10/projects/${TARGET.project}/env?teamId=${TARGET.team}`;
const PROJECT_PATH=`/v9/projects/${TARGET.project}?teamId=${TARGET.team}`;
const SETTINGS=`${ISSUER}/settings`;
const SECRET_KEYS=['CP_BFF_ENCRYPTION_KEY','CP_BFF_RATE_LIMIT_KEY'];
const PUBLIC_KEY='CP_SUPABASE_PUBLISHABLE_KEY';
const PRESETS=Object.freeze({CP_BFF_ENABLED:'false',CP_RESERVATIONS_ENABLED:'false',CP_BFF_ORIGIN:TARGET.origin,CP_JWT_ISSUER:ISSUER,CP_JWT_JWK_SET_URI:`${ISSUER}/.well-known/jwks.json`});
const RELEVANT=new Set([...Object.keys(PRESETS),...SECRET_KEYS,PUBLIC_KEY,'CP_BFF_SESSION_HOURS']);
const SHA=/^[a-f0-9]{40}$/;
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const own=(v,k)=>Object.prototype.hasOwnProperty.call(v,k);
const fail=code=>{throw new SafeFailure(code);};
class SafeFailure extends Error {constructor(code){super(code);this.code=code;}}
function requireValue(ok,code){if(!ok)fail(code);}
function object(v){requireValue(v!==null&&typeof v==='object'&&!Array.isArray(v),'BFF_RESPONSE_INVALID');return v;}
function cleanText(v,max=1024){return typeof v==='string'&&v.length>0&&v.length<=max&&!/[\u0000-\u001f\u007f]/.test(v);}
function canonical(value){return JSON.stringify(value);}
function sha(value){return createHash('sha256').update(value).digest('hex');}
function marker(request){return `condpulse-bff:${TARGET.supabase}:${request.generation}:${request.source}`;}

/** Gate before the protected Environment is opened. No credential is inspected here. */
export function validateRequest(env){
 const operation=env.CP_BFF_CONFIG_OPERATION||'inspect';
 const generation=env.CP_BFF_CONFIG_GENERATION,source=env.CP_BFF_CONFIG_EXPECTED_COMMIT;
 requireValue(['inspect','apply'].includes(operation)&&UUID.test(generation||'')&&generation!=='00000000-0000-0000-0000-000000000000'&&SHA.test(source||'')&&source===env.CP_BFF_CONFIG_SOURCE_COMMIT&&env.GITHUB_EVENT_NAME==='workflow_dispatch'&&env.GITHUB_REPOSITORY==='advancedgit-alt/Condpulse-v2.0'&&env.GITHUB_REF==='refs/heads/ccr-5e725112-3x8hyg'&&env.GITHUB_SHA===source,'BFF_REQUEST_INVALID');
 const ack=env.CP_BFF_CONFIG_ACK||'';
 const expectedPrefix=`${TARGET.supabase}:bff:${generation}:${source}:`;
 requireValue(operation==='inspect'?ack==='':ack.startsWith(expectedPrefix)&&/^[a-f0-9]{64}$/.test(ack.slice(expectedPrefix.length)),'BFF_ACK_INVALID');
 requireValue(operation!=='apply'||env.MAINTENANCE_CONFIRMED==='true','BFF_MAINTENANCE_REQUIRED');
 return Object.freeze({operation,generation,source,ack,expectedPrefix});
}

/** Fixed destinations, normal TLS, no redirects/retries, bounded total response time and streamed bytes. */
export function createRequester(fetcher=globalThis.fetch){
 return async function request({provider,method,path,token,body}){
  const isVercel=provider==='vercel';
  requireValue(isVercel?(path===PROJECT_PATH&&method==='GET'||path===`${ENV_PATH}&decrypt=false`&&method==='GET'||path===ENV_PATH&&method==='POST'):(provider==='supabase'&&path===SETTINGS&&method==='GET'&&body===undefined),'BFF_DESTINATION_DENIED');
  const url=isVercel?`https://api.vercel.com${path}`:SETTINGS;
  const controller=new AbortController();
  let reader,responseBody;
  let timer;
  try{
   const headers={Accept:'application/json',...(isVercel?{Authorization:`Bearer ${token}`}:{apikey:token})};
   if(body!==undefined)headers['Content-Type']='application/json';
   const work=(async()=>{
   const response=await fetcher(url,{method,headers,redirect:'manual',signal:controller.signal,...(body===undefined?{}:{body:JSON.stringify(body)})});
   responseBody=response.body;
   requireValue(!controller.signal.aborted,'BFF_PROVIDER_UNAVAILABLE');
   requireValue(response.status>=200&&response.status<=299,'BFF_PROVIDER_HTTP_DENIED');
   requireValue((response.headers.get('content-type')||'').split(';')[0].trim().toLowerCase()==='application/json','BFF_RESPONSE_INVALID');
   const length=response.headers.get('content-length');
   requireValue(length===null||/^\d+$/.test(length)&&Number(length)<=256*1024,'BFF_RESPONSE_INVALID');
   requireValue(response.body&&typeof response.body.getReader==='function','BFF_RESPONSE_INVALID');
   reader=response.body.getReader();let total=0;const chunks=[];
   while(true){const part=await reader.read();requireValue(!controller.signal.aborted,'BFF_PROVIDER_UNAVAILABLE');if(part.done)break;total+=part.value.byteLength;requireValue(total<=256*1024,'BFF_RESPONSE_INVALID');chunks.push(part.value);}
   const bytes=Buffer.concat(chunks,total);requireValue(bytes.length>0,'BFF_RESPONSE_INVALID');
   return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));
   })();
   const deadline=new Promise((_,reject)=>{timer=setTimeout(()=>{controller.abort();if(reader)void reader.cancel().catch(()=>{});reject(new SafeFailure('BFF_PROVIDER_UNAVAILABLE'));},10_000);});
   return await Promise.race([work,deadline]);
  }catch(error){if(error instanceof SafeFailure)throw error;fail('BFF_PROVIDER_UNAVAILABLE');}
  finally{clearTimeout(timer);controller.abort();if(reader)void reader.cancel().catch(()=>{});else if(responseBody)void responseBody.cancel().catch(()=>{});}
 };
}

function scope(entry){
 requireValue(Array.isArray(entry.target)&&entry.target.length===1&&entry.target[0]==='production'&&entry.gitBranch==null&&(!own(entry,'customEnvironmentIds')||Array.isArray(entry.customEnvironmentIds)&&entry.customEnvironmentIds.length===0)&&entry.system!==true&&entry.configurationId==null,'BFF_ENV_SCOPE_INVALID');
}
function visibility(entry,secret){
 const type=entry.type,visible=entry.visibility;
 if(secret)requireValue(['sensitive','secret'].includes(type)&&(visible===undefined||visible===null||visible==='secret')&&entry.decrypted!==true,'BFF_SECRET_METADATA_INVALID');
 else requireValue(type==='plain'&&(visible===undefined||visible===null||visible==='config'),'BFF_PUBLIC_CONFIG_INVALID');
}
function metadata(entry){
 const updated=entry.updatedAt;
 requireValue(cleanText(entry.id,256)&&/^[A-Za-z0-9_-]+$/.test(entry.id)&&((typeof updated==='number'&&Number.isSafeInteger(updated)&&updated>=0)||(typeof updated==='string'&&/^\d{1,20}$/.test(updated))),'BFF_RESPONSE_INVALID');
 const comment=entry.comment==null?'':entry.comment;requireValue(typeof comment==='string'&&comment.length<=500,'BFF_RESPONSE_INVALID');
 return {key:entry.key,id:entry.id,type:entry.type,visibility:entry.visibility??null,target:['production'],gitBranch:null,customEnvironmentIds:[],updatedAt:String(updated),comment};
}
async function snapshot(requester,token,request,publishable){
 const project=object(await requester({provider:'vercel',method:'GET',path:PROJECT_PATH,token}));
 const link=object(project.link);
 requireValue(project.id===TARGET.project&&project.accountId===TARGET.team&&link.type==='github'&&link.org==='advancedgit-alt'&&link.repo==='Condpulse-v2.0','BFF_PROJECT_MISMATCH');
 const response=object(await requester({provider:'vercel',method:'GET',path:`${ENV_PATH}&decrypt=false`,token}));
 requireValue(Array.isArray(response.envs)&&response.envs.length<=1000&&(!own(response,'pagination')||response.pagination===null||object(response.pagination).next==null),'BFF_ENV_PAGE_INCOMPLETE');
 const entries=new Map();
 for(const value of response.envs){
  const entry=object(value);requireValue(cleanText(entry.key,256),'BFF_RESPONSE_INVALID');
  // Never access value, serialize or spread unknown/secret rows. Their payload can be ciphertext or an echoed secret.
  if(!RELEVANT.has(entry.key))continue;
  requireValue(!entries.has(entry.key),'BFF_ENV_DUPLICATE');scope(entry);
  const isSecret=SECRET_KEYS.includes(entry.key);visibility(entry,isSecret);
  const safe=metadata(entry);
  if(!isSecret){requireValue(typeof entry.value==='string'&&entry.value.length<=4096,'BFF_PUBLIC_CONFIG_INVALID');safe.value=entry.value;}
  entries.set(entry.key,safe);
 }
 for(const [key,value]of Object.entries(PRESETS))requireValue(entries.has(key)&&entries.get(key).value===value,'BFF_PRECONDITION_MISMATCH');
 if(entries.has('CP_BFF_SESSION_HOURS'))requireValue(entries.get('CP_BFF_SESSION_HOURS').value==='8','BFF_PRECONDITION_MISMATCH');
 const staged=[...SECRET_KEYS,PUBLIC_KEY].filter(key=>entries.has(key));
 let state='ABSENT';
 if(staged.length){
  requireValue(staged.length===3,'BFF_PARTIAL_STATE');
  requireValue(staged.every(key=>entries.get(key).comment===marker(request))&&entries.get(PUBLIC_KEY).value===publishable,'BFF_FOREIGN_STATE');
  state='STAGED_METADATA_ONLY';
 }
 const rows=[...entries.values()].sort((a,b)=>a.key<b.key?-1:a.key>b.key?1:0);
 const fingerprint=sha(canonical({project:TARGET.project,team:TARGET.team,supabase:TARGET.supabase,generation:request.generation,source:request.source,publishableSha256:sha(publishable),rows}));
 const baseRows=rows.filter(row=>!SECRET_KEYS.includes(row.key)&&row.key!==PUBLIC_KEY);
 return {state,fingerprint,entries,base:canonical(baseRows)};
}
function validWrite(response){
 const result=object(response);
 requireValue(!own(result,'error')&&!own(result,'errors')&&(!own(result,'status')||result.status==='success')&&Array.isArray(result.failed)&&result.failed.length===0,'BFF_WRITE_INCOMPLETE');
 const created=Array.isArray(result.created)?result.created:[result.created];
 requireValue(created.length===3,'BFF_WRITE_INCOMPLETE');
 const entries=new Map();
 for(const raw of created){const entry=object(raw);requireValue(!own(entry,'error')&&!own(entry,'errors')&&(!own(entry,'status')||entry.status==='success')&&[...SECRET_KEYS,PUBLIC_KEY].includes(entry.key)&&!entries.has(entry.key),'BFF_WRITE_INCOMPLETE');scope(entry);visibility(entry,SECRET_KEYS.includes(entry.key));entries.set(entry.key,metadata(entry));}
 return entries;
}

/** The only mutation is one create-only Vercel POST; this never enables login or deploys. */
export async function execute(env,{request:createRequest=createRequester(),randomBytes=secureRandomBytes}={}){
 let attempted=false,fingerprint='';
 try{
  const request=validateRequest(env),token=env.CP_VERCEL_TOKEN,publishable=env.CP_SUPABASE_PUBLISHABLE_KEY;
  requireValue(typeof token==='string'&&/^[!-~]{16,4096}$/.test(token),'BFF_VERCEL_TOKEN_INVALID');
  requireValue(typeof publishable==='string'&&/^sb_publishable_[A-Za-z0-9_-]{16,256}$/.test(publishable),'BFF_PUBLISHABLE_KEY_INVALID');
  const settings=object(await createRequest({provider:'supabase',method:'GET',path:SETTINGS,token:publishable}));
  // A successful project-scoped Auth settings read validates this supplied public key; no session/user is created.
  requireValue(object(settings.external)&&typeof settings.disable_signup==='boolean','BFF_AUTH_SETTINGS_INVALID');
  const before=await snapshot(createRequest,token,request,publishable);fingerprint=before.fingerprint;
  if(request.operation==='apply')requireValue(request.ack===request.expectedPrefix+fingerprint,'BFF_ACK_MISMATCH');
  if(request.operation==='inspect'||before.state==='STAGED_METADATA_ONLY')return {version:1,eligible:before.state==='ABSENT',applied:false,alreadyStaged:before.state==='STAGED_METADATA_ONLY',state:before.state,fingerprint,secretValuesVerified:false,code:''};
  const rechecked=await snapshot(createRequest,token,request,publishable);
  requireValue(rechecked.state==='ABSENT'&&rechecked.fingerprint===fingerprint,'BFF_STATE_CHANGED');
  const encryption=randomBytes(32),rate=randomBytes(32);
  requireValue(Buffer.isBuffer(encryption)&&Buffer.isBuffer(rate)&&encryption.length===32&&rate.length===32&&!encryption.equals(rate),'BFF_RANDOM_INVALID');
  const comment=marker(request);
  const body=[{key:SECRET_KEYS[0],value:encryption.toString('base64'),type:'sensitive',visibility:'secret'},{key:SECRET_KEYS[1],value:rate.toString('base64'),type:'sensitive',visibility:'secret'},{key:PUBLIC_KEY,value:publishable,type:'plain',visibility:'config'}].map(value=>({...value,target:['production'],gitBranch:null,customEnvironmentIds:[],comment}));
  encryption.fill(0);rate.fill(0);
  attempted=true;
  const created=validWrite(await createRequest({provider:'vercel',method:'POST',path:ENV_PATH,token,body}));
  // The transport and response are kept out of diagnostics, including failed.error.value.
  const after=await snapshot(createRequest,token,request,publishable);
  requireValue(after.state==='STAGED_METADATA_ONLY'&&after.base===before.base&&[...created].every(([key,item])=>canonical(metadata(after.entries.get(key)))===canonical(item)),'BFF_READBACK_MISMATCH');
  return {version:1,eligible:true,applied:true,alreadyStaged:false,state:'STAGED',fingerprint:after.fingerprint,secretValuesVerified:false,code:''};
 }catch(error){return {version:1,eligible:false,applied:false,alreadyStaged:false,state:attempted?'UNKNOWN':'BLOCKED',fingerprint,secretValuesVerified:false,code:attempted?'BFF_WRITE_UNCERTAIN':error instanceof SafeFailure?error.code:'BFF_OPERATION_FAILED'};}
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const result=await execute(process.env);console.log(JSON.stringify(result));
 if(result.code)process.exitCode=1;
}
