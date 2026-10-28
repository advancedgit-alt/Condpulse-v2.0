import type { AmenityAvailability } from '../api/types';
import { instantMicros } from '../api/instant';
import { addDays, toInstant } from './dateTime';

export function availabilityDayRange(date: string, timeZone: string): { from: string; until: string } {
  return { from: toInstant(`${date}T00:00`, timeZone), until: toInstant(`${addDays(date, 1)}T00:00`, timeZone) };
}

export type SelectionAvailability = 'incomplete' | 'outside' | 'blocked' | 'unblocked';

/** A limpeza da nova intenção participa da comparação de intervalos [início,fim). */
export function selectionAvailability(snapshot: AmenityAvailability, startsAt: string, endsAt: string,
  bufferMinutes: number): SelectionAvailability {
  const start = instantMicros(startsAt); const end = instantMicros(endsAt);
  const from = instantMicros(snapshot.from); const until = instantMicros(snapshot.until);
  if (start === null || end === null || from === null || until === null || start >= end
      || !Number.isSafeInteger(bufferMinutes) || bufferMinutes < 0) return 'incomplete';
  const occupiedUntil = end + BigInt(bufferMinutes) * 60_000_000n;
  if (start < from || occupiedUntil > until) return 'outside';
  return snapshot.blockedIntervals.some(interval => {
    const blockedFrom = instantMicros(interval.occupiedFrom); const blockedUntil = instantMicros(interval.occupiedUntil);
    return blockedFrom !== null && blockedUntil !== null && start < blockedUntil && occupiedUntil > blockedFrom;
  }) ? 'blocked' : 'unblocked';
}
