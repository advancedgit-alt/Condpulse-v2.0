import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi, type Membership } from '../api/client';
import { normalizePlate, type CreateMove, type Move, type MoveKind, type MoveRules, type MoveState } from '../api/moves';
import { addDays, dateInZone, formatWhen, toInstant } from '../time';
import type { KeyFactory } from './Reservations';
import { colors, touch } from './theme';

/**
 * Mudança e entrega de móveis no celular (CP-042): regras publicadas pela gestão, pedido com aceite da versão vigente,
 * acompanhamento da decisão e retirada antes do início. A aprovação não reserva elevador nem área. Resultado incerto
 * mantém a mesma chave: repetir confirma o mesmo pedido, sem gravar outro.
 */
export const kindLabels: Record<MoveKind, string> = { MOVE_IN: 'Mudança de entrada', MOVE_OUT: 'Mudança de saída', FURNITURE_DELIVERY: 'Entrega de móveis' };
const stateLabels: Record<MoveState, string> = { REQUESTED: 'Aguardando a gestão', APPROVED: 'Aprovada', REJECTED: 'Recusada', CANCELLED: 'Cancelada' };
const dayNames = ['segunda', 'terça', 'quarta', 'quinta', 'sexta', 'sábado', 'domingo'];
const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
const uncertain = (error: unknown) => error instanceof ApiError && error.uncertain;
/** Recurso desligado, dependente ou vínculo que mudou: a seção some sem erro técnico. */
const hidden = (error: unknown) => error instanceof ApiError && (error.status === 404 || ['UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(error.code));

export function rulesSummary(rules: MoveRules): string {
  const days = rules.weekDays.length === 7 ? 'todos os dias' : rules.weekDays.map((day) => dayNames[day - 1]).join(', ');
  return `${days}, das ${rules.startTime} às ${rules.endTime}; antecedência mínima de ${rules.minNoticeHours} h; até ${rules.maxDurationMinutes} min por pedido.`;
}

function Button({ label, onPress, disabled, secondary }: { label: string; onPress(): void; disabled?: boolean; secondary?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled: !!disabled }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, secondary && styles.secondary, (disabled || pressed) && styles.dimmed]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{label}</Text>
  </Pressable>;
}
function Notice({ tone, children }: { tone: 'error' | 'info' | 'success'; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, styles[tone]]}>{children}</Text>;
}
function Field({ label, value, onChange, disabled, placeholder }: { label: string; value: string; onChange(value: string): void; disabled?: boolean; placeholder?: string }) {
  return <View><Text style={styles.label}>{label}</Text>
    <TextInput accessibilityLabel={label} style={styles.input} value={value} onChangeText={onChange} editable={!disabled} placeholder={placeholder}
      autoCapitalize="none" autoCorrect={false} /></View>;
}

export function Moves({ api, accountId, membership, newKey, now = () => new Date() }: {
  api: CondPulseApi; accountId: string; membership: Membership & { unitId: string }; newKey: KeyFactory; now?: () => Date;
}) {
  const { tenantId, unitId, timeZone } = membership;
  const scope = ['private', accountId, 'tenant', tenantId, 'unit', unitId] as const;
  const list = useQuery({ queryKey: [...scope, 'moves'], queryFn: ({ signal }) => api.unitMoves(tenantId, unitId, signal),
    retry: (count, error) => !hidden(error) && count < 1 });
  const rules = useQuery({ queryKey: [...scope, 'move-rules'], queryFn: ({ signal }) => api.moveRules(tenantId, signal), enabled: !hidden(list.error),
    retry: (count, error) => !hidden(error) && count < 1 });
  const [opening, setOpening] = useState(false);
  const [outcome, setOutcome] = useState<string | null>(null);
  if (hidden(list.error) || hidden(rules.error)) return null;
  const current = rules.data ?? null;
  return <View accessibilityLabel="Mudança e entrega de móveis" style={styles.section}>
    <Text accessibilityRole="header" style={styles.title}>Mudança e entrega de móveis</Text>
    {rules.isPending ? <ActivityIndicator accessibilityLabel="Consultando as regras de mudança" />
      : rules.isError ? <View><Notice tone="error">{message(rules.error)}</Notice><Button label="Tentar novamente" secondary onPress={() => void rules.refetch()} /></View>
        : !current ? <Notice tone="info">A gestão ainda não publicou as regras de mudança. Quando publicar, o pedido fica disponível aqui.</Notice>
          : <View style={styles.card}>
            <Text style={styles.cardTitle}>{`Regras de mudança (versão ${current.version})`}</Text>
            <Text style={styles.body}>{rulesSummary(current)}</Text>
            <Text style={styles.body}>{current.rulesText}</Text>
            <Text style={styles.body}>A aprovação libera o período combinado com a portaria; não reserva elevador nem área.</Text>
          </View>}
    {outcome ? <Notice tone="success">{outcome}</Notice> : null}
    {current && !opening ? <Button label="Pedir mudança" onPress={() => { setOutcome(null); setOpening(true); }} /> : null}
    {current && opening ? <MoveForm api={api} scope={scope} membership={membership} rules={current} newKey={newKey} now={now}
      close={(text) => { setOpening(false); if (text) setOutcome(text); }} /> : null}
    <Button label="Atualizar mudanças" secondary disabled={list.isFetching} onPress={() => { void list.refetch(); void rules.refetch(); }} />
    {list.isPending ? <ActivityIndicator accessibilityLabel="Consultando os pedidos de mudança" />
      : list.isError ? <View><Notice tone="error">{message(list.error)}</Notice><Button label="Tentar de novo" secondary onPress={() => void list.refetch()} /></View>
        : list.data.moves.length === 0 ? <Text style={styles.body}>Nenhum pedido de mudança.</Text>
          : <View accessibilityRole="list" accessibilityLabel="Pedidos de mudança da unidade">{list.data.moves.map((item) =>
            <MoveCard key={item.moveId} api={api} scope={scope} tenantId={tenantId} item={item} timeZone={timeZone} now={now} done={setOutcome} />)}</View>}
    {list.data?.truncated ? <Notice tone="info">A lista mostra os 100 pedidos mais recentes.</Notice> : null}
  </View>;
}

function MoveCard({ api, scope, tenantId, item, timeZone, now, done }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; item: Move; timeZone: string; now(): Date; done(text: string): void;
}) {
  const queries = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const open = (item.state === 'REQUESTED' || item.state === 'APPROVED') && Date.parse(item.startsAt) > now().getTime();
  async function cancel() {
    setBusy(true); setFailure(null);
    try { await api.cancelMove(tenantId, item.moveId, item.version); setConfirming(false); done('Pedido de mudança retirado.'); }
    catch (error) { setFailure(error); }
    finally { setBusy(false); void queries.invalidateQueries({ queryKey: [...scope, 'moves'] }); }
  }
  return <View style={styles.card} accessibilityLabel={`${kindLabels[item.kind]}, ${stateLabels[item.state]}`}>
    <Text style={styles.cardTitle}>{`${kindLabels[item.kind]} · ${stateLabels[item.state]}`}</Text>
    <Text style={styles.body}>{`${formatWhen(item.startsAt, timeZone)} até ${formatWhen(item.endsAt, timeZone)}`}</Text>
    <Text style={styles.body}>{`Responsável: ${item.providerName}${item.vehiclePlate ? ` · placa ${item.vehiclePlate}` : ''}`}</Text>
    {item.items ? <Text style={styles.body}>{`Itens: ${item.items}`}</Text> : null}
    {item.decisionNote ? <Text style={styles.body}>{`${item.state === 'CANCELLED' ? 'Motivo do cancelamento' : 'Nota da gestão'}: ${item.decisionNote}`}</Text> : null}
    {failure ? <Notice tone="error">{`${message(failure)}${uncertain(failure) ? ' Atualize e confira antes de repetir.' : ''}`}</Notice> : null}
    {open && !confirming ? <Button label="Retirar pedido" secondary onPress={() => { setFailure(null); setConfirming(true); }} /> : null}
    {open && confirming ? <View style={styles.gap}>
      <Text style={styles.body}>Retirar este pedido? A gestão e a portaria deixam de considerar o período.</Text>
      <Button label={busy ? 'Retirando…' : 'Confirmar retirada'} disabled={busy} onPress={() => void cancel()} />
      <Button label="Voltar" secondary disabled={busy} onPress={() => setConfirming(false)} />
    </View> : null}
  </View>;
}

function MoveForm({ api, scope, membership, rules, newKey, now, close }: {
  api: CondPulseApi; scope: readonly unknown[]; membership: Membership & { unitId: string }; rules: MoveRules; newKey: KeyFactory; now(): Date;
  close(text: string | null): void;
}) {
  const queries = useQueryClient();
  const { tenantId, unitId, timeZone } = membership;
  const [kind, setKind] = useState<MoveKind>('MOVE_IN');
  const [date, setDate] = useState(() => addDays(dateInZone(timeZone, now()), Math.ceil(rules.minNoticeHours / 24) + 1));
  const [start, setStart] = useState(rules.startTime);
  const [end, setEnd] = useState(rules.endTime);
  const [provider, setProvider] = useState('');
  const [plate, setPlate] = useState('');
  const [items, setItems] = useState('');
  const [accepted, setAccepted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  // Pedido com resultado incerto: mesma chave e mesmo corpo até a confirmação; os campos ficam travados.
  const [pending, setPending] = useState<{ key: string; body: CreateMove } | null>(null);
  let window: { startsAt: string; endsAt: string } | null = null;
  let problem: string | null = null;
  try {
    const startsAt = toInstant(`${date}T${start}`, timeZone);
    const endsAt = toInstant(`${date}T${end}`, timeZone);
    if (Date.parse(endsAt) <= Date.parse(startsAt)) problem = 'O fim precisa ser depois do início.';
    else if ((Date.parse(endsAt) - Date.parse(startsAt)) / 60_000 > rules.maxDurationMinutes) problem = `As regras permitem até ${rules.maxDurationMinutes} minutos por pedido.`;
    else window = { startsAt, endsAt };
  } catch { problem = 'Informe a data (AAAA-MM-DD) e os horários (HH:MM).'; }
  const name = provider.trim().replace(/\s+/g, ' ');
  const normalizedPlate = normalizePlate(plate);
  const normalizedItems = items.trim() ? items.trim() : null;
  const locked = pending !== null;
  const valid = locked || (window !== null && [...name].length >= 2 && [...name].length <= 160 && normalizedPlate !== ''
    && (normalizedItems === null || [...normalizedItems].length <= 500) && accepted);
  async function submit() {
    if (!valid || busy) return;
    const attempt = pending ?? { key: newKey(), body: { kind, startsAt: window!.startsAt, endsAt: window!.endsAt, providerName: name,
      vehiclePlate: normalizedPlate, items: normalizedItems, acceptedRulesVersion: rules.version } };
    setBusy(true); setFailure(null);
    try {
      const result = await api.createMove(tenantId, unitId, attempt.body, attempt.key);
      setPending(null);
      close(result.replayed ? `Este pedido já estava enviado (${stateLabels[result.move.state].toLowerCase()}).`
        : `Pedido de ${kindLabels[result.move.kind].toLowerCase()} enviado para ${formatWhen(result.move.startsAt, timeZone)}. A gestão decide por aqui.`);
    } catch (error) {
      setFailure(error);
      if (uncertain(error)) setPending(attempt);
      else {
        setPending(null);
        if (error instanceof ApiError && error.code === 'MOVE_RULES_CHANGED') {
          setAccepted(false);
          void queries.invalidateQueries({ queryKey: [...scope, 'move-rules'] });
        }
      }
    } finally { setBusy(false); void queries.invalidateQueries({ queryKey: [...scope, 'moves'] }); }
  }
  return <View style={styles.gap} accessibilityLabel="Pedir mudança">
    <Text style={styles.label}>Tipo</Text>
    <View style={styles.row}>{(['MOVE_IN', 'MOVE_OUT', 'FURNITURE_DELIVERY'] as const).map((value) =>
      <Pressable key={value} accessibilityRole="button" accessibilityLabel={kindLabels[value]} accessibilityState={{ selected: kind === value, disabled: locked || busy }}
        disabled={locked || busy} onPress={() => setKind(value)} style={[styles.chip, kind === value && styles.chipSelected]}>
        <Text style={[styles.chipText, kind === value && styles.chipTextSelected]}>{kindLabels[value]}</Text>
      </Pressable>)}</View>
    <Field label="Data (AAAA-MM-DD)" value={date} onChange={setDate} disabled={locked || busy} />
    <Field label="Início (HH:MM)" value={start} onChange={setStart} disabled={locked || busy} />
    <Field label="Fim (HH:MM)" value={end} onChange={setEnd} disabled={locked || busy} />
    <Text style={styles.body}>{`Horário do condomínio (${timeZone}). ${rulesSummary(rules)}`}</Text>
    {problem && !locked ? <Text style={styles.fieldError}>{problem}</Text> : null}
    <Field label="Responsável ou transportadora" value={provider} onChange={setProvider} disabled={locked || busy} />
    <Field label="Placa do veículo (opcional)" value={plate} onChange={setPlate} disabled={locked || busy} />
    {normalizedPlate === '' && !locked ? <Text style={styles.fieldError}>Informe a placa com 5 a 8 letras e números, ou deixe em branco.</Text> : null}
    <Field label="Itens (opcional)" value={items} onChange={setItems} disabled={locked || busy} />
    <Pressable accessibilityRole="checkbox" accessibilityLabel={`Li e aceito as regras de mudança (versão ${pending?.body.acceptedRulesVersion ?? rules.version})`}
      accessibilityState={{ checked: accepted || locked, disabled: locked || busy }} disabled={locked || busy} onPress={() => setAccepted(!accepted)} style={styles.row}>
      <Text style={styles.check}>{accepted || locked ? '☑' : '☐'}</Text>
      <Text style={styles.label}>{`Li e aceito as regras de mudança (versão ${pending?.body.acceptedRulesVersion ?? rules.version})`}</Text>
    </Pressable>
    {failure ? <Notice tone="error">{message(failure)}</Notice> : null}
    {locked ? <Notice tone="info">O resultado não foi confirmado e o pedido ficou travado. Use “Tentar confirmar o mesmo pedido”: se ele já foi enviado, nada novo é gravado.</Notice> : null}
    <Button label={busy ? 'Enviando…' : locked ? 'Tentar confirmar o mesmo pedido' : 'Enviar pedido de mudança'} disabled={!valid || busy} onPress={() => void submit()} />
    {!locked ? <Button label="Voltar" secondary disabled={busy} onPress={() => close(null)} /> : null}
  </View>;
}

const styles = StyleSheet.create({
  section: { gap: 12, marginTop: 16 },
  gap: { gap: 10 },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, alignItems: 'center' },
  title: { fontSize: 20, fontWeight: '700', color: colors.navy },
  body: { fontSize: 15, color: colors.muted },
  label: { fontSize: 14, fontWeight: '600', color: colors.text },
  check: { fontSize: 22, color: colors.navy },
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
  card: { padding: 16, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface, gap: 6 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
  chip: { minHeight: touch, paddingHorizontal: 14, borderRadius: 24, borderWidth: 1, borderColor: colors.line, justifyContent: 'center', backgroundColor: colors.surface },
  chipSelected: { backgroundColor: colors.teal, borderColor: colors.teal },
  chipText: { color: colors.navy, fontSize: 15 },
  chipTextSelected: { color: '#fff', fontWeight: '700' },
});
