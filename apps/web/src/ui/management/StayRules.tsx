import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { stayRulesApi, type StayRule } from '../../api/stayRules';
import { stayKindLabels } from '../../api/stays';
import { ErrorState, Loading, Notice } from '../primitives';
import { ActionFeedback, managementKey, useAccessGuard, useAction, useManagement } from './shared';

export function StayRulesSection() {
  const scope = useManagement();
  const query = useQuery({ queryKey: managementKey(scope, 'stay-rules'), queryFn: ({ signal }) => stayRulesApi.list(scope.tenantId, signal),
    refetchOnWindowFocus: false });
  const denied = useAccessGuard(query.error);
  if (denied) return null;
  return <section className="management-section" aria-labelledby="stay-rules-title">
    <div className="section-heading"><div><span className="section-index">PERMANÊNCIAS</span><h2 id="stay-rules-title">Horários e alertas</h2>
      <p>Regras por categoria, no fuso {scope.timeZone}. Entrada e saída continuam registradas com o controle desligado.</p></div>
      <button className="button button-secondary" type="button" disabled={query.isFetching} onClick={() => void query.refetch()}>Atualizar regras</button></div>
    <Notice>O horário e as tolerâncias ficam fixados quando a entrada é registrada. Desligar suspende o horário diário e novas escaladas; reativar pode gerar os alertas pendentes das permanências abertas. O fim do convite ou da reserva também limita a permanência.</Notice>
    <p className="field-help">Esta etapa configura horários e alertas. Bloqueio de reentrada, aceite de responsabilidade e confirmação física têm validação própria; um alerta não prova que a pessoa permanece no local.</p>
    {query.isPending ? <Loading label="Consultando regras de permanência…" /> : query.isError && !query.data
      ? <ErrorState error={query.error} retry={() => void query.refetch()} />
      : query.data?.map((rule) => <StayRuleForm key={`${rule.kind}:${rule.version ?? 'default'}:${rule.enabled}`} rule={rule} />)}
  </section>;
}
function StayRuleForm({ rule }: { rule: StayRule }) {
  const scope = useManagement(); const queries = useQueryClient(); const action = useAction();
  const [enabled, setEnabled] = useState(rule.enabled); const [scheduled, setScheduled] = useState(rule.startsLocal !== null);
  const [start, setStart] = useState(rule.startsLocal ?? '08:00'); const [end, setEnd] = useState(rule.endsLocal ?? '17:00');
  const [max, setMax] = useState(String(rule.maxMinutes)); const [l1, setL1] = useState(String(rule.l1AfterMinutes));
  const [l2, setL2] = useState(String(rule.l2AfterMinutes));
  const id = `stay-rule-${rule.kind}`; const busy = action.state.pending;
  const valid = /^\d+$/.test(max) && Number(max) >= 5 && Number(max) <= 1440 && /^\d+$/.test(l1) && Number(l1) <= 720
    && /^\d+$/.test(l2) && Number(l2) > Number(l1) && Number(l2) <= 1440 && (!scheduled || (start !== '' && end !== '' && start < end));
  async function save(event: FormEvent) {
    event.preventDefault(); if (!valid || busy) return;
    await action.run(() => stayRulesApi.update(scope.tenantId, rule.kind, { maxMinutes: Number(max), l1AfterMinutes: Number(l1),
      l2AfterMinutes: Number(l2), version: rule.version, enabled, scheduleEnabled: scheduled,
      startsLocal: scheduled ? start : null, endsLocal: scheduled ? end : null }), 'Regra de permanência gravada e auditada.',
    (rows) => { queries.setQueryData(managementKey(scope, 'stay-rules'), rows); });
  }
  return <form className="panel" aria-label={`Regra de permanência: ${stayKindLabels[rule.kind]}`} onSubmit={(event) => void save(event)}>
    <h3>{stayKindLabels[rule.kind]}</h3><p className="field-help">{rule.source === 'TENANT_RULE' ? 'Regra do condomínio' : 'Padrão provisório da plataforma'}</p>
    <label><input type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} disabled={busy} /> Controle de permanência ativo</label>
    <label><input type="checkbox" checked={scheduled} onChange={(event) => setScheduled(event.target.checked)} disabled={busy} /> Limitar ao horário diário</label>
    <div className="form-grid"><label htmlFor={`${id}-start`}>Entrada a partir de<input id={`${id}-start`} type="time" value={start} onChange={(event) => setStart(event.target.value)} disabled={busy || !scheduled} required={scheduled} /></label>
      <label htmlFor={`${id}-end`}>Saída até<input id={`${id}-end`} type="time" value={end} onChange={(event) => setEnd(event.target.value)} disabled={busy || !scheduled} required={scheduled} /></label>
      <label htmlFor={`${id}-max`}>Duração máxima sem convite (minutos)<input id={`${id}-max`} type="number" min={5} max={1440} value={max} onChange={(event) => setMax(event.target.value)} disabled={busy} required /></label>
      <label htmlFor={`${id}-l1`}>Primeira escalada após o prazo (minutos)<input id={`${id}-l1`} type="number" min={0} max={720} value={l1} onChange={(event) => setL1(event.target.value)} disabled={busy} required /></label>
      <label htmlFor={`${id}-l2`}>Segunda escalada após o prazo (minutos)<input id={`${id}-l2`} type="number" min={1} max={1440} value={l2} onChange={(event) => setL2(event.target.value)} disabled={busy} required /></label></div>
    {rule.kind === 'SERVICE_PROVIDER' ? <button className="button button-secondary" type="button" disabled={busy}
      onClick={() => { setScheduled(true); setStart('08:00'); setEnd('17:00'); setMax('540'); setL1('30'); setL2('60'); }}>Preencher sugestão 08h–17h, +30 e +60 minutos</button> : null}
    <ActionFeedback state={action.state} />
    <button className="button" type="submit" disabled={busy || !valid}>{busy ? 'Salvando…' : `Salvar regra: ${stayKindLabels[rule.kind]}`}</button>
  </form>;
}
