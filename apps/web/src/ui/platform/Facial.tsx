import { useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError, isUUID } from '../../api/client';
import { accessCatalog, allowedDecisions, capabilityLabels, capabilityNames, categoryLabels, decisionLabels, homologationStatusLabels, isApproval, lifecycleLabels,
  limitLabels, limitNames, manufacturerLabels, manufacturers, operatingModeLabels, operatingModes, profileStatusLabels, topologyLabels, topologies,
  type AccessHomologation, type AccessModelProfile, type CapabilityName, type Decision, type HomologationStatus, type JsonValue, type Manufacturer } from '../../api/accessCatalog';
import { Empty, ErrorState, Loading, Notice, SectionTabs, errorMessage } from '../primitives';
import { formatWhen } from '../dateTime';
import { queryFailure } from '../queryFailure';
import { centralPath } from '../shell/paths';
import { approvable, decisionBody, dossierBody, emptyDecision, emptyDossier, safeLink, triLabel, type DecisionDraft, type DossierDraft, type TriState } from './facial';
import { PLATFORM_ZONE, isUncertain, platformKey, react, short, useAccessGuard, usePlatform, type Outcome } from './shared';
import './Facial.css';

/**
 * Acesso facial na central (V040): catálogo de modelos (perfis documentais), homologações por combinação exata e a
 * trilha de decisões do MASTER. Só o que a API devolve; gravação só pelas rotas existentes (AAL2 pela porta da central).
 * Gateways ainda não têm API na central: aparecem como "em breve". Nenhuma senha, token ou segredo passa por aqui.
 */
const views = [{ id: 'catalogo', label: 'Catálogo de modelos' }, { id: 'homologacoes', label: 'Homologações' }, { id: 'gateways', label: 'Gateways' }] as const;
type View = typeof views[number]['id'];
export const facialKey = (accountId: string, ...parts: unknown[]) => platformKey(accountId, 'facial', ...parts);
const facialPath = (params: Record<string, string>) => `${centralPath('facial')}?${new URLSearchParams(params)}`;
const statusTone = (status: HomologationStatus) => (['HOMOLOGADO', 'APROVADO_PILOTO', 'APROVADO_BANCADA'].includes(status) ? 'status-ready'
  : ['REPROVADO', 'SUSPENSO', 'EXPIRADO'].includes(status) ? 'status-neutral' : 'status-pending');
const modelName = (model: AccessModelProfile | undefined, id: string) => (model ? `${model.catalogCode} · ${manufacturerLabels[model.manufacturer]} ${model.sku}` : `perfil ${short(id)}`);

export function FacialSection() {
  const [params] = useSearchParams();
  const view: View = views.find((item) => item.id === params.get('vista'))?.id ?? 'catalogo';
  const model = params.get('modelo') ?? '';
  const homologation = params.get('homologacao') ?? '';
  return <section className="management-section" aria-labelledby="facial-title">
    <div className="section-heading"><div><span className="section-index">ACESSO FACIAL</span><h2 id="facial-title">Modelos e homologações</h2></div></div>
    <p className="field-help">Catálogo não é homologação: um modelo só atua num condomínio com a combinação exata (firmware, adaptador, modo e topologia) aprovada pelo MASTER, com evidência. Toda criação e decisão fica na auditoria da plataforma.</p>
    <SectionTabs label="Seções do acesso facial" current={view} items={views.map((item) => ({ id: item.id, label: item.label, to: facialPath({ vista: item.id }) }))} />
    {view === 'catalogo' ? (isUUID(model) ? <ModelDetail key={model} modelId={model.toLowerCase()} /> : <CatalogView />) : null}
    {view === 'homologacoes' ? (isUUID(homologation) ? <HomologationView key={homologation} id={homologation.toLowerCase()} />
      : <HomologationList modelId={isUUID(model) ? model.toLowerCase() : null} />) : null}
    {view === 'gateways' ? <div className="panel"><Empty icon="door" title="Gateways: em breve">A central ainda não tem API para cadastrar ou acompanhar gateways. O gateway de cada condomínio continua com a implantação técnica, sem segredo exibido em tela.</Empty></div> : null}
  </section>;
}

function useModels(manufacturer: Manufacturer | null) {
  const scope = usePlatform();
  return useQuery({ queryKey: facialKey(scope.accountId, 'models', manufacturer), queryFn: ({ signal }) => accessCatalog.models(manufacturer, signal) });
}
function unavailable(error: unknown): ReactNode {
  return error instanceof ApiError && error.code === 'API_NOT_AVAILABLE'
    ? <div className="panel"><Empty icon="door" title="Acesso facial indisponível">O catálogo e as homologações do acesso facial ainda não estão ativos neste ambiente.</Empty></div> : null;
}

function CatalogView() {
  const [manufacturer, setManufacturer] = useState<Manufacturer | ''>('');
  const models = useModels(manufacturer || null);
  const failed = queryFailure([models]);
  if (useAccessGuard(failed)) return null;
  const off = unavailable(failed);
  if (off) return off;
  return <>
    <div className="reservation-filter"><label htmlFor="facial-manufacturer">Fabricante</label>
      <select id="facial-manufacturer" value={manufacturer} onChange={(event) => setManufacturer(event.target.value as Manufacturer | '')}>
        <option value="">Todos</option>{manufacturers.map((item) => <option key={item} value={item}>{manufacturerLabels[item]}</option>)}</select>
      <button className="button button-secondary button-compact" type="button" disabled={models.isFetching} onClick={() => void models.refetch()}>Atualizar</button></div>
    {models.isPending ? <div className="panel"><Loading label="Consultando o catálogo…" /></div>
      : failed && !models.data ? <ErrorState error={failed} retry={() => void models.refetch()} />
        : (models.data ?? []).length === 0 ? <div className="panel"><Empty icon="grid" title="Nenhum modelo">Nenhum perfil documental {manufacturer ? 'deste fabricante ' : ''}no catálogo.</Empty></div>
          : <ol className="management-list" aria-label="Modelos do catálogo">{models.data!.map((model) => <li key={model.id} className="management-row">
            <div><strong>{model.catalogCode} · {manufacturerLabels[model.manufacturer]} <bdi>{model.sku}</bdi></strong> <span className="status-pill status-neutral">{profileStatusLabels[model.status]}</span>
              <span>{categoryLabels[model.category]} · {lifecycleLabels[model.lifecycle]} · revisão {model.revision}</span>
              <span>Fontes consultadas em {model.consultedOn}</span></div>
            <div className="button-row"><Link className="button button-secondary button-compact" to={facialPath({ vista: 'catalogo', modelo: model.id })}
              aria-label={`Ver perfil ${model.catalogCode}`}>Ver perfil</Link>
              <Link className="button button-secondary button-compact" to={facialPath({ vista: 'homologacoes', modelo: model.id })}
                aria-label={`Homologações de ${model.catalogCode}`}>Homologações</Link></div>
          </li>)}</ol>}
  </>;
}

/** Valor declarado pelo fabricante (JSON do catálogo) como lista legível; nada é interpretado como aprovação. */
function JsonView({ value }: { value: JsonValue }): ReactNode {
  if (value === null) return <span className="muted">não informado</span>;
  if (typeof value === 'boolean') return value ? 'sim' : 'não';
  if (typeof value === 'number') return value.toLocaleString('pt-BR');
  if (typeof value === 'string') return <bdi>{value}</bdi>;
  if (Array.isArray(value)) return value.length === 0 ? <span className="muted">nenhum</span> : <>{value.map((item, index) => <span key={index}>{index ? ', ' : ''}<JsonView value={item} /></span>)}</>;
  const entries = Object.entries(value);
  if (entries.length === 0) return <span className="muted">nenhum</span>;
  return <dl className="facial-json">{entries.map(([key, item]) => <div key={key}><dt>{key}</dt><dd><JsonView value={item} /></dd></div>)}</dl>;
}

function ModelDetail({ modelId }: { modelId: string }) {
  const scope = usePlatform();
  const model = useQuery({ queryKey: facialKey(scope.accountId, 'model', modelId), queryFn: ({ signal }) => accessCatalog.model(modelId, signal) });
  const failed = queryFailure([model]);
  if (useAccessGuard(failed)) return null;
  const back = <Link className="button button-secondary button-compact" to={facialPath({ vista: 'catalogo' })}>Voltar ao catálogo</Link>;
  if (model.isPending) return <div className="panel"><Loading label="Consultando o perfil…" /></div>;
  if (failed && !model.data) return <>{back}{unavailable(failed) ?? <ErrorState error={failed} retry={() => void model.refetch()} />}</>;
  const item = model.data!;
  return <div className="facial-detail">
    <div className="section-heading"><div><span className="section-index">PERFIL DOCUMENTAL</span><h3>{item.catalogCode} · {manufacturerLabels[item.manufacturer]} <bdi>{item.sku}</bdi></h3></div>
      <div className="button-row">{back}<Link className="button button-secondary button-compact" to={facialPath({ vista: 'homologacoes', modelo: item.id })}>Ver homologações</Link></div></div>
    <div className="panel"><ul className="detail-list">
      <li>{categoryLabels[item.category]} ({item.sourceCategory}) · {lifecycleLabels[item.lifecycle]} · {profileStatusLabels[item.status]} · revisão {item.revision}</li>
      <li>Revisão de hardware: {item.hardwareRevision || 'não informada'} · região: {item.region || 'não informada'}</li>
      <li>Fontes consultadas em {item.consultedOn} · hash do documento <code>{item.documentHash.slice(0, 16)}…</code></li></ul>
      {item.notes ? <p>{item.notes}</p> : null}</div>
    <div className="panel"><h4>Fontes oficiais</h4>{item.sources.length === 0 ? <p className="field-help">Nenhuma fonte informada.</p>
      : <ul className="detail-list" aria-label="Fontes oficiais">{item.sources.map((source, index) => {
        const href = safeLink(source.url);
        return <li key={source.id ?? index}>{href ? <a href={href} target="_blank" rel="noopener noreferrer">{source.title ?? href}</a> : <bdi>{source.title ?? source.url ?? 'Fonte sem título'}</bdi>}
          {source.consultedOn ? ` · consultada em ${source.consultedOn}` : ''}</li>;
      })}</ul>}</div>
    <div className="panel"><h4>Capacidades declaradas (não homologadas)</h4><JsonView value={item.declaredCapabilities} /></div>
    <div className="panel"><h4>Limites declarados</h4><JsonView value={item.declaredLimits} /></div>
  </div>;
}

function HomologationList({ modelId }: { modelId: string | null }) {
  const scope = usePlatform();
  const [, setParams] = useSearchParams();
  const models = useModels(null);
  const list = useQuery({ queryKey: facialKey(scope.accountId, 'homologations', modelId), queryFn: ({ signal }) => accessCatalog.homologations(modelId, signal) });
  const [creating, setCreating] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const failed = queryFailure([list, models]);
  if (useAccessGuard(failed)) return null;
  const off = unavailable(failed);
  if (off) return off;
  const byId = new Map((models.data ?? []).map((item) => [item.id, item]));
  return <>
    <div className="reservation-filter"><label htmlFor="facial-model">Modelo</label>
      <select id="facial-model" value={modelId ?? ''} onChange={(event) => setParams(event.target.value ? { vista: 'homologacoes', modelo: event.target.value } : { vista: 'homologacoes' }, { replace: true })}>
        <option value="">Todos</option>{(models.data ?? []).map((item) => <option key={item.id} value={item.id}>{modelName(item, item.id)}</option>)}</select>
      <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button>
      {!creating ? <button className="button button-compact" type="button" disabled={!models.data} onClick={() => { setOutcome(null); setCreating(true); }}>Novo dossiê</button> : null}</div>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {creating && models.data ? <DossierForm models={models.data} initialModel={modelId ?? ''} close={(result) => { setCreating(false); if (result) setOutcome(result); }} /> : null}
    {list.isPending ? <div className="panel"><Loading label="Consultando homologações…" /></div>
      : failed && !list.data ? <ErrorState error={failed} retry={() => { void list.refetch(); void models.refetch(); }} />
        : (list.data ?? []).length === 0 ? <div className="panel"><Empty icon="shield" title="Nenhuma homologação">Nenhum dossiê {modelId ? 'para este modelo' : 'registrado'} ainda.</Empty></div>
          : <ol className="management-list" aria-label="Homologações">{list.data!.map((item) => <li key={item.id} className="management-row">
            <div><strong>{modelName(byId.get(item.modelProfileId), item.modelProfileId)}</strong> <span className={`status-pill ${statusTone(item.status)}`}>{homologationStatusLabels[item.status]}</span>
              {item.current ? <span className="status-pill status-ready">Vigente</span> : null}
              <span>Firmware <bdi>{item.firmwareVersion}</bdi> · adaptador <bdi>{item.adapterName}@{item.adapterVersion}</bdi> · contrato {item.apiContractVersion}</span>
              <span>{operatingModeLabels[item.operatingMode]} · {topologyLabels[item.topology]} · revisão {item.revision}{item.reviewDueAt ? ` · revisar até ${formatWhen(item.reviewDueAt, PLATFORM_ZONE)}` : ''}</span></div>
            <div className="button-row"><Link className="button button-secondary button-compact" to={facialPath({ vista: 'homologacoes', homologacao: item.id })}
              aria-label={`Abrir dossiê ${item.firmwareVersion} de ${byId.get(item.modelProfileId)?.catalogCode ?? short(item.modelProfileId)}`}>Abrir dossiê</Link></div>
          </li>)}</ol>}
  </>;
}

function HomologationView({ id }: { id: string }) {
  const scope = usePlatform();
  const detail = useQuery({ queryKey: facialKey(scope.accountId, 'homologation', id), queryFn: ({ signal }) => accessCatalog.homologation(id, signal) });
  const [deciding, setDeciding] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const failed = queryFailure([detail]);
  const modelId = detail.data?.homologation.modelProfileId ?? '';
  const model = useQuery({ queryKey: facialKey(scope.accountId, 'model', modelId), queryFn: ({ signal }) => accessCatalog.model(modelId, signal), enabled: modelId !== '' });
  if (useAccessGuard(failed)) return null;
  const back = <Link className="button button-secondary button-compact" to={facialPath({ vista: 'homologacoes' })}>Voltar às homologações</Link>;
  if (detail.isPending) return <div className="panel"><Loading label="Consultando o dossiê…" /></div>;
  if (failed && !detail.data) return <>{back}{unavailable(failed) ?? <ErrorState error={failed} retry={() => void detail.refetch()} />}</>;
  const { homologation: item, decisions } = detail.data!;
  const approve = scope.session.capabilities.includes('homologation.approve');
  const options = allowedDecisions(item.status);
  return <div className="facial-detail">
    <div className="section-heading"><div><span className="section-index">DOSSIÊ · REVISÃO {item.revision}</span><h3>{modelName(model.data, item.modelProfileId)}</h3></div>
      <div className="button-row">{back}<button className="button button-secondary button-compact" type="button" disabled={detail.isFetching} onClick={() => void detail.refetch()}>Atualizar</button></div></div>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    <div className="panel"><p><span className={`status-pill ${statusTone(item.status)}`}>{homologationStatusLabels[item.status]}</span> {item.current ? <span className="status-pill status-ready">Vigente</span> : <span className="status-pill status-neutral">Não vigente</span>}</p>
      <ul className="detail-list">
        <li>Firmware exato <bdi>{item.firmwareVersion}</bdi> · adaptador <bdi>{item.adapterName}@{item.adapterVersion}</bdi> · contrato da API {item.apiContractVersion}</li>
        <li>{operatingModeLabels[item.operatingMode]} · {topologyLabels[item.topology]} · versão {item.version}</li>
        <li>{item.approvedAt ? `Aprovado em ${formatWhen(item.approvedAt, PLATFORM_ZONE)}` : 'Sem aprovação'}{item.reviewDueAt ? ` · revisar até ${formatWhen(item.reviewDueAt, PLATFORM_ZONE)}` : ''}</li>
        <li>Evidências: {item.evidenceRefs.length} referência(s) · hash <code>{item.evidenceHash.slice(0, 16)}…</code></li>
        <li>Código <code>{item.id}</code></li></ul></div>
    <div className="panel"><h4>Capacidades do dossiê</h4>
      <table className="facial-table"><caption className="field-help">Documentada pelo fabricante e observada em ensaio próprio; só a decisão do MASTER aprova.</caption>
        <thead><tr><th scope="col">Capacidade</th><th scope="col">Documentada</th><th scope="col">Observada</th><th scope="col">Aprovada</th></tr></thead>
        <tbody>{capabilityNames.map((name) => <tr key={name}><th scope="row">{capabilityLabels[name]}</th><td>{triLabel(item.capabilities[name]?.documented)}</td>
          <td>{triLabel(item.capabilities[name]?.observed)}</td><td>{item.approvedCapabilities.includes(name) ? 'Sim' : 'Não'}</td></tr>)}</tbody></table></div>
    <div className="panel"><h4>Limites observados</h4><dl className="facial-json">{limitNames.map((name) => <div key={name}><dt>{limitLabels[name]}</dt>
      <dd>{item.limits[name] === null || item.limits[name] === undefined ? <span className="muted">desconhecido</span> : item.limits[name]!.toLocaleString('pt-BR')}</dd></div>)}</dl></div>
    {item.limitations.length > 0 ? <div className="panel"><h4>Limitações</h4><ul className="detail-list">{item.limitations.map((text, index) => <li key={index}><bdi>{text}</bdi></li>)}</ul></div> : null}
    {item.restrictions.length > 0 ? <div className="panel"><h4>Restrições da aprovação</h4><ul className="detail-list">{item.restrictions.map((text, index) => <li key={index}><bdi>{text}</bdi></li>)}</ul></div> : null}
    <div className="panel"><h4>Trilha de decisões</h4>{decisions.length === 0 ? <p className="field-help">Nenhuma decisão registrada.</p>
      : <ol className="management-list audit-list" aria-label="Decisões da homologação">{decisions.map((decision) => <li key={decision.decisionId} className="management-row"><div>
        <strong>{decisionLabels[decision.decision as Decision] ?? decision.decision}: {homologationStatusLabels[decision.fromStatus]} → {homologationStatusLabels[decision.toStatus]}</strong>
        <span>{formatWhen(decision.decidedAt, PLATFORM_ZONE)} · ator {short(decision.decidedBy)} · {decision.evidenceRefs.length} evidência(s)</span>
        {decision.approvedCapabilities.length ? <span>Capacidades aprovadas: {decision.approvedCapabilities.map((name) => capabilityLabels[name as CapabilityName] ?? name).join(', ')}</span> : null}
        <span>Motivo: <bdi>{decision.reason}</bdi></span></div></li>)}</ol>}</div>
    {approve ? options.length === 0 ? <Notice>Não há decisão possível a partir de “{homologationStatusLabels[item.status]}”. Para mudar o conteúdo técnico, registre uma nova revisão do dossiê.</Notice>
      : deciding ? <DecisionForm homologation={item} options={options} close={(result) => { setDeciding(false); if (result) setOutcome(result); }} />
        : <div className="button-row"><button className="button" type="button" onClick={() => { setOutcome(null); setDeciding(true); }}>Registrar decisão</button></div>
      : <Notice>Sua sessão consulta os dossiês, mas não decide homologações (falta homologation.approve).</Notice>}
  </div>;
}

function DecisionForm({ homologation, options, close }: { homologation: AccessHomologation; options: Decision[]; close(result: Outcome | null): void }) {
  const scope = usePlatform();
  const queries = useQueryClient();
  // Identificador e versão nascem com o formulário: a repetição após resultado incerto usa a mesma decisão.
  const [decisionId, setDecisionId] = useState(() => globalThis.crypto.randomUUID());
  const [read] = useState(homologation.version);
  const [draft, setDraft] = useState<DecisionDraft>(emptyDecision);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [sent, setSent] = useState(false);
  const stale = homologation.version !== read;
  const body = decisionBody(decisionId, draft, { ...homologation, version: read });
  const approval = draft.decision !== '' && isApproval(draft.decision);
  const observed = approvable(homologation);
  const locked = pending || (sent && failure !== null && isUncertain(failure));
  const update = (patch: Partial<DecisionDraft>) => { setDraft((current) => ({ ...current, ...patch })); setFailure(null); };
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (typeof body === 'string' || pending || stale) return;
    setPending(true); setFailure(null); setSent(true);
    try {
      const detail = await accessCatalog.decide(homologation.id, body);
      queries.setQueryData(facialKey(scope.accountId, 'homologation', homologation.id), detail);
      void queries.invalidateQueries({ queryKey: facialKey(scope.accountId, 'homologations') });
      const last = detail.decisions.find((item) => item.decisionId === decisionId);
      close({ tone: 'success', text: last ? `Decisão registrada: ${homologationStatusLabels[last.fromStatus]} → ${homologationStatusLabels[last.toStatus]}.`
        : `Decisão registrada. Situação atual: ${homologationStatusLabels[detail.homologation.status]}.` });
    } catch (error) {
      react(error, scope);
      setFailure(error);
      if (!isUncertain(error)) setSent(false);
      void queries.invalidateQueries({ queryKey: facialKey(scope.accountId, 'homologation', homologation.id) });
    } finally { setPending(false); }
  }
  const uncertain = failure !== null && isUncertain(failure);
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Registrar decisão da homologação">
    {stale ? <Notice tone="warning">A homologação mudou depois que você abriu a decisão (versão {read} → {homologation.version}). Feche e abra de novo para decidir sobre o estado atual.</Notice> : null}
    <label htmlFor="decision-kind">Decisão</label>
    <select id="decision-kind" value={draft.decision} disabled={locked} onChange={(event) => update({ decision: event.target.value as Decision | '', approved: [], reviewDue: '', restrictions: '' })}>
      <option value="">Escolha…</option>{options.map((item) => <option key={item} value={item}>{decisionLabels[item]}</option>)}</select>
    <label htmlFor="decision-evidence">Evidências (um identificador por linha{approval ? '; obrigatório na aprovação' : ''})</label>
    <textarea id="decision-evidence" rows={3} value={draft.evidence} disabled={locked} onChange={(event) => update({ evidence: event.target.value })} />
    {approval ? <fieldset className="weekday-fieldset"><legend>Capacidades aprovadas (só as observadas no dossiê)</legend>
      {observed.length === 0 ? <p className="field-help">Nenhuma capacidade foi observada neste dossiê; a aprovação não lista capacidades.</p>
        : observed.map((name) => <label key={name} className="weekday-option"><input type="checkbox" checked={draft.approved.includes(name)} disabled={locked}
          onChange={(event) => update({ approved: event.target.checked ? [...draft.approved, name] : draft.approved.filter((item) => item !== name) })} />{capabilityLabels[name]}</label>)}</fieldset> : null}
    {approval ? <><label htmlFor="decision-review">Revisar até (opcional, futura)</label>
      <input id="decision-review" type="datetime-local" value={draft.reviewDue} disabled={locked} onChange={(event) => update({ reviewDue: event.target.value })} />
      <label htmlFor="decision-restrictions">Restrições (uma por linha, opcional)</label>
      <textarea id="decision-restrictions" rows={2} value={draft.restrictions} disabled={locked} onChange={(event) => update({ restrictions: event.target.value })} /></> : null}
    <label htmlFor="decision-reason">Motivo (8 a 1000 caracteres; fica na auditoria)</label>
    <textarea id="decision-reason" rows={2} maxLength={1000} value={draft.reason} disabled={locked} onChange={(event) => update({ reason: event.target.value })} />
    {typeof body === 'string' && (draft.decision || draft.reason) ? <p className="field-help" role="status">{body}</p> : null}
    {failure ? <Notice tone={uncertain ? 'warning' : 'error'}>{errorMessage(failure)}{uncertain ? ' O resultado é incerto: reenvie a mesma decisão (não duplica) ou descarte e confira a trilha.' : ''}</Notice> : null}
    <div className="button-row">
      <button className={`button ${draft.decision === 'REJECT' || draft.decision === 'SUSPEND' ? 'button-danger' : ''}`} type="submit"
        disabled={typeof body === 'string' || pending || stale}>{pending ? 'Registrando…' : uncertain ? 'Reenviar a mesma decisão' : 'Confirmar decisão'}</button>
      {uncertain ? <button className="button button-secondary" type="button" disabled={pending} onClick={() => { setDecisionId(globalThis.crypto.randomUUID()); setSent(false); setFailure(null); close(null); }}>Descartar</button>
        : <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Voltar</button>}
    </div>
  </form>;
}

const triOptions: { value: TriState; label: string }[] = [{ value: '', label: 'Desconhecido' }, { value: 'yes', label: 'Sim' }, { value: 'no', label: 'Não' }];

function DossierForm({ models, initialModel, close }: { models: AccessModelProfile[]; initialModel: string; close(result: Outcome | null): void }) {
  const scope = usePlatform();
  const queries = useQueryClient();
  const [homologationId, setHomologationId] = useState(() => globalThis.crypto.randomUUID());
  const [draft, setDraft] = useState<DossierDraft>(() => emptyDossier(initialModel));
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const body = dossierBody(homologationId, draft);
  const uncertain = failure !== null && isUncertain(failure);
  const locked = pending || uncertain;
  const update = (patch: Partial<DossierDraft>) => { setDraft((current) => ({ ...current, ...patch })); setFailure(null); };
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (typeof body === 'string' || pending) return;
    setPending(true); setFailure(null);
    try {
      const created = await accessCatalog.create(body);
      void queries.invalidateQueries({ queryKey: facialKey(scope.accountId, 'homologations') });
      close(created.applied ? { tone: 'success', text: `Dossiê candidato registrado (revisão ${created.homologation.revision}). Nada foi aprovado: o próximo passo é iniciar o ensaio.` }
        : { tone: 'info', text: 'Este dossiê já estava registrado com os mesmos dados. Nada foi gravado de novo.' });
    } catch (error) {
      react(error, scope);
      setFailure(error);
    } finally { setPending(false); }
  }
  return <form className="reason-form facial-form" onSubmit={(event) => void submit(event)} aria-label="Novo dossiê de homologação">
    <p className="field-help">O dossiê descreve uma combinação exata e nasce como candidato documentado. Não aprova nada nem habilita atuação. Nenhuma senha ou chave do equipamento é pedida aqui.</p>
    <label htmlFor="dossier-model">Perfil do catálogo</label>
    <select id="dossier-model" value={draft.modelProfileId} disabled={locked} onChange={(event) => update({ modelProfileId: event.target.value })}>
      <option value="">Escolha…</option>{models.map((item) => <option key={item.id} value={item.id}>{modelName(item, item.id)}</option>)}</select>
    <div className="two-fields"><div><label htmlFor="dossier-firmware">Firmware exato</label><input id="dossier-firmware" value={draft.firmwareVersion} disabled={locked} onChange={(event) => update({ firmwareVersion: event.target.value })} /></div>
      <div><label htmlFor="dossier-contract">Versão do contrato da API</label><input id="dossier-contract" value={draft.apiContractVersion} disabled={locked} onChange={(event) => update({ apiContractVersion: event.target.value })} /></div></div>
    <div className="two-fields"><div><label htmlFor="dossier-adapter">Adaptador</label><input id="dossier-adapter" value={draft.adapterName} disabled={locked} onChange={(event) => update({ adapterName: event.target.value })} /></div>
      <div><label htmlFor="dossier-adapter-version">Versão do adaptador</label><input id="dossier-adapter-version" value={draft.adapterVersion} disabled={locked} onChange={(event) => update({ adapterVersion: event.target.value })} /></div></div>
    <div className="two-fields"><div><label htmlFor="dossier-mode">Modo de operação</label>
      <select id="dossier-mode" value={draft.operatingMode} disabled={locked} onChange={(event) => update({ operatingMode: event.target.value as DossierDraft['operatingMode'] })}>
        <option value="">Escolha…</option>{operatingModes.map((item) => <option key={item} value={item}>{operatingModeLabels[item]}</option>)}</select></div>
      <div><label htmlFor="dossier-topology">Topologia</label>
        <select id="dossier-topology" value={draft.topology} disabled={locked} onChange={(event) => update({ topology: event.target.value as DossierDraft['topology'] })}>
          <option value="">Escolha…</option>{topologies.map((item) => <option key={item} value={item}>{topologyLabels[item]}</option>)}</select></div></div>
    <fieldset className="weekday-fieldset"><legend>Capacidades (documentada / observada; desconhecido quando não se sabe)</legend>
      <div className="facial-capabilities">{capabilityNames.map((name) => <div key={name} className="facial-capability"><span>{capabilityLabels[name]}</span>
        {(['documented', 'observed'] as const).map((field) => <label key={field}><span className="field-help">{field === 'documented' ? 'Documentada' : 'Observada'}</span>
          <select aria-label={`${capabilityLabels[name]}: ${field === 'documented' ? 'documentada' : 'observada'}`} value={draft.capabilities[name][field]} disabled={locked}
            onChange={(event) => update({ capabilities: { ...draft.capabilities, [name]: { ...draft.capabilities[name], [field]: event.target.value as TriState } } })}>
            {triOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>)}</div>)}</div></fieldset>
    <fieldset className="weekday-fieldset"><legend>Limites observados (em branco = desconhecido)</legend>
      <div className="facial-limits">{limitNames.map((name) => <label key={name}><span className="field-help">{limitLabels[name]}</span>
        <input inputMode="numeric" value={draft.limits[name]} disabled={locked} onChange={(event) => update({ limits: { ...draft.limits, [name]: event.target.value } })} /></label>)}</div></fieldset>
    <label htmlFor="dossier-evidence">Evidências (um identificador por linha, opcional)</label>
    <textarea id="dossier-evidence" rows={2} value={draft.evidence} disabled={locked} onChange={(event) => update({ evidence: event.target.value })} />
    <label htmlFor="dossier-limitations">Limitações conhecidas (uma por linha)</label>
    <textarea id="dossier-limitations" rows={2} value={draft.limitations} disabled={locked} onChange={(event) => update({ limitations: event.target.value })} />
    <label htmlFor="dossier-reason">Motivo (8 a 1000 caracteres; fica na auditoria)</label>
    <textarea id="dossier-reason" rows={2} maxLength={1000} value={draft.reason} disabled={locked} onChange={(event) => update({ reason: event.target.value })} />
    {typeof body === 'string' ? <p className="field-help" role="status">{body}</p> : null}
    {failure ? <Notice tone={uncertain ? 'warning' : 'error'}>{errorMessage(failure)}{uncertain ? ' O resultado é incerto: reenvie os mesmos dados (não duplica) ou descarte e confira a lista.' : ''}</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={typeof body === 'string' || pending}>{pending ? 'Registrando…' : uncertain ? 'Reenviar os mesmos dados' : 'Registrar dossiê candidato'}</button>
      {uncertain ? <button className="button button-secondary" type="button" disabled={pending} onClick={() => { setHomologationId(globalThis.crypto.randomUUID()); close(null); }}>Descartar</button>
        : <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Cancelar</button>}
    </div>
  </form>;
}
