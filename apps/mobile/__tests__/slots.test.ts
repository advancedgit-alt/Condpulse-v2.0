import { bookableDates, daySlots, durations, type Policy } from '../src/reservations/slots';

const policy: Policy = { weekDays: [1, 2, 3, 4, 5], opensAt: '08:00', closesAt: '12:00', minimumLeadMinutes: 60, maximumAdvanceDays: 10,
  maximumDurationMinutes: 120, maximumActivePerUnit: 1, cancellationLeadMinutes: 120 };
const zone = 'America/Sao_Paulo';
// Segunda-feira, 07/01/2030, 08:10 em São Paulo (UTC−3).
const now = new Date('2030-01-07T11:10:00Z');

describe('horários oferecidos no celular', () => {
  it('só dias permitidos pela regra, de hoje até a antecedência máxima', () => {
    expect(bookableDates(policy, zone, now)).toEqual(['2030-01-07', '2030-01-08', '2030-01-09', '2030-01-10', '2030-01-11',
      '2030-01-14', '2030-01-15', '2030-01-16', '2030-01-17']);
    expect(bookableDates({ ...policy, weekDays: [6, 7] }, zone, now)).toEqual(['2030-01-12', '2030-01-13']);
    // Toda a janela configurada, sem corte: 31 dias corridos com todos os dias liberados.
    const all = bookableDates({ ...policy, weekDays: [1, 2, 3, 4, 5, 6, 7], maximumAdvanceDays: 30 }, zone, now);
    expect(all).toHaveLength(31);
    expect(all.at(-1)).toBe('2030-02-06');
  });

  it('durações em passos de 30 minutos até o máximo que cabe com a limpeza', () => {
    expect(durations(policy, 0)).toEqual([30, 60, 90, 120]);
    expect(durations({ ...policy, maximumDurationMinutes: 600 }, 60)).toEqual([30, 60, 90, 120, 150, 180]);
    // Limite menor que o passo ou fora do múltiplo: o próprio máximo é oferecido.
    expect(durations({ ...policy, maximumDurationMinutes: 20 }, 0)).toEqual([20]);
    expect(durations({ ...policy, maximumDurationMinutes: 100 }, 0)).toEqual([30, 60, 90, 100]);
    expect(durations({ ...policy, opensAt: '08:00', closesAt: '08:45', maximumDurationMinutes: 120 }, 20)).toEqual([25]);
  });

  it('respeita antecedência mínima, limpeza dentro do horário e marca o ocupado', () => {
    const slots = daySlots({ policy, bufferMinutes: 30, date: '2030-01-07', timeZone: zone, durationMinutes: 60, now,
      blocked: [{ occupiedFrom: '2030-01-07T13:00:00Z', occupiedUntil: '2030-01-07T13:30:00Z' }] });
    // 08:00–09:10 fica antes da antecedência mínima; uso + limpeza termina até 12:00.
    expect(slots.map((slot) => `${slot.label}:${slot.state}`)).toEqual([
      '09:30–10:30:blocked', '10:00–11:00:blocked', '10:30–11:30:free']);
    expect(slots[2]).toMatchObject({ startsAt: '2030-01-07T13:30:00.000Z', endsAt: '2030-01-07T14:30:00.000Z' });
  });

  it('a limpeza da reserva nova também não pode encostar no ocupado', () => {
    const slots = daySlots({ policy, bufferMinutes: 30, date: '2030-01-08', timeZone: zone, durationMinutes: 60, now,
      blocked: [{ occupiedFrom: '2030-01-08T12:30:00Z', occupiedUntil: '2030-01-08T13:00:00Z' }] });
    // Ocupado 09:30–10:00 local: 08:30–09:30 + limpeza até 10:00 colide; 08:00–09:00 + limpeza termina às 09:30 (encosta, livre).
    expect(slots.find((slot) => slot.label === '08:30–09:30')!.state).toBe('blocked');
    expect(slots.find((slot) => slot.label === '08:00–09:00')!.state).toBe('free');
    expect(slots.find((slot) => slot.label === '10:00–11:00')!.state).toBe('free');
  });

  it('dia fora da regra, depois da antecedência máxima ou duração acima do máximo não oferece nada', () => {
    const base = { policy, bufferMinutes: 0, timeZone: zone, now, blocked: [] };
    expect(daySlots({ ...base, date: '2030-01-12', durationMinutes: 60 })).toEqual([]);
    expect(daySlots({ ...base, date: '2030-01-18', durationMinutes: 60 })).toEqual([]);
    expect(daySlots({ ...base, date: '2030-01-08', durationMinutes: 150 })).toEqual([]);
  });

  it('não oferece horário inexistente nem uso que atravesse a mudança de horário de verão', () => {
    const night: Policy = { ...policy, weekDays: [7], opensAt: '01:00', closesAt: '05:00', maximumAdvanceDays: 366 };
    const slots = daySlots({ policy: night, bufferMinutes: 0, date: '2030-03-10', timeZone: 'America/New_York', durationMinutes: 60,
      now: new Date('2030-03-01T00:00:00Z'), blocked: [] });
    expect(slots.map((slot) => slot.label)).toEqual(['03:00–04:00', '03:30–04:30', '04:00–05:00']);
  });

  it('abertura e último início fora da grade de 30 minutos também são oferecidos', () => {
    const odd: Policy = { ...policy, opensAt: '08:10', closesAt: '08:30', maximumDurationMinutes: 20 };
    expect(daySlots({ policy: odd, bufferMinutes: 0, date: '2030-01-08', timeZone: zone, durationMinutes: 20, now, blocked: [] })
      .map((slot) => slot.label)).toEqual(['08:10–08:30']);
    const late: Policy = { ...policy, opensAt: '08:10', closesAt: '10:20' };
    expect(daySlots({ policy: late, bufferMinutes: 10, date: '2030-01-08', timeZone: zone, durationMinutes: 60, now, blocked: [] })
      .map((slot) => slot.label)).toEqual(['08:10–09:10', '08:30–09:30', '09:00–10:00', '09:10–10:10']);
  });
});
