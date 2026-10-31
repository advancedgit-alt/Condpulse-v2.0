import { useState } from 'react';
import { ActivityIndicator, Pressable, Share, StyleSheet, Text, TextInput, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi, type Membership } from '../api/client';
import { normalizeDocument, type Arrival, type CreatePermit, type Permit } from '../api/visits';
import { formatWhen, toInstant, wallTimeInZone } from '../time';
import type { KeyFactory } from './Reservations';
import { colors, touch } from './theme';
import { VisitRules, type RulePendingStore } from './VisitRules';

/**
 * Acessos da unidade no celular (CP-025, CP-026 e CP-037): chegadas que aguardam a resposta da unidade e convites.
 * Convite é autorização temporal, não prova de identidade nem de entrada: a portaria segue conferindo a pessoa. Sem
 * resposta no prazo nada é liberado. Convite com resultado incerto mantém a mesma chave; o link aparece uma única vez.
 */
export type PermitPendingStore = Map<string, { key: string; body: CreatePermit }>;
export type ShareFn = (message: string) => Promise<void>;
const defaultShare: ShareFn = async (message) => { await Share.share({ message }); };

const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
const uncertain = (error: unknown) => error instanceof ApiError && error.uncertain;
const staleKey = (error: unknown) => error instanceof ApiError && ['IDEMPOTENCY_KEY_EXPIRED', 'IDEMPOTENCY_KEY_REUSED'].includes(error.code);
/** Recurso desligado ou vínculo que mudou: a seção some sem erro técnico. */
const hidden = (error: unknown) => error instanceof ApiError && (error.status === 404 || ['UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(error.code));
const closedArrival = (error: unknown) => error instanceof ApiError && ['ARRIVAL_EXPIRED', 'ARRIVAL_ALREADY_DECIDED', 'ARRIVAL_CANCELLED', 'ARRIVAL_NOT_FOUND'].includes(error.code);
const permitLabels: Record<Permit['state'], string> = { SCHEDULED: 'Agendado', ACTIVE: 'Vale agora', REVOKED: 'Revogado', EXPIRED: 'Vigência encerrada' };
const arrivalLabels: Record<Arrival['state'], string> = { WAITING: 'Aguardando resposta', APPROVED: 'Entrada autorizada', DENIED: 'Entrada recusada',
  EXPIRED: 'Sem resposta no prazo', CANCELLED: 'Encerrada pela portaria' };
const sourceLabels = { APP: 'pelo aplicativo', INTERCOM: 'pelo interfone, registrada pela portaria', IN_PERSON: 'presencialmente, registrada pela portaria',
  CONCIERGE: 'pela portaria' } as const;
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
function Field({ label, value, onChange, disabled }: { label: string; value: string; onChange(value: string): void; disabled?: boolean }) {
  return <View><Text style={styles.label}>{label}</Text>
    <TextInput accessibilityLabel={label} style={styles.input} value={value} onChangeText={onChange} editable={!disabled} autoCapitalize="none" autoCorrect={false} /></View>;
}

export function Visits({ api, accountId, membership, newKey, webOrigin, now = () => new Date(), pending: sharedPending, share = defaultShare, rulePending }: {
  api: CondPulseApi; accountId: string; membership: Membership & { unitId: string }; newKey: KeyFactory; webOrigin: string | null; now?: () => Date;
  pending?: PermitPendingStore; share?: ShareFn; rulePending?: RulePendingStore;
}) {
  const { tenantId, unitId, timeZone } = membership;
  const [ownPending] = useState<PermitPendingStore>(() => new Map());
  const pending = sharedPending ?? ownPending;
  const storeKey = `${accountId}|${tenantId}|${unitId}`;
  const scope = ['private', accountId, 'tenant', tenantId, 'unit', unitId, 'visits'] as const;
  const queries = useQueryClient();
  // Sem push ainda: as chegadas são consultadas de novo a cada 10 segundos enquanto a seção está aberta.
  const arrivals = useQuery({ queryKey: [...scope, 'arrivals'], queryFn: ({ signal }) => api.unitArrivals(tenantId, unitId, signal),
    retry: (count, error) => !hidden(error) && count < 1, refetchInterval: (query) => hidden(query.state.error) ? false : 10_000 });
  const permits = useQuery({ queryKey: [...scope, 'permits'], queryFn: ({ signal }) => api.unitPermits(tenantId, unitId, signal),
    retry: (count, error) => !hidden(error) && count < 1 });
  const [creating, setCreating] = useState(() => pending.has(storeKey));
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'info'; text: string } | null>(null);
  if (hidden(arrivals.error) && hidden(permits.error)) return null;
  const refresh = () => { void queries.invalidateQueries({ queryKey: scope }); };
  const list = permits.data?.permits ?? [];
  return <View accessibilityLabel="Acessos da unidade" style={styles.section}>
    <Text accessibilityRole="header" style={styles.title}>Acessos da unidade</Text>
    <Text style={styles.body}>{`Chegadas que aguardam a sua resposta e convites. Convite não substitui a conferência da portaria. Horários em ${timeZone}.`}</Text>
    <Button label="Atualizar acessos" secondary disabled={arrivals.isFetching || permits.isFetching} onPress={refresh} />
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}

    <Text accessibilityRole="header" style={styles.subtitle}>Chegadas na portaria</Text>
    {hidden(arrivals.error) ? <Text style={styles.body}>As chegadas desta unidade não estão disponíveis para o seu vínculo.</Text>
      : arrivals.isPending ? <ActivityIndicator accessibilityLabel="Consultando chegadas" />
        : arrivals.isError && !arrivals.data ? <View style={styles.gap}><Notice tone="error">{message(arrivals.error)}</Notice>
          <Button label="Tentar consultar chegadas de novo" secondary onPress={() => void arrivals.refetch()} /></View>
          : <>{arrivals.isError && !arrivals.isFetching ? <Notice tone="info">Não foi possível atualizar as chegadas. A lista é da última consulta: novas chegadas podem não aparecer.</Notice> : null}
            <ArrivalList api={api} scope={scope} tenantId={tenantId} unitId={unitId} timeZone={timeZone} arrivals={arrivals.data.arrivals} answered={setOutcome} /></>}

    <Text accessibilityRole="header" style={styles.subtitle}>Visitas previstas</Text>
    {hidden(permits.error) ? <Text style={styles.body}>Convidar não está disponível para o seu vínculo. Peça a um responsável pela unidade.</Text>
      : permits.isPending ? <ActivityIndicator accessibilityLabel="Consultando convites" />
        : permits.isError && !permits.data ? <View style={styles.gap}><Notice tone="error">{message(permits.error)}</Notice>
          <Button label="Tentar consultar convites de novo" secondary onPress={() => void permits.refetch()} /></View>
          : <View style={styles.gap}>
            {permits.isError ? <Notice tone="info">Não foi possível atualizar os convites. A lista é da última consulta.</Notice> : null}
            {!creating ? <Button label="Convidar visitante" onPress={() => { setOutcome(null); setCreating(true); }} />
              : <PermitForm api={api} scope={scope} membership={membership} newKey={newKey} now={now} store={pending} storeKey={storeKey}
                close={(text) => { setCreating(false); if (text) setOutcome({ tone: 'success', text }); }} />}
            {list.filter(open).length === 0 ? <Text style={styles.body}>Nenhum convite em aberto. A portaria vê o nome e o período e confere a pessoa na chegada.</Text>
              : <View accessibilityRole="list" accessibilityLabel="Convites em aberto" style={styles.gap}>{list.filter(open).map((item) =>
                <PermitCard key={item.permitId} api={api} scope={scope} tenantId={tenantId} unitId={unitId} timeZone={timeZone} item={item} webOrigin={webOrigin}
                  share={share} done={(text) => setOutcome({ tone: 'success', text })} />)}</View>}
            {list.some((item) => !open(item)) ? <View accessibilityRole="list" accessibilityLabel="Convites encerrados" style={styles.gap}>{list.filter((item) => !open(item)).map((item) =>
              <View key={item.permitId} style={styles.card}><PermitSummary item={item} timeZone={timeZone} /></View>)}</View> : null}
            {permits.data.truncated ? <Notice tone="info">A lista mostra os 100 convites de fim mais recente.</Notice> : null}
          </View>}
    <VisitRules api={api} accountId={accountId} membership={membership} newKey={newKey} now={now} pending={rulePending} />
  </View>;
}

function ArrivalList({ api, scope, tenantId, unitId, timeZone, arrivals, answered }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; unitId: string; timeZone: string; arrivals: Arrival[];
  answered(outcome: { tone: 'success' | 'info'; text: string }): void;
}) {
  const waiting = arrivals.filter((item) => item.state === 'WAITING');
  const recent = arrivals.filter((item) => item.state !== 'WAITING');
  return <View style={styles.gap}>
    {waiting.length === 0 ? <Text style={styles.body}>Ninguém aguardando. Quando a portaria registrar alguém para a unidade, a chegada aparece aqui.</Text>
      : <View accessibilityRole="list" accessibilityLabel="Chegadas aguardando resposta" style={styles.gap}>{waiting.map((item) =>
        <WaitingArrival key={item.arrivalId} api={api} scope={scope} tenantId={tenantId} unitId={unitId} timeZone={timeZone} item={item} answered={answered} />)}</View>}
    {recent.length > 0 ? <View accessibilityRole="list" accessibilityLabel="Chegadas recentes" style={styles.gap}>{recent.map((item) =>
      <View key={item.arrivalId} style={styles.card}>
        <Text style={styles.cardTitle}>{`${item.visitorName} · ${arrivalLabels[item.state]}`}</Text>
        <Text style={styles.body}>{`Chegou em ${formatWhen(item.createdAt, timeZone)}${item.documentLast4 ? ` · documento final ${item.documentLast4}` : ''}`}</Text>
        {item.state === 'EXPIRED' ? <Text style={styles.body}>Sem resposta pelo aplicativo no prazo: nada foi liberado automaticamente.</Text> : null}
        {item.decisionSource && item.decidedAt ? <Text style={styles.body}>{`${item.state === 'CANCELLED' ? 'Encerrada' : 'Respondida'} ${sourceLabels[item.decisionSource]}${item.decidedByMe ? ' por você' : ''} em ${formatWhen(item.decidedAt, timeZone)}`}</Text> : null}
        {item.decisionNote ? <Text style={styles.body}>{item.decisionNote}</Text> : null}
      </View>)}</View> : null}
  </View>;
}

function WaitingArrival({ api, scope, tenantId, unitId, timeZone, item, answered }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; unitId: string; timeZone: string; item: Arrival;
  answered(outcome: { tone: 'success' | 'info'; text: string }): void;
}) {
  const queries = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  async function answer(decision: 'APPROVE' | 'DENY') {
    if (busy) return;
    setBusy(true); setFailure(null);
    try {
      const result = await api.decideArrival(tenantId, unitId, item.arrivalId, decision);
      answered(result.state === (decision === 'APPROVE' ? 'APPROVED' : 'DENIED')
        ? { tone: 'success', text: decision === 'APPROVE' ? `Entrada de ${result.visitorName} autorizada. A portaria confere a pessoa antes de liberar.` : `Entrada de ${result.visitorName} recusada.` }
        : { tone: 'info', text: 'A resposta não confirmou a sua decisão. Atualize e confira.' });
    } catch (error) {
      // Prazo vencido ou já decidida por outra via: a lista atualizada mostra a situação real.
      if (closedArrival(error)) answered({ tone: 'info', text: `${message(error)} ${error instanceof ApiError && error.code === 'ARRIVAL_EXPIRED' ? 'A sua resposta não foi registrada.' : 'Confira a decisão registrada na lista.'}` });
      else setFailure(error);
    } finally { setBusy(false); void queries.invalidateQueries({ queryKey: [...scope, 'arrivals'] }); }
  }
  return <View style={styles.card} accessibilityLabel={`${item.visitorName}, aguardando resposta`}>
    <Text style={styles.cardTitle}>{`${item.visitorName} · Aguardando resposta`}</Text>
    <Text style={styles.body}>{`Na portaria desde ${formatWhen(item.createdAt, timeZone)} · responda até ${formatWhen(item.expiresAt, timeZone)}${item.documentLast4 ? ` · documento final ${item.documentLast4}` : ''}`}</Text>
    {item.permitId ? <Text style={styles.body}>A portaria vinculou esta chegada a um convite da unidade e pede a sua confirmação.</Text> : null}
    <Text style={styles.body}>Sem resposta no prazo nada é liberado: a portaria segue pelo interfone ou pelo procedimento do condomínio.</Text>
    {failure ? <Notice tone="error">{`${message(failure)}${uncertain(failure) ? ' Atualize e confira antes de repetir.' : ''}`}</Notice> : null}
    <Button label={`Autorizar entrada de ${item.visitorName}`} disabled={busy} onPress={() => void answer('APPROVE')} />
    <Button label={`Recusar entrada de ${item.visitorName}`} secondary disabled={busy} onPress={() => void answer('DENY')} />
  </View>;
}

function PermitSummary({ item, timeZone }: { item: Permit; timeZone: string }) {
  return <>
    <Text style={styles.cardTitle}>{`${item.visitorName} · ${permitLabels[item.state]}`}</Text>
    <Text style={styles.body}>{`De ${formatWhen(item.validFrom, timeZone)} até ${formatWhen(item.validUntil, timeZone)}${item.documentLast4 ? ` · documento final ${item.documentLast4}` : ''}`}</Text>
    {item.purpose ? <Text style={styles.body}>{item.purpose}</Text> : null}
    {item.ruleId ? <Text style={styles.body}>Janela de prestador recorrente: revogar aqui cancela só este dia.</Text>
      : item.reservationId ? <Text style={styles.body}>Convidado de reserva: termina com o evento.</Text> : null}
    <Text style={styles.body}>{item.createdByMe ? 'Criado por você' : 'Criado por outra pessoa da unidade'}</Text>
    {item.revokeReason ? <Text style={styles.body}>{`Revogado: ${item.revokeReason}`}</Text> : null}
  </>;
}

function PermitCard({ api, scope, tenantId, unitId, timeZone, item, webOrigin, share, done }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; unitId: string; timeZone: string; item: Permit; webOrigin: string | null; share: ShareFn;
  done(text: string): void;
}) {
  const queries = useQueryClient();
  const [revoking, setRevoking] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [link, setLink] = useState<string | null>(null);
  const [shareFailed, setShareFailed] = useState(false);
  const invalidate = () => {
    void queries.invalidateQueries({ queryKey: [...scope, 'permits'] });
    // Convite de convidado de reserva: a lista de convidados (fora do escopo de visitas) também muda.
    void queries.invalidateQueries({ queryKey: [...scope.slice(0, -1), 'guests'] });
  };
  const trimmed = reason.trim().replace(/\s+/g, ' ');
  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setFailure(null);
    try { await action(); } catch (error) { setFailure(error); } finally { setBusy(false); invalidate(); }
  }
  const revoke = () => run(async () => {
    await api.revokePermit(tenantId, unitId, item.permitId, { reason: trimmed, expectedVersion: item.version });
    setRevoking(false); done(`Convite de ${item.visitorName} revogado. A portaria deixa de vê-lo como autorização.`);
  });
  const generate = () => run(async () => {
    setLink(null); setShareFailed(false);
    const result = await api.shareLink(tenantId, item.permitId);
    if (webOrigin) setLink(`${webOrigin}/visita#${result.token}`);
  });
  const revokeLink = () => run(async () => { setLink(null); await api.revokeLink(tenantId, unitId, item.permitId); done(`Link do convite de ${item.visitorName} revogado. O convite continua valendo.`); });
  async function send() {
    if (!link) return;
    setShareFailed(false);
    try { await share(`Convite para ${item.visitorName}: ${link}`); } catch { setShareFailed(true); }
  }
  return <View style={styles.card} accessibilityLabel={`Convite de ${item.visitorName}`}>
    <PermitSummary item={item} timeZone={timeZone} />
    {link ? <View style={styles.gap}>
      <Text style={styles.label}>{`Link para enviar a ${item.visitorName}`}</Text>
      <Text selectable style={styles.link}>{link}</Text>
      <Text style={styles.body}>Este link aparece só agora. Ele mostra o condomínio, o primeiro nome e o período; não mostra a unidade. Gerar outro revoga este.</Text>
      {shareFailed ? <Notice tone="info">Não foi possível abrir o compartilhamento. Selecione o link e copie manualmente.</Notice> : null}
      <Button label="Enviar link" onPress={() => void send()} />
    </View> : item.shareLinkActive ? <Text style={styles.body}>Há um link em vigor para este convite.</Text> : null}
    {failure ? <Notice tone="error">{uncertain(failure) && !revoking ? 'Não foi possível confirmar o resultado. Atualize e confira; se precisar, gere outro link: o anterior deixa de valer.'
      : `${message(failure)}${uncertain(failure) ? ' Atualize e confira antes de repetir.' : ''}`}</Notice> : null}
    {revoking ? <View style={styles.gap}>
      <Field label="Motivo da revogação" value={reason} onChange={setReason} disabled={busy} />
      {trimmed && [...trimmed].length > 500 ? <Text style={styles.fieldError}>Use até 500 caracteres.</Text> : null}
      <Button label={busy ? 'Revogando…' : 'Confirmar revogação'} disabled={busy || !trimmed || [...trimmed].length > 500} onPress={() => void revoke()} />
      <Button label="Voltar" secondary disabled={busy} onPress={() => { setRevoking(false); setFailure(null); }} />
    </View> : <View style={styles.gap}>
      {webOrigin ? <Button label={busy ? 'Gerando…' : item.shareLinkActive || link ? 'Gerar novo link' : 'Compartilhar link'} secondary disabled={busy} onPress={() => void generate()} /> : null}
      {item.shareLinkActive || link ? <Button label="Revogar link" secondary disabled={busy} onPress={() => void revokeLink()} /> : null}
      <Button label="Revogar convite" secondary disabled={busy} onPress={() => { setFailure(null); setRevoking(true); }} />
    </View>}
  </View>;
}

function PermitForm({ api, scope, membership, newKey, now, store, storeKey, close }: {
  api: CondPulseApi; scope: readonly unknown[]; membership: Membership & { unitId: string }; newKey: KeyFactory; now(): Date;
  store: PermitPendingStore; storeKey: string; close(text: string | null): void;
}) {
  const queries = useQueryClient();
  const { tenantId, unitId, timeZone } = membership;
  const [name, setName] = useState('');
  const [document, setDocument] = useState('');
  const [purpose, setPurpose] = useState('');
  const [from, setFrom] = useState(() => wallTimeInZone(timeZone, now()).replace('T', ' '));
  const [until, setUntil] = useState(() => wallTimeInZone(timeZone, new Date(now().getTime() + 2 * 3_600_000)).replace('T', ' '));
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  // Convite com resultado incerto: mesma chave e mesmo corpo até a confirmação; os campos ficam travados.
  const [pending, setPending] = useState<{ key: string; body: CreatePermit } | null>(() => store.get(storeKey) ?? null);
  const locked = pending !== null;
  // Envio travado: a tela mostra exatamente o período que será repetido, no fuso do condomínio.
  const shownWall = (instant: string) => wallTimeInZone(timeZone, new Date(instant)).replace('T', ' ');
  const visitor = name.trim().replace(/\s+/g, ' ');
  const documentValue = normalizeDocument(document);
  const purposeValue = purpose.trim().replace(/\s+/g, ' ') || null;
  let period: { validFrom: string; validUntil: string } | null = null;
  let problem: string | null = null;
  try {
    const validFrom = toInstant(from.trim().replace(' ', 'T'), timeZone), validUntil = toInstant(until.trim().replace(' ', 'T'), timeZone);
    const span = Date.parse(validUntil) - Date.parse(validFrom);
    if (span <= 0) problem = 'O fim precisa ser depois do início.';
    else if (span > 7 * 24 * 3_600_000) problem = 'O convite vale por até 7 dias.';
    else period = { validFrom, validUntil };
  } catch { problem = 'Informe início e fim como AAAA-MM-DD HH:MM.'; }
  const valid = locked || ([...visitor].length >= 2 && [...visitor].length <= 160 && documentValue !== '' && (purposeValue === null || [...purposeValue].length <= 200) && period !== null);
  async function submit() {
    if (!valid || busy) return;
    const attempt = pending ?? { key: newKey(), body: { visitorName: visitor, documentLast4: documentValue, purpose: purposeValue, ...period! } };
    store.set(storeKey, attempt);
    setBusy(true); setFailure(null);
    try {
      const result = await api.createPermit(tenantId, unitId, attempt.body, attempt.key);
      setPending(null); store.delete(storeKey);
      close(result.replayed ? `Este convite já estava registrado (${permitLabels[result.permit.state].toLowerCase()}).`
        : `Convite para ${result.permit.visitorName} registrado de ${formatWhen(result.permit.validFrom, timeZone)} até ${formatWhen(result.permit.validUntil, timeZone)}.`);
    } catch (error) {
      setFailure(error);
      if (uncertain(error) || (pending !== null && staleKey(error))) setPending(attempt);
      else { setPending(null); store.delete(storeKey); }
    } finally { setBusy(false); void queries.invalidateQueries({ queryKey: [...scope, 'permits'] }); }
  }
  return <View style={styles.gap} accessibilityLabel="Convidar visitante">
    <Field label="Nome de quem vai chegar" value={locked ? pending.body.visitorName : name} onChange={setName} disabled={locked || busy} />
    <Field label="Final do documento (opcional, 2 a 4 caracteres)" value={locked ? pending.body.documentLast4 ?? '' : document} onChange={setDocument} disabled={locked || busy} />
    {documentValue === '' && !locked ? <Text style={styles.fieldError}>Informe só os 2 a 4 últimos caracteres do documento, ou deixe em branco.</Text> : null}
    <Field label="Motivo (opcional)" value={locked ? pending.body.purpose ?? '' : purpose} onChange={setPurpose} disabled={locked || busy} />
    <Field label="Início (AAAA-MM-DD HH:MM)" value={locked ? shownWall(pending.body.validFrom) : from} onChange={setFrom} disabled={locked || busy} />
    <Field label="Fim (AAAA-MM-DD HH:MM)" value={locked ? shownWall(pending.body.validUntil) : until} onChange={setUntil} disabled={locked || busy} />
    <Text style={styles.body}>{`Horário do condomínio (${timeZone}). O convite vale por até 7 dias.`}</Text>
    {problem && !locked ? <Text style={styles.fieldError}>{problem}</Text> : null}
    {failure ? <Notice tone="error">{message(failure)}</Notice> : null}
    {locked && staleKey(failure) ? <View style={styles.gap}>
      <Notice tone="info">O prazo para confirmar este envio terminou. Confira a lista de convites: se o convite aparecer, ele já foi gravado. Só descarte se ele não estiver lá.</Notice>
      <Button label="Conferi a lista: descartar este envio" secondary disabled={busy} onPress={() => { store.delete(storeKey); setPending(null); close(null); }} />
    </View> : null}
    {locked ? <Notice tone="info">O resultado não foi confirmado e o convite ficou travado. Use “Tentar confirmar o mesmo convite”: se ele já foi registrado, nada novo é gravado.</Notice> : null}
    <Button label={busy ? 'Enviando…' : locked ? 'Tentar confirmar o mesmo convite' : 'Registrar convite'} disabled={!valid || busy} onPress={() => void submit()} />
    {!locked ? <Button label="Voltar" secondary disabled={busy} onPress={() => close(null)} /> : null}
  </View>;
}

const styles = StyleSheet.create({
  section: { gap: 12, marginTop: 16 },
  gap: { gap: 10 },
  title: { fontSize: 20, fontWeight: '700', color: colors.navy },
  subtitle: { fontSize: 17, fontWeight: '700', color: colors.navy, marginTop: 8 },
  body: { fontSize: 15, color: colors.muted },
  label: { fontSize: 14, fontWeight: '600', color: colors.text },
  link: { fontSize: 14, color: colors.text },
  fieldError: { fontSize: 14, color: colors.error },
  input: { minHeight: touch, borderWidth: 1, borderColor: colors.line, borderRadius: 10, paddingHorizontal: 14, fontSize: 16, backgroundColor: colors.surface, color: colors.text },
  button: { minHeight: touch, borderRadius: 10, backgroundColor: colors.teal, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  secondary: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.line },
  dimmed: { opacity: 0.6 },
  buttonText: { color: colors.onAction, fontSize: 16, fontWeight: '700' },
  secondaryText: { color: colors.navy },
  notice: { padding: 12, borderRadius: 10, fontSize: 15 },
  error: { backgroundColor: colors.errorBg, color: colors.error },
  info: { backgroundColor: colors.infoBg, color: colors.text },
  success: { backgroundColor: colors.infoBg, color: colors.navy },
  card: { padding: 16, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface, gap: 6 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
});
