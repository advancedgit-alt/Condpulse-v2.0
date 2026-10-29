import { describe, expect, it } from 'vitest';
import type { Reservation } from '../api/types';
import { upcomingReservations } from './Dashboard';

const unit = '11111111-1111-4111-8111-111111111111';
const now = Date.parse('2030-01-10T12:00:00Z');
function reservation(id: string, status: Reservation['status'], start: string, end: string, unitId = unit): Reservation {
  return { reservationId: id, unitId, amenityId: '22222222-2222-4222-8222-222222222222', status,
    startsAt: start, endsAt: end, occupiedFrom: start, occupiedUntil: end, acceptedPolicyVersion: 1, feeCents: 0, version: 0,
    decisionReason: null };
}

describe('resumo das próximas reservas da própria unidade', () => {
  it('inclui solicitação em andamento e estados ativos; exclui passadas e encerradas', () => {
    const rows = [
      reservation('past', 'CONFIRMED', '2030-01-10T09:00:00Z', '2030-01-10T12:00:00Z'),
      reservation('ongoing', 'CONFIRMED', '2030-01-10T11:00:00Z', '2030-01-10T13:00:00Z'),
      reservation('pending', 'PENDING_APPROVAL', '2030-01-11T12:00:00Z', '2030-01-11T13:00:00Z'),
      reservation('cancelled', 'CANCELLED', '2030-01-11T14:00:00Z', '2030-01-11T15:00:00Z'),
      reservation('rejected', 'REJECTED', '2030-01-11T16:00:00Z', '2030-01-11T17:00:00Z'),
      reservation('expired', 'EXPIRED', '2030-01-11T18:00:00Z', '2030-01-11T19:00:00Z'),
      reservation('held', 'HELD', '2030-01-12T12:00:00Z', '2030-01-12T13:00:00Z'),
    ];
    expect(upcomingReservations(rows, unit, now).map(row => row.reservationId)).toEqual(['ongoing', 'pending', 'held']);
  });
  it('ordena sem alterar a resposta original e desempata pelo identificador', () => {
    const rows = [reservation('b', 'CONFIRMED', '2030-01-11T12:00:00Z', '2030-01-11T13:00:00Z'), reservation('a', 'CONFIRMED', '2030-01-11T12:00:00Z', '2030-01-11T13:00:00Z')];
    expect(upcomingReservations(rows, unit, now).map(row => row.reservationId)).toEqual(['a', 'b']);
    expect(rows.map(row => row.reservationId)).toEqual(['b', 'a']);
  });
  it('rejeita a resposta inteira se outra unidade aparecer, inclusive em reserva encerrada', () => {
    const foreign = reservation('foreign', 'CANCELLED', '2030-01-09T12:00:00Z', '2030-01-09T13:00:00Z', '33333333-3333-4333-8333-333333333333');
    expect(() => upcomingReservations([foreign], unit, now)).toThrow(/não pôde ser validada/);
  });
});
