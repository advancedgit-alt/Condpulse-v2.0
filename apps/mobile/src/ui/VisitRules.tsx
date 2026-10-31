import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi, type Membership } from '../api/client';
import { normalizeDocument, type CreateRule, type VisitRule } from '../api/visits';
import { addDays, dateInZone, formatWhen } from '../time';
import type { KeyFactory } from './Reservations';
import { colors, touch } from './theme';

/**
 * Prestador recorrente no celular (CP-039): diarista, cuidador etc. A regra gera janelas (convites) só nos dias e
 * horários escolhidos; fora delas não há convite e a portaria segue o procedimento de chegada sem convite. Cadastro com
 * resultado incerto mantém a mesma chave; revogação vale para a versão lida e cancela as janelas em vigor e as próximas.
 */
export type RulePendingStore = Map<string, { key: string; body: CreateRule }>;
const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
const uncertain = (error: unknown) => error instanceof ApiError && error.uncertain;
const staleKey = (error: unknown) => error instanceof ApiError && ['IDEMPOTENCY_KEY_EXPIRED', 'IDEMPOTENCY_KEY_REUSED'].includes(error.code);
const hidden = (error: unknown) => error instanceof ApiError && (error.status === 404 || ['UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(error.code));
/** Estado mudado por outra pessoa: o formulário fecha e a lista atualizada mostra a situação real. */
const changed = (error: unknown) => error instanceof ApiError && ['VISIT_RULE_REVOKED', 'VISIT_RULE_CHANGED', 'VISIT_RULE_NOT_FOUND'].includes(error.code);
const dayNames = ['segunda', 'terça', 'quarta', 'quinta', 'sexta', 'sábado', 'domingo'] as const;
const shortDay = ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom'] as const;
const dayList = (days: number[]) => days.length === 7 ? 'todos os dias' : days.map((day) => dayNames[day - 1]).join(', ');
const brDay = (day: string) => { const [year, month, date] = day.split('-'); return `${date}/${month}/${year}`; };
const stateLabels: Record<VisitRule['state'], string> = { ACTIVE: 'Ativa', REVOKED: 'Revogada', EXPIRED: 'Encerrada' };

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

export function VisitRules({ api, accountId, membership, newKey, now = () => new Date(), pending: sharedPending }: {
  api: CondPulseApi; accountId: string; membership: Membership & { unitId: string }; newKey: KeyFactory; now?: () => Date; pending?: RulePendingStore;
}) {
  const { tenantId, unitId, timeZone } = membership;
  const [ownPending] = useState<RulePendingStore>(() => new Map());
  const pending = sharedPending ?? ownPending;
  const storeKey = `${accountId}|${tenantId}|${unitId}`;
  const scope = ['private', accountId, 'tenant', tenantId, 'unit', unitId, 'visits', 'rules'] as const;
  const rules = useQuery({ queryKey: scope, queryFn: ({ signal }) => api.unitRules(tenantId, unitId, signal), retry: (count, error) => !hidden(error) && count < 1 });
  const [creating, setCreating] = useState(() => pending.has(storeKey));
  const [revoking, setRevoking] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  const adult = membership.role !== 'DEPENDENT';
  if (hidden(rules.error)) return null;
  const list = rules.data?.rules ?? [];
  const active = list.filter((rule) => rule.state === 'ACTIVE');
  const closed = list.filter((rule) => rule.state !== 'ACTIVE');
  const finish = (text: string | null) => { setCreating(false); setRevoking(null); if (text) setOutcome(text); };
  return <View accessibilityLabel="Prestadores recorrentes" style={styles.section}>
    <Text accessibilityRole="header" style={styles.subtitle}>Prestadores recorrentes</Text>
    <Text style={styles.body}>Quem vem em dias fixos: a portaria vê o convite só nos dias e horários da regra.</Text>
    {outcome ? <Notice tone="success">{outcome}</Notice> : null}
    {rules.isPending ? <ActivityIndicator accessibilityLabel="Consultando prestadores" />
      : rules.isError && !rules.data ? <View style={styles.gap}><Notice tone="error">{message(rules.error)}</Notice>
        <Button label="Tentar consultar prestadores de novo" secondary onPress={() => void rules.refetch()} /></View>
        : <View style={styles.gap}>
          {rules.isError ? <Notice tone="info">Não foi possível atualizar os prestadores. A lista é da última consulta.</Notice> : null}
          {!creating ? <Button label="Cadastrar prestador" onPress={() => { setOutcome(null); setRevoking(null); setCreating(true); }} />
            : <RuleForm api={api} scope={scope} membership={membership} newKey={newKey} now={now} store={pending} storeKey={storeKey} close={finish} />}
          {active.length === 0 ? <Text style={styles.body}>Nenhum prestador recorrente ativo.</Text>
            : <View accessibilityRole="list" accessibilityLabel="Prestadores ativos" style={styles.gap}>{active.map((rule) =>
              <View key={rule.ruleId} style={styles.card} accessibilityLabel={`Prestador ${rule.visitorName}`}>
                <RuleSummary rule={rule} timeZone={timeZone} />
                {revoking === rule.ruleId ? <RevokeRule api={api} scope={scope} tenantId={tenantId} unitId={unitId} rule={rule} close={finish} />
                  : adult || rule.createdByMe ? <Button label={`Revogar regra de ${rule.visitorName}`} secondary
                    onPress={() => { setOutcome(null); setCreating(false); setRevoking(rule.ruleId); }} /> : null}
              </View>)}</View>}
          {closed.length > 0 ? <View accessibilityRole="list" accessibilityLabel="Prestadores encerrados" style={styles.gap}>{closed.map((rule) =>
            <View key={rule.ruleId} style={styles.card}><RuleSummary rule={rule} timeZone={timeZone} /></View>)}</View> : null}
          {rules.data.truncated ? <Notice tone="info">A lista mostra as 100 regras mais recentes.</Notice> : null}
        </View>}
  </View>;
}

function RuleSummary({ rule, timeZone }: { rule: VisitRule; timeZone: string }) {
  return <>
    <Text style={styles.cardTitle}>{`${rule.visitorName} · ${stateLabels[rule.state]}`}</Text>
    <Text style={styles.body}>{`${dayList(rule.weekDays)}, das ${rule.startTime} às ${rule.endTime} · de ${brDay(rule.validFrom)} até ${brDay(rule.validUntil)}${rule.documentLast4 ? ` · documento final ${rule.documentLast4}` : ''}`}</Text>
    {rule.purpose ? <Text style={styles.body}>{rule.purpose}</Text> : null}
    {rule.state === 'ACTIVE' ? <Text style={styles.body}>{rule.nextWindowFrom && rule.nextWindowUntil
      ? `Próxima janela: ${formatWhen(rule.nextWindowFrom, timeZone)} até ${formatWhen(rule.nextWindowUntil, timeZone)}.` : 'Sem janela nos próximos dias.'}</Text> : null}
    <Text style={styles.body}>{rule.createdByMe ? 'Criada por você' : 'Criada por outra pessoa da unidade'}</Text>
    {rule.revokeReason ? <Text style={styles.body}>{`Revogada: ${rule.revokeReason}`}</Text> : null}
  </>;
}

function RevokeRule({ api, scope, tenantId, unitId, rule, close }: {
  api: CondPulseApi; scope: readonly unknown[]; tenantId: string; unitId: string; rule: VisitRule; close(text: string | null): void;
}) {
  const queries = useQueryClient();
  const [read] = useState(rule.version);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const trimmed = reason.trim().replace(/\s+/g, ' ');
  const valid = trimmed.length > 0 && [...trimmed].length <= 500;
  async function submit() {
    if (!valid || busy) return;
    setBusy(true); setFailure(null);
    try {
      const result = await api.revokeRule(tenantId, unitId, rule.ruleId, { reason: trimmed, expectedVersion: read });
      close(`Regra de ${result.visitorName} revogada. As próximas janelas foram canceladas.`);
    } catch (error) {
      if (changed(error)) close(`${message(error)} Nada foi alterado.`);
      else setFailure(error);
    } finally { setBusy(false); void queries.invalidateQueries({ queryKey: scope.slice(0, -1) }); }
  }
  return <View style={styles.gap}>
    <Notice tone="info">Revogar cancela as janelas em vigor e as próximas. Quem chegar depois segue o procedimento de chegada sem convite.</Notice>
    <Field label="Motivo da revogação da regra" value={reason} onChange={setReason} disabled={busy} />
    {failure ? <Notice tone="error">{`${message(failure)}${uncertain(failure) ? ' Atualize e confira antes de repetir.' : ''}`}</Notice> : null}
    <Button label={busy ? 'Revogando…' : 'Confirmar revogação da regra'} disabled={!valid || busy} onPress={() => void submit()} />
    <Button label="Manter regra" secondary disabled={busy} onPress={() => close(null)} />
  </View>;
}

function RuleForm({ api, scope, membership, newKey, now, store, storeKey, close }: {
  api: CondPulseApi; scope: readonly unknown[]; membership: Membership & { unitId: string }; newKey: KeyFactory; now(): Date;
  store: RulePendingStore; storeKey: string; close(text: string | null): void;
}) {
  const queries = useQueryClient();
  const { tenantId, unitId, timeZone } = membership;
  const today = dateInZone(timeZone, now());
  const [name, setName] = useState('');
  const [document, setDocument] = useState('');
  const [purpose, setPurpose] = useState('');
  const [days, setDays] = useState<number[]>([]);
  const [startTime, setStartTime] = useState('08:00');
  const [endTime, setEndTime] = useState('17:00');
  const [validFrom, setValidFrom] = useState(today);
  const [validUntil, setValidUntil] = useState(() => addDays(today, 30));
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [pending, setPending] = useState<{ key: string; body: CreateRule } | null>(() => store.get(storeKey) ?? null);
  const locked = pending !== null;
  const visitor = name.trim().replace(/\s+/g, ' ');
  const documentValue = normalizeDocument(document);
  const purposeValue = purpose.trim().replace(/\s+/g, ' ') || null;
  const timeValid = /^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(startTime) && /^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(endTime) && startTime < endTime;
  let periodError: string | null = null;
  try {
    periodError = !/^\d{4}-\d{2}-\d{2}$/.test(validFrom) || !/^\d{4}-\d{2}-\d{2}$/.test(validUntil) ? 'Informe o primeiro e o último dia (AAAA-MM-DD).'
      : validFrom < today ? 'A regra começa hoje ou depois.'
        : validFrom > addDays(today, 90) ? 'A regra começa em até 90 dias.'
          : validUntil < validFrom ? 'O último dia precisa ser o primeiro ou depois.'
            : validUntil > addDays(validFrom, 180) ? 'A regra vale por até 180 dias.' : null;
  } catch { periodError = 'Informe o primeiro e o último dia (AAAA-MM-DD).'; }
  const valid = locked || ([...visitor].length >= 2 && [...visitor].length <= 160 && documentValue !== '' && (purposeValue === null || [...purposeValue].length <= 200)
    && days.length > 0 && timeValid && periodError === null);
  const toggle = (day: number) => setDays((current) => current.includes(day) ? current.filter((item) => item !== day) : [...current, day].sort((a, b) => a - b));
  async function submit() {
    if (!valid || busy) return;
    const attempt = pending ?? { key: newKey(), body: { visitorName: visitor, documentLast4: documentValue, purpose: purposeValue, weekDays: days, startTime, endTime, validFrom, validUntil } };
    store.set(storeKey, attempt);
    setBusy(true); setFailure(null);
    try {
      const result = await api.createRule(tenantId, unitId, attempt.body, attempt.key);
      setPending(null); store.delete(storeKey);
      close(`${result.replayed ? 'Esta regra já estava cadastrada' : 'Prestador cadastrado'}: ${result.rule.visitorName}, ${dayList(result.rule.weekDays)}, das ${result.rule.startTime} às ${result.rule.endTime}.`);
    } catch (error) {
      setFailure(error);
      if (uncertain(error) || (pending !== null && staleKey(error))) setPending(attempt);
      else { setPending(null); store.delete(storeKey); }
    } finally { setBusy(false); void queries.invalidateQueries({ queryKey: scope.slice(0, -1) }); }
  }
  const shown = pending?.body;
  const disabled = locked || busy;
  return <View style={styles.gap} accessibilityLabel="Cadastrar prestador recorrente">
    <Field label="Nome do prestador" value={shown?.visitorName ?? name} onChange={setName} disabled={disabled} />
    <Field label="Final do documento do prestador (opcional)" value={shown ? shown.documentLast4 ?? '' : document} onChange={setDocument} disabled={disabled} />
    {documentValue === '' && !locked ? <Text style={styles.fieldError}>Informe só os 2 a 4 últimos caracteres do documento, ou deixe em branco.</Text> : null}
    <Field label="Serviço (opcional)" value={shown ? shown.purpose ?? '' : purpose} onChange={setPurpose} disabled={disabled} />
    <Text style={styles.label}>Dias da semana</Text>
    <View style={styles.row}>{shortDay.map((label, index) => {
      const selected = (shown?.weekDays ?? days).includes(index + 1);
      return <Pressable key={label} accessibilityRole="checkbox" accessibilityLabel={dayNames[index]} accessibilityState={{ checked: selected, disabled }}
        disabled={disabled} onPress={() => toggle(index + 1)} style={[styles.chip, selected && styles.chipSelected]}>
        <Text style={[styles.chipText, selected && styles.chipTextSelected]}>{label}</Text>
      </Pressable>;
    })}</View>
    <Field label="Chega a partir de (HH:MM)" value={shown?.startTime ?? startTime} onChange={setStartTime} disabled={disabled} />
    <Field label="Até (HH:MM)" value={shown?.endTime ?? endTime} onChange={setEndTime} disabled={disabled} />
    {!timeValid && !locked ? <Text style={styles.fieldError}>O fim precisa ser depois do início, no mesmo dia.</Text> : null}
    <Field label="Primeiro dia (AAAA-MM-DD)" value={shown?.validFrom ?? validFrom} onChange={setValidFrom} disabled={disabled} />
    <Field label="Último dia (AAAA-MM-DD)" value={shown?.validUntil ?? validUntil} onChange={setValidUntil} disabled={disabled} />
    {periodError && !locked ? <Text style={styles.fieldError}>{periodError}</Text> : null}
    <Text style={styles.body}>{`Horários em ${timeZone}. Fora dos dias e horários da regra não há convite.`}</Text>
    {failure ? <Notice tone="error">{message(failure)}</Notice> : null}
    {locked && staleKey(failure) ? <View style={styles.gap}>
      <Notice tone="info">O prazo para confirmar este envio terminou. Confira a lista: se a regra aparecer, ela já foi gravada. Só descarte se ela não estiver lá.</Notice>
      <Button label="Conferi a lista: descartar este cadastro" secondary disabled={busy} onPress={() => { store.delete(storeKey); setPending(null); close(null); }} />
    </View> : null}
    {locked ? <Notice tone="info">O resultado não foi confirmado e o cadastro ficou travado. Use “Tentar confirmar a mesma regra”: se ela já foi gravada, nada novo é criado.</Notice> : null}
    <Button label={busy ? 'Cadastrando…' : locked ? 'Tentar confirmar a mesma regra' : 'Cadastrar prestador recorrente'} disabled={!valid || busy} onPress={() => void submit()} />
    {!locked ? <Button label="Voltar sem cadastrar" secondary disabled={busy} onPress={() => close(null)} /> : null}
  </View>;
}

const styles = StyleSheet.create({
  section: { gap: 12, marginTop: 8 },
  gap: { gap: 10 },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, alignItems: 'center' },
  subtitle: { fontSize: 17, fontWeight: '700', color: colors.navy, marginTop: 8 },
  body: { fontSize: 15, color: colors.muted },
  label: { fontSize: 14, fontWeight: '600', color: colors.text },
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
  chip: { minHeight: touch, minWidth: touch, paddingHorizontal: 12, borderRadius: 24, borderWidth: 1, borderColor: colors.line, justifyContent: 'center', alignItems: 'center', backgroundColor: colors.surface },
  chipSelected: { backgroundColor: colors.teal, borderColor: colors.teal },
  chipText: { color: colors.navy, fontSize: 15 },
  chipTextSelected: { color: colors.onAction, fontWeight: '700' },
});
