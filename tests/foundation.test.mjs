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
 assert.deepEqual(Object.keys(spec.paths),['/v1/me','/auth/csrf','/auth/login','/auth/refresh','/auth/logout','/auth/mfa','/auth/mfa/enroll','/auth/mfa/verify','/auth/recovery','/auth/recovery/verify','/auth/recovery/complete','/auth/invitation/accept','/v1/tenants/{tenantId}/units','/v1/tenants/{tenantId}/amenities','/v1/tenants/{tenantId}/amenities/{amenityId}/availability','/v1/tenants/{tenantId}/reservations','/v1/tenants/{tenantId}/reservations/{reservationId}/cancel','/v1/tenants/{tenantId}/management/blocks','/v1/tenants/{tenantId}/management/units','/v1/tenants/{tenantId}/management/units/{unitId}','/v1/tenants/{tenantId}/management/amenities','/v1/tenants/{tenantId}/management/amenities/{amenityId}','/v1/tenants/{tenantId}/management/audit-events','/v1/tenants/{tenantId}/management/memberships','/v1/tenants/{tenantId}/management/memberships/{membershipId}/decision','/v1/tenants/{tenantId}/management/memberships/{membershipId}/revoke','/v1/tenants/{tenantId}/management/invitations','/v1/tenants/{tenantId}/management/invitations/{invitationId}/revoke','/v1/invitations/accept','/v1/tenants/{tenantId}/management/reservations','/v1/tenants/{tenantId}/management/reservations/{reservationId}/decision','/v1/tenants/{tenantId}/management/reservations/{reservationId}/cancel','/v1/tenants/{tenantId}/management/amenities/{amenityId}/maintenance-blocks','/v1/platform/session','/v1/platform/tenants','/v1/platform/tenants/{tenantId}','/v1/platform/audit-events','/v1/platform/billing/plans','/v1/platform/billing/plans/{planId}','/v1/platform/billing/subscriptions','/v1/platform/billing/subscriptions/{subscriptionId}/end','/v1/platform/billing/subscriptions/{subscriptionId}/charges','/v1/platform/billing/charges','/v1/platform/billing/charges/{chargeId}/cancel','/v1/platform/billing/charges/{chargeId}/payments','/v1/platform/billing/payments/{paymentId}/void','/v1/tenants/{tenantId}/parcels','/v1/tenants/{tenantId}/parcels/{parcelId}','/v1/tenants/{tenantId}/parcels/{parcelId}/handoff','/v1/tenants/{tenantId}/parcels/{parcelId}/recipients','/v1/tenants/{tenantId}/parcels/{parcelId}/void','/v1/tenants/{tenantId}/units/{unitId}/parcels','/v1/tenants/{tenantId}/concierge/units','/v1/tenants/{tenantId}/units/{unitId}/permits','/v1/tenants/{tenantId}/permits/{permitId}/revoke','/v1/tenants/{tenantId}/units/{unitId}/arrivals','/v1/tenants/{tenantId}/arrivals/{arrivalId}/decision','/v1/tenants/{tenantId}/concierge/permits','/v1/tenants/{tenantId}/arrivals','/v1/tenants/{tenantId}/arrivals/{arrivalId}/assisted-decision','/v1/tenants/{tenantId}/arrivals/{arrivalId}/cancel','/v1/tenants/{tenantId}/access-events','/v1/tenants/{tenantId}/access-events/entries','/v1/tenants/{tenantId}/access-events/{eventId}/exit','/v1/tenants/{tenantId}/access-events/{eventId}/correction','/v1/tenants/{tenantId}/units/{unitId}/access-events','/v1/tenants/{tenantId}/units/{unitId}/notifications','/v1/tenants/{tenantId}/notifications/{notificationId}/open','/v1/tenants/{tenantId}/management/announcements','/v1/tenants/{tenantId}/management/announcements/{announcementId}','/v1/tenants/{tenantId}/management/announcements/{announcementId}/publish','/v1/tenants/{tenantId}/management/announcements/{announcementId}/archive','/v1/tenants/{tenantId}/units/{unitId}/announcements','/v1/tenants/{tenantId}/announcements/{announcementId}/open','/v1/tenants/{tenantId}/units/{unitId}/requests','/v1/tenants/{tenantId}/requests/{requestId}','/v1/tenants/{tenantId}/requests/{requestId}/messages','/v1/tenants/{tenantId}/management/requests','/v1/tenants/{tenantId}/management/requests/{requestId}','/v1/tenants/{tenantId}/management/requests/{requestId}/messages','/v1/tenants/{tenantId}/management/requests/{requestId}/state','/v1/tenants/{tenantId}/management/imports','/v1/tenants/{tenantId}/management/imports/{batchId}','/v1/tenants/{tenantId}/management/imports/{batchId}/approve','/v1/tenants/{tenantId}/management/imports/{batchId}/discard','/v1/tenants/{tenantId}/uploads/intents','/v1/tenants/{tenantId}/attachments/{attachmentId}/complete','/v1/tenants/{tenantId}/attachments/{attachmentId}/download','/v1/tenants/{tenantId}/requests/{requestId}/attachments','/v1/tenants/{tenantId}/units/{unitId}/events','/v1/tenants/{tenantId}/permits/{permitId}/link','/v1/tenants/{tenantId}/permits/{permitId}/link/revoke','/auth/permit-links/view','/v1/tenants/{tenantId}/reservations/{reservationId}/guests','/v1/tenants/{tenantId}/units/{unitId}/visit-rules','/v1/tenants/{tenantId}/visit-rules/{ruleId}/revoke','/v1/tenants/{tenantId}/units/{unitId}/waitlist','/v1/tenants/{tenantId}/waitlist','/v1/tenants/{tenantId}/waitlist/{waitlistId}/leave','/v1/tenants/{tenantId}/waitlist/{waitlistId}/accept','/v1/tenants/{tenantId}/parcels/{parcelId}/delegation','/v1/tenants/{tenantId}/parcels/{parcelId}/delegation/revoke','/v1/tenants/{tenantId}/parcels/{parcelId}/handoff-code','/v1/tenants/{tenantId}/move-rules','/v1/tenants/{tenantId}/management/move-rules','/v1/tenants/{tenantId}/units/{unitId}/moves','/v1/tenants/{tenantId}/moves/{moveId}/cancel','/v1/tenants/{tenantId}/management/moves','/v1/tenants/{tenantId}/management/moves/{moveId}/decision','/v1/tenants/{tenantId}/concierge/moves','/v1/tenants/{tenantId}/access-connectors','/v1/tenants/{tenantId}/management/access-credentials','/v1/tenants/{tenantId}/management/access-credentials/{credentialId}/revoke','/v1/tenants/{tenantId}/concierge/pending-revocations','/v1/tenants/{tenantId}/units/{unitId}/access-credentials','/v1/tenants/{tenantId}/units/{unitId}/device-events','/v1/tenants/{tenantId}/concierge/device-events','/v1/tenants/{tenantId}/access-health','/v1/tenants/{tenantId}/management/waitlist','/v1/platform/outbox','/v1/platform/outbox/tasks','/v1/platform/outbox/tasks/{taskId}/replay','/v1/tenants/{tenantId}/concierge/occurrences','/v1/tenants/{tenantId}/concierge/occurrences/{occurrenceId}/resolve','/v1/tenants/{tenantId}/management/rentals','/v1/tenants/{tenantId}/management/units/{unitId}/rental','/v1/tenants/{tenantId}/management/units/{unitId}/rental/end','/v1/tenants/{tenantId}/units/{unitId}/rental','/v1/tenants/{tenantId}/units/{unitId}/rental/invitations','/v1/tenants/{tenantId}/units/{unitId}/rental/invitations/{invitationId}/revoke','/v1/tenants/{tenantId}/units/{unitId}/rental/occupants/{membershipId}/remove','/v1/platform/tenants/{tenantId}/manager-invitations','/v1/platform/tenants/{tenantId}/manager-invitations/{invitationId}/revoke','/v1/manager-invitations/accept','/auth/manager-invitation/accept','/v1/tenants/{tenantId}/management/finance/units','/v1/tenants/{tenantId}/management/finance/units/{unitId}/payer','/v1/tenants/{tenantId}/management/finance/charges','/v1/tenants/{tenantId}/management/finance/charges/issue-pending','/v1/tenants/{tenantId}/management/finance/charges/{chargeId}/issue','/v1/tenants/{tenantId}/management/finance/charges/{chargeId}/cancel','/v1/tenants/{tenantId}/management/finance/charges/{chargeId}/sync','/v1/tenants/{tenantId}/management/finance/recurring-fees','/v1/tenants/{tenantId}/management/finance/recurring-fees/{feeId}/end','/v1/tenants/{tenantId}/management/finance/recurring-fees/generate','/v1/tenants/{tenantId}/management/finance/delinquency','/v1/tenants/{tenantId}/management/finance/report','/v1/tenants/{tenantId}/units/{unitId}/finance/charges','/v1/tenants/{tenantId}/units/{unitId}/finance/charges/{chargeId}/payment','/v1/finance/asaas/webhook','/v1/platform/tenants/{tenantId}/technical-grants','/v1/platform/tenants/{tenantId}/technical-grants/{grantId}/revoke','/v1/tenants/{tenantId}/technical/session','/v1/tenants/{tenantId}/management/technical-grants','/v1/tenants/{tenantId}/management/access-subjects','/v1/tenants/{tenantId}/management/access-subjects/materialize','/v1/tenants/{tenantId}/concierge/access-subjects/materialize','/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/block','/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/unblock','/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/functional-authorizations','/v1/tenants/{tenantId}/management/subject-contexts/{contextId}/revoke','/v1/tenants/{tenantId}/management/subject-contexts/{contextId}/reauthorize','/v1/tenants/{tenantId}/management/facial-settings','/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/age-evidence','/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/age-check','/v1/tenants/{tenantId}/management/age-evidence/{evidenceId}/revoke','/v1/platform/access-models','/v1/platform/access-models/{modelId}','/v1/platform/access-homologations','/v1/platform/access-homologations/{homologationId}','/v1/platform/access-homologations/{homologationId}/decisions','/v1/tenants/{tenantId}/management/access-homologations','/v1/tenants/{tenantId}/management/access-areas','/v1/tenants/{tenantId}/management/access-areas/{areaId}','/v1/tenants/{tenantId}/management/access-points','/v1/tenants/{tenantId}/technical/access-areas','/v1/tenants/{tenantId}/technical/access-areas/{areaId}/physical','/v1/tenants/{tenantId}/technical/access-points','/v1/tenants/{tenantId}/technical/access-points/{pointId}','/v1/tenants/{tenantId}/technical/access-devices','/v1/tenants/{tenantId}/technical/access-devices/{deviceId}','/v1/tenants/{tenantId}/technical/device-channels','/v1/tenants/{tenantId}/technical/device-channels/{channelId}','/v1/tenants/{tenantId}/technical/point-bindings','/v1/tenants/{tenantId}/technical/point-bindings/{bindingId}/submit','/v1/tenants/{tenantId}/technical/point-bindings/{bindingId}/discard','/v1/tenants/{tenantId}/technical/installation-acceptances','/v1/tenants/{tenantId}/technical/installation-acceptances/{acceptanceId}/results','/v1/tenants/{tenantId}/technical/installation-acceptances/{acceptanceId}/technical-signature','/v1/tenants/{tenantId}/technical/installation-acceptances/{acceptanceId}/status','/v1/tenants/{tenantId}/management/installation-acceptances','/v1/tenants/{tenantId}/management/installation-acceptances/{acceptanceId}/rules-signature','/v1/tenants/{tenantId}/management/access-policies','/v1/tenants/{tenantId}/management/access-policies/{policyId}','/v1/tenants/{tenantId}/management/access-policies/{policyId}/versions','/v1/tenants/{tenantId}/management/access-policies/{policyId}/validate','/v1/tenants/{tenantId}/management/access-policies/{policyId}/simulate','/v1/tenants/{tenantId}/management/access-policies/{policyId}/versions/{versionId}/approve','/v1/tenants/{tenantId}/management/access-policies/{policyId}/versions/{versionId}/reject','/v1/tenants/{tenantId}/management/access-policies/{policyId}/grants/derive','/v1/tenants/{tenantId}/management/access-grants','/v1/tenants/{tenantId}/management/access-grants/{grantId}/revoke','/v1/tenants/{tenantId}/management/access-subjects/{subjectId}/revocation-markers','/v1/tenants/{tenantId}/maintenance/requests','/v1/tenants/{tenantId}/maintenance/requests/{requestId}','/v1/tenants/{tenantId}/maintenance/requests/{requestId}/state','/v1/tenants/{tenantId}/maintenance/requests/{requestId}/messages','/v1/platform/tenants/{tenantId}/modules','/v1/tenants/{tenantId}/management/access-publications/plan','/v1/tenants/{tenantId}/management/access-publications','/v1/tenants/{tenantId}/technical/edge-agents','/v1/tenants/{tenantId}/technical/edge-agents/{agentId}/rotate','/v1/tenants/{tenantId}/technical/edge-agents/{agentId}/revoke','/v1/edge/identity','/v1/edge/sync-commands/lease','/v1/edge/sync-commands/{commandId}/result','/v1/tenants/{tenantId}/security-log','/v1/tenants/{tenantId}/security-log/integrity','/v1/tenants/{tenantId}/security-log/exports']);
 const maintenance=spec.paths['/v1/tenants/{tenantId}/management/amenities/{amenityId}/maintenance-blocks'].post;
 assert.ok(maintenance.parameters.some(p=>p.name==='Idempotency-Key' && p.required));
 assert.deepEqual(spec.components.schemas.ReservationDecision.properties.decision.enum,['APPROVE','REJECT']);
 assert.ok(!spec.components.schemas.Amenity.properties.bookingState.enum.includes('APPROVAL_UNAVAILABLE'));
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/management/') && !p.includes('/announcements') && !p.includes('/requests') && !p.includes('/move') && !p.includes('/access-credentials') && !p.includes('/finance') && !p.includes('/technical-grants') && !p.includes('/access-subjects') && !p.includes('/subject-contexts') && !p.includes('/facial-settings') && !p.includes('/age-evidence') && !p.includes('/access-homologations') && !p.includes('/access-areas') && !p.includes('/access-points') && !p.includes('/installation-acceptances') && !p.includes('/access-policies') && !p.includes('/access-grants') && !p.includes('/access-publications'))) for(const operation of Object.values(item)) {
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
  assert.equal(operation['x-feature-flag'],path.startsWith('/v1/platform/billing/')?'CP_PLATFORM_BILLING_ENABLED':/technical-grants|access-models|access-homologations/.test(path)?'CP_FACIAL_ACCESS_ENABLED':'CP_PLATFORM_ENABLED',path);
  assert.match(operation.responses['403'].description,/PLATFORM_ACCESS_DENIED/,path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
 }
 assert.deepEqual(Object.keys(spec.paths['/v1/platform/tenants']),['get','post']);
 assert.deepEqual(Object.keys(spec.paths['/v1/platform/tenants/{tenantId}/modules']),['get','put'],'modulos por condominio na central');
 assert.deepEqual(spec.components.schemas.ChangeTenantModule.required,['module','enabled','expectedVersion','reason']);
 assert.ok(spec.components.schemas.CurrentIdentity.required.includes('tenantModules'),'menu recebe os modulos liberados');
 for(const path of ['/v1/tenants/{tenantId}/parcels','/v1/tenants/{tenantId}/reservations','/v1/tenants/{tenantId}/management/reservations'])
  for(const operation of Object.values(spec.paths[path])) assert.match(operation.responses['403'].description,/MODULE_DISABLED/,path);
 const creation=spec.paths['/v1/platform/tenants'].post;
 assert.ok(creation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),'criacao de condominio exige chave de operacao');
 assert.ok(!JSON.stringify(spec.components.schemas.PlatformManagerInvitation).includes('email'),'convite listado sem e-mail');
 assert.ok(!JSON.stringify(spec.components.schemas.PlatformManager).includes('accountId'),'gestor listado sem conta');
 const managerFirstAccess=spec.paths['/auth/manager-invitation/accept'].post;
 assert.equal(managerFirstAccess['x-feature-flag'],'CP_PLATFORM_ENABLED');
 assert.match(managerFirstAccess.description,/CP_BFF_ENABLED/);
 assert.ok(managerFirstAccess.parameters.some(p=>p.name==='X-CSRF-Token' && p.required));
 assert.ok(managerFirstAccess.parameters.some(p=>p.name==='Origin' && p.required));
 assert.ok(managerFirstAccess.responses['429'].headers['Retry-After'].required);
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/parcels') || p.endsWith('/concierge/units'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],path.endsWith('/concierge/units')?'CP_PARCELS_ENABLED|CP_VISITS_ENABLED':'CP_PARCELS_ENABLED',path);
  // Revogar a autorização é versionado (repetir devolve DELEGATION_NOT_ACTIVE), como a revogação de convite.
  if(method==='post' && !path.endsWith('/delegation/revoke')) assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
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
 assert.ok(!Object.keys(spec.paths).some(path=>/push|\/installations?(\/|$)/i.test(path)),'push ainda nao existe');
 const guests=spec.paths['/v1/tenants/{tenantId}/reservations/{reservationId}/guests'];
 for(const operation of Object.values(guests)) assert.equal(operation['x-feature-flag'],'CP_VISITS_ENABLED');
 assert.ok(guests.post.parameters.some(p=>p.name==='Idempotency-Key' && p.required),'convidado com chave de operacao');
 assert.match(guests.post.description,/revoga todos os convidados na mesma transação/);
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/visit-rules'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_VISITS_ENABLED',path);
  if(method==='post' && path.endsWith('/visit-rules')) assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
 }
 assert.match(spec.paths['/v1/tenants/{tenantId}/units/{unitId}/visit-rules'].post.description,/não autoriza sozinha/);
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/waitlist') && !p.includes('/management/'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_RESERVATIONS_ENABLED',path);
  if(method==='post' && !path.endsWith('/leave')) assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
 }
 assert.match(spec.paths['/v1/tenants/{tenantId}/waitlist'].post.description,/até 15 minutos/);
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/delegation') || p.endsWith('/handoff-code'))) for(const operation of Object.values(item))
  assert.equal(operation['x-feature-flag'],'CP_PARCELS_ENABLED',path);
 assert.ok(spec.paths['/v1/tenants/{tenantId}/parcels/{parcelId}/handoff-code'].post.parameters.some(p=>p.name==='Idempotency-Key' && p.required));
 assert.match(spec.paths['/v1/tenants/{tenantId}/parcels/{parcelId}/delegation'].post.description,/aparece só nesta resposta/);
 assert.deepEqual(spec.components.schemas.ParcelHandoff.properties.evidenceKind.enum,['ASSISTED','DELEGATION']);
 assert.ok(!('code' in spec.components.schemas.ParcelDelegation.properties),'resumo da delegação nunca traz o código');
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/move'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_REQUESTS_ENABLED',path);
  if(path.endsWith('/units/{unitId}/moves') && method==='post') assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
 }
 assert.match(spec.paths['/v1/tenants/{tenantId}/units/{unitId}/moves'].post.description,/não reserva elevador nem área/);
 assert.ok(spec.components.schemas.MoveCreate.required.includes('acceptedRulesVersion'),'pedido aceita a versão vigente das regras');
 assert.match(spec.paths['/v1/tenants/{tenantId}/concierge/moves'].get.description,/somente pedidos aprovados/);
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>/access-(credentials|connectors|health)|pending-revocations|device-events/.test(p))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_ACCESS_CREDENTIALS_ENABLED',path);
  if(method==='post' && path.endsWith('/access-credentials')) assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
 }
 assert.match(spec.paths['/v1/tenants/{tenantId}/management/access-credentials/{credentialId}/revoke'].post.description,/nenhuma confirmação antiga a reativa/);
 assert.deepEqual(spec.components.schemas.AccessCredential.properties.kind.enum,['CARD','QR']);
 assert.match(spec.paths['/v1/tenants/{tenantId}/units/{unitId}/device-events'].get.description,/nunca presença/);
 assert.match(spec.paths['/v1/tenants/{tenantId}/waitlist/{waitlistId}/accept'].post.description,/Oferta vencida nunca é confirmada/);
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
 assert.deepEqual(spec.components.schemas.ParcelHandoff.properties.evidenceKind.enum,['ASSISTED','DELEGATION'],'retirada assistida ou por delegacao com codigo (CP-041)');
 assert.deepEqual(spec.components.schemas.ParcelDeliver.required,['recipientMembershipId','identityChecked','expectedVersion'],'retirada identificada por vinculo');
 assert.deepEqual(Object.keys(spec.components.schemas.PlatformTenantUpdate.properties),['name','active','expectedVersion','reason']);
 assert.ok(!JSON.stringify(spec.components.schemas.PlatformTenant).includes('manifest'));
 assert.deepEqual(spec.components.schemas.BillingCharge.properties.status.enum,['OPEN','PAID','CANCELLED']);
 assert.deepEqual(spec.components.schemas.BillingPaymentRecord.required,['amountCents','paidOn','method','reference','expectedVersion']);
 assert.ok(!Object.keys(spec.paths).some(path=>path.startsWith('/v1/platform/billing/') && /gateway|webhook|invoice/i.test(path)),'sem gateway ou emissao fiscal');
 // Financeiro condominial (ADR-0008): flag própria, gestão com AAL2, criação idempotente, webhook sem sessão e sem dado sensível.
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/finance'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_FINANCE_ENABLED',path);
  if(path.includes('/management/')) assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
  if(method==='post' && (path.endsWith('/management/finance/charges') || path.endsWith('/management/finance/recurring-fees'))) assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key' && p.required),path);
 }
 // Acesso facial AC-003 (V037): flag própria desligada, AAL2 em todas as rotas, gestor só lê, nada de segredo na projeção.
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/technical-grants') || p.includes('/technical/'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_FACIAL_ACCESS_ENABLED',path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
  if(path.includes('/management/')) assert.equal(method,'get',path);
  if(method==='post') assert.ok(operation.parameters.some(p=>p.name==='X-CSRF-Token'),path);
 }
 assert.ok(!JSON.stringify(spec.components.schemas.TechnicalGrant).match(/secret_ref|secretRef|password|token|email/i),'HAC-013: concessão sem segredo nem e-mail');
 assert.ok(!spec.components.schemas.IssueTechnicalGrant.properties.scopes.items.enum.includes('*'),'sem curinga de escopo');
 // Sujeitos de acesso AC-005 (V038): mesma flag desligada, AAL2, CSRF nas escritas e projeção sem documento.
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/access-subjects') || p.includes('/subject-contexts'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_FACIAL_ACCESS_ENABLED',path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
  if(method==='post') assert.ok(operation.parameters.some(p=>p.name==='X-CSRF-Token'),path);
 }
 assert.ok(!Object.keys({...spec.components.schemas.AccessSubject.properties,...spec.components.schemas.SubjectContext.properties}).some(k=>/cpf|document|birth|biometr|email|name/i.test(k)),'HAC-085: sujeito sem documento, nascimento, biometria ou e-mail');
 // Idade AC-006 (V039): mesma flag, AAL2; a data de nascimento só entra (HAC-085).
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/facial-settings') || p.includes('/age-'))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_FACIAL_ACCESS_ENABLED',path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
 }
 for(const name of ['AgeEvidence','AgeCheck']) assert.ok(!Object.keys(spec.components.schemas[name].properties).some(k=>/birth|document|cpf/i.test(k)),name);
 // Catálogo e homologação AC-019 (V040): mesma flag; aprovação nunca é campo do dossiê (HAC-002).
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>/access-models|access-homologations/.test(p))) for(const operation of Object.values(item)) {
  assert.equal(operation['x-feature-flag'],'CP_FACIAL_ACCESS_ENABLED',path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
 }
 assert.deepEqual(Object.keys(spec.components.schemas.CapabilityObservation.properties),['documented','observed'],'HAC-002');
 assert.deepEqual(spec.components.schemas.CreateAccessHomologation.properties.topology.enum,['LOCAL_GATEWAY','CLOUD_SERVICE']);
 // Áreas e pontos AC-004 (V041): mesma flag, AAL2; ponto físico só na rota técnica e gestão sem referências de projeto.
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>/access-(areas|points)/.test(p))) for(const [method,operation] of Object.entries(item)) {
  assert.equal(operation['x-feature-flag'],'CP_FACIAL_ACCESS_ENABLED',path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
  if(path.includes('/access-points') && method!=='get') assert.ok(path.includes('/technical/'),'HAC-027: '+path);
 }
 assert.ok(!('emergencyDesignRef' in spec.components.schemas.AccessPointSummary.properties));
 // Instalação física AC-004 parte 2 (V042): mesma flag, AAL2; equipamento, canal e vínculo só em rota técnica (HAC-009).
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>/access-devices|device-channels|point-bindings|installation-acceptances/.test(p))) for(const operation of Object.values(item)) {
  assert.equal(operation['x-feature-flag'],'CP_FACIAL_ACCESS_ENABLED',path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
  if(!path.includes('/installation-acceptances')) assert.ok(path.includes('/technical/'),'HAC-009: '+path);
 }
 assert.ok(!Object.keys(spec.components.schemas.InstallationAcceptanceSummary.properties).some(k=>/plant|topology|channel|relay|report/i.test(k)),'HAC-009: resumo da gestão sem planta, topologia ou canal');
 // Políticas AC-007/AC-008 (V043): mesma flag, AAL2, só gestão; simulação nunca cria comando; documento fechado.
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/access-policies'))) for(const operation of Object.values(item)) {
  assert.equal(operation['x-feature-flag'],'CP_FACIAL_ACCESS_ENABLED',path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
  assert.ok(path.includes('/management/'),path);
 }
 assert.equal(spec.components.schemas.AccessPolicySimulation.properties.hardwareCommandCreated.const,false);
 assert.equal(spec.components.schemas.PolicyDocument.additionalProperties,false);
 assert.equal(spec.components.schemas.PolicyDocument.properties.defaultEffect.const,'DENY');
 // Gateway (V048): rotas de máquina sem sessão, autenticadas só pela credencial do gateway; nada de segredo nas respostas.
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.startsWith('/v1/edge/'))) for(const operation of Object.values(item)) {
  assert.equal(operation['x-feature-flag'],'CP_FACIAL_ACCESS_ENABLED',path);
  assert.deepEqual(operation.security,[{edgeCredential:[]}],path);
  assert.ok(operation.parameters.some(p=>p.name==='X-CondPulse-Edge-Credential' && p.in==='header' && p.required),path);
  assert.ok(!operation.parameters.some(p=>p.name==='tenantId'),'condomínio vem da identidade: '+path);
  assert.match(operation.responses['401'].description,/EDGE_UNAUTHENTICATED/,path);
 }
 assert.equal(spec.components.securitySchemes.edgeCredential.in,'header');
 assert.ok(spec.paths['/v1/edge/sync-commands/{commandId}/result'].post.parameters.some(p=>p.name==='Idempotency-Key' && p.required),'relatório idempotente');
 assert.deepEqual(spec.components.schemas.EdgeReportRequest.properties.state.enum,['ACK','APPLIED_UNVERIFIED','CONFIRMED','PARTIAL','FAILED','UNKNOWN'],'gateway nunca relata PENDING nem SUPERSEDED');
 for(const name of ['EdgeAgent','EdgeIdentity','EdgeSyncCommand','EdgeReportResult']) assert.ok(!Object.keys(spec.components.schemas[name].properties).some(k=>/token|secret|password|credentialHash/i.test(k)),name);
 assert.ok(!('tenantId' in spec.components.schemas.EdgeReportRequest.properties) && !('tenantId' in spec.components.schemas.EdgeLeaseRequest.properties));
 // Log de Segurança (V049): atrás de CP_SECURITY_LOG_ENABLED, AAL2, exportação com motivo e manifesto; sem biometria.
 for(const [path,item] of Object.entries(spec.paths).filter(([p])=>p.includes('/security-log'))) for(const operation of Object.values(item)) {
  assert.equal(operation['x-feature-flag'],'CP_SECURITY_LOG_ENABLED',path);
  assert.match(operation.responses['403'].description,/MFA_REQUIRED/,path);
  assert.equal(operation.responses['200'].headers['Cache-Control'].schema.const,'no-store',path);
 }
 assert.deepEqual(spec.components.schemas.SecurityLogExportRequest.required,['reason']);
 assert.equal(spec.components.schemas.SecurityLogExportRequest.properties.reason.minLength,10);
 for(const field of ['fromSeq','toSeq','firstPrevHash','firstHash','lastHash','exportedBy','exportEventId'])
  assert.ok(spec.components.schemas.SecurityLogManifest.required.includes(field),field);
 assert.ok(!JSON.stringify(spec.components.schemas.SecurityLogEvent).match(/template|biometr|embedding/i),'evento do log sem campo biométrico');
 const webhook=spec.paths['/v1/finance/asaas/webhook'].post;
 assert.deepEqual(webhook.security,[],'webhook do Asaas não usa sessão');
 assert.ok(webhook.parameters.some(p=>p.name==='asaas-access-token' && p.required));
 assert.ok(!JSON.stringify(spec.components.schemas.FinanceCharge).match(/"(asaasPaymentId|payerId|document|cpfCnpj)"/),'cobrança sem ids internos nem documento');
 assert.match(spec.components.schemas.FinancePayer.properties.document.description,/Mascarado/);
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

