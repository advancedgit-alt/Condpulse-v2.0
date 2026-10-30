import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi, type Membership } from '../api/client';
import { multiline, requestCategories, singleLine, type OpenRequest, type RequestCategory, type RequestMessage, type RequestState,
  type UnitRequest } from '../api/requests';
import { formatWhen } from '../time';
import type { KeyFactory } from './Reservations';
import { colors, touch } from './theme';

/**
 * Pedidos da unidade no celular (CP-033): abrir pedido, acompanhar o andamento e conversar com a equipe no próprio pedido.
 * A equipe aparece como "Equipe do condomínio"; comentário interno nunca chega aqui. Envio com resultado incerto guarda
 * a mesma chave e o mesmo texto fora da tela: repetir só confirma, sem abrir outro pedido nem gravar outra mensagem.
 */
export const categoryLabels: Record<RequestCategory, string> = {
  MAINTENANCE: 'Manutenção', CLEANING: 'Limpeza', SECURITY: 'Segurança', NOISE: 'Barulho', ADMINISTRATIVE: 'Administrativo', OTHER: 'Outro',
};
export const stateLabels: Record<RequestState, string> = {
  OPEN: 'Enviado', TRIAGED: 'Em análise', IN_PROGRESS: 'Em execução', WAITING_RESIDENT: 'Aguardando a unidade', RESOLVED: 'Concluído', CLOSED: 'Encerrado',
};
/** Envios incertos por conta/condomínio/unidade (abertura) e por pedido (mensagem): sobrevivem à troca de vínculo. */
export type RequestPendingStore = Map<string, { key: string; body: OpenRequest | string }>;
const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
const uncertain = (error: unknown) => error instanceof ApiError && error.uncertain;
const staleKey = (error: unknown) => error instanceof ApiError && ['IDEMPOTENCY_KEY_EXPIRED', 'IDEMPOTENCY_KEY_REUSED'].includes(error.code);
/** Recurso desligado ou vínculo que mudou: a seção some sem erro técnico. */
const hidden = (error: unknown) => error instanceof ApiError && (['UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(error.code)
  || (error.status === 404 && error.code === 'HTTP_ERROR'));
const civilDate = (value: string) => { const [year, month, day] = value.split('-'); return `${day}/${month}/${year}`; };

function Button({ label, onPress, disabled, secondary }: { label: string; onPress(): void; disabled?: boolean; secondary?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled: !!disabled }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, secondary && styles.secondary, (disabled || pressed) && styles.dimmed]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{label}</Text>
  </Pressable>;
}
function Notice({ tone, children }: { tone: 'error' | 'info' | 'success'; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, styles[tone]]}>{children}</Text>;
}
function Field({ label, value, onChange, disabled, lines = 1, max }: { label: string; value: string; onChange(value: string): void; disabled?: boolean; lines?: number; max: number }) {
  return <View><Text style={styles.label}>{label}</Text>
    <TextInput accessibilityLabel={label} style={[styles.input, lines > 1 && styles.multiline]} value={value} onChangeText={onChange} editable={!disabled}
      multiline={lines > 1} numberOfLines={lines} maxLength={max} autoCorrect /></View>;
}

export function Requests({ api, accountId, membership, newKey, pending: sharedPending }: {
  api: CondPulseApi; accountId: string; membership: Membership & { unitId: string }; newKey: KeyFactory; pending?: RequestPendingStore;
}) {
  const { tenantId, unitId, timeZone } = membership;
  const [ownPending] = useState<RequestPendingStore>(() => new Map());
  const pending = sharedPending ?? ownPending;
  const openScope = `open|${accountId}|${tenantId}|${unitId}`;
  const scope = ['private', accountId, 'tenant', tenantId, 'unit', unitId] as const;
  const queries = useQueryClient();
  const list = useQuery({ queryKey: [...scope, 'requests'], queryFn: ({ signal }) => api.unitRequests(tenantId, unitId, signal),
    retry: (count, error) => !hidden(error) && count < 1 });
  const [opening, setOpening] = useState(() => pending.has(openScope));
  const [selected, setSelected] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'info'; text: string } | null>(null);
  if (hidden(list.error)) return null;
  const active = list.data?.requests.filter((item) => item.state !== 'CLOSED').length ?? 0;
  return <View accessibilityLabel="Pedidos da unidade" style={styles.section}>
    <Text accessibilityRole="header" style={styles.title}>{`Pedidos${active > 0 ? ` · ${active} em andamento` : ''}`}</Text>
    <Text style={styles.body}>Use para manutenção, limpeza, segurança e outros assuntos com a gestão. A conversa fica no próprio pedido.</Text>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {!opening ? <Button label="Abrir pedido" onPress={() => { setOutcome(null); setOpening(true); }} /> : null}
    {opening ? <OpenForm api={api} scope={scope} tenantId={tenantId} unitId={unitId} newKey={newKey} store={pending} storeKey={openScope}
      close={(created) => { setOpening(false); if (created) { setOutcome(created.outcome); setSelected(created.requestId); } }} /> : null}
    <Button label="Atualizar pedidos" secondary disabled={list.isFetching} onPress={() => {
      // A conversa aberta também: resposta ou encerramento pela gestão aparecem sem fechar e abrir de novo.
      void list.refetch(); void queries.invalidateQueries({ queryKey: [...scope, 'request'] });
    }} />
    {list.isPending ? <ActivityIndicator accessibilityLabel="Consultando os pedidos" />
      : list.isError && !list.data ? <View><Notice tone="error">{message(list.error)}</Notice><Button label="Tentar de novo" secondary onPress={() => void list.refetch()} /></View>
        : !list.data || list.data.requests.length === 0 ? <Text style={styles.body}>Nenhum pedido. Os pedidos abertos pela unidade aparecem aqui, com o andamento informado pela gestão.</Text>
          : <View accessibilityRole="list" accessibilityLabel="Pedidos abertos pela unidade">{list.data.requests.map((item) =>
            <View key={item.requestId} style={styles.card} accessibilityLabel={`${item.title}, ${stateLabels[item.state]}`}>
              <Text style={styles.cardTitle}>{`${item.title} · ${stateLabels[item.state]}`}</Text>
              <Text style={styles.body}>{`${categoryLabels[item.category]} · aberto em ${formatWhen(item.createdAt, timeZone)} · atualizado em ${formatWhen(item.updatedAt, timeZone)}`}</Text>
              {item.assigned || item.dueOn ? <Text style={styles.body}>{`${item.assigned ? 'Responsável da equipe definido' : 'Sem responsável definido'}${item.dueOn ? ` · prazo comunicado: ${civilDate(item.dueOn)}` : ''}`}</Text> : null}
              {selected === item.requestId ? <Detail api={api} scope={scope} tenantId={tenantId} unitId={unitId} accountId={accountId} requestId={item.requestId}
                timeZone={timeZone} newKey={newKey} store={pending} close={() => setSelected(null)} />
                : <Button label={`Ver conversa: ${item.title}`} secondary onPress={() => { setOutcome(null); setSelected(item.requestId); }} />}
            </View>)}</View>}
    {list.data && list.isError ? <Notice tone="error">{`${message(list.error)} A lista mostra a última consulta.`}</Notice> : null}
    {list.data?.truncated ? <Notice tone="info">A lista mostra os 100 pedidos mais recentes.</Notice> : null}
  </View>;
}

function OpenForm({ api, scope, tenantId, unitId, newKey, store, storeKey, close }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; unitId: string; newKey: KeyFactory; store: RequestPendingStore; storeKey: string;
  close(created: { requestId: string; outcome: { tone: 'success' | 'info'; text: string } } | null): void;
}) {
  const queries = useQueryClient();
  const [pending, setPending] = useState(() => (store.get(storeKey) as { key: string; body: OpenRequest } | undefined) ?? null);
  const [category, setCategory] = useState<RequestCategory>(pending?.body.category ?? 'MAINTENANCE');
  const [title, setTitle] = useState(pending?.body.title ?? '');
  const [description, setDescription] = useState(pending?.body.description ?? '');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const locked = pending !== null;
  const normalizedTitle = singleLine(title, 3, 120);
  const normalizedDescription = multiline(description, 2000);
  const valid = locked || (normalizedTitle !== null && normalizedDescription !== null);
  async function submit() {
    if (!valid || busy) return;
    const attempt = pending ?? { key: newKey(), body: { category, title: normalizedTitle!, description: normalizedDescription! } };
    // Guardado antes do envio: se a seção for desmontada no meio, a volta retoma a mesma chave.
    store.set(storeKey, attempt);
    setBusy(true); setFailure(null);
    try {
      const result = await api.openRequest(tenantId, unitId, attempt.body, attempt.key);
      store.delete(storeKey); setPending(null);
      close({ requestId: result.request.requestId, outcome: result.replayed
        ? { tone: 'info', text: `Este pedido já estava aberto: ${result.request.title} (${stateLabels[result.request.state].toLowerCase()}).` }
        : { tone: 'success', text: `Pedido enviado: ${result.request.title}. A gestão acompanha por aqui.` } });
    } catch (error) {
      setFailure(error);
      // Repetição de envio incerto com chave vencida: continua travado até a pessoa conferir a lista.
      if (uncertain(error) || (pending !== null && staleKey(error))) setPending(attempt);
      else { store.delete(storeKey); setPending(null); }
    } finally { setBusy(false); void queries.invalidateQueries({ queryKey: [...scope, 'requests'] }); }
  }
  return <View style={styles.gap} accessibilityLabel="Abrir pedido">
    <Text style={styles.label}>Categoria</Text>
    <View style={styles.row}>{requestCategories.map((value) =>
      <Pressable key={value} accessibilityRole="button" accessibilityLabel={categoryLabels[value]} accessibilityState={{ selected: category === value, disabled: locked || busy }}
        disabled={locked || busy} onPress={() => setCategory(value)} style={[styles.chip, category === value && styles.chipSelected]}>
        <Text style={[styles.chipText, category === value && styles.chipTextSelected]}>{categoryLabels[value]}</Text>
      </Pressable>)}</View>
    <Field label="Título" value={title} onChange={setTitle} disabled={locked || busy} max={120} />
    {!locked && title.trim() !== '' && normalizedTitle === null ? <Text style={styles.fieldError}>O título tem de 3 a 120 caracteres.</Text> : null}
    <Field label="Descrição" value={description} onChange={setDescription} disabled={locked || busy} lines={5} max={2000} />
    <Text style={styles.body}>Texto simples. Não inclua senhas, documentos pessoais ou dados de outras pessoas.</Text>
    {failure ? <Notice tone="error">{message(failure)}</Notice> : null}
    {locked && staleKey(failure) ? <View style={styles.gap}>
      <Notice tone="info">O prazo para confirmar este envio terminou. Confira a lista de pedidos: se o pedido aparecer, ele já foi gravado. Só descarte este envio se ele não estiver lá.</Notice>
      <Button label="Conferi a lista: descartar este envio" secondary disabled={busy} onPress={() => { store.delete(storeKey); setPending(null); close(null); }} />
    </View> : null}
    {locked ? <Notice tone="info">O resultado não foi confirmado e o pedido ficou travado. Use “Tentar confirmar o mesmo pedido”: se ele já foi enviado, nada novo é gravado.</Notice> : null}
    <Button label={busy ? 'Enviando…' : locked ? 'Tentar confirmar o mesmo pedido' : 'Enviar pedido'} disabled={!valid || busy} onPress={() => void submit()} />
    {!locked ? <Button label="Voltar" secondary disabled={busy} onPress={() => close(null)} /> : null}
  </View>;
}

function author(item: RequestMessage): string {
  if (item.mine) return item.authorRole === 'STAFF' ? 'Você, pela equipe do condomínio' : 'Você';
  return item.authorRole === 'STAFF' ? 'Equipe do condomínio' : item.authorName ?? 'Unidade';
}

function Detail({ api, scope, tenantId, unitId, accountId, requestId, timeZone, newKey, store, close }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; unitId: string; accountId: string; requestId: string; timeZone: string;
  newKey: KeyFactory; store: RequestPendingStore; close(): void;
}) {
  const detail = useQuery({ queryKey: [...scope, 'request', requestId], queryFn: ({ signal }) => api.unitRequest(tenantId, unitId, requestId, signal),
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 1 });
  if (detail.isPending) return <ActivityIndicator accessibilityLabel="Abrindo o pedido" />;
  if (detail.isError && !detail.data) return <View style={styles.gap}><Notice tone="error">{message(detail.error)}</Notice>
    <Button label="Tentar abrir de novo" secondary onPress={() => void detail.refetch()} />
    <Button label="Fechar" secondary onPress={close} /></View>;
  const request = detail.data!;
  return <View style={styles.gap} accessibilityLabel={`Conversa do pedido ${request.title}`}>
    <Text style={styles.text}>{request.description}</Text>
    <Text style={styles.body}>{`Aberto por ${request.openedByMe ? 'você' : request.requesterName}`}</Text>
    {request.resolution ? <Notice tone="success">{`Resolução: ${request.resolution}`}</Notice> : null}
    {request.messagesTruncated ? <Notice tone="info">A conversa mostra as 200 mensagens mais recentes.</Notice> : null}
    {request.messages.length === 0 ? <Text style={styles.body}>Nenhuma mensagem ainda.</Text>
      : <View accessibilityRole="list" accessibilityLabel="Mensagens do pedido">{request.messages.map((item) =>
        <View key={item.messageId} style={styles.message}>
          <Text style={styles.label}>{`${author(item)} · ${formatWhen(item.createdAt, timeZone)}`}</Text>
          <Text style={styles.text}>{item.body}</Text>
        </View>)}</View>}
    {detail.isError ? <Notice tone="error">{`${message(detail.error)} A conversa mostra a última consulta.`}</Notice> : null}
    {request.state === 'CLOSED' ? <Notice tone="info">Pedido encerrado. A conversa fica guardada; se precisar, abra um novo pedido.</Notice>
      : <Reply api={api} scope={scope} tenantId={tenantId} request={request} newKey={newKey} store={store} storeKey={`message|${accountId}|${tenantId}|${request.requestId}`} />}
    <Button label="Fechar conversa" secondary onPress={close} />
  </View>;
}

function Reply({ api, scope, tenantId, request, newKey, store, storeKey }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; request: UnitRequest; newKey: KeyFactory; store: RequestPendingStore; storeKey: string;
}) {
  const queries = useQueryClient();
  const [pending, setPending] = useState(() => (store.get(storeKey) as { key: string; body: string } | undefined) ?? null);
  const [body, setBody] = useState(pending?.body ?? '');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [sent, setSent] = useState(false);
  const locked = pending !== null;
  const normalized = multiline(body, 2000);
  async function submit() {
    if ((!locked && normalized === null) || busy) return;
    const attempt = pending ?? { key: newKey(), body: normalized! };
    store.set(storeKey, attempt);
    setBusy(true); setFailure(null); setSent(false);
    try {
      await api.requestMessage(tenantId, request.requestId, attempt.body, attempt.key);
      store.delete(storeKey); setPending(null); setBody(''); setSent(true);
    } catch (error) {
      setFailure(error);
      if (uncertain(error) || (pending !== null && staleKey(error))) setPending(attempt);
      else { store.delete(storeKey); setPending(null); }
    } finally {
      setBusy(false);
      void queries.invalidateQueries({ queryKey: [...scope, 'request', request.requestId] });
      void queries.invalidateQueries({ queryKey: [...scope, 'requests'] });
    }
  }
  return <View style={styles.gap} accessibilityLabel={`Responder no pedido ${request.title}`}>
    <Field label="Mensagem para a gestão" value={body} onChange={(value) => { setBody(value); setSent(false); }} disabled={locked || busy} lines={3} max={2000} />
    {sent ? <Notice tone="success">Mensagem enviada.</Notice> : null}
    {failure ? <Notice tone="error">{message(failure)}</Notice> : null}
    {locked && staleKey(failure) ? <View style={styles.gap}>
      <Notice tone="info">O prazo para confirmar este envio terminou. Confira a conversa: se a mensagem aparecer, ela já foi gravada. Só descarte se ela não estiver lá.</Notice>
      <Button label="Conferi a conversa: descartar este envio" secondary disabled={busy} onPress={() => { store.delete(storeKey); setPending(null); setFailure(null); }} />
    </View> : null}
    {locked ? <Notice tone="info">O envio não foi confirmado. Use “Tentar confirmar a mesma mensagem”: se ela já foi gravada, nada novo é enviado.</Notice> : null}
    <Button label={busy ? 'Enviando…' : locked ? 'Tentar confirmar a mesma mensagem' : 'Enviar mensagem'} disabled={(!locked && normalized === null) || busy}
      onPress={() => void submit()} />
  </View>;
}

const styles = StyleSheet.create({
  section: { gap: 12, marginTop: 16 },
  gap: { gap: 10 },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, alignItems: 'center' },
  title: { fontSize: 20, fontWeight: '700', color: colors.navy },
  body: { fontSize: 15, color: colors.muted },
  text: { fontSize: 15, color: colors.text },
  label: { fontSize: 14, fontWeight: '600', color: colors.text },
  fieldError: { fontSize: 14, color: colors.error },
  input: { minHeight: touch, borderWidth: 1, borderColor: colors.line, borderRadius: 10, paddingHorizontal: 14, fontSize: 16, backgroundColor: colors.surface, color: colors.text },
  multiline: { minHeight: 96, paddingVertical: 10, textAlignVertical: 'top' },
  button: { minHeight: touch, borderRadius: 10, backgroundColor: colors.teal, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  secondary: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.line },
  dimmed: { opacity: 0.6 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  secondaryText: { color: colors.navy },
  notice: { padding: 12, borderRadius: 10, fontSize: 15 },
  error: { backgroundColor: colors.errorBg, color: colors.error },
  info: { backgroundColor: colors.infoBg, color: colors.text },
  success: { backgroundColor: colors.infoBg, color: colors.navy },
  card: { padding: 16, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface, gap: 6 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
  message: { paddingVertical: 8, borderTopWidth: 1, borderTopColor: colors.line, gap: 4 },
  chip: { minHeight: touch, paddingHorizontal: 14, borderRadius: 24, borderWidth: 1, borderColor: colors.line, justifyContent: 'center', backgroundColor: colors.surface },
  chipSelected: { backgroundColor: colors.teal, borderColor: colors.teal },
  chipText: { color: colors.navy, fontSize: 15 },
  chipTextSelected: { color: '#fff', fontWeight: '700' },
});
