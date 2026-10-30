import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi } from '../api/client';
import type { JoinWaitlist as JoinBody, WaitlistEntry } from '../api/waitlist';
import { formatTime, formatWhen } from '../time';
import { colors, touch } from './theme';

/**
 * Fila de espera no celular (CP-040), como na web. Quando um período ocupado é liberado, o primeiro da fila recebe a vaga
 * por até 15 minutos: só o servidor diz se a oferta ainda vale. Aceitar e entrar usam chave de operação; com resultado
 * incerto, a próxima tentativa usa a mesma chave (nada é gravado duas vezes) até a unidade conferir.
 */
type Tone = 'error' | 'info' | 'success' | 'warning';
const labels: Record<WaitlistEntry['state'], string> = {
  WAITING: 'Na fila', OFFERED: 'Vaga oferecida', ACCEPTED: 'Oferta aceita', LEFT: 'Saiu da fila', EXPIRED: 'Oferta vencida', CLOSED: 'Período encerrado',
};
const open = (entry: WaitlistEntry) => entry.state === 'WAITING' || entry.state === 'OFFERED';
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
function Notice({ tone, children }: { tone: Tone; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, styles[tone]]}>{children}</Text>;
}

export function WaitlistSection({ api, scope, tenantId, unitId, timeZone, names, newKey, now }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; unitId: string; timeZone: string; names: Map<string, string>;
  newKey: () => string; now: () => Date;
}) {
  const entries = useQuery({ queryKey: [...scope, 'waitlist'], queryFn: ({ signal }) => api.unitWaitlist(tenantId, unitId, signal),
    // Oferta tem prazo curto: enquanto houver entrada aberta, a fila é consultada de novo a cada 30 segundos.
    refetchInterval: (query) => query.state.data?.entries.some(open) ? 30_000 : false });
  const [outcome, setOutcome] = useState<{ tone: Tone; text: string } | null>(null);
  return <View style={styles.section}>
    <Text accessibilityRole="header" style={styles.subheading}>Fila de espera</Text>
    <Text style={styles.small}>Quando um período ocupado é liberado, o primeiro da fila recebe a vaga por até 15 minutos. Sem aceite no prazo, a vez passa ao próximo.</Text>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {entries.isPending ? <ActivityIndicator accessibilityLabel="Consultando a fila de espera" />
      : entries.isError && !entries.data ? <View><Notice tone="error">{message(entries.error)}</Notice>
        <Button label="Atualizar fila" secondary onPress={() => void entries.refetch()} /></View>
        : entries.data.entries.length === 0
          ? <Text style={styles.body}>Nenhuma fila de espera. Num horário ocupado, você pode entrar na fila e receber a vaga se ela for liberada.</Text>
          : <View accessibilityRole="list" accessibilityLabel="Entradas da fila de espera">{entries.data.entries.map((entry) =>
            <WaitlistCard key={entry.waitlistId} api={api} scope={scope} tenantId={tenantId} unitId={unitId} timeZone={timeZone} entry={entry}
              area={names.get(entry.amenityId) ?? 'Área da reserva'} newKey={newKey} now={now} onOutcome={setOutcome} />)}</View>}
    {entries.data?.truncated ? <Notice tone="warning">A lista mostra as 50 entradas mais relevantes.</Notice> : null}
    {entries.data ? <Button label={entries.isFetching ? 'Atualizando…' : 'Atualizar fila'} secondary disabled={entries.isFetching}
      onPress={() => void entries.refetch()} /> : null}
  </View>;
}

function WaitlistCard({ api, scope, tenantId, unitId, timeZone, entry, area, newKey, now, onOutcome }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; unitId: string; timeZone: string; entry: WaitlistEntry; area: string;
  newKey: () => string; now: () => Date; onOutcome(value: { tone: Tone; text: string }): void;
}) {
  const queries = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  // Chave do aceite presa à oferta lida; mantida enquanto o resultado for incerto.
  const [held, setHeld] = useState<{ intent: string; key: string } | null>(null);
  const refresh = () => queries.invalidateQueries({ queryKey: [...scope] });
  const offered = entry.state === 'OFFERED';
  const expired = offered && entry.offerExpiresAt !== null && Date.parse(entry.offerExpiresAt) <= now().getTime();
  async function accept() {
    if (busy) return;
    const intent = `${entry.waitlistId}:${entry.version}`;
    const key = held?.intent === intent ? held.key : newKey();
    setHeld({ intent, key }); setBusy(true); setFailure(null);
    try {
      const result = await api.acceptOffer(tenantId, entry.waitlistId, { unitId, expectedVersion: entry.version }, key);
      setHeld(null);
      onOutcome(result.reservation.status === 'PENDING_APPROVAL'
        ? { tone: 'info', text: `Oferta aceita: o pedido de ${area} foi enviado para aprovação da gestão. Ainda não é uma reserva confirmada.` }
        : result.reservation.status === 'CONFIRMED' ? { tone: 'success', text: `Reserva de ${area} confirmada pela fila de espera.` }
          : { tone: 'warning', text: 'A resposta não confirmou a reserva. Atualize e confira em Minhas reservas.' });
    } catch (error) {
      setFailure(error);
      if (!uncertain(error)) setHeld(null);
    } finally { setBusy(false); await refresh(); }
  }
  async function leave() {
    if (busy) return;
    setBusy(true); setFailure(null);
    try {
      await api.leaveWaitlist(tenantId, entry.waitlistId, { unitId, expectedVersion: entry.version });
      onOutcome({ tone: 'success', text: offered ? `Vaga de ${area} recusada. A vez passa ao próximo da fila.` : `Você saiu da fila de ${area}.` });
    } catch (error) { setFailure(error); }
    finally { setBusy(false); setConfirming(false); await refresh(); }
  }
  return <View style={styles.card} accessibilityLabel={`${area}, ${labels[entry.state]}`}>
    <Text style={styles.cardTitle}>{area}</Text>
    <Text style={[styles.status, offered || entry.state === 'ACCEPTED' ? styles.statusOk : entry.state === 'WAITING' ? styles.statusPending : styles.statusNeutral]}>
      {labels[entry.state]}</Text>
    <Text style={styles.body}>{`${formatWhen(entry.startsAt, timeZone)} até ${formatTime(entry.endsAt, timeZone)} · regras v${entry.acceptedPolicyVersion}`}</Text>
    {entry.state === 'WAITING' && entry.position !== null ? <Text style={styles.body}>{`Posição na fila: ${entry.position}.`}</Text> : null}
    {offered && entry.offerExpiresAt ? <Text style={styles.body}>{expired ? 'O prazo desta oferta terminou. Atualize a fila.'
      : `Aceite até ${formatTime(entry.offerExpiresAt, timeZone)}. ${entry.approvalRequired ? 'Ao aceitar, vira pedido sujeito à aprovação da gestão.' : 'Ao aceitar, a reserva é confirmada.'}`}</Text> : null}
    {!entry.createdByMe && open(entry) ? <Text style={styles.small}>Entrada de outra pessoa da unidade: somente quem entrou na fila pode alterá-la.</Text> : null}
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{uncertain(failure) ? `${message(failure)} Confira em Minhas reservas antes de repetir.` : message(failure)}</Notice> : null}
    {held && open(entry) && uncertain(failure) ? <Notice tone="warning">Não foi possível confirmar o aceite. A próxima tentativa usa o mesmo envio, sem criar outra reserva.</Notice> : null}
    {entry.createdByMe && open(entry) ? confirming
      ? <View accessibilityLabel={offered ? 'Confirmação de recusa' : 'Confirmação de saída'} style={styles.gap}>
        <Text style={styles.body}>{offered ? 'Recusar esta vaga? Ela passa ao próximo da fila e você sai da fila deste período.'
          : 'Sair da fila deste período? Para voltar, será preciso entrar de novo no fim da fila.'}</Text>
        <Button danger label={busy ? 'Enviando…' : offered ? 'Confirmar recusa' : 'Confirmar saída'} disabled={busy} onPress={() => void leave()} />
        <Button secondary label="Voltar" disabled={busy} onPress={() => setConfirming(false)} />
      </View>
      : <View style={styles.gap}>
        {offered ? <Button label={busy ? 'Confirmando…' : held && uncertain(failure) ? 'Tentar confirmar o mesmo aceite' : 'Aceitar vaga'}
          accessibilityLabel={`Aceitar vaga de ${area}`} disabled={busy || expired} onPress={() => void accept()} /> : null}
        <Button secondary label={offered ? 'Recusar vaga' : 'Sair da fila'} accessibilityLabel={`${offered ? 'Recusar vaga' : 'Sair da fila'} de ${area}`}
          disabled={busy || (held !== null && uncertain(failure))} onPress={() => { setFailure(null); setConfirming(true); }} />
      </View> : null}
  </View>;
}

/** Entrar na fila do período ocupado escolhido, com as regras aceitas; resultado incerto repete com a mesma chave. */
export function JoinWaitlist({ api, scope, tenantId, body, disabled, newKey }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; body: JoinBody; disabled: boolean; newKey: () => string;
}) {
  const queries = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [held, setHeld] = useState<{ intent: string; key: string } | null>(null);
  const [joined, setJoined] = useState<{ position: number | null; replayed: boolean } | null>(null);
  async function join() {
    if (busy || disabled) return;
    const intent = JSON.stringify(body);
    const key = held?.intent === intent ? held.key : newKey();
    setHeld({ intent, key }); setBusy(true); setFailure(null);
    try {
      const result = await api.joinWaitlist(tenantId, body, key);
      setHeld(null); setJoined({ position: result.entry.position, replayed: result.replayed });
    } catch (error) {
      setFailure(error);
      if (!uncertain(error)) setHeld(null);
      if (error instanceof ApiError && error.code === 'WAITLIST_SLOT_AVAILABLE') void queries.invalidateQueries({ queryKey: [...scope, 'availability'] });
      // Regras mudaram: a área é consultada de novo e o aceite volta a ser exigido na nova versão.
      if (error instanceof ApiError && error.code === 'AMENITY_POLICY_CHANGED') void queries.invalidateQueries({ queryKey: [...scope, 'amenities'] });
    } finally { setBusy(false); void queries.invalidateQueries({ queryKey: [...scope, 'waitlist'] }); }
  }
  if (joined) return <Notice tone="success">{`${joined.replayed ? 'Você já estava na fila deste período.' : 'Você entrou na fila de espera deste período.'}${joined.position !== null ? ` Posição: ${joined.position}.` : ''} Acompanhe em Fila de espera.`}</Notice>;
  return <View style={styles.gap}>
    <Text style={styles.small}>Se o período for liberado, a vaga é oferecida por ordem de entrada, por até 15 minutos.</Text>
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{message(failure)}</Notice> : null}
    <Button label={busy ? 'Entrando na fila…' : held && uncertain(failure) ? 'Tentar confirmar a mesma entrada' : 'Entrar na fila de espera'}
      disabled={busy || disabled} onPress={() => void join()} />
  </View>;
}

const styles = StyleSheet.create({
  section: { gap: 10, marginTop: 12 },
  subheading: { fontSize: 18, fontWeight: '700', color: colors.navy },
  card: { padding: 16, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface, gap: 8, marginBottom: 10 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
  body: { fontSize: 15, color: colors.text, flexShrink: 1 },
  small: { fontSize: 13, color: colors.muted },
  gap: { gap: 8 },
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
