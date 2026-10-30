import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi } from '../api/client';
import type { Reservation } from '../api/reservations';
import { normalizeDocument, type Permit } from '../api/visits';
import { formatWhen } from '../time';
import type { KeyFactory } from './Reservations';
import { colors, touch } from './theme';

/**
 * Convidados da reserva no celular (CP-038). Cada convidado vira um convite da unidade com a janela do evento (até 1 hora
 * antes do início até o fim do uso), conferido pela portaria; cancelar a reserva revoga todos de uma vez. Resultado
 * incerto mantém a mesma chave para os mesmos dados (guardada fora da tela, por conta, condomínio, unidade e reserva:
 * sobrevive à troca de vínculo): repetir confirma o mesmo convidado, sem duplicar. O limite de convidados é o das regras
 * aceitas na reserva, decidido pelo servidor.
 */
export type GuestPendingStore = Map<string, { body: string; key: string; data: { visitorName: string; documentLast4: string | null } }>;
const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
const uncertain = (error: unknown) => error instanceof ApiError && error.uncertain;
/** Chave vencida ou usada com outros dados: a repetição não confirma nem grava; só a lista mostra se o convidado existe. */
const staleKey = (error: unknown) => error instanceof ApiError && ['IDEMPOTENCY_KEY_EXPIRED', 'IDEMPOTENCY_KEY_REUSED'].includes(error.code);
const stateLabels: Record<Permit['state'], string> = { SCHEDULED: 'Agendado', ACTIVE: 'Em vigor', REVOKED: 'Revogado', EXPIRED: 'Encerrado' };
const open = (item: Permit) => item.state === 'ACTIVE' || item.state === 'SCHEDULED';

function Button({ label, onPress, disabled, secondary }: { label: string; onPress(): void; disabled?: boolean; secondary?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled: !!disabled }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, secondary && styles.secondary, (disabled || pressed) && styles.dimmed]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{label}</Text>
  </Pressable>;
}
function Notice({ tone, children }: { tone: 'error' | 'info' | 'success'; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, styles[tone]]}>{children}</Text>;
}

export function ReservationGuests({ api, scope, accountId, tenantId, unitId, reservation, timeZone, newKey, pending: sharedPending }: {
  api: CondPulseApi; scope: readonly unknown[]; accountId: string; tenantId: string; unitId: string; reservation: Reservation; timeZone: string; newKey: KeyFactory;
  pending?: GuestPendingStore;
}) {
  const [ownPending] = useState<GuestPendingStore>(() => new Map());
  const store = sharedPending ?? ownPending;
  const storeKey = `${accountId}|${tenantId}|${unitId}|${reservation.reservationId}`;
  const queries = useQueryClient();
  const [expanded, setExpanded] = useState(() => (sharedPending ?? new Map()).has(`${accountId}|${tenantId}|${unitId}|${reservation.reservationId}`));
  const key = [...scope, 'guests', reservation.reservationId] as const;
  const guests = useQuery({ queryKey: key, enabled: expanded, queryFn: ({ signal }) => api.reservationGuests(tenantId, unitId, reservation.reservationId, signal),
    retry: (count) => count < 1 });
  const [name, setName] = useState('');
  const [document, setDocument] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [intent, setIntentState] = useState<{ body: string; key: string; data: { visitorName: string; documentLast4: string | null } } | null>(() => store.get(storeKey) ?? null);
  const setIntent = (value: typeof intent) => { if (value) store.set(storeKey, value); else store.delete(storeKey); setIntentState(value); };
  const [outcome, setOutcome] = useState<string | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const label = formatWhen(reservation.startsAt, timeZone);
  // Restaurado da troca de vínculo: sem falha conhecida nesta tela, mas o resultado continua sem confirmação.
  const locked = intent !== null && (uncertain(failure) || failure === null || staleKey(failure));
  // Os convidados também são convites da unidade: a seção de visitas precisa refletir a mudança.
  const refresh = () => {
    void queries.invalidateQueries({ queryKey: key });
    void queries.invalidateQueries({ queryKey: [...scope, 'visits', 'permits'] });
  };
  const visitor = name.trim().replace(/\s+/g, ' ');
  const documentValue = normalizeDocument(document);
  const valid = locked || ([...visitor].length >= 2 && [...visitor].length <= 160 && documentValue !== '');
  async function add() {
    if (busy || !valid) return;
    const data = locked ? intent!.data : { visitorName: visitor, documentLast4: documentValue };
    const serialized = JSON.stringify(data);
    const operation = intent?.body === serialized ? intent.key : newKey();
    setIntent({ body: serialized, key: operation, data }); setBusy(true); setFailure(null); setOutcome(null);
    try {
      const result = await api.addGuest(tenantId, unitId, reservation.reservationId, data, operation);
      setIntent(null); setName(''); setDocument('');
      setOutcome(`${result.replayed ? `${result.permit.visitorName} já estava convidado(a).` : `${result.permit.visitorName} foi convidado(a).`} A portaria vê o nome de ${formatWhen(result.permit.validFrom, timeZone)} até o fim do uso.`);
    } catch (error) {
      setFailure(error);
      // Repetição de envio incerto com chave vencida: continua travado (sem chave nova automática) até a pessoa conferir.
      if (!uncertain(error) && !(locked && staleKey(error))) setIntent(null);
    } finally { setBusy(false); refresh(); }
  }
  async function remove(guest: Permit) {
    if (busy) return;
    setBusy(true); setFailure(null); setOutcome(null);
    try {
      await api.revokePermit(tenantId, unitId, guest.permitId, { reason: 'Convidado removido pela unidade', expectedVersion: guest.version });
      setOutcome(`Convite de ${guest.visitorName} revogado.`);
    } catch (error) { setFailure(error); }
    finally { setBusy(false); setRemoving(null); refresh(); }
  }
  return <View style={styles.gap} accessibilityLabel={`Convidados da reserva de ${label}`}>
    <Button label={expanded ? `Ocultar convidados de ${label}` : `Convidados de ${label}`} secondary onPress={() => setExpanded(!expanded)} />
    {expanded ? <View style={styles.gap}>
      <Text style={styles.body}>Cada convidado entra na lista da portaria de até 1 hora antes do início até o fim do uso. Cancelar a reserva revoga todos.</Text>
      {guests.isPending ? <ActivityIndicator accessibilityLabel="Consultando convidados" />
        : guests.isError && !guests.data ? <View style={styles.gap}><Notice tone="error">{message(guests.error)}</Notice>
          <Button label="Tentar consultar convidados de novo" secondary onPress={() => void guests.refetch()} /></View>
          : guests.data.permits.length === 0 ? <Text style={styles.body}>Nenhum convidado.</Text>
            : <View accessibilityRole="list" accessibilityLabel="Convidados" style={styles.gap}>{guests.data.permits.map((guest) =>
              <View key={guest.permitId} style={styles.guest}>
                <Text style={styles.body}>{`${guest.visitorName}${guest.documentLast4 ? ` · documento final ${guest.documentLast4}` : ''} · ${stateLabels[guest.state]}`}</Text>
                {open(guest) ? removing === guest.permitId ? <View style={styles.gap}>
                  <Button label={`Confirmar remoção de ${guest.visitorName}`} disabled={busy} onPress={() => void remove(guest)} />
                  <Button label={`Manter ${guest.visitorName}`} secondary disabled={busy} onPress={() => setRemoving(null)} />
                </View> : <Button label={`Remover ${guest.visitorName}`} secondary disabled={busy} onPress={() => setRemoving(guest.permitId)} /> : null}
              </View>)}</View>}
      {guests.data?.truncated ? <Notice tone="info">A lista mostra os convidados ativos primeiro e, depois, os 500 mais recentes.</Notice> : null}
      {outcome ? <Notice tone="success">{outcome}</Notice> : null}
      {failure ? <Notice tone="error">{`${message(failure)}${locked && !staleKey(failure) ? ' Use “Tentar confirmar o mesmo convidado”: se já foi registrado, nada novo é gravado.' : ''}`}</Notice> : null}
      {locked && staleKey(failure) ? <View style={styles.gap}>
        <Notice tone="info">O prazo para confirmar este envio terminou. Confira a lista de convidados: se o nome aparecer, ele já foi registrado. Só descarte este envio se ele não estiver lá.</Notice>
        <Button label="Conferi a lista: descartar este envio" secondary disabled={busy} onPress={() => { setIntent(null); setFailure(null); }} />
      </View> : null}
      <View style={styles.gap}>
        <Text style={styles.label}>Nome do convidado</Text>
        <TextInput accessibilityLabel="Nome do convidado" style={styles.input} value={locked ? intent!.data.visitorName : name} onChangeText={setName}
          editable={!busy && !locked} autoCorrect={false} />
        <Text style={styles.label}>Final do documento do convidado (opcional)</Text>
        <TextInput accessibilityLabel="Final do documento do convidado (opcional)" style={styles.input} value={locked ? intent!.data.documentLast4 ?? '' : document}
          onChangeText={setDocument} editable={!busy && !locked} autoCapitalize="characters" autoCorrect={false} />
        {documentValue === '' && !locked ? <Text style={styles.fieldError}>Informe só os 2 a 4 últimos caracteres do documento, ou deixe em branco.</Text> : null}
        <Button label={busy ? 'Enviando…' : locked ? 'Tentar confirmar o mesmo convidado' : 'Adicionar convidado'} disabled={busy || !valid} onPress={() => void add()} />
      </View>
    </View> : null}
  </View>;
}

const styles = StyleSheet.create({
  gap: { gap: 8 },
  guest: { gap: 6, paddingVertical: 6, borderTopWidth: 1, borderTopColor: colors.line },
  body: { fontSize: 15, color: colors.text, flexShrink: 1 },
  label: { fontSize: 14, fontWeight: '600', color: colors.text },
  fieldError: { fontSize: 14, color: colors.error },
  input: { minHeight: touch, borderWidth: 1, borderColor: colors.line, borderRadius: 10, paddingHorizontal: 14, fontSize: 16, backgroundColor: colors.surface, color: colors.text },
  button: { minHeight: touch, borderRadius: 10, backgroundColor: colors.teal, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  secondary: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.line },
  dimmed: { opacity: 0.6 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  secondaryText: { color: colors.navy },
  notice: { padding: 12, borderRadius: 10, fontSize: 15 },
  error: { backgroundColor: colors.errorBg, color: colors.error },
  info: { backgroundColor: colors.infoBg, color: colors.text },
  success: { backgroundColor: colors.infoBg, color: colors.navy },
});
