import { describe, expect, it } from 'vitest';
import type { AmenityAvailability } from '../api/types';
import { instantMicros } from '../api/instant';
import { availabilityDayRange, selectionAvailability } from './availabilityWindow';

const snapshot: AmenityAvailability = {
  tenantId: '10000000-0000-4000-8000-000000000001', unitId: '20000000-0000-4000-8000-000000000001',
  amenityId: '30000000-0000-4000-8000-000000000001', timeZone: 'America/Sao_Paulo',
  from: '2030-01-01T03:00:00Z', until: '2030-01-02T03:00:00Z', observedAt: '2029-12-30T12:00:00Z',
  blockedIntervals: [{ occupiedFrom: '2030-01-01T15:00:00Z', occupiedUntil: '2030-01-01T16:15:00Z' }],
};

describe('seleção sobre a ocupação anônima', () => {
  it('consulta o dia inteiro no fuso do condomínio, incluindo dia com 23 horas', () => {
    expect(availabilityDayRange('2030-01-01', 'America/Sao_Paulo')).toEqual({
      from: '2030-01-01T03:00:00.000Z', until: '2030-01-02T03:00:00.000Z',
    });
    const daylight = availabilityDayRange('2027-03-14', 'America/New_York');
    expect(Date.parse(daylight.until) - Date.parse(daylight.from)).toBe(23 * 3_600_000);
  });

  it('inclui a limpeza da proposta e permite adjacência exata do intervalo meio aberto', () => {
    expect(selectionAvailability(snapshot, '2030-01-01T14:00:00Z', '2030-01-01T14:50:00Z', 15)).toBe('blocked');
    expect(selectionAvailability(snapshot, '2030-01-01T14:00:00Z', '2030-01-01T14:45:00Z', 15)).toBe('unblocked');
    expect(selectionAvailability(snapshot, '2030-01-01T16:15:00Z', '2030-01-01T17:00:00Z', 15)).toBe('unblocked');
    expect(selectionAvailability(snapshot, '2030-01-01T16:00:00Z', '2030-01-01T17:00:00Z', 0)).toBe('blocked');
  });

  it('não infere disponibilidade além da janela nem de um período incompleto', () => {
    expect(selectionAvailability(snapshot, '2030-01-01T02:00:00Z', '2030-01-01T04:00:00Z', 0)).toBe('outside');
    expect(selectionAvailability(snapshot, '2030-01-02T02:00:00Z', '2030-01-02T02:50:00Z', 15)).toBe('outside');
    expect(selectionAvailability(snapshot, '', '2030-01-01T04:00:00Z', 0)).toBe('incomplete');
    expect(selectionAvailability(snapshot, '2030-01-01T05:00:00Z', '2030-01-01T04:00:00Z', 0)).toBe('incomplete');
  });

  it('um microssegundo ocupado no limite continua bloqueado', () => {
    const precise = { ...snapshot, blockedIntervals: [{ occupiedFrom: '2030-01-01T14:00:00Z', occupiedUntil: '2030-01-01T15:00:00.000001Z' }] };
    expect(selectionAvailability(precise, '2030-01-01T15:00:00Z', '2030-01-01T16:00:00Z', 0)).toBe('blocked');
  });

  it('valida instantes canônicos sem normalizar datas inexistentes ou perder frações', () => {
    expect(instantMicros('2030-01-01T12:00:00.123456-03:00')).toBe(instantMicros('2030-01-01T15:00:00.123456Z'));
    expect(instantMicros('2030-02-30T12:00:00Z')).toBeNull();
    expect(instantMicros('2030-01-01T24:00:00Z')).toBeNull();
    expect(instantMicros('2030-01-01T12:00:00.1234567Z')).toBeNull();
  });
});
