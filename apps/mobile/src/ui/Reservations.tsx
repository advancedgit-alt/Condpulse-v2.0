import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi, type Membership } from '../api/client';
import type { Amenity, CreateReservation as ReservationBody, Reservation, ReservationStatus } from '../api/reservations';
import { bookableDates, daySlots, durations, type Slot } from '../reservations/slots';
import { addDays, dateInZone, formatDay, formatTime, formatWhen, money, toInstant } from '../time';
import { colors, touch } from './theme';

/**
 * Reservas no celular (CP-024): áreas, dia, duração, horário livre, regras e taxa informativa, e o estado do pedido.
 * Pedido de área com aprovação aparece sempre como "aguardando aprovação", nunca como confirmado. Conflito (horário
 * ocupado por outra unidade entre a consulta e o envio) atualiza a disponibilidade e pede outro horário.
 * A chave de envio é a mesma ao repetir depois de resultado incerto: nada é reservado duas vezes.
 */
export type KeyFactory = () => string;
/** A chave venceu (24 h) sem resposta confirmada: o resultado continua desconhecido, mas não dá mais para repetir. */
const expiredKey = (error: unknown) => error instanceof ApiError && error.code === 'IDEMPOTENCY_KEY_EXPIRED';
const EXPIRED_TEXT = 'O prazo para confirmar o pedido anterior terminou e não dá para saber se ele foi gravado. Confira em Minhas reservas antes de fazer outro.';
/**
 * Recusa ao repetir um pedido incerto não prova que o primeiro falhou (ex.: a permissão foi retirada no meio, e a
 * autorização é conferida antes de consultar a chave): o resultado continua desconhecido.
 */
const unknownAfterRetry = (error: unknown) => `${message(error)} Não dá para saber se o pedido anterior foi gravado. Confira em Minhas reservas antes de fazer outro.`;
/** Antecedência máxima aceita pelo servidor em qualquer regra (ReservationPolicy). */
export const MAX_ADVANCE_DAYS = 366;
/**
 * Pedido de reserva com resultado ainda não confirmado, guardado fora da tela (só em memória, por conta, condomínio e
 * unidade): trocar de vínculo e voltar restaura a confirmação do mesmo pedido, com a mesma chave e o mesmo corpo.
 */
export interface PendingCreate { areaId: string; date: string; length: number; slot: Slot; intent: string; key: string; body: ReservationBody }
export type PendingStore = Map<string, PendingCreate>;
const UNCERTAIN_MESSAGE = 'Não foi possível confirmar o resultado. Tente de novo: o mesmo pedido não é gravado duas vezes.';
const DATES_PER_PAGE = 14;
const PERIOD_DAYS = 30;
const statusLabels: Record<ReservationStatus, string> = {
  CONFIRMED: 'Confirmada', PENDING_APPROVAL: 'Aguardando aprovação', HELD: 'Em retenção', REJECTED: 'Não aprovada',
  CANCELLED: 'Cancelada', EXPIRED: 'Expirada',
};
const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
const uncertain = (error: unknown) => error instanceof ApiError && error.uncertain;

function Button({ label, onPress, disabled, secondary, danger, accessibilityLabel }: {
  label: string; onPress(): void; disabled?: boolean; secondary?: boolean; danger?: boolean; accessibilityLabel?: string;
}) {
  return <Pressable accessibilityRole="button" accessibilityLabel={accessibilityLabel ?? label} accessibilityState={{ disabled: !!disabled }}
    disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, secondary && styles.secondary, danger && styles.danger, (disabled || pressed) && styles.dimmed]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{label}</Text>
  </Pressable>;
}
function Chip({ label, selected, onPress, disabled, accessibilityLabel }: { label: string; selected: boolean; onPress(): void; disabled?: boolean; accessibilityLabel?: string }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={accessibilityLabel ?? label} accessibilityState={{ selected, disabled: !!disabled }}
    disabled={disabled} onPress={onPress} style={[styles.chip, selected && styles.chipSelected, disabled && styles.chipDisabled]}>
    <Text style={[styles.chipText, selected && styles.chipTextSelected]}>{label}</Text>
  </Pressable>;
}
function Notice({ tone, children }: { tone: 'error' | 'info' | 'success' | 'warning'; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, styles[tone]]}>{children}</Text>;
}

export function Reservations({ api, accountId, membership, newKey, now = () => new Date(), pending: sharedPending }: {
  api: CondPulseApi; accountId: string; membership: Membership & { unitId: string }; newKey: KeyFactory; now?: () => Date; pending?: PendingStore;
}) {
  // Sem mapa compartilhado (testes, uso isolado), um próprio que dura enquanto a tela existir (não um novo a cada desenho).
  const [ownPending] = useState<PendingStore>(() => new Map());
  const pending = sharedPending ?? ownPending;
  const { tenantId, unitId, timeZone } = membership;
  const scope = ['private', accountId, 'tenant', tenantId, 'unit', unitId] as const;
  const amenities = useQuery({ queryKey: [...scope, 'amenities'], queryFn: ({ signal }) => api.amenities(tenantId, signal) });
  const today = dateInZone(timeZone, now());
  // Períodos de 30 dias a partir de hoje (a API aceita até 31 por consulta), até cobrir a maior antecedência permitida.
  const [period, setPeriodState] = useState(0);
  // Lista cortada pelo servidor (mais de 100 no período): continuar a partir do início da última mostrada.
  const [cursor, setCursor] = useState<string | null>(null);
  const setPeriod = (next: number) => { setCursor(null); setPeriodState(next); };
  // Navegação pelo maior limite que o servidor aceita em qualquer regra (366 dias), não pela regra atual: reservas feitas
  // antes de a gestão reduzir a antecedência continuam válidas e precisam ser alcançáveis para consulta e cancelamento.
  const lastPeriod = Math.floor(MAX_ADVANCE_DAYS / PERIOD_DAYS);
  const periodStart = addDays(today, period * PERIOD_DAYS);
  const periodEnd = addDays(periodStart, PERIOD_DAYS);
  const range = useMemo(() => ({ from: cursor ?? toInstant(`${periodStart}T00:00`, timeZone), until: toInstant(`${periodEnd}T00:00`, timeZone) }),
    [cursor, periodStart, periodEnd, timeZone]);
  const mine = useQuery({ queryKey: [...scope, 'reservations', range.from], queryFn: ({ signal }) => api.reservations(tenantId, unitId, range, signal) });
  const pendingScope = `${accountId}|${tenantId}|${unitId}`;
  const [areaId, setAreaId] = useState<string | null>(() => pending.get(pendingScope)?.areaId ?? null);
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'info'; text: string } | null>(null);
  const listed = amenities.data?.find((item) => item.amenityId === areaId) ?? null;
  const area = listed && listed.policy ? listed : null;
  const [, refresh] = useState(0);
  // Pedido incerto cuja área sumiu da lista (desativada) ou perdeu as regras: a confirmação continua, sem a área.
  const orphan = !area ? pending.get(pendingScope) ?? null : null;
  const blocked = pending.has(pendingScope);
  const names = new Map((amenities.data ?? []).map((item) => [item.amenityId, item.name]));

  return <View style={styles.section}>
    <Text accessibilityRole="header" style={styles.heading}>Reservas</Text>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {area ? <Booking api={api} scope={scope} membership={membership} area={area} newKey={newKey} now={now} pending={pending} pendingScope={pendingScope}
      close={(result) => {
        setAreaId(null);
        if (!result) return;
        setOutcome(result);
        // Leva a lista ao período da reserva recém-feita, mesmo além dos próximos 30 dias.
        const day = dateInZone(timeZone, new Date(result.startsAt));
        const offset = Math.floor((Date.parse(`${day}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / (86_400_000 * PERIOD_DAYS));
        setPeriod(Math.max(0, offset));
      }} />
      : orphan && !amenities.isPending ? <PendingConfirmation api={api} scope={scope} tenantId={tenantId} timeZone={timeZone} saved={orphan}
        settle={(result) => {
          pending.delete(pendingScope); setAreaId(null); refresh((value) => value + 1);
          if (result) setOutcome(result);
        }} />
      : amenities.isPending ? <ActivityIndicator accessibilityLabel="Carregando as áreas" />
        : amenities.isError ? <View><Notice tone="error">{message(amenities.error)}</Notice>
          <Button label="Tentar novamente" secondary onPress={() => void amenities.refetch()} /></View>
          : amenities.data.length === 0 ? <Notice tone="info">Nenhuma área disponível para reserva neste condomínio.</Notice>
            : <View accessibilityRole="list" accessibilityLabel="Áreas">{amenities.data.map((item) => {
              const ready = item.policy !== null && item.bookingState !== 'POLICY_NOT_CONFIGURED';
              return <View key={item.amenityId} style={styles.card}>
                <Text style={styles.cardTitle}>{item.name}</Text>
                <Text style={styles.body}>{`Até ${item.capacity} pessoas · taxa informativa ${money(item.feeCents)}${item.approvalRequired ? ' · sujeita a aprovação' : ''}`}</Text>
                {ready ? <Button label="Ver horários" accessibilityLabel={`Ver horários de ${item.name}`} disabled={blocked}
                  onPress={() => { setOutcome(null); setAreaId(item.amenityId); }} />
                  : <Text style={styles.body}>A gestão ainda precisa configurar as regras desta área.</Text>}
              </View>;
            })}</View>}
    <Text accessibilityRole="header" style={styles.subheading}>Minhas reservas</Text>
    <Text style={styles.small}>{`De ${formatDay(periodStart, timeZone)} a ${formatDay(addDays(periodEnd, -1), timeZone)}`}</Text>
    <View style={styles.row}>
      <Chip label="Período anterior" selected={false} disabled={period === 0} onPress={() => setPeriod(period - 1)} />
      <Chip label="Próximo período" selected={false} disabled={period >= lastPeriod} onPress={() => setPeriod(period + 1)} />
    </View>
    {mine.isPending ? <ActivityIndicator accessibilityLabel="Carregando suas reservas" />
      : mine.isError ? <View><Notice tone="error">{message(mine.error)}</Notice>
        <Button label="Atualizar reservas" secondary onPress={() => void mine.refetch()} /></View>
        : mine.data.reservations.length === 0 ? <Text style={styles.body}>Nenhuma reserva da unidade neste período.</Text>
          : <View accessibilityRole="list" accessibilityLabel="Reservas da unidade">{mine.data.reservations.map((item) =>
            <ReservationCard key={item.reservationId} api={api} scope={scope} membership={membership} reservation={item}
              areaName={names.get(item.amenityId) ?? 'Área'} newKey={newKey} now={now} />)}</View>}
    {mine.data?.truncated ? (() => {
      const last = mine.data.reservations.at(-1)?.startsAt ?? null;
      // Sem avanço possível (a última mostrada começa no início da consulta ou antes): não oferecer botão que repete a mesma página.
      const next = last !== null && Date.parse(last) > Date.parse(range.from) ? last : null;
      return <View>
        <Notice tone="info">{next ? 'A lista mostra só parte das reservas do período. Continue para ver as seguintes.'
          : 'A lista mostra só parte das reservas do período e não foi possível avançar daqui. Consulte o portal web.'}</Notice>
        {next ? <Button label="Ver as reservas seguintes" secondary onPress={() => setCursor(next)} /> : null}
      </View>;
    })() : null}
    {cursor ? <Button label="Voltar ao início do período" secondary onPress={() => setCursor(null)} /> : null}
  </View>;
}

/** Confirmação de pedido incerto sem a área na lista: mesma chave e mesmo corpo; nenhum outro pedido antes de resolver. */
function PendingConfirmation({ api, scope, tenantId, timeZone, saved, settle }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; timeZone: string; saved: PendingCreate;
  settle(result: { tone: 'success' | 'info'; text: string } | null): void;
}) {
  const queries = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  async function confirm() {
    if (busy) return;
    setBusy(true); setFailure(null);
    try {
      const result = await api.createReservation(tenantId, saved.body, saved.key);
      void queries.invalidateQueries({ queryKey: [...scope] });
      const when = `${formatWhen(result.reservation.startsAt, timeZone)} às ${formatTime(result.reservation.endsAt, timeZone)}`;
      settle({ tone: result.reservation.status === 'CONFIRMED' ? 'success' : 'info',
        text: `Pedido confirmado pelo servidor (${statusLabels[result.reservation.status]}): ${when}.` });
    } catch (error) {
      if (uncertain(error)) setFailure(error);
      else {
        void queries.invalidateQueries({ queryKey: [...scope] });
        settle({ tone: 'info', text: expiredKey(error) ? EXPIRED_TEXT : unknownAfterRetry(error) });
      }
    } finally { setBusy(false); }
  }
  return <View style={styles.card} accessibilityLabel="Pedido com resultado incerto">
    <Notice tone="warning">{`Há um pedido de reserva para ${formatWhen(saved.body.startsAt, timeZone)} com resultado ainda não confirmado, e a área não aparece mais na lista. Confirme o mesmo pedido antes de fazer outro.`}</Notice>
    {failure ? <Notice tone="warning">{message(failure)}</Notice> : null}
    <Button label={busy ? 'Enviando…' : 'Tentar confirmar o mesmo pedido'} disabled={busy} onPress={() => void confirm()} />
  </View>;
}

function Booking({ api, scope, membership, area, newKey, now, close, pending, pendingScope }: {
  api: CondPulseApi; scope: readonly unknown[]; membership: Membership & { unitId: string }; area: Amenity; newKey: KeyFactory;
  pending: PendingStore; pendingScope: string;
  now: () => Date; close(result: { tone: 'success' | 'info'; text: string; startsAt: string } | null): void;
}) {
  const queries = useQueryClient();
  const { tenantId, unitId, timeZone } = membership;
  const policy = area.policy!;
  const dates = useMemo(() => bookableDates(policy, timeZone, now()), [policy, timeZone, now]);
  const lengths = useMemo(() => durations(policy, area.bufferMinutes), [policy, area.bufferMinutes]);
  const saved = pending.get(pendingScope)?.areaId === area.amenityId ? pending.get(pendingScope)! : null;
  const [date, setDate] = useState(saved?.date ?? dates[0] ?? null);
  const [page, setPage] = useState(0);
  const visibleDates = dates.slice(page * DATES_PER_PAGE, (page + 1) * DATES_PER_PAGE);
  const [length, setLength] = useState(saved?.length ?? (lengths.includes(60) ? 60 : lengths[0] ?? null));
  const [slot, setSlot] = useState<Slot | null>(saved?.slot ?? null);
  // Aceite preso à versão lida: se a gestão mudar regras ou taxa enquanto a tela está aberta, o aceite cai e precisa ser refeito.
  const [acceptedVersion, setAcceptedVersion] = useState<number | null>(null);
  const accepted = acceptedVersion === area.policyVersion;
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(() => saved ? new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN', UNCERTAIN_MESSAGE, true) : null);
  // Chave presa ao horário escolhido: repetir depois de resultado incerto confirma o mesmo pedido.
  // Depois de resultado incerto, a repetição reenvia exatamente o corpo original (inclusive a versão aceita).
  const [pendingKey, setPendingKey] = useState<{ intent: string; key: string; body: ReservationBody } | null>(
    saved ? { intent: saved.intent, key: saved.key, body: saved.body } : null);
  const range = date ? { from: toInstant(`${date}T00:00`, timeZone), until: toInstant(`${addDays(date, 1)}T00:00`, timeZone) } : null;
  const availability = useQuery({ enabled: range !== null, queryKey: [...scope, 'availability', area.amenityId, date],
    queryFn: ({ signal }) => api.availability(tenantId, unitId, area.amenityId, range!, signal) });
  const slots = date && length && availability.data
    ? daySlots({ policy, bufferMinutes: area.bufferMinutes, date, timeZone, durationMinutes: length, now: now(), blocked: availability.data.blockedIntervals })
    : [];
  const locked = pendingKey !== null && uncertain(failure);

  const versionChanged = acceptedVersion !== null && !accepted && !locked;
  function choose(next: Slot | null) { setSlot(next); setAcceptedVersion(null); setFailure(null); }
  async function submit() {
    if (busy || !slot) return;
    let attempt: { intent: string; key: string; body: ReservationBody };
    const retry = locked && pendingKey !== null;
    if (locked && pendingKey) attempt = pendingKey;
    else {
      if (!accepted) return;
      const intent = `${area.amenityId}|${slot.startsAt}|${slot.endsAt}|${acceptedVersion}`;
      const body = { unitId, amenityId: area.amenityId, startsAt: slot.startsAt, endsAt: slot.endsAt, acceptedPolicyVersion: acceptedVersion! };
      attempt = { intent, key: pendingKey?.intent === intent ? pendingKey.key : newKey(), body };
    }
    setPendingKey(attempt); setBusy(true); setFailure(null);
    // Guardado antes do envio: se a pessoa trocar de vínculo no meio, a resposta incerta continua recuperável.
    pending.set(pendingScope, { areaId: area.amenityId, date: date!, length: length!, slot, ...attempt });
    try {
      const result = await api.createReservation(tenantId, attempt.body, attempt.key);
      pending.delete(pendingScope);
      setPendingKey(null);
      void queries.invalidateQueries({ queryKey: [...scope] });
      const when = `${formatWhen(result.reservation.startsAt, timeZone)} às ${formatTime(result.reservation.endsAt, timeZone)}`;
      const startsAt = result.reservation.startsAt;
      close(result.reservation.status === 'CONFIRMED'
        ? { tone: 'success', text: `Reserva confirmada: ${area.name}, ${when}.`, startsAt }
        : result.reservation.status === 'PENDING_APPROVAL'
          ? { tone: 'info', text: `Pedido enviado: ${area.name}, ${when}. Aguardando aprovação da gestão — ainda não é uma reserva confirmada.`, startsAt }
          : { tone: 'info', text: `Pedido registrado com a situação "${statusLabels[result.reservation.status]}". Confira em Minhas reservas.`, startsAt });
    } catch (error) {
      if (!uncertain(error) && (expiredKey(error) || retry)) {
        // Sem como saber o resultado do primeiro envio: fecha a escolha e leva a pessoa a conferir a lista, sem dizer "não gravado".
        pending.delete(pendingScope); setPendingKey(null);
        void queries.invalidateQueries({ queryKey: [...scope] });
        close({ tone: 'info', text: expiredKey(error) ? EXPIRED_TEXT : unknownAfterRetry(error), startsAt: attempt.body.startsAt });
        return;
      }
      setFailure(error);
      if (!uncertain(error)) pending.delete(pendingScope);
      if (error instanceof ApiError && !error.uncertain) setPendingKey(null);
      if (error instanceof ApiError && ['RESERVATION_CONFLICT', 'RESERVATION_WINDOW_INVALID', 'AMENITY_POLICY_CHANGED'].includes(error.code)) {
        // Outro horário: a disponibilidade e as regras são consultadas de novo e a escolha é desfeita.
        setSlot(null); setAcceptedVersion(null);
        void availability.refetch();
        void queries.invalidateQueries({ queryKey: [...scope, 'amenities'] });
      }
    } finally { setBusy(false); }
  }

  return <View style={styles.card}>
    <Text accessibilityRole="header" style={styles.cardTitle}>{area.name}</Text>
    <View accessibilityLabel="Regras da área" style={styles.rules}>
      <Text style={styles.body}>{`Horário: ${policy.opensAt.slice(0, 5)}–${policy.closesAt.slice(0, 5)} · até ${policy.maximumDurationMinutes} min por reserva`}</Text>
      <Text style={styles.body}>{`Antecedência: mínimo ${policy.minimumLeadMinutes} min, até ${policy.maximumAdvanceDays} dias`}</Text>
      <Text style={styles.body}>{`Limpeza depois do uso: ${area.bufferMinutes} min · taxa informativa ${money(area.feeCents)} (cobrança fora do aplicativo)`}</Text>
      <Text style={styles.body}>{area.approvalRequired ? `Sujeita a aprovação da gestão. O pedido pode ser retirado até o início; confirmada, cancela até ${policy.cancellationLeadMinutes} min antes.`
        : `Cancelamento pelo autor até ${policy.cancellationLeadMinutes} min antes do início.`}</Text>
    </View>
    {dates.length === 0 || lengths.length === 0 ? <Notice tone="info">Não há dias ou durações disponíveis pelas regras atuais.</Notice> : <>
      <Text style={styles.label}>Dia</Text>
      <View style={styles.row}>{visibleDates.map((item) => <Chip key={item} label={formatDay(item, timeZone)} selected={item === date}
        disabled={busy || locked} onPress={() => { setDate(item); choose(null); }} />)}</View>
      {dates.length > DATES_PER_PAGE ? <View style={styles.row}>
        <Chip label="Dias anteriores" selected={false} disabled={page === 0 || busy || locked} onPress={() => setPage(page - 1)} />
        <Chip label="Próximos dias" selected={false} disabled={(page + 1) * DATES_PER_PAGE >= dates.length || busy || locked}
          onPress={() => setPage(page + 1)} />
      </View> : null}
      <Text style={styles.label}>Duração</Text>
      <View style={styles.row}>{lengths.map((item) => <Chip key={item} label={item % 60 === 0 ? `${item / 60} h` : `${item} min`}
        selected={item === length} disabled={busy || locked} onPress={() => { setLength(item); choose(null); }} />)}</View>
      <Text style={styles.label}>Horário</Text>
      {availability.isPending ? <ActivityIndicator accessibilityLabel="Consultando a ocupação" />
        : availability.isError ? <View><Notice tone="error">{message(availability.error)}</Notice>
          <Button label="Consultar de novo" secondary onPress={() => void availability.refetch()} /></View>
          : slots.length === 0 ? <Text style={styles.body}>Nenhum horário possível neste dia com esta duração.</Text>
            : <View accessibilityRole="list" accessibilityLabel="Horários" style={styles.row}>{slots.map((item) =>
              <Chip key={item.startsAt} label={item.state === 'blocked' ? `${item.label} ocupado` : item.label}
                accessibilityLabel={item.state === 'blocked' ? `${item.label} ocupado` : `Horário ${item.label}`}
                selected={slot?.startsAt === item.startsAt && slot.endsAt === item.endsAt} disabled={item.state === 'blocked' || busy || locked}
                onPress={() => choose(item)} />)}</View>}
      {availability.data ? <Text style={styles.small}>{`Ocupação consultada às ${formatTime(availability.data.observedAt, timeZone)}. O servidor confere de novo ao enviar.`}</Text> : null}
    </>}
    {slot ? <View style={styles.confirm} accessibilityLabel="Confirmação da reserva">
      <Text style={styles.body}>{`${formatDay(date!, timeZone)}, ${slot.label} · ${area.approvalRequired ? 'vai como pedido para aprovação' : 'confirmada se o horário continuar livre'}`}</Text>
      <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: accepted, disabled: busy || locked }} disabled={busy || locked}
        accessibilityLabel={`Li e aceito as regras da área (versão ${area.policyVersion})`} onPress={() => setAcceptedVersion(accepted ? null : area.policyVersion)} style={styles.check}>
        <Text style={styles.checkBox}>{accepted ? '☑' : '☐'}</Text>
        <Text style={styles.body}>{`Li e aceito as regras da área (versão ${area.policyVersion})`}</Text>
      </Pressable>
      <Button label={busy ? 'Enviando…' : locked ? 'Tentar confirmar o mesmo pedido' : area.approvalRequired ? 'Solicitar reserva' : 'Reservar'}
        disabled={(!accepted && !locked) || busy} onPress={() => void submit()} />
      {versionChanged ? <Notice tone="warning">{`As regras desta área mudaram (agora versão ${area.policyVersion}). Leia de novo e marque o aceite para continuar.`}</Notice> : null}
    </View> : null}
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{message(failure)}</Notice> : null}
    <Button label="Voltar às áreas" secondary disabled={busy || locked} onPress={() => close(null)} />
  </View>;
}

function ReservationCard({ api, scope, membership, reservation, areaName, newKey, now }: {
  api: CondPulseApi; scope: readonly unknown[]; membership: Membership & { unitId: string }; reservation: Reservation; areaName: string;
  newKey: KeyFactory; now: () => Date;
}) {
  const queries = useQueryClient();
  const { tenantId, unitId, timeZone } = membership;
  const pending = reservation.status === 'PENDING_APPROVAL';
  const canAsk = (reservation.status === 'CONFIRMED' || pending) && new Date(reservation.startsAt).getTime() > now().getTime();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [key, setKey] = useState<string | null>(null);
  async function cancel() {
    if (busy) return;
    const operation = key ?? newKey();
    setKey(operation); setBusy(true); setFailure(null);
    try {
      await api.cancelReservation(tenantId, reservation.reservationId, { unitId, expectedVersion: reservation.version }, operation);
      setKey(null); setConfirming(false);
    } catch (error) {
      setFailure(error);
      if (!uncertain(error)) setKey(null);
    } finally {
      setBusy(false);
      // Reservas e ocupação do contexto: o horário liberado volta a aparecer livre em quem estiver escolhendo.
      void queries.invalidateQueries({ queryKey: [...scope] });
    }
  }
  const reason = ['REJECTED', 'CANCELLED', 'EXPIRED'].includes(reservation.status) ? reservation.decisionReason : null;
  return <View style={styles.card} accessibilityLabel={`${areaName}, ${statusLabels[reservation.status]}`}>
    <Text style={styles.cardTitle}>{areaName}</Text>
    <Text style={[styles.status, reservation.status === 'CONFIRMED' ? styles.statusOk : pending ? styles.statusPending : styles.statusNeutral]}>
      {statusLabels[reservation.status]}</Text>
    <Text style={styles.body}>{`${formatWhen(reservation.startsAt, timeZone)} até ${formatTime(reservation.endsAt, timeZone)} · regras v${reservation.acceptedPolicyVersion}`}</Text>
    {pending ? <Text style={styles.body}>Aguardando decisão da gestão. O horário fica separado para a unidade, mas ainda não é uma reserva confirmada.</Text> : null}
    {reason ? <Text style={styles.body}>{`Motivo: ${reason}`}</Text> : null}
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{message(failure)}</Notice> : null}
    {canAsk ? confirming
      ? <View><Text style={styles.body}>{pending ? 'Retirar este pedido? O horário será liberado para outras unidades.' : 'Cancelar esta reserva? Só o autor pode cancelar, dentro do prazo das regras.'}</Text>
        <Button danger label={busy ? 'Enviando…' : pending ? 'Confirmar retirada' : 'Confirmar cancelamento'} disabled={busy} onPress={() => void cancel()} />
        <Button secondary label={pending ? 'Manter pedido' : 'Manter reserva'} disabled={busy || key !== null} onPress={() => setConfirming(false)} /></View>
      : <Button secondary label={pending ? 'Retirar pedido' : 'Cancelar reserva'}
        accessibilityLabel={`${pending ? 'Retirar pedido' : 'Cancelar reserva'} ${formatWhen(reservation.startsAt, timeZone)}`}
        onPress={() => { setFailure(null); setConfirming(true); }} /> : null}
  </View>;
}

const styles = StyleSheet.create({
  section: { gap: 12, marginTop: 8 },
  heading: { fontSize: 22, fontWeight: '700', color: colors.navy },
  subheading: { fontSize: 18, fontWeight: '700', color: colors.navy, marginTop: 12 },
  card: { padding: 16, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface, gap: 8, marginBottom: 10 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
  body: { fontSize: 15, color: colors.text, flexShrink: 1 },
  small: { fontSize: 13, color: colors.muted },
  label: { fontSize: 14, fontWeight: '600', color: colors.text, marginTop: 4 },
  rules: { gap: 4, padding: 12, borderRadius: 10, backgroundColor: colors.infoBg },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { minHeight: touch, minWidth: touch, paddingHorizontal: 12, borderRadius: 10, borderWidth: 1, borderColor: colors.line, justifyContent: 'center', backgroundColor: colors.surface },
  chipSelected: { borderColor: colors.teal, borderWidth: 2, backgroundColor: '#e8f4f3' },
  chipDisabled: { opacity: 0.45 },
  chipText: { fontSize: 15, color: colors.text },
  chipTextSelected: { fontWeight: '700', color: colors.navy },
  confirm: { gap: 8, paddingTop: 8, borderTopWidth: 1, borderTopColor: colors.line },
  check: { minHeight: touch, flexDirection: 'row', alignItems: 'center', gap: 10 },
  checkBox: { fontSize: 22, color: colors.teal },
  button: { minHeight: touch, borderRadius: 10, backgroundColor: colors.teal, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  secondary: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.line },
  danger: { backgroundColor: colors.error },
  dimmed: { opacity: 0.6 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  secondaryText: { color: colors.navy },
  notice: { padding: 12, borderRadius: 10, fontSize: 15 },
  error: { backgroundColor: colors.errorBg, color: colors.error },
  warning: { backgroundColor: '#fff4e0', color: '#7a4b00' },
  info: { backgroundColor: colors.infoBg, color: colors.text },
  success: { backgroundColor: '#e8f4ed', color: '#226644' },
  status: { alignSelf: 'flex-start', paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12, fontSize: 13, fontWeight: '700', overflow: 'hidden' },
  statusOk: { backgroundColor: '#e8f4ed', color: '#226644' },
  statusPending: { backgroundColor: '#fff4e0', color: '#7a4b00' },
  statusNeutral: { backgroundColor: '#eef2f6', color: '#647789' },
});
