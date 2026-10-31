// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { createRentalApi, managedRental, ownerRentalView, rentalOccupant } from './rental';

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const tenant = '00000000-0000-4000-8000-000000000001';
const unit = '20000000-0000-4000-8000-000000000001';
const other = '20000000-0000-4000-8000-000000000002';
const occupant = { membershipId: '51000000-0000-4000-8000-000000000001', displayName: 'Inquilino Fictício', role: 'RESPONSIBLE', status: 'ACTIVE',
  validFrom: '2030-01-01T12:00:00Z', version: 1 };
const invitation = { invitationId: '52000000-0000-4000-8000-000000000001', unitId: unit, unitLabel: 'FICTÍCIA 101', role: 'RESIDENT',
  displayName: 'Novo Inquilino', status: 'PENDING', expiresAt: '2030-01-08T12:00:00Z', createdAt: '2030-01-01T12:00:00Z', acceptedAt: null };
const rented = { unitId: unit, unitLabel: 'FICTÍCIA 101', rented: true, rentedSince: '2030-01-01T10:00:00Z', occupants: [occupant], invitations: [invitation] };

describe('contrato de proprietário × inquilino', () => {
  it('visão do proprietário: sem locação, nada além da indicação; com locação, só convites de inquilino da unidade', () => {
    expect(ownerRentalView(rented, unit).occupants).toHaveLength(1);
    const idle = { unitId: unit, unitLabel: 'FICTÍCIA 101', rented: false, rentedSince: null, occupants: [], invitations: [] };
    expect(ownerRentalView(idle, unit).rented).toBe(false);
    expect(() => ownerRentalView({ ...idle, occupants: [occupant] }, unit)).toThrow();
    expect(() => ownerRentalView({ ...idle, rentedSince: '2030-01-01T10:00:00Z' }, unit)).toThrow();
    expect(() => ownerRentalView(rented, other)).toThrow();
    expect(() => ownerRentalView({ ...rented, invitations: [{ ...invitation, role: 'OWNER' }] }, unit)).toThrow();
    expect(() => ownerRentalView({ ...rented, invitations: [{ ...invitation, unitId: other }] }, unit)).toThrow();
  });
  it('ocupante e locação validados', () => {
    expect(rentalOccupant(occupant).role).toBe('RESPONSIBLE');
    expect(() => rentalOccupant({ ...occupant, role: 'OWNER' })).toThrow();
    expect(() => rentalOccupant({ ...occupant, version: -1 })).toThrow();
    expect(managedRental({ unitId: unit, unitLabel: 'FICTÍCIA 101', startedAt: '2030-01-01T10:00:00Z', owners: ['Dona Fictícia'] }).owners).toEqual(['Dona Fictícia']);
    expect(() => managedRental({ unitId: unit, unitLabel: 'FICTÍCIA 101', startedAt: '2030-01-01T10:00:00Z', owners: [] })).toThrow();
  });
  it('rotas e corpos das ações; remoção confere o vínculo revogado', async () => {
    let reply: () => Response = () => json({ ...occupant, status: 'REVOKED', version: 2 });
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : reply());
    const client = createRentalApi(new CondPulseApi({ fetch: fetcher }));
    expect((await client.remove(tenant, unit, occupant.membershipId, 1, 'Fim do contrato')).status).toBe('REVOKED');
    let call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/units/${unit}/rental/occupants/${occupant.membershipId}/remove`);
    expect(JSON.parse(String(call[1]?.body))).toEqual({ expectedVersion: 1, reason: 'Fim do contrato' });
    reply = () => json(occupant);
    await expect(client.remove(tenant, unit, occupant.membershipId, 1, 'x')).rejects.toMatchObject({ uncertain: true });
    reply = () => json({ invitationId: invitation.invitationId, token: 'a'.repeat(43), expiresAt: '2030-01-08T12:00:00Z' }, 201);
    await client.invite(tenant, unit, { role: 'RESPONSIBLE', displayName: 'Inquilino', email: 'i@example.invalid', validDays: 7 });
    call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/units/${unit}/rental/invitations`);
    reply = () => new Response(null, { status: 204 });
    await client.revokeInvitation(tenant, unit, invitation.invitationId);
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/units/${unit}/rental/invitations/${invitation.invitationId}/revoke`);
    await client.end(tenant, unit, 'Contrato encerrado');
    expect(String(fetcher.mock.calls.at(-1)![0])).toBe(`/v1/tenants/${tenant}/management/units/${unit}/rental/end`);
    reply = () => json({ unitId: other, unitLabel: 'OUTRA', startedAt: '2030-01-01T10:00:00Z', owners: ['Dona'] }, 201);
    await expect(client.start(tenant, unit, 'Contrato')).rejects.toMatchObject({ uncertain: true });
  });
});
