// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { waitlistEntry } from './waitlist';

const entry = {
  waitlistId: '79000000-0000-4000-8000-000000000001', unitId: '20000000-0000-4000-8000-000000000001',
  amenityId: '30000000-0000-4000-8000-000000000001', state: 'WAITING', startsAt: '2030-01-01T15:00:00Z', endsAt: '2030-01-01T17:00:00Z',
  occupiedFrom: '2030-01-01T15:00:00Z', occupiedUntil: '2030-01-01T17:30:00Z', acceptedPolicyVersion: 1, approvalRequired: false,
  feeCents: 0, offerReservationId: null, offeredAt: null, offerExpiresAt: null, closedAt: null, createdByMe: true, position: 2,
  version: 0, createdAt: '2029-12-30T10:00:00Z',
};

describe('fila de espera', () => {
  it('aceita entrada aguardando e oferta com retenção e prazo', () => {
    expect(waitlistEntry(entry).position).toBe(2);
    const offered = waitlistEntry({ ...entry, state: 'OFFERED', offerReservationId: '78000000-0000-4000-8000-000000000001',
      offeredAt: '2030-01-01T10:00:00Z', offerExpiresAt: '2030-01-01T10:15:00Z', position: null, version: 1 });
    expect(offered.offerExpiresAt).toBe('2030-01-01T10:15:00Z');
  });
  it('recusa oferta sem retenção ou prazo, entrada aguardando com oferta e estado desconhecido', () => {
    expect(() => waitlistEntry({ ...entry, state: 'OFFERED' })).toThrow();
    expect(() => waitlistEntry({ ...entry, offerReservationId: '78000000-0000-4000-8000-000000000001' })).toThrow();
    expect(() => waitlistEntry({ ...entry, state: 'HELD' })).toThrow();
    expect(() => waitlistEntry({ ...entry, position: 0 })).toThrow();
  });
  it('resultado inválido de comando fica incerto', () => {
    try { waitlistEntry({ ...entry, version: -1 }, true); expect.unreachable(); }
    catch (error) { expect((error as { uncertain?: boolean }).uncertain).toBe(true); }
  });
});
