import { useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi, type Membership } from '../api/client';
import type { Amenity, Reservation, ReservationStatus } from '../api/reservations';
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
const DATES_PER_PAGE = 14;
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

export function Reservations({ api, accountId, membership, newKey, now = () => new Date() }: {
  api: CondPulseApi; accountId: string; membership: Membership & { unitId: string }; newKey: KeyFactory; now?: () => Date;
}) {
  const { tenantId, unitId, timeZone } = membership;
  const scope = ['private', accountId, 'tenant', tenantId, 'unit', unitId] as const;
  const amenities = useQuery({ queryKey: [...scope, 'amenities'], queryFn: ({ signal }) => api.amenities(tenantId, signal) });
  const today = dateInZone(timeZone, now());
  const range = useMemo(() => ({ from: toInstant(`${today}T00:00`, timeZone), until: toInstant(`${addDays(today, 30)}T00:00`, timeZone) }), [today, timeZone]);
  const mine = useQuery({ queryKey: [...scope, 'reservations', range.from], queryFn: ({ signal }) => api.reservations(tenantId, unitId, range, signal) });
  const [areaId, setAreaId] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'info'; text: string } | null>(null);
  const area = amenities.data?.find((item) => item.amenityId === areaId) ?? null;
  const names = new Map((amenities.data ?? []).map((item) => [item.amenityId, item.name]));

  return <View style={styles.section}>
    <Text accessibilityRole="header" style={styles.heading}>Reservas</Text>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {area ? <Booking api={api} scope={scope} membership={membership} area={area} newKey={newKey} now={now}
      close={(result) => { setAreaId(null); if (result) setOutcome(result); }} />
      : amenities.isPending ? <ActivityIndicator accessibilityLabel="Carregando as áreas" />
        : amenities.isError ? <View><Notice tone="error">{message(amenities.error)}</Notice>
          <Button label="Tentar novamente" secondary onPress={() => void amenities.refetch()} /></View>
          : amenities.data.length === 0 ? <Notice tone="info">Nenhuma área disponível para reserva neste condomínio.</Notice>
            : <View accessibilityRole="list" accessibilityLabel="Áreas">{amenities.data.map((item) => {
              const ready = item.policy !== null && item.bookingState !== 'POLICY_NOT_CONFIGURED';
              return <View key={item.amenityId} style={styles.card}>
                <Text style={styles.cardTitle}>{item.name}</Text>
                <Text style={styles.body}>{`Até ${item.capacity} pessoas · taxa informativa ${money(item.feeCents)}${item.approvalRequired ? ' · sujeita a aprovação' : ''}`}</Text>
                {ready ? <Button label="Ver horários" accessibilityLabel={`Ver horários de ${item.name}`} onPress={() => { setOutcome(null); setAreaId(item.amenityId); }} />
                  : <Text style={styles.body}>A gestão ainda precisa configurar as regras desta área.</Text>}
              </View>;
            })}</View>}
    <Text accessibilityRole="header" style={styles.subheading}>Minhas reservas (próximos 30 dias)</Text>
    {mine.isPending ? <ActivityIndicator accessibilityLabel="Carregando suas reservas" />
      : mine.isError ? <View><Notice tone="error">{message(mine.error)}</Notice>
        <Button label="Atualizar reservas" secondary onPress={() => void mine.refetch()} /></View>
        : mine.data.reservations.length === 0 ? <Text style={styles.body}>Nenhuma reserva da unidade neste período.</Text>
          : <View accessibilityRole="list" accessibilityLabel="Reservas da unidade">{mine.data.reservations.map((item) =>
            <ReservationCard key={item.reservationId} api={api} scope={scope} membership={membership} reservation={item}
              areaName={names.get(item.amenityId) ?? 'Área'} newKey={newKey} now={now} />)}</View>}
    {mine.data?.truncated ? <Notice tone="info">A lista mostra só parte das reservas do período.</Notice> : null}
  </View>;
}

function Booking({ api, scope, membership, area, newKey, now, close }: {
  api: CondPulseApi; scope: readonly unknown[]; membership: Membership & { unitId: string }; area: Amenity; newKey: KeyFactory;
  now: () => Date; close(result: { tone: 'success' | 'info'; text: string } | null): void;
}) {
  const queries = useQueryClient();
  const { tenantId, unitId, timeZone } = membership;
  const policy = area.policy!;
  const dates = useMemo(() => bookableDates(policy, timeZone, now()), [policy, timeZone, now]);
  const lengths = useMemo(() => durations(policy, area.bufferMinutes), [policy, area.bufferMinutes]);
  const [date, setDate] = useState(dates[0] ?? null);
  const [page, setPage] = useState(0);
  const visibleDates = dates.slice(page * DATES_PER_PAGE, (page + 1) * DATES_PER_PAGE);
  const [length, setLength] = useState(lengths.includes(60) ? 60 : lengths[0] ?? null);
  const [slot, setSlot] = useState<Slot | null>(null);
  const [accepted, setAccepted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  // Chave presa ao horário escolhido: repetir depois de resultado incerto confirma o mesmo pedido.
  const [pendingKey, setPendingKey] = useState<{ intent: string; key: string } | null>(null);
  const range = date ? { from: toInstant(`${date}T00:00`, timeZone), until: toInstant(`${addDays(date, 1)}T00:00`, timeZone) } : null;
  const availability = useQuery({ enabled: range !== null, queryKey: [...scope, 'availability', area.amenityId, date],
    queryFn: ({ signal }) => api.availability(tenantId, unitId, area.amenityId, range!, signal) });
  const slots = date && length && availability.data
    ? daySlots({ policy, bufferMinutes: area.bufferMinutes, date, timeZone, durationMinutes: length, now: now(), blocked: availability.data.blockedIntervals })
    : [];
  const locked = pendingKey !== null && uncertain(failure);

  function choose(next: Slot | null) { setSlot(next); setAccepted(false); setFailure(null); }
  async function submit() {
    if (!slot || !accepted || busy) return;
    const intent = `${area.amenityId}|${slot.startsAt}|${slot.endsAt}|${area.policyVersion}`;
    const key = pendingKey?.intent === intent ? pendingKey.key : newKey();
    setPendingKey({ intent, key }); setBusy(true); setFailure(null);
    try {
      const result = await api.createReservation(tenantId, { unitId, amenityId: area.amenityId, startsAt: slot.startsAt, endsAt: slot.endsAt,
        acceptedPolicyVersion: area.policyVersion }, key);
      setPendingKey(null);
      void queries.invalidateQueries({ queryKey: [...scope] });
      const when = `${formatWhen(result.reservation.startsAt, timeZone)} às ${formatTime(result.reservation.endsAt, timeZone)}`;
      close(result.reservation.status === 'CONFIRMED'
        ? { tone: 'success', text: `Reserva confirmada: ${area.name}, ${when}.` }
        : result.reservation.status === 'PENDING_APPROVAL'
          ? { tone: 'info', text: `Pedido enviado: ${area.name}, ${when}. Aguardando aprovação da gestão — ainda não é uma reserva confirmada.` }
          : { tone: 'info', text: `Pedido registrado com a situação "${statusLabels[result.reservation.status]}". Confira em Minhas reservas.` });
    } catch (error) {
      setFailure(error);
      if (error instanceof ApiError && !error.uncertain) setPendingKey(null);
      if (error instanceof ApiError && ['RESERVATION_CONFLICT', 'RESERVATION_WINDOW_INVALID', 'AMENITY_POLICY_CHANGED'].includes(error.code)) {
        // Outro horário: a disponibilidade e as regras são consultadas de novo e a escolha é desfeita.
        setSlot(null); setAccepted(false);
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
        accessibilityLabel={`Li e aceito as regras da área (versão ${area.policyVersion})`} onPress={() => setAccepted(!accepted)} style={styles.check}>
        <Text style={styles.checkBox}>{accepted ? '☑' : '☐'}</Text>
        <Text style={styles.body}>{`Li e aceito as regras da área (versão ${area.policyVersion})`}</Text>
      </Pressable>
      <Button label={busy ? 'Enviando…' : locked ? 'Tentar confirmar o mesmo pedido' : area.approvalRequired ? 'Solicitar reserva' : 'Reservar'}
        disabled={!accepted || busy} onPress={() => void submit()} />
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
