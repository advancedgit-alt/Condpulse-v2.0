import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {execute,validateRequest,createRequester,TARGET} from '../infra/scripts/bff-configuration/stage.mjs';

const SOURCE='a'.repeat(40),GENERATION='2ca929e1-1f7e-4a3e-b9ab-1b9ab147ff82';
const PUBLISHABLE='sb_publishable_SYNTHETIC_PUBLIC_KEY_FOR_TESTS_123456';
const env=(extra={})=>({GITHUB_EVENT_NAME:'workflow_dispatch',GITHUB_REPOSITORY:'advancedgit-alt/Condpulse-v2.0',GITHUB_REF:'refs/heads/ccr-5e725112-3x8hyg',GITHUB_SHA:SOURCE,CP_BFF_CONFIG_OPERATION:'inspect',CP_BFF_CONFIG_GENERATION:GENERATION,CP_BFF_CONFIG_EXPECTED_COMMIT:SOURCE,CP_BFF_CONFIG_SOURCE_COMMIT:SOURCE,CP_SUPABASE_PUBLISHABLE_KEY:PUBLISHABLE,CP_VERCEL_TOKEN:'SYNTHETIC_VERCEL_TEST_TOKEN',...extra});
const issuer=`https://${TARGET.supabase}.supabase.co/auth/v1`;
const comment=`condpulse-bff:${TARGET.supabase}:${GENERATION}:${SOURCE}`;
const secretNames=['CP_BFF_ENCRYPTION_KEY','CP_BFF_RATE_LIMIT_KEY'];
function poison(value){Object.defineProperty(value,'value',{get(){throw Error('SYNTHETIC_SECRET_MUST_NOT_BE_READ');},enumerable:true});return value;}
function row(key,value,id=key){return {id,key,type:'plain',visibility:'config',target:['production'],gitBranch:null,customEnvironmentIds:[],updatedAt:1,comment:'',value};}
function secret(key,id=key){const result=row(key,undefined,id);delete result.value;return poison({...result,type:'sensitive',visibility:'secret',comment});}
function fixture(){
 const rows=[row('CP_BFF_ENABLED','false'),row('CP_RESERVATIONS_ENABLED','false'),row('CP_BFF_ORIGIN',TARGET.origin),row('CP_JWT_ISSUER',issuer),row('CP_JWT_JWK_SET_URI',`${issuer}/.well-known/jwks.json`),poison({key:'SOME_OTHER_SECRET',type:'encrypted',target:['production']})];
 const calls=[];let posts=0,lists=0;
 const state={rows,calls,randomCalls:0,settings:{external:{email:true},disable_signup:false},mutateBeforeList:null,afterPost:null,postResponse:null,pagination:null};
 state.randomBytes=length=>{state.randomCalls++;return Buffer.alloc(length,state.randomCalls);};
 state.request=async request=>{
  calls.push(request);
  if(request.provider==='supabase'){assert.equal(request.path,`${issuer}/settings`);assert.equal(request.token,PUBLISHABLE);return state.settings;}
  if(request.path.startsWith('/v9/projects/'))return {id:TARGET.project,accountId:TARGET.team,link:{type:'github',org:'advancedgit-alt',repo:'Condpulse-v2.0'}};
  if(request.method==='GET'){lists++;if(state.mutateBeforeList)state.mutateBeforeList(lists);return {envs:rows,pagination:state.pagination};}
  assert.equal(request.method,'POST');assert.ok(!request.path.includes('upsert'));posts++;
  for(const change of request.body){assert.equal(change.comment,comment);assert.deepEqual(change.target,['production']);assert.deepEqual(change.customEnvironmentIds,[]);assert.equal(change.gitBranch,null);assert.ok(!rows.some(row=>row.key===change.key));rows.push(secretNames.includes(change.key)?secret(change.key,`created_${change.key}`):{...row(change.key,change.value,`created_${change.key}`),comment});}
  if(state.afterPost)state.afterPost();
  if(state.postResponse)return state.postResponse();
  const created=rows.filter(row=>typeof row.id==='string'&&row.id.startsWith('created_'));
  return {created,failed:[]};
 };
 state.postCount=()=>posts;
 return state;
}
async function approvedApply(fixture,extra={}){
 const inspected=await execute(env(),fixture);assert.equal(inspected.state,'ABSENT');assert.equal(inspected.code,'');
 return execute(env({CP_BFF_CONFIG_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_BFF_CONFIG_ACK:`${TARGET.supabase}:bff:${GENERATION}:${SOURCE}:${inspected.fingerprint}`,...extra}),fixture);
}

test('request gate does not access credential fields and rejects stale source or unapproved writes',()=>{
 const requestEnv=env();Object.defineProperty(requestEnv,'CP_VERCEL_TOKEN',{get(){throw Error('must not read');}});assert.equal(validateRequest(requestEnv).operation,'inspect');
 for(const extra of [{CP_BFF_CONFIG_SOURCE_COMMIT:'b'.repeat(40)},{GITHUB_SHA:'b'.repeat(40)},{GITHUB_EVENT_NAME:'push'},{GITHUB_REF:'refs/tags/a'},{GITHUB_REPOSITORY:'other/repo'},{CP_BFF_CONFIG_GENERATION:'not-a-uuid'},{CP_BFF_CONFIG_GENERATION:'00000000-0000-0000-0000-000000000000'},{CP_BFF_CONFIG_OPERATION:'deploy'},{CP_BFF_CONFIG_ACK:'unexpected'},{CP_BFF_CONFIG_OPERATION:'apply',CP_BFF_CONFIG_ACK:'bad'}])assert.throws(()=>validateRequest(env(extra)));
 const ack=`${TARGET.supabase}:bff:${GENERATION}:${SOURCE}:${'f'.repeat(64)}`;
 assert.throws(()=>validateRequest(env({CP_BFF_CONFIG_OPERATION:'apply',CP_BFF_CONFIG_ACK:ack})),/BFF_MAINTENANCE_REQUIRED/);
});

test('inspect validates fixed Auth project and metadata without generating or writing keys',async()=>{
 const f=fixture(),result=await execute(env(),f);assert.equal(result.state,'ABSENT');assert.equal(result.eligible,true);assert.match(result.fingerprint,/^[a-f0-9]{64}$/);assert.equal(f.randomCalls,0);assert.equal(f.postCount(),0);assert.ok(f.calls.every(c=>c.method==='GET'));assert.equal(result.secretValuesVerified,false);
});

test('approved first apply creates only a distinct canonical32-byte Secret pair and public Config',async()=>{
 const f=fixture(),result=await approvedApply(f);assert.equal(result.state,'STAGED');assert.equal(result.applied,true);assert.equal(f.randomCalls,2);assert.equal(f.postCount(),1);
 const post=f.calls.find(c=>c.method==='POST');assert.equal(post.body.length,3);
 const [encryption,rate,pub]=post.body;for(const value of [encryption,rate]){assert.equal(Buffer.from(value.value,'base64').length,32);assert.equal(Buffer.from(value.value,'base64').toString('base64'),value.value);assert.equal(value.type,'sensitive');assert.equal(value.visibility,'secret');}
 assert.notEqual(encryption.value,rate.value);assert.equal(pub.key,'CP_SUPABASE_PUBLISHABLE_KEY');assert.equal(pub.value,PUBLISHABLE);assert.equal(pub.visibility,'config');
 assert.ok(!JSON.stringify(result).includes(encryption.value));assert.ok(!JSON.stringify(result).includes(rate.value));assert.ok(!JSON.stringify(result).includes(PUBLISHABLE));
});

test('owned complete metadata reinspects without reading or regenerating secrets',async()=>{
 const f=fixture();await approvedApply(f);const result=await execute(env(),f);assert.equal(result.state,'STAGED_METADATA_ONLY');assert.equal(result.alreadyStaged,true);assert.equal(result.eligible,false);assert.equal(result.secretValuesVerified,false);assert.equal(f.postCount(),1);assert.equal(f.randomCalls,2);
});

test('wrong ACK stops before the immediate metadata recheck, key generation and POST',async()=>{
 const f=fixture(),result=await execute(env({CP_BFF_CONFIG_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_BFF_CONFIG_ACK:`${TARGET.supabase}:bff:${GENERATION}:${SOURCE}:${'f'.repeat(64)}`}),f);assert.equal(result.code,'BFF_ACK_MISMATCH');assert.equal(f.postCount(),0);assert.equal(f.randomCalls,0);
});

test('metadata revision change after ACK blocks the write before generating keys',async()=>{
 const f=fixture();f.mutateBeforeList=count=>{if(count===3)f.rows[0].updatedAt=2;};const result=await approvedApply(f);assert.equal(result.code,'BFF_STATE_CHANGED');assert.equal(f.randomCalls,0);assert.equal(f.postCount(),0);
});

test('partial, foreign, mixed-target, duplicate and contradictory metadata block without writes',async()=>{
 const cases=[
  f=>f.rows.push(secret(secretNames[0])),
  f=>{f.rows.push(...secretNames.map(key=>{const value=secret(key);value.comment='foreign';return value;}),{...row('CP_SUPABASE_PUBLISHABLE_KEY',PUBLISHABLE),comment});},
  f=>f.rows[0].target.push('preview'),
  f=>f.rows.push(row('CP_BFF_ENABLED','false','another')),
  f=>f.rows[0].visibility='secret',
  f=>f.rows[0].gitBranch='branch',
  f=>f.rows[0].customEnvironmentIds=['some-custom-environment'],
  f=>f.rows[0].configurationId='shared',
  f=>f.rows[0].type='encrypted'
 ];
 for(const mutate of cases){const f=fixture();mutate(f);const result=await execute(env(),f);assert.equal(result.state,'BLOCKED');assert.ok(result.code);assert.equal(f.randomCalls,0);assert.equal(f.postCount(),0);}
});

test('incomplete pagination and changed flags are denied even when first rows look valid',async()=>{
 for(const mutate of [f=>f.pagination={next:123},f=>f.rows[0].value='true',f=>f.rows[1].value='true',f=>f.rows[2].value='https://foreign.invalid']){const f=fixture();mutate(f);const result=await execute(env(),f);assert.equal(result.state,'BLOCKED');assert.equal(f.postCount(),0);}
});

test('invalid credentials and Auth settings stop before Vercel inspection',async()=>{
 for(const changes of [{CP_SUPABASE_PUBLISHABLE_KEY:'sb_secret_SYNTHETIC_PRIVILEGED_KEY'},{CP_VERCEL_TOKEN:' token with spaces '}]){const f=fixture(),result=await execute(env(changes),f);assert.equal(result.state,'BLOCKED');assert.equal(f.calls.length,0);}
 const f=fixture();f.settings={error:'synthetic'};const result=await execute(env(),f);assert.equal(result.state,'BLOCKED');assert.ok(f.calls.every(c=>c.provider==='supabase'));
});

test('bulk partial response never exposes failed.error.value and becomes UNKNOWN without retry',async()=>{
 const f=fixture();f.postResponse=()=>({created:[],failed:[{error:{get value(){throw Error('secret echo must not be read');}}}]});const result=await approvedApply(f);assert.equal(result.state,'UNKNOWN');assert.equal(result.code,'BFF_WRITE_UNCERTAIN');assert.equal(f.postCount(),1);
 const reinspect=await execute(env(),f);assert.equal(reinspect.state,'STAGED_METADATA_ONLY');assert.equal(reinspect.secretValuesVerified,false);assert.equal(f.postCount(),1);
});

test('network failure after POST remains UNKNOWN even if provider committed, never auto-overwrites',async()=>{
 const f=fixture(),original=f.request;f.request=async request=>{const result=await original(request);if(request.method==='POST')throw Error('SYNTHETIC_SECRET_IN_EXCEPTION');return result;};const result=await approvedApply(f);assert.equal(result.state,'UNKNOWN');assert.ok(!JSON.stringify(result).includes('SYNTHETIC_SECRET'));assert.equal(f.postCount(),1);const reinspect=await execute(env(),f);assert.equal(reinspect.state,'STAGED_METADATA_ONLY');assert.equal(f.postCount(),1);
});

test('post-write flag drift or created-set mismatch is uncertain, with no second write',async()=>{
 for(const mutate of [f=>f.afterPost=()=>{f.rows[0].value='true';},f=>f.postResponse=()=>({created:{key:'CP_BFF_ENCRYPTION_KEY'},failed:[]})]){const f=fixture();mutate(f);const result=await approvedApply(f);assert.equal(result.state,'UNKNOWN');assert.equal(f.postCount(),1);}
});

test('a new Secret revision between create response and readback remains UNKNOWN despite matching id/comment',async()=>{
 const f=fixture();
 f.postResponse=()=>{
  const created=f.rows.filter(row=>typeof row.id==='string'&&row.id.startsWith('created_')).map(row=>Object.create(null,Object.getOwnPropertyDescriptors(row)));
  f.rows.find(row=>row.key==='CP_BFF_ENCRYPTION_KEY').updatedAt=2;
  return {created,failed:[]};
 };
 const result=await approvedApply(f);assert.equal(result.state,'UNKNOWN');assert.equal(result.code,'BFF_WRITE_UNCERTAIN');assert.equal(f.postCount(),1);
});

test('faulty random source is rejected before a write',async()=>{
 const f=fixture();f.randomBytes=length=>Buffer.alloc(length,7);const result=await approvedApply(f);assert.equal(result.code,'BFF_RANDOM_INVALID');assert.equal(f.postCount(),0);
});

const transportRequest={provider:'supabase',method:'GET',path:`${issuer}/settings`,token:PUBLISHABLE};
test('production transport pins destinations and sets manual redirects without credentials in URL',async()=>{
 const seen=[];const request=createRequester(async(url,options)=>{seen.push({url,options});return new Response('{"external":{},"disable_signup":false}',{headers:{'content-type':'application/json'}});});await request(transportRequest);assert.equal(seen[0].url,`${issuer}/settings`);assert.equal(seen[0].options.redirect,'manual');assert.equal(seen[0].options.headers.apikey,PUBLISHABLE);assert.equal(seen[0].options.headers.Authorization,undefined);
 for(const changed of [{provider:'unknown'},{path:'https://foreign.invalid'},{method:'POST'},{provider:'vercel',path:`/v1/projects/${TARGET.project}/env/SECRET_ID?teamId=${TARGET.team}`}])await assert.rejects(()=>request({...transportRequest,...changed}),/BFF_DESTINATION_DENIED/);assert.equal(seen.length,1);
});

test('production transport rejects redirects, bad MIME, oversized streams and invalid JSON without response bodies',async()=>{
 const responses=[new Response('SYNTHETIC_SECRET_RESPONSE',{status:302}),new Response('SYNTHETIC_SECRET_RESPONSE',{headers:{'content-type':'text/html'}}),new Response('{broken', {headers:{'content-type':'application/json'}}),new Response(' '.repeat(256*1024+1),{headers:{'content-type':'application/json'}})];
 for(const response of responses){await assert.rejects(()=>createRequester(async()=>response)(transportRequest),error=>{assert.ok(error.message.startsWith('BFF_'));assert.ok(!error.message.includes('SYNTHETIC_SECRET'));return true;});}
});

test('HTTP/header refusal cancels the response body and aborts transport without reading it',async()=>{
 for(const status of [302,403,200]){
  let cancelled=false,signal;
  const response=new Response(new ReadableStream({cancel(){cancelled=true;}}),{status,headers:{'content-type':'text/html'}});
  const request=createRequester(async(_url,options)=>{signal=options.signal;return response;});
  await assert.rejects(()=>request(transportRequest));assert.equal(signal.aborted,true);assert.equal(cancelled,true);
 }
});

test('production transport bounds total time even if fetch ignores AbortSignal',async t=>{
 t.mock.timers.enable({apis:['setTimeout']});const pending=createRequester(()=>new Promise(()=>{}))(transportRequest);t.mock.timers.tick(10001);await assert.rejects(()=>pending,/BFF_PROVIDER_UNAVAILABLE/);t.mock.timers.reset();
});

test('production transport deadline also bounds a slow response body',async t=>{
 t.mock.timers.enable({apis:['setTimeout']});
 const request=createRequester(async()=>new Response(new ReadableStream({start(){}}),{headers:{'content-type':'application/json'}}));
 const pending=request(transportRequest);await Promise.resolve();await Promise.resolve();t.mock.timers.tick(10001);await assert.rejects(()=>pending,/BFF_PROVIDER_UNAVAILABLE/);t.mock.timers.reset();
});

test('CLI emits only controlled JSON and exit1 for invalid protected credentials',()=>{
 const cli=fileURLToPath(new URL('../infra/scripts/bff-configuration/stage.mjs',import.meta.url));
 const child=spawnSync(process.execPath,[cli],{env:env({CP_SUPABASE_PUBLISHABLE_KEY:'sb_secret_SYNTHETIC_MUST_NOT_APPEAR'}),encoding:'utf8'});
 assert.equal(child.status,1);assert.equal(child.stderr,'');assert.equal(JSON.parse(child.stdout).code,'BFF_PUBLISHABLE_KEY_INVALID');assert.ok(!child.stdout.includes('SYNTHETIC'));
});
