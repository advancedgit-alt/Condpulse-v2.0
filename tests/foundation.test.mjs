import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const read=p=>readFileSync(join(root,p),'utf8');
const json=p=>JSON.parse(read(p));
test('rastreabilidade preserva 68 tickets e 60 cenarios sem marcar aceite ficticio',()=>{
 const tickets=json('docs/backlog.json').tickets;
 const cases=json('tests/acceptance-catalog.json').cases;
 assert.equal(tickets.length,68); assert.equal(new Set(tickets.map(x=>x.id)).size,68);
 assert.equal(cases.length,60); assert.equal(new Set(cases.map(x=>x.id)).size,60);
 assert.ok(cases.every(x=>x.status==='NAO_EXECUTADO' && x.evidence.length===0));
});
test('contrato publica somente a superficie implementada da fundacao',()=>{
 const spec=json('contracts/openapi.json');
 assert.equal(spec.openapi,'3.1.0');
 assert.deepEqual(Object.keys(spec.paths),['/v1/me','/auth/csrf','/auth/login','/auth/refresh','/auth/logout','/v1/tenants/{tenantId}/units','/v1/tenants/{tenantId}/amenities','/v1/tenants/{tenantId}/reservations','/v1/tenants/{tenantId}/reservations/{reservationId}/cancel']);
 for(const op of ['login','refresh','logout']) {const route=spec.paths['/auth/'+op].post; assert.equal(route['x-feature-flag'],'CP_BFF_ENABLED'); assert.ok(route.parameters.some(p=>p.name==='X-CSRF-Token' && p.required));}
 for(const path of ['/v1/tenants/{tenantId}/reservations','/v1/tenants/{tenantId}/reservations/{reservationId}/cancel']) {
  const route=spec.paths[path].post;
  assert.equal(route['x-feature-flag'],'CP_RESERVATIONS_ENABLED');
  assert.ok(route.parameters.some(p=>p.name==='Idempotency-Key' && p.required));
  assert.ok(route.parameters.some(p=>p.name==='X-CSRF-Token' && p.required));
 }
 assert.ok(spec.paths['/v1/me'].get.security[0].bearerAuth);
 const refs=JSON.stringify(spec).matchAll(/"\$ref":"#\/components\/schemas\/([^"]+)"/g);
 for(const [,name] of refs) assert.ok(spec.components.schemas[name],name);
});
test('dominio de reserva nao importa framework ou adaptador',()=>{
 const path='apps/backend/src/main/java/br/com/condpulse/reservations/domain';
 for(const filename of readdirSync(join(root,path))) {
  const code=read(path+'/'+filename);
  assert.doesNotMatch(code,/^import (org\.springframework|jakarta\.|.*\.infrastructure\.|.*\.api\.)/m);
 }
});
test('tokens mantem a identidade propria CondPulse e alvo de toque',()=>{
 const tokens=json('packages/design-tokens/tokens.json');
 assert.equal(tokens.brand,'CondPulse'); assert.ok(tokens.minimumTouchTargetPx>=48);
 assert.equal(tokens.colors.navy,'#102A43'); assert.equal(tokens.colors.teal,'#007F86');
});

