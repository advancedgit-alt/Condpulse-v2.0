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
  const existing=envs.find(e=>e.key===body.key);
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
 assert.deepEqual(inspected.state,{flags:{CP_ACCESS_CREDENTIALS_ENABLED:'false'},secrets:{CP_PARCEL_CODE_KEY:false}});
 assert.ok(first.calls.every(c=>c.method==='GET'));
 const second=fake(envs);
 const applied=await execute({...base,CP_FLAGS_OPERATION:'apply',MAINTENANCE_CONFIRMED:'true',CP_FLAGS_ACK:inspected.ack},
  {request:second.request,randomBytes:n=>Buffer.alloc(n,7)});
 assert.deepEqual(applied.changed,['CP_ACCESS_CREDENTIALS_ENABLED','CP_PARCEL_CODE_KEY']);
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
