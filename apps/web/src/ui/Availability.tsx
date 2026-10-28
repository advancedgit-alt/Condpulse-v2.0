import { useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Amenity } from '../api/types';
import { scopeKey, type Scope } from './Operations';
import { ErrorState, Loading, Notice } from './primitives';
import { availabilityDayRange } from './availabilityWindow';
import { addDays, dateInZone, formatWhen } from './dateTime';
import './Availability.css';

export function useAmenityAvailability(scope: Scope, area: Amenity, timeZone: string, date: string, validating: boolean) {
  const range = useMemo(() => {
    try { return { value: availabilityDayRange(date, timeZone), error: '' }; }
    catch (error) { return { value: null, error: error instanceof Error ? error.message : 'Informe uma data válida.' }; }
  }, [date, timeZone]);
  const query = useQuery({
    queryKey: [...scopeKey(scope), 'availability', area.amenityId, area.policyVersion, area.bufferMinutes,
      timeZone, range.value?.from ?? date, range.value?.until ?? ''],
    queryFn: ({ signal }) => api.amenityAvailability(scope.tenantId, scope.unitId, area.amenityId, range.value!, timeZone, signal),
    enabled: !validating && range.value !== null,
    staleTime: 30_000,
  });
  useEffect(() => {
    if (query.error instanceof ApiError && [403, 404].includes(query.error.status)) {
      window.dispatchEvent(new CustomEvent('condpulse:context-denied', { detail: { scope, error: query.error } }));
    }
  }, [query.error, scope]);
  return { query, range, current: query.isSuccess && !query.isFetching && !query.isStale && range.value !== null };
}

export function AvailabilityPanel({ availability, area, timeZone, date, onDateChange, disabled }: {
  availability: ReturnType<typeof useAmenityAvailability>; area: Amenity; timeZone: string;
  date: string; onDateChange(date: string): void; disabled: boolean;
}) {
  const { query, range, current } = availability;
  const snapshot = query.data;
  return <section className="availability-panel" aria-labelledby="availability-title">
    <div className="section-heading"><div><h4 id="availability-title">Disponibilidade da área</h4>
      <p>Intervalos bloqueados, com a limpeza já incluída. A consulta não garante a reserva.</p></div>
      <button type="button" className="button button-secondary button-compact" disabled={disabled || query.isFetching || range.value === null}
        onClick={() => void query.refetch()}>Atualizar disponibilidade</button></div>
    <div className="reservation-filter"><label htmlFor="availability-date">Consultar o dia</label>
      <input id="availability-date" type="date" min={dateInZone(timeZone)} max={addDays(dateInZone(timeZone), area.policy!.maximumAdvanceDays)}
        value={date} onChange={event => onDateChange(event.target.value)} disabled={disabled} />
      <span>Horários em {timeZone}</span></div>
    {range.error ? <Notice tone="error">{range.error}</Notice> : query.isPending ? <Loading label="Consultando a disponibilidade da área." /> : null}
    {query.isFetching && !query.isPending ? <Notice>Atualizando a disponibilidade. Aguarde a nova consulta antes de confirmar.</Notice> : null}
    {query.isError ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : null}
    {snapshot ? <>
      {!current ? <Notice tone="warning">Consulta anterior. Atualize a disponibilidade antes de confirmar; os intervalos podem ter mudado.</Notice> : null}
      <p className="availability-observed">Consulta registrada em {formatWhen(snapshot.observedAt, timeZone)}.</p>
      {snapshot.blockedIntervals.length === 0
        ? <p className="availability-empty">Nenhum intervalo bloqueado nesta consulta. As regras e a disponibilidade serão conferidas novamente no envio.</p>
        : <ul className="availability-intervals" aria-label="Intervalos bloqueados da área">{snapshot.blockedIntervals.map(interval =>
          <li key={`${interval.occupiedFrom}:${interval.occupiedUntil}`}><strong>Bloqueado</strong><span>
            {formatWhen(interval.occupiedFrom, timeZone)} até {formatWhen(interval.occupiedUntil, timeZone)}
          </span></li>)}</ul>}
    </> : null}
  </section>;
}
