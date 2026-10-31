import type { Page, Route } from '@playwright/test';
import { AMENITY_A, TENANT_A, UNIT_A, futureWindow } from './fixtures';

// API sintética da gestão e do segundo fator. Registrada depois de installSyntheticApi: rotas mais novas têm precedência.
export const PENDING_RESERVATION = '41000000-0000-4000-8000-000000000001';
export const PENDING_MEMBERSHIP = '51000000-0000-4000-8000-000000000001';
export const FACTOR = '61000000-0000-4000-8000-000000000001';
export const INVITATION_TOKEN = 'SYNTHETICinvitationTOKEN0123456789abcdefghi';
const SECRET = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP';

export interface ManagementState {
  assurance: 'AAL1' | 'AAL2';
  verifiedFactor: boolean;
  enrollRequests: number;
  verifyBodies: unknown[];
  decisions: unknown[];
  membershipDecisions: unknown[];
  invitationBodies: unknown[];
  acceptBodies: unknown[];
  managementRequests: string[];
  reservationStatus: string;
  membershipStatus: string;
  /** Outro gestor já gravou a mesma decisão: o servidor devolve o estado atual sem aplicar esta. */
  decisionAlreadyApplied?: boolean;
  /** Fila de espera (CP-040), só leitura pela gestão. */
  waitlist?: Record<string, unknown>[];
  waitlistFailures?: number;
  waitlistQueries?: string[];
  /** Locações em vigor (V034); vazias por padrão. */
  rentals?: Record<string, unknown>[];
}

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) });
}
async function problem(route: Route, status: number, code: string) {
  await json(route, status, { type: `urn:condpulse:problem:${code}`, status, code, detail: 'PRIVATE-SERVER-DETAIL', traceId: '60000000-0000-4000-8000-000000000002' });
}

function pendingReservation(state: ManagementState) {
  const window = futureWindow();
  return { reservationId: PENDING_RESERVATION, kind: 'BOOKING', unitId: UNIT_A, unitLabel: '101 — Sintética A', amenityId: AMENITY_A,
    amenityName: 'Área Sintética A', status: state.reservationStatus, startsAt: window.startsAt, endsAt: window.endsAt,
    occupiedFrom: window.startsAt, occupiedUntil: window.endsAt,
    expiresAt: state.reservationStatus === 'PENDING_APPROVAL' ? new Date(Date.now() + 3_600_000).toISOString() : null,
    acceptedPolicyVersion: 1, feeCents: 2500, version: state.reservationStatus === 'PENDING_APPROVAL' ? 0 : 1,
    decisionReason: state.reservationStatus === 'REJECTED' ? 'Área em manutenção' : null, requesterName: 'Morador Sintético',
    createdAt: new Date(Date.now() - 600_000).toISOString() };
}
function pendingMembership(state: ManagementState) {
  return { membershipId: PENDING_MEMBERSHIP, unitId: UNIT_A, unitLabel: '101 — Sintética A', displayName: 'Pessoa Convidada Sintética',
    role: 'RESIDENT', status: state.membershipStatus, validFrom: new Date(Date.now() - 3_600_000).toISOString(), validUntil: null,
    version: state.membershipStatus === 'PENDING' ? 0 : 1 };
}

export async function installManagementApi(page: Page, initial: Partial<ManagementState> = {}): Promise<ManagementState> {
  const state: ManagementState = { assurance: 'AAL1', verifiedFactor: false, enrollRequests: 0, verifyBodies: [], decisions: [],
    membershipDecisions: [], invitationBodies: [], acceptBodies: [], managementRequests: [], reservationStatus: 'PENDING_APPROVAL',
    membershipStatus: 'PENDING', ...initial };

  await page.route('**/auth/mfa**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === '/auth/mfa' && request.method() === 'GET') {
      await json(route, 200, { assuranceLevel: state.assurance,
        factors: state.verifiedFactor ? [{ factorId: FACTOR, status: 'VERIFIED', friendlyName: null }] : [] });
      return;
    }
    if (path === '/auth/mfa/enroll' && request.method() === 'POST') {
      state.enrollRequests += 1;
      await json(route, 200, { factorId: FACTOR, otpauthUri: `otpauth://totp/CondPulse:sintetico?secret=${SECRET}`, secret: SECRET,
        qrCodeDataUri: 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"></svg>' });
      return;
    }
    if (path === '/auth/mfa/verify' && request.method() === 'POST') {
      const body = request.postDataJSON() as { factorId: string; code: string };
      state.verifyBodies.push(body);
      if (body.code !== '123456') { await problem(route, 422, 'MFA_CODE_REJECTED'); return; }
      state.assurance = 'AAL2'; state.verifiedFactor = true;
      await json(route, 200, { accountId: '10000000-0000-4000-8000-000000000001', expiresAt: new Date(Date.now() + 28_800_000).toISOString() });
      return;
    }
    await route.fallback();
  });

  await page.route(`**/v1/tenants/${TENANT_A}/management/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(`/v1/tenants/${TENANT_A}/management`, '');
    state.managementRequests.push(`${request.method()} ${path}`);
    if (state.assurance !== 'AAL2') { await problem(route, 403, 'MFA_REQUIRED'); return; }
    if (request.method() === 'GET' && path === '/waitlist') {
      const open = url.searchParams.get('open') === 'true';
      (state.waitlistQueries ??= []).push(open ? 'open' : 'all');
      if ((state.waitlistFailures ?? 0) > 0) { state.waitlistFailures! -= 1; await problem(route, 503, 'MANAGEMENT_DEPENDENCY_UNAVAILABLE'); return; }
      const rows = state.waitlist ?? [];
      await json(route, 200, { entries: rows.filter((item) => !open || item.state === 'WAITING' || item.state === 'OFFERED'), truncated: false });
      return;
    }
    if (request.method() === 'GET' && path === '/reservations') {
      const status = url.searchParams.get('status');
      const rows = [pendingReservation(state)].filter((item) => !status || item.status === status);
      await json(route, 200, { reservations: rows, truncated: false });
      return;
    }
    if (request.method() === 'POST' && path === `/reservations/${PENDING_RESERVATION}/decision`) {
      const body = request.postDataJSON() as { decision: string; reason: string | null };
      state.decisions.push(body);
      state.reservationStatus = body.decision === 'APPROVE' ? 'CONFIRMED' : 'REJECTED';
      await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store',
        'Reservation-Decision-Applied': String(!state.decisionAlreadyApplied) }, body: JSON.stringify(pendingReservation(state)) });
      return;
    }
    if (request.method() === 'GET' && path === '/memberships') {
      const status = url.searchParams.get('status');
      const rows = [pendingMembership(state)].filter((item) => !status || item.status === status);
      await json(route, 200, { memberships: rows, truncated: false });
      return;
    }
    if (request.method() === 'POST' && path === `/memberships/${PENDING_MEMBERSHIP}/decision`) {
      const body = request.postDataJSON() as { decision: string };
      state.membershipDecisions.push(body);
      state.membershipStatus = body.decision === 'APPROVE' ? 'ACTIVE' : 'REVOKED';
      await json(route, 200, pendingMembership(state));
      return;
    }
    if (request.method() === 'GET' && path === '/units') {
      await json(route, 200, { units: [{ unitId: UNIT_A, label: '101 — Sintética A', blockId: null, blockName: null, status: 'ACTIVE', version: 0 }], truncated: false });
      return;
    }
    if (request.method() === 'GET' && path === '/blocks') { await json(route, 200, []); return; }
    if (request.method() === 'GET' && path === '/amenities') {
      await json(route, 200, [{ amenityId: AMENITY_A, name: 'Área Sintética A', capacity: 12, approvalRequired: true, feeCents: 2500,
        bufferMinutes: 15, active: true, policyVersion: 1, version: 0, policyState: 'CONFIGURED',
        policy: { schemaVersion: 1, weekDays: [1, 2, 3, 4, 5, 6, 7], opensAt: '08:00', closesAt: '22:00', minimumLeadMinutes: 0,
          maximumAdvanceDays: 366, maximumDurationMinutes: 600, maximumActivePerUnit: 3, cancellationLeadMinutes: 0 } }]);
      return;
    }
    if (request.method() === 'GET' && path === '/invitations') { await json(route, 200, []); return; }
    if (request.method() === 'GET' && path === '/rentals') { await json(route, 200, state.rentals ?? []); return; }
    if (request.method() === 'POST' && path === '/invitations') {
      state.invitationBodies.push(request.postDataJSON());
      await json(route, 201, { invitationId: '71000000-0000-4000-8000-000000000001', token: INVITATION_TOKEN,
        expiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString() });
      return;
    }
    if (request.method() === 'GET' && path === '/audit-events') {
      await json(route, 200, { events: [{ eventId: '81000000-0000-4000-8000-000000000001', actorId: '10000000-0000-4000-8000-000000000001',
        action: 'RESERVATION_APPROVED', resourceType: 'RESERVATION', resourceId: PENDING_RESERVATION, reason: null,
        createdAt: new Date().toISOString() }], nextCursor: null });
      return;
    }
    await problem(route, 404, 'SYNTHETIC_TEST_ROUTE_NOT_FOUND');
  });

  await page.route('**/v1/invitations/accept', async (route) => {
    state.acceptBodies.push(route.request().postDataJSON());
    await json(route, 200, { tenantId: TENANT_A, membershipId: PENDING_MEMBERSHIP, status: 'PENDING' });
  });
  return state;
}
