import { addDays, dateInZone, isoWeekDay, toInstant } from '../time';

/**
 * Horários oferecidos no celular (CP-024), com as mesmas regras que o servidor confere em ReservationPolicy: dia da
 * semana permitido, antecedência mínima e máxima, duração máxima, uso e limpeza dentro do horário local do mesmo dia.
 * A ocupação vem da consulta de disponibilidade; o servidor continua sendo quem decide (conflito volta como 409).
 */
export interface Policy {
  weekDays: number[]; opensAt: string; closesAt: string; minimumLeadMinutes: number; maximumAdvanceDays: number;
  maximumDurationMinutes: number; maximumActivePerUnit: number; cancellationLeadMinutes: number;
}
export interface Interval { occupiedFrom: string; occupiedUntil: string }
export interface Slot { startsAt: string; endsAt: string; label: string; state: 'free' | 'blocked' }

export const STEP_MINUTES = 30;
const minutes = (hhmm: string) => {
  const match = /^(\d{2}):(\d{2})(?::\d{2})?$/.exec(hhmm);
  if (!match) throw new Error('POLICY_TIME_INVALID');
  return Number(match[1]) * 60 + Number(match[2]);
};
const wall = (date: string, total: number) => `${date}T${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;

/** Todas as datas que a regra permite, de hoje até a antecedência máxima (no fuso do condomínio); a tela pagina. */
export function bookableDates(policy: Policy, timeZone: string, now = new Date()): string[] {
  const today = dateInZone(timeZone, now);
  const dates: string[] = [];
  for (let offset = 0; offset <= policy.maximumAdvanceDays; offset += 1) {
    const date = addDays(today, offset);
    if (policy.weekDays.includes(isoWeekDay(date))) dates.push(date);
  }
  return dates;
}

/**
 * Durações oferecidas: passos de 30 minutos e, quando o máximo (regra ou horário de funcionamento menos a limpeza) não
 * é múltiplo de 30, também o próprio máximo — uma área com limite de 20 minutos continua reservável.
 */
export function durations(policy: Policy, bufferMinutes: number): number[] {
  const window = minutes(policy.closesAt) - minutes(policy.opensAt) - bufferMinutes;
  const maximum = Math.min(policy.maximumDurationMinutes, window);
  const options: number[] = [];
  for (let value = STEP_MINUTES; value <= maximum; value += STEP_MINUTES) options.push(value);
  if (maximum > 0 && maximum % STEP_MINUTES !== 0) options.push(maximum);
  return options;
}

export function daySlots(input: {
  policy: Policy; bufferMinutes: number; date: string; timeZone: string; durationMinutes: number; now?: Date; blocked: Interval[];
}): Slot[] {
  const { policy, bufferMinutes, date, timeZone, durationMinutes, blocked } = input;
  const now = input.now ?? new Date();
  if (!policy.weekDays.includes(isoWeekDay(date)) || durationMinutes < 1 || durationMinutes > policy.maximumDurationMinutes) return [];
  const latest = addDays(dateInZone(timeZone, now), policy.maximumAdvanceDays);
  if (date > latest) return [];
  const opens = minutes(policy.opensAt);
  const closes = minutes(policy.closesAt);
  const earliest = now.getTime() + policy.minimumLeadMinutes * 60_000;
  const slots: Slot[] = [];
  for (let start = Math.ceil(opens / STEP_MINUTES) * STEP_MINUTES; start + durationMinutes + bufferMinutes <= closes; start += STEP_MINUTES) {
    let startsAt: string; let endsAt: string; let occupiedUntil: number;
    try {
      startsAt = toInstant(wall(date, start), timeZone);
      endsAt = toInstant(wall(date, start + durationMinutes), timeZone);
      occupiedUntil = new Date(toInstant(wall(date, start + durationMinutes + bufferMinutes), timeZone)).getTime();
    } catch { continue; } // horário inexistente ou ambíguo na mudança de fuso: não é oferecido
    const begin = new Date(startsAt).getTime();
    if (begin <= now.getTime() || begin < earliest) continue;
    // Mudança de fuso no meio do uso: o servidor recusa; a duração em relógio precisa bater com a real.
    if (occupiedUntil - begin !== (durationMinutes + bufferMinutes) * 60_000) continue;
    const taken = blocked.some((interval) => begin < new Date(interval.occupiedUntil).getTime() && occupiedUntil > new Date(interval.occupiedFrom).getTime());
    slots.push({ startsAt, endsAt, label: `${wall(date, start).slice(11)}–${wall(date, start + durationMinutes).slice(11)}`, state: taken ? 'blocked' : 'free' });
  }
  return slots;
}
