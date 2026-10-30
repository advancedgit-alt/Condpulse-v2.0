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
 assert.deepEqual(Object.keys(spec.paths),['/v1/me','/auth/csrf','/auth/login','/auth/refresh','/auth/logout','/auth/mfa','/auth/mfa/enroll','/auth/mfa/verify','/auth/recovery','/auth/recovery/verify','/auth/recovery/complete','/auth/invitation/accept','/v1/tenants/{tenantId}/units','/v1/tenants/{tenantId}/amenities','/v1/tenants/{tenantId}/amenities/{amenityId}/availability','/v1/tenants/{tenantId}/reservations','/v1/tenants/{tenantId}/reservations/{reservationId}/cancel','/v1/tenants/{tenantId}/management/blocks','/v1/tenants/{tenantId}/management/units','/v1/tenants/{tenantId}/management/units/{unitId}','/v1/tenants/{tenantId}/management/amenities','/v1/tenants/{tenantId}/management/amenities/{amenityId}','/v1/tenants/{tenantId}/management/audit-events','/v1/tenants/{tenantId}/management/memberships','/v1/tenants/{tenantId}/management/memberships/{membershipId}/decision','/v1/tenants/{tenantId}/management/memberships/{membershipId}/revoke','/v1/tenants/{tenantId}/management/invitations','/v1/tenants/{tenantId}/management/invitations/{invitationId}/revoke','/v1/invitations/accept','/v1/tenants/{tenantId}/management/reservations','/v1/tenants/{tenantId}/management/reservations/{reservationId}/decision','/v1/tenants/{tenantId}/management/reservations/{reservationId}/cancel','/v1/tenants/{tenantId}/management/amenities/{amenityId}/maintenance-blocks','/v1/platform/session','/v1/platform/tenants','/v1/platform/tenants/{tenantId}','/v1/platform/audit-events','/v1/platform/billing/plans','/v1/platform/billing/plans/{planId}','/v1/platform/billing/subscriptions','/v1/platform/billing/subscriptions/{subscriptionId}/end','/v1/platform/billing/subscriptions/{subscriptionId}/charges','/v1/platform/billing/charges','/v1/platform/billing/charges/{chargeId}/cancel','/v1/platform/billing/charges/{chargeId}/payments','/v1/platform/billing/payments/{paymentId}/void','/v1/tenants/{tenantId}/parcels','/v1/tenants/{tenantId}/parcels/{parcelId}','/v1/tenants/{tenantId}/parcels/{parcelId}/handoff','/v1/tenants/{tenantId}/parcels/{parcelId}/recipients','/v1/tenants/{tenantId}/parcels/{parcelId}/void','/v1/tenants/{tenantId}/units/{unitId}/parcels','/v1/tenants/{tenantId}/concierge/units','/v1/tenants/{tenantId}/units/{unitId}/permits','/v1/tenants/{tenantId}/permits/{permitId}/revoke','/v1/tenants/{tenantId}/units/{unitId}/arrivals','/v1/tenants/{tenantId}/arrivals/{arrivalId}/decision','/v1/tenants/{tenantId}/concierge/permits','/v1/tenants/{tenantId}/arrivals','/v1/tenants/{tenantId}/arrivals/{arrivalId}/assisted-decision','/v1/tenants/{tenantId}/arrivals/{arrivalId}/cancel','/v1/tenants/{tenantId}/access-events','/v1/tenants/{tenantId}/access-events/entries','/v1/tenants/{tenantId}/access-events/{eventId}/exit','/v1/tenants/{tenantId}/access-events/{eventId}/correction','/v1/tenants/{tenantId}/units/{unitId}/access-events','/v1/tenants/{tenantId}/units/{unitId}/notifications','/v1/tenants/{tenantId}/notifications/{notificationId}/open','/v1/tenants/{tenantId}/management/announcements','/v1/tenants/{tenantId}/management/announcements/{announcementId}','/v1/tenants/{tenantId}/management/announcements/{announcementId}/publish','/v1/tenants/{tenantId}/management/announcements/{announcementId}/archive','/v1/tenants/{tenantId}/units/{unitId}/announcements','/v1/tenants/{tenantId}/announcements/{announcementId}/open','/v1/tenants/{tenantId}/units/{unitId}/requests','/v1/tenants/{tenantId}/requests/{requestId}','/v1/tenants/{tenantId}/requests/{requestId}/messages','/v1/tenants/{tenantId}/management/requests','/v1/tenants/{tenantId}/management/requests/{requestId}','/v1/tenants/{tenantId}/management/requests/{requestId}/messages','/v1/tenants/{tenantId}/management/requests/{requestId}/state','/v1/tenants/{tenantId}/management/imports','/v1/tenants/{tenantId}/management/imports/{batchId}','/v1/tenants/{tenantId}/management/imports/{batchId}/approve','/v1/tenants/{tenantId}/management/imports/{batchId}/discard','/v1/tenants/{tenantId}/uploads/intents','/v1/tenants/{tenantId}/attachments/{attachmentId}/complete','/v1/tenants/{tenantId}/attachments/{attachmentId}/download','/v1/tenants/{tenantId}/requests/{requestId}/attachments','/v1/tenants/{tenantId}/units/{unitId}/events','/v1/tenants/{tenantId}/permits/{permitId}/link','/v1/tenants/{tenantId}/permits/{permitId}/link/revoke','/auth/permit-links/view','/v1/tenants/{tenantId}/reservations/{reservationId}/guests','/v1/tenants/{tenantId}/units/{unitId}/visit-rules','/v1/tenants/{tenantId}/visit-rules/{ruleId}/revoke']);
 const maintenance=spec.paths['/v1/tenants/{tenantId}/management/amenities/{amenityId}/maintenance-blocks'].post;
 assert.ok(maintenance.parameters.some(p=>p.name==='Idempotency-Key' && p.required));
 assert.deepEqual(spec.components.schemas.ReservationDecision.properties.decision.enum,['APPROVE','REJECT']);
 assert.ok(!spec.components.schemas.Amenity.properties.bookingState.enum.includes('APPROVAL_UNAVAILABLE'));
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/management/') && !p.includes('/announcements') && !p.includes('/requests'))) for(const operation of Object.values(item)) {
  assert.equal(operation['x-feature-flag'],'CP_MANAGEMENT_ENABLED',path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
 }
 for(const op of ['login','refresh','logout','mfa/enroll','mfa/verify']) {const route=spec.paths['/auth/'+op].post; assert.equal(route['x-feature-flag'],'CP_BFF_ENABLED'); assert.ok(route.parameters.some(p=>p.name==='X-CSRF-Token' && p.required));}
 for(const op of ['recovery','recovery/verify','recovery/complete']) {
  const route=spec.paths['/auth/'+op].post;
  assert.equal(route['x-feature-flag'],'CP_BFF_RECOVERY_ENABLED');
  assert.ok(route.parameters.some(p=>p.name==='X-CSRF-Token' && p.required));
  assert.ok(route.parameters.some(p=>p.name==='Origin' && p.required));
  assert.ok(route.responses['429'].headers['Retry-After'].required);
 }
 assert.deepEqual(Object.keys(spec.paths['/auth/recovery'].post.responses).filter(code=>code.startsWith('2')),['202']);
 const firstAccess=spec.paths['/auth/invitation/accept'].post;
 assert.equal(firstAccess['x-feature-flag'],'CP_MANAGEMENT_ENABLED');
 assert.match(firstAccess.description,/CP_BFF_ENABLED/);
 assert.ok(firstAccess.parameters.some(p=>p.name==='X-CSRF-Token' && p.required));
 assert.ok(firstAccess.parameters.some(p=>p.name==='Origin' && p.required));
 assert.ok(firstAccess.responses['429'].headers['Retry-After'].required);
 assert.ok(!JSON.stringify(spec.components.schemas.InvitationFirstAccessResult).includes('token'));
 assert.ok(!JSON.stringify(spec.components.schemas.RecoveryVerified).includes('token'));
 for(const path of ['/v1/tenants/{tenantId}/reservations','/v1/tenants/{tenantId}/reservations/{reservationId}/cancel']) {
  const route=spec.paths[path].post;
  assert.equal(route['x-feature-flag'],'CP_RESERVATIONS_ENABLED');
  assert.ok(route.parameters.some(p=>p.name==='Idempotency-Key' && p.required));
  assert.ok(route.parameters.some(p=>p.name==='X-CSRF-Token' && p.required));
 }
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.startsWith('/v1/platform/'))) for(const operation of Object.values(item)) {
  assert.equal(operation['x-feature-flag'],path.startsWith('/v1/platform/billing/')?'CP_PLATFORM_BILLING_ENABLED':'CP_PLATFORM_ENABLED',path);
  assert.match(operation.responses['403'].description,/PLATFORM_ACCESS_DENIED/,path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
 }
 assert.deepEqual(Object.keys(spec.paths['/v1/platform/tenants']),['get'],'condominio nao e criado pela API');
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/parcels') || p.endsWith('/concierge/units'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],path.endsWith('/concierge/units')?'CP_PARCELS_ENABLED|CP_VISITS_ENABLED':'CP_PARCELS_ENABLED',path);
  if(method==='post') assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
 }
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/permits') || p.includes('/arrivals'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_VISITS_ENABLED',path);
  if(method==='post' && (path.endsWith('/units/{unitId}/permits') || path.endsWith('/arrivals'))) assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
 }
 assert.deepEqual(spec.components.schemas.ArrivalAssistedDecision.properties.source.enum,['INTERCOM','IN_PERSON'],'portaria nao decide em nome do aplicativo');
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/access-events'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_VISITS_ENABLED',path);
  if(method==='post') assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
 }
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/notifications'))) for(const operation of Object.values(item))
  assert.equal(operation['x-feature-flag'],'CP_NOTIFICATIONS_ENABLED',path);
 assert.deepEqual(spec.components.schemas.InboxNotification.properties.state.enum,['AVAILABLE','READ_IN_APP'],'entrega na caixa nao e leitura');
 assert.deepEqual(Object.keys(spec.components.schemas.InboxNotification.properties),
  ['notificationId','unitId','kind','resourceId','resourceState','state','createdAt','readAt'],'aviso sem dado do registro');
 assert.ok(!Object.keys(spec.paths).some(path=>/push|installation/i.test(path)),'push ainda nao existe');
 const guests=spec.paths['/v1/tenants/{tenantId}/reservations/{reservationId}/guests'];
 for(const operation of Object.values(guests)) assert.equal(operation['x-feature-flag'],'CP_VISITS_ENABLED');
 assert.ok(guests.post.parameters.some(p=>p.name==='Idempotency-Key' && p.required),'convidado com chave de operacao');
 assert.match(guests.post.description,/revoga todos os convidados na mesma transação/);
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/visit-rules'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_VISITS_ENABLED',path);
  if(method==='post' && path.endsWith('/visit-rules')) assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
 }
 assert.match(spec.paths['/v1/tenants/{tenantId}/units/{unitId}/visit-rules'].post.description,/não autoriza sozinha/);
 const view=spec.paths['/auth/permit-links/view'].post;
 assert.equal(view['x-feature-flag'],'CP_VISITS_ENABLED'); assert.match(view.description,/CP_BFF_ENABLED/);
 assert.ok(view.parameters.some(p=>p.name==='X-CSRF-Token' && p.required) && view.parameters.some(p=>p.name==='Origin' && p.required));
 assert.ok(view.responses['429'].headers['Retry-After'].required);
 assert.deepEqual(Object.keys(spec.components.schemas.PublicVisitPermit.properties),
  ['tenantName','visitorFirstName','purpose','validFrom','validUntil','timeZone','state'],'link publico sem unidade nem identificadores');
 assert.deepEqual(spec.components.schemas.PublicVisitPermit.properties.state.enum,['SCHEDULED','ACTIVE'],'revogado e vencido sao resposta neutra');
 assert.ok(!spec.paths['/v1/tenants/{tenantId}/permits/{permitId}/link'].post.parameters.some(p=>p.name==='Idempotency-Key'),'token nao e guardado para repeticao');
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.endsWith('/events'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(method,'get',path); assert.equal(operation['x-feature-flag'],'CP_REALTIME_ENABLED',path);
  assert.ok(operation.responses['200'].content['text/event-stream'],path);
  assert.match(operation.description,/Nenhum dado pessoal/,path);
 }
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/announcements'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_ANNOUNCEMENTS_ENABLED',path);
  if(path.includes('/management/')) assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
  if(method==='post' && path.endsWith('/management/announcements')) assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
 }
 assert.ok(!('body' in spec.components.schemas.UnitAnnouncement.properties),'lista da unidade sem o texto');
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>/\/requests(\/|$)/.test(p) && !p.endsWith('/attachments'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_REQUESTS_ENABLED',path);
  if(path.includes('/management/')) assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
  if(method==='post' && !path.endsWith('/state')) assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
  if(path.includes('/requests/{requestId}')) assert.match(operation.responses['404'].description,/SERVICE_REQUEST_NOT_FOUND/,path);
 }
 for(const name of ['UnitRequestSummary','ManagedRequestSummary']) assert.ok(!('description' in spec.components.schemas[name].properties) && !('messages' in spec.components.schemas[name].properties),name);
 assert.ok(!('assigneeName' in spec.components.schemas.UnitRequest.properties),'unidade nao recebe o nome do responsavel');
 assert.deepEqual(spec.components.schemas.RequestMessage.properties.visibility.enum,['RESIDENT','INTERNAL']);
 assert.deepEqual(spec.components.schemas.RequestOpen.required,['category','title','description']);
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/management/imports'))) for(const [method,operation] of Object.entries(item)) {
  if(path.includes('{batchId}')) assert.match(operation.responses['404'].description,/IMPORT_BATCH_NOT_FOUND/,path);
  if(method==='post') assert.ok(operation.parameters.some(p=>p.name==='X-CSRF-Token'),path);
 }
 assert.deepEqual(spec.components.schemas.UnitImportApprove.required,['expectedVersion']);
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>/\/(uploads|attachments)(\/|$)/.test(p))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_FILES_ENABLED',path);
  if(method==='post') assert.ok(operation.parameters.some(p=>p.name==='X-CSRF-Token'),path);
  assert.equal(operation.responses['200']?.headers?.['Cache-Control']?.schema?.const ?? operation.responses['201'].headers['Cache-Control'].schema.const,'no-store',path);
 }
 assert.deepEqual(spec.components.schemas.AttachmentIntent.properties.mimeType.enum,['image/jpeg','image/png','application/pdf']);
 assert.ok(!JSON.stringify(spec.components.schemas.Attachment).match(/"(bucket|objectKey|sha256|ownerId|url)"/),'anexo nao expoe bucket, chave, hash, dono nem URL permanente');
 assert.equal(spec.components.schemas.AttachmentUpload.properties.method.const,'PUT');
 assert.ok(!JSON.stringify(spec.components.schemas.UnitImportRow).match(/"(accountId|membership|email|displayName)"/),'importacao nao traz pessoas nem vinculos');
 assert.ok(!JSON.stringify(spec.components.schemas.ManagedAnnouncement).match(/"(readers|accountIds|readBy)"/),'gestao ve contagem, nao quem abriu');
 assert.equal(spec.components.schemas.AccessRecord.properties.eventType.const,'MANUAL_IN','registro manual nunca e passagem de equipamento');
 assert.equal(spec.components.schemas.AccessRecord.properties.source.const,'CONCIERGE');
 assert.deepEqual(spec.components.schemas.ParcelHandoff.properties.evidenceKind.enum,['ASSISTED'],'codigo e delegacao ainda nao existem');
 assert.deepEqual(spec.components.schemas.ParcelDeliver.required,['recipientMembershipId','identityChecked','expectedVersion'],'retirada identificada por vinculo');
 assert.deepEqual(Object.keys(spec.components.schemas.PlatformTenantUpdate.properties),['name','active','expectedVersion','reason']);
 assert.ok(!JSON.stringify(spec.components.schemas.PlatformTenant).includes('manifest'));
 assert.deepEqual(spec.components.schemas.BillingCharge.properties.status.enum,['OPEN','PAID','CANCELLED']);
 assert.deepEqual(spec.components.schemas.BillingPaymentRecord.required,['amountCents','paidOn','method','reference','expectedVersion']);
 assert.ok(!Object.keys(spec.paths).some(path=>path.startsWith('/v1/platform/billing/') && /gateway|webhook|invoice/i.test(path)),'sem gateway ou emissao fiscal');
 assert.ok(spec.paths['/v1/me'].get.security[0].bearerAuth);
 const availability=spec.paths['/v1/tenants/{tenantId}/amenities/{amenityId}/availability'].get;
 assert.equal(availability['x-feature-flag'],'CP_RESERVATIONS_ENABLED');
 assert.deepEqual(availability.parameters.filter(p=>p.required).map(p=>p.name),['tenantId','amenityId','unitId','from','until']);
 assert.equal(availability.responses['200'].headers['Cache-Control'].schema.const,'no-store');
 assert.deepEqual(Object.keys(spec.components.schemas.BlockedInterval.properties),['occupiedFrom','occupiedUntil']);
 assert.equal(spec.components.schemas.BlockedInterval.additionalProperties,false);
 assert.equal(spec.components.schemas.AmenityAvailability.properties.blockedIntervals.maxItems,1000);
 assert.equal(spec.components.schemas.AmenityAvailability.additionalProperties,false);
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

