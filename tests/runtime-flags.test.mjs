import test from 'node:test';
import assert from 'node:assert/strict';
import {execute, state, validateRequest, TARGET} from '../infra/scripts/runtime-flags/apply.mjs';

const SHA='a'.repeat(40);
const base={GITHUB_SHA:SHA,GITHUB_EVENT_NAME:'workflow_dispatch',GITHUB_REPOSITORY:'advancedgit-alt/Condpulse-v2.0',GITHUB_REF:'refs/heads/ccr-5e725112-3x8hyg',
 CP_FLAGS_EXPECTED_COMMIT:SHA,CP_VERCEL_TOKEN:'synthetic-token-not-real-0000'};
function fake(envs){
 const calls=[];
 return {calls,request:async({method,path,body})=>{calls.push({method,path,body});
  if(path.startsWith('/v9/projects')) return {id:TARGET.project,accountId:TARGET.team,link:{type:'github',org:'advancedgit-alt',repo:'Condpulse-v2.0'}};
  if(method==='GET') return {envs:envs.map(e=>({...e}))};
  const existing=envs.find(e=>e.key===body.key&&e.target.includes('production'));
  if(existing) Object.assign(existing,{value:body.type==='plain'?body.value:undefined,type:body.type});
  else envs.push({key:body.key,type:body.type,target:['production'],...(body.type==='plain'?{value:body.value}:{})});
  return {created:{key:body.key}};
 }};
}

test('pedido só pela branch principal, ACK obrigatório no apply e janela confirmada', () => {
 assert.equal(validateRequest(base).operation,'inspect');
 assert.throws(()=>validateRequest({...base,GITHUB_REF:'refs/heads/outra'}),/FLAGS_REQUEST_INVALID/);
 assert.throws(()=>validateRequest({...base,CP_FLAGS_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true'}),/FLAGS_ACK_INVALID/);
 assert.throws(()=>validateRequest({...base,CP_FLAGS_OPERATION:'apply',CP_FLAGS_ACK:`${TARGET.project}:flags:${SHA}:${'b'.repeat(64)}`}),/FLAGS_MAINTENANCE_REQUIRED/);
});

test('inspect não escreve; apply liga a flag, gera a chave sem devolver o valor e confere a leitura', async () => {
 const envs=[{key:'CP_ACCESS_CREDENTIALS_ENABLED',type:'plain',value:'false',target:['production']}];
 const first=fake(envs);
 const inspected=await execute(base,{request:first.request});
 assert.deepEqual(inspected.state,{flags:{CP_ACCESS_CREDENTIALS_ENABLED:'false',CP_FACIAL_ACCESS_ENABLED:null},secrets:{CP_PARCEL_CODE_KEY:false}});
 assert.ok(first.calls.every(c=>c.method==='GET'));
 const second=fake(envs);
 const applied=await execute({...base,CP_FLAGS_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLAGS_ACK:inspected.ack},
  {request:second.request,randomBytes:n=>Buffer.alloc(n,7)});
 assert.deepEqual(applied.changed,['CP_ACCESS_CREDENTIALS_ENABLED','CP_FACIAL_ACCESS_ENABLED','CP_PARCEL_CODE_KEY']);
 assert.equal(JSON.stringify(applied).includes(Buffer.alloc(32,7).toString('base64')),false,'Valor gerado nunca volta no resultado');
 const secretPost=second.calls.find(c=>c.body?.key==='CP_PARCEL_CODE_KEY');
 assert.equal(secretPost.body.type,'sensitive');
 // Repetir com o novo estado não muda nada.
 const third=fake(envs);const again=await execute(base,{request:third.request});
 const fourth=fake(envs);
 const repeat=await execute({...base,CP_FLAGS_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLAGS_ACK:again.ack},{request:fourth.request});
 assert.deepEqual(repeat.changed,[]);
});

test('ACK de outro estado é recusado sem escrita; segredo existente nunca é regerado', async () => {
 const envs=[{key:'CP_PARCEL_CODE_KEY',type:'sensitive',target:['production']}];
 const f=fake(envs);
 await assert.rejects(execute({...base,CP_FLAGS_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLAGS_ACK:`${TARGET.project}:flags:${SHA}:${'c'.repeat(64)}`},{request:f.request}),/FLAGS_STATE_CHANGED/);
 assert.ok(f.calls.every(c=>c.method==='GET'));
 assert.deepEqual(state(envs).secrets,{CP_PARCEL_CODE_KEY:true});
 assert.throws(()=>state([{key:'CP_ACCESS_CREDENTIALS_ENABLED',type:'sensitive',target:['production']}]),/FLAGS_PLAIN_EXPECTED/);
});

test('perfil demo altera só a flag demo e nunca gera ou modifica segredo nem facial', async () => {
 const envs=[
  {key:'CP_FACIAL_ACCESS_ENABLED',type:'plain',value:'false',target:['production']},
  {key:'CP_PARCEL_CODE_KEY',type:'sensitive',target:['production']},
  {key:'CP_DEMO_ENABLED',type:'plain',value:'false',target:['preview']}
 ];
 const demo={...base,CP_FLAGS_PROFILE:'demo'};
 const first=fake(envs);
 const inspected=await execute(demo,{request:first.request});
 assert.deepEqual(inspected.state,{flags:{CP_DEMO_ENABLED:null},secrets:{}});
 assert.ok(first.calls.every(c=>c.method==='GET'));
 const second=fake(envs);
 const applied=await execute({...demo,CP_FLAGS_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLAGS_ACK:inspected.ack},
  {request:second.request,randomBytes:()=>assert.fail('Demo não gera segredo')});
 assert.deepEqual(applied.changed,['CP_DEMO_ENABLED']);
 assert.deepEqual(second.calls.filter(c=>c.method==='POST').map(c=>c.body),
  [{key:'CP_DEMO_ENABLED',value:'true',type:'plain',target:['production']}]);
 assert.equal(envs.find(e=>e.key==='CP_FACIAL_ACCESS_ENABLED').value,'false');
 assert.equal(envs.find(e=>e.key==='CP_PARCEL_CODE_KEY').type,'sensitive');
 assert.equal(envs.find(e=>e.key==='CP_DEMO_ENABLED'&&e.target.includes('preview')).value,'false');
});

test('ACK de access não pode autorizar demo e perfil desconhecido não faz chamada', async () => {
 const access=await execute(base,{request:fake([]).request});
 const calls=fake([]);
 await assert.rejects(execute({...base,CP_FLAGS_PROFILE:'demo',CP_FLAGS_OPERATION:'apply',
  MAINTENANCE_CONFIRMED:'true',CP_FLAGS_ACK:access.ack},{request:calls.request}),/FLAGS_ACK_INVALID/);
 await assert.rejects(execute({...base,CP_FLAGS_PROFILE:'outro'},{request:calls.request}),/FLAGS_PROFILE_INVALID/);
 assert.deepEqual(calls.calls,[]);
});

test('demo já ligado não escreve e ACK obsoleto ou tipo sensível recusam antes de gravar', async () => {
 const envs=[{key:'CP_DEMO_ENABLED',type:'plain',value:'true',target:['production']}];
 const demo={...base,CP_FLAGS_PROFILE:'demo'};
 const first=fake(envs);const inspected=await execute(demo,{request:first.request});
 const second=fake(envs);
 assert.deepEqual((await execute({...demo,CP_FLAGS_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLAGS_ACK:inspected.ack},
  {request:second.request})).changed,[]);
 assert.ok(second.calls.every(c=>c.method==='GET'));
 envs[0].value='false';const stale=fake(envs);
 await assert.rejects(execute({...demo,CP_FLAGS_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLAGS_ACK:inspected.ack},
  {request:stale.request}),/FLAGS_STATE_CHANGED/);
 assert.ok(stale.calls.every(c=>c.method==='GET'));
 assert.throws(()=>state([{key:'CP_DEMO_ENABLED',type:'sensitive',target:['production']}],'demo'),/FLAGS_PLAIN_EXPECTED/);
});
