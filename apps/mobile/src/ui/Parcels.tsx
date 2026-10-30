import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi, type Membership } from '../api/client';
import { normalizeDocument, type CreateDelegation, type Delegation, type Parcel } from '../api/parcels';
import { formatWhen, toInstant, wallTimeInZone } from '../time';
import type { KeyFactory } from './Reservations';
import { colors, touch } from './theme';

/**
 * Encomendas da unidade no celular (CP-029) e retirada por terceiro com código (CP-041). O código de 8 dígitos aparece
 * só na resposta da criação, nesta tela; a portaria confere o código e o documento. Nova autorização substitui a anterior.
 * Resultado incerto mantém a mesma chave e o mesmo corpo: repetir confirma a mesma autorização, sem criar outra.
 */
const stateText: Record<Delegation['state'], string> = {
  ACTIVE: 'Autorização em vigor', USED: 'Retirada feita com o código', REVOKED: 'Autorização cancelada', LOCKED: 'Bloqueada por tentativas erradas',
  CLOSED: 'Autorização encerrada', EXPIRED: 'Autorização vencida',
};
const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
/** 503 sem a chave do código é recusa certa: nada foi gravado. */
const uncertain = (error: unknown) => error instanceof ApiError && error.uncertain && error.code !== 'DELEGATION_UNAVAILABLE';
const hidden = (error: unknown) => error instanceof ApiError && (error.status === 404 || ['UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(error.code));

function Button({ label, onPress, disabled, secondary }: { label: string; onPress(): void; disabled?: boolean; secondary?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled: !!disabled }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, secondary && styles.secondary, (disabled || pressed) && styles.dimmed]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{label}</Text>
  </Pressable>;
}
function Notice({ tone, children }: { tone: 'error' | 'info' | 'success'; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, styles[tone]]}>{children}</Text>;
}
function Field({ label, value, onChange, disabled }: { label: string; value: string; onChange(value: string): void; disabled?: boolean }) {
  return <View><Text style={styles.label}>{label}</Text>
    <TextInput accessibilityLabel={label} style={styles.input} value={value} onChangeText={onChange} editable={!disabled} autoCorrect={false} /></View>;
}
const volumes = (item: Parcel) => `${item.quantity} ${item.quantity === 1 ? 'volume' : 'volumes'}`;

export function Parcels({ api, accountId, membership, newKey, now = () => new Date() }: {
  api: CondPulseApi; accountId: string; membership: Membership & { unitId: string }; newKey: KeyFactory; now?: () => Date;
}) {
  const { tenantId, unitId, timeZone } = membership;
  const key = ['private', accountId, 'tenant', tenantId, 'unit', unitId, 'parcels'] as const;
  const retry = (count: number, error: unknown) => !hidden(error) && count < 1;
  const waiting = useQuery({ queryKey: [...key, 'RECEIVED'], queryFn: ({ signal }) => api.unitParcels(tenantId, unitId, 'RECEIVED', signal), retry });
  const delivered = useQuery({ queryKey: [...key, 'DELIVERED'], queryFn: ({ signal }) => api.unitParcels(tenantId, unitId, 'DELIVERED', signal), retry });
  if (hidden(waiting.error) || hidden(delivered.error)) return null;
  const failure = waiting.error ?? delivered.error;
  const refresh = () => { void waiting.refetch(); void delivered.refetch(); };
  return <View accessibilityLabel="Encomendas" style={styles.section}>
    <Text accessibilityRole="header" style={styles.title}>Encomendas</Text>
    <Text style={styles.body}>{`Na retirada, a portaria confere o documento de quem tem vínculo com a unidade ou de quem você autorizar com código. Horários em ${timeZone}.`}</Text>
    <Button label="Atualizar encomendas" secondary disabled={waiting.isFetching || delivered.isFetching} onPress={refresh} />
    {waiting.isPending || delivered.isPending ? <ActivityIndicator accessibilityLabel="Consultando encomendas" />
      : failure || !waiting.data || !delivered.data ? <View><Notice tone="error">{message(failure)}</Notice><Button label="Tentar consultar de novo" secondary onPress={refresh} /></View>
        : <View style={styles.gap}>
          <Text style={styles.subtitle}>Aguardando retirada</Text>
          {waiting.data.parcels.length === 0 ? <Text style={styles.body}>Nada a retirar.</Text>
            : <View accessibilityRole="list" accessibilityLabel="Encomendas a retirar">{waiting.data.parcels.map((item) =>
              <View key={item.parcelId} style={styles.card} accessibilityLabel={`Encomenda de ${volumes(item)} recebida em ${formatWhen(item.receivedAt, timeZone)}`}>
                <Text style={styles.cardTitle}>{`${volumes(item)} · a retirar`}</Text>
                <Text style={styles.body}>{`Recebida em ${formatWhen(item.receivedAt, timeZone)} · guardada em ${item.storageLocation}`}</Text>
                {item.description ? <Text style={styles.body}>{item.description}</Text> : null}
                <ParcelDelegation api={api} baseKey={key} tenantId={tenantId} parcel={item} timeZone={timeZone} newKey={newKey} now={now} />
              </View>)}</View>}
          {waiting.data.truncated ? <Notice tone="info">A lista mostra as 100 encomendas mais antigas aguardando retirada.</Notice> : null}
          <Text style={styles.subtitle}>Retiradas</Text>
          {delivered.data.parcels.length === 0 ? <Text style={styles.body}>Nenhuma retirada registrada recentemente.</Text>
            : <View accessibilityRole="list" accessibilityLabel="Encomendas retiradas">{delivered.data.parcels.map((item) =>
              <View key={item.parcelId} style={styles.card}>
                <Text style={styles.cardTitle}>{`${volumes(item)} · retirada`}</Text>
                {item.handoff ? <Text style={styles.body}>{`Retirada por ${item.handoff.recipientName}${item.handoff.evidenceKind === 'DELEGATION'
                  ? ' (pessoa autorizada pela unidade, com código)' : ''} em ${formatWhen(item.handoff.deliveredAt, timeZone)}`}</Text> : null}
              </View>)}</View>}
          {delivered.data.truncated ? <Notice tone="info">O histórico mostra as 100 retiradas mais recentes.</Notice> : null}
        </View>}
  </View>;
}

function ParcelDelegation({ api, baseKey, tenantId, parcel, timeZone, newKey, now }: {
  api: CondPulseApi; baseKey: readonly unknown[]; tenantId: string; parcel: Parcel; timeZone: string; newKey: KeyFactory; now(): Date;
}) {
  const queries = useQueryClient();
  const key = [...baseKey, 'delegation', parcel.parcelId];
  const current = useQuery({ queryKey: key, queryFn: ({ signal }) => api.parcelDelegation(tenantId, parcel.parcelId, signal) });
  const [creating, setCreating] = useState(false);
  // Retrato da autorização exibida ao abrir a confirmação: o cancelamento vale só para ela, mesmo que a consulta mude.
  const [revoking, setRevoking] = useState<{ delegationId: string; version: number; name: string } | null>(null);
  const [code, setCode] = useState<{ value: string; name: string } | null>(null);
  const [notice, setNotice] = useState<{ tone: 'error' | 'info' | 'success'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const refresh = () => queries.invalidateQueries({ queryKey: key });
  const delegation = current.data ?? null;
  const active = delegation?.state === 'ACTIVE';
  async function revoke() {
    const target = revoking;
    if (!target || busy) return;
    setBusy(true); setNotice(null);
    try {
      await api.revokeDelegation(tenantId, parcel.parcelId, { delegationId: target.delegationId, expectedVersion: target.version });
      setCode(null); setNotice({ tone: 'success', text: `Autorização de ${target.name} cancelada. O código deixou de valer.` });
    } catch (error) { setNotice({ tone: 'error', text: `${message(error)}${uncertain(error) ? ' Atualize e confira antes de repetir.' : ''}` }); }
    finally { setBusy(false); setRevoking(null); void refresh(); }
  }
  return <View style={styles.gap} accessibilityLabel="Retirada por terceiro">
    {current.isPending ? <ActivityIndicator accessibilityLabel="Consultando autorização" />
      : current.isError ? <View><Notice tone="error">{message(current.error)}</Notice>
        <Button label="Consultar autorização de novo" secondary onPress={() => void current.refetch()} /></View>
        : delegation ? <Text style={styles.body}>{`${stateText[delegation.state]}: ${delegation.delegateName}${delegation.documentLast4 ? ` · documento final ${delegation.documentLast4}` : ''}${active
          ? ` · até ${formatWhen(delegation.validUntil, timeZone)} · ${delegation.attemptsLeft} tentativas restantes` : ''}`}</Text> : null}
    {code ? <View accessibilityRole="alert" style={[styles.notice, styles.info]}>
      <Text style={styles.label}>{`Código para ${code.name}:`}</Text>
      <Text accessibilityLabel={`Código ${code.value.split('').join(' ')}`} selectable style={styles.code}>{code.value}</Text>
      <Text style={styles.body}>Envie à pessoa autorizada. Ele aparece só agora; na portaria ela informa o código e mostra o documento.</Text>
    </View> : null}
    {notice ? <Notice tone={notice.tone}>{notice.text}</Notice> : null}
    {creating ? <DelegationForm api={api} tenantId={tenantId} parcel={parcel} timeZone={timeZone} replacing={active} newKey={newKey} now={now}
      close={(result) => { setCreating(false); if (result) { setCode(result.code ? { value: result.code, name: result.name } : null); setNotice(result.notice); } void refresh(); }} />
      : revoking ? <View style={styles.gap} accessibilityLabel="Confirmação de cancelamento da autorização">
        <Text style={styles.body}>{`Cancelar a autorização de ${revoking.name}? O código deixa de valer imediatamente.`}</Text>
        <Button label={busy ? 'Cancelando…' : 'Confirmar cancelamento'} disabled={busy} onPress={() => void revoke()} />
        <Button label="Manter" secondary disabled={busy} onPress={() => setRevoking(null)} />
      </View>
        : <View style={styles.gap}>
          <Button label={active ? 'Gerar nova autorização' : 'Autorizar retirada por terceiro'} secondary disabled={current.isPending}
            onPress={() => { setNotice(null); setCreating(true); }} />
          {active ? <Button label="Cancelar autorização" secondary onPress={() => { setNotice(null);
            setRevoking({ delegationId: delegation!.delegationId, version: delegation!.version, name: delegation!.delegateName }); }} /> : null}
        </View>}
  </View>;
}

function DelegationForm({ api, tenantId, parcel, timeZone, replacing, newKey, now, close }: {
  api: CondPulseApi; tenantId: string; parcel: Parcel; timeZone: string; replacing: boolean; newKey: KeyFactory; now(): Date;
  close(result: { code: string | null; name: string; notice: { tone: 'info' | 'success'; text: string } } | null): void;
}) {
  const [name, setName] = useState('');
  const [document, setDocument] = useState('');
  const [initial] = useState(() => wallTimeInZone(timeZone, new Date(now().getTime() + 48 * 3_600_000)));
  const [day, setDay] = useState(initial.slice(0, 10));
  const [time, setTime] = useState(initial.slice(11));
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  // Envio com resultado incerto: a repetição manda exatamente o mesmo corpo com a mesma chave, sem revalidar o prazo.
  const [pending, setPending] = useState<{ key: string; body: CreateDelegation } | null>(null);
  const person = name.trim().replace(/\s+/g, ' ');
  const documentValue = normalizeDocument(document);
  let validUntil: string | null = null; let periodError: string | null = null;
  try {
    validUntil = toInstant(`${day}T${time}`, timeZone);
    const span = Date.parse(validUntil) - now().getTime();
    if (span < 15 * 60_000) periodError = 'A autorização vale por pelo menos 15 minutos.';
    else if (span > 7 * 86_400_000) periodError = 'A autorização vale por até 7 dias.';
  } catch (error) { periodError = error instanceof Error ? error.message : 'Informe a validade.'; }
  const locked = pending !== null;
  const valid = locked || ([...person].length >= 2 && [...person].length <= 160 && documentValue !== '' && periodError === null && validUntil !== null);
  async function submit() {
    if (busy || !valid) return;
    const attempt = pending ?? { key: newKey(), body: { delegateName: person, documentLast4: documentValue, validUntil } };
    setBusy(true); setFailure(null);
    try {
      const result = await api.delegate(tenantId, parcel.parcelId, attempt.body, attempt.key);
      setPending(null);
      close(result.code ? { code: result.code, name: result.delegation.delegateName, notice: { tone: 'success',
        text: `Autorização criada para ${result.delegation.delegateName} até ${formatWhen(result.delegation.validUntil, timeZone)}.` } }
        : { code: null, name: result.delegation.delegateName, notice: { tone: 'info',
          text: 'Esta autorização já estava criada, mas o código só aparece na primeira resposta. Gere uma nova autorização para ter outro código.' } });
    } catch (error) {
      setFailure(error);
      setPending(uncertain(error) ? attempt : null);
    } finally { setBusy(false); }
  }
  const disabled = busy || locked;
  return <View style={styles.gap} accessibilityLabel="Autorizar retirada por terceiro">
    {replacing ? <Notice tone="info">A nova autorização substitui a atual: o código anterior deixa de valer.</Notice> : null}
    <Field label="Nome de quem vai retirar" value={name} onChange={setName} disabled={disabled} />
    <Field label="Final do documento (opcional)" value={document} onChange={setDocument} disabled={disabled} />
    {documentValue === '' && !locked ? <Text style={styles.fieldError}>Informe só os 2 a 4 últimos caracteres do documento, ou deixe em branco.</Text> : null}
    <Field label="Válida até: data (AAAA-MM-DD)" value={day} onChange={setDay} disabled={disabled} />
    <Field label="Válida até: hora (HH:MM)" value={time} onChange={setTime} disabled={disabled} />
    {periodError && !locked ? <Text style={styles.fieldError}>{periodError}</Text> : <Text style={styles.body}>{`Horário em ${timeZone}.`}</Text>}
    {failure ? <Notice tone="error">{message(failure)}</Notice> : null}
    {locked ? <Notice tone="info">Não foi possível confirmar a autorização. A próxima tentativa usa o mesmo envio; se ela já tiver sido criada, gere outra para ver um código.</Notice> : null}
    <Button label={busy ? 'Gerando…' : locked ? 'Tentar confirmar a mesma autorização' : 'Gerar código'} disabled={busy || !valid} onPress={() => void submit()} />
    <Button label="Voltar" secondary disabled={busy} onPress={() => close(null)} />
  </View>;
}

const styles = StyleSheet.create({
  section: { gap: 12, marginTop: 16 },
  gap: { gap: 10 },
  title: { fontSize: 20, fontWeight: '700', color: colors.navy },
  subtitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
  body: { fontSize: 15, color: colors.muted },
  label: { fontSize: 14, fontWeight: '600', color: colors.text },
  code: { fontSize: 28, fontWeight: '700', letterSpacing: 4, color: colors.navy },
  fieldError: { fontSize: 14, color: colors.error },
  input: { minHeight: touch, borderWidth: 1, borderColor: colors.line, borderRadius: 10, paddingHorizontal: 14, fontSize: 16, backgroundColor: colors.surface, color: colors.text },
  button: { minHeight: touch, borderRadius: 10, backgroundColor: colors.teal, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  secondary: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.line },
  dimmed: { opacity: 0.6 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  secondaryText: { color: colors.navy },
  notice: { padding: 12, borderRadius: 10, fontSize: 15, gap: 4 },
  error: { backgroundColor: colors.errorBg, color: colors.error },
  info: { backgroundColor: colors.infoBg, color: colors.text },
  success: { backgroundColor: colors.infoBg, color: colors.navy },
  card: { padding: 16, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface, gap: 6 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
});
