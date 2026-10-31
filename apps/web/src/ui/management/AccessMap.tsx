import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  acceptanceStatusLabels, accessInstall, areaKindLabels, areaKinds, areaStatusLabels, areaStatuses, areaSubtypeLabels, areaSubtypes, flowLabels,
  pointKindLabels, pointStatusLabels, CODE,
  type AcceptanceSummary, type AccessArea, type AreaKind, type AreaStatus, type AreaSubtype, type UpdateAreaBody,
} from '../../api/accessInstall';
import { ApiError, isUUID } from '../../api/client';
import { management } from '../../api/management';
import { displayText } from '../../api/text';
import { formatWhen } from '../dateTime';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { ActionFeedback, managementKey, useAccessGuard, useAction, useManagement } from './shared';

/**
 * Mapa de acesso na gestão (AC-024, área lógica e aceite local): áreas, pontos pelo nome e o resumo do aceite de
 * instalação com a assinatura das regras. A gestão nunca vê equipamento, canal, relé nem vínculo físico.
 */
const unavailable = (error: unknown) => error instanceof ApiError && error.code === 'API_NOT_AVAILABLE';
function Unavailable() {
  return <div className="panel"><Empty icon="door" title="Mapa de acesso indisponível">O acesso facial e a implantação física ainda não estão ativos neste ambiente.</Empty></div>;
}

export function AccessMapSection() {
  const scope = useManagement();
  const areas = useQuery({ queryKey: managementKey(scope, 'access-areas'), queryFn: ({ signal }) => accessInstall.managementAreas(scope.tenantId, signal) });
  if (useAccessGuard(areas.error)) return null;
  if (unavailable(areas.error)) return <Unavailable />;
  return <>
    <AreaList areas={areas.data} pending={areas.isPending} error={areas.isError && !areas.data ? areas.error : null} refetch={() => void areas.refetch()} />
    <PointList areas={areas.data ?? []} />
    <AcceptanceList />
  </>;
}

function AreaList({ areas, pending, error, refetch }: { areas: AccessArea[] | undefined; pending: boolean; error: unknown; refetch(): void }) {
  const [editing, setEditing] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  const close = (done?: string) => { setEditing(null); setOutcome(done ?? null); };
  return <section className="management-section" aria-labelledby="access-areas-title">
    <div className="section-heading"><div><span className="section-index">01 / ÁREAS DE ACESSO</span><h2 id="access-areas-title">Áreas de acesso</h2></div>
      <div className="button-row"><button className="button button-compact" type="button" disabled={editing !== null || !areas} onClick={() => { setOutcome(null); setEditing('new'); }}>Nova área de acesso</button>
        <button className="button button-secondary button-compact" type="button" onClick={refetch}>Atualizar</button></div></div>
    <p className="field-help">Áreas por onde as pessoas circulam (hall, garagem, academia). A gestão define nome, hierarquia e interdição; rota essencial e separação física são conferidas pela implantação no local.</p>
    {outcome ? <Notice tone="success">{outcome}</Notice> : null}
    {editing === 'new' && areas ? <AreaForm area={null} areas={areas} close={close} /> : null}
    {pending ? <div className="panel"><Loading label="Consultando áreas de acesso…" /></div> : error ? <ErrorState error={error} retry={refetch} />
      : !areas || areas.length === 0 ? <div className="panel"><Empty icon="grid" title="Nenhuma área de acesso">Cadastre as áreas antes da implantação dos pontos.</Empty></div>
        : <ol className="management-list" aria-label="Áreas de acesso">{areas.map((area) => editing === area.areaId
          ? <li key={area.areaId}><AreaForm area={area} areas={areas} close={close} /></li>
          : <li key={area.areaId} className="management-row"><div><strong>{area.name}</strong>
            <span className={`status-pill ${area.status === 'ACTIVE' ? 'status-ready' : 'status-neutral'}`}>{areaStatusLabels[area.status]}</span>
            <span>Código {area.code} · {areaKindLabels[area.areaKind]}{area.physicalSubtype ? ` · ${areaSubtypeLabels[area.physicalSubtype]}` : ''} · fuso {area.timeZone}</span>
            {area.parentAreaId ? <span>Dentro de {areas.find((item) => item.areaId === area.parentAreaId)?.name ?? 'outra área'}</span> : null}
            <span>{area.physicalBoundaryVerified ? 'Separação física conferida pela implantação' : 'Separação física ainda não conferida'}{area.essentialRoute ? ' · rota essencial' : ''}</span></div>
            <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={editing !== null} onClick={() => { setOutcome(null); setEditing(area.areaId); }}>Editar</button></div>
          </li>)}</ol>}
  </section>;
}

interface AreaDraft {
  code: string; name: string; areaKind: AreaKind | ''; physicalSubtype: AreaSubtype | ''; blockId: string; parentAreaId: string; amenityId: string;
  timeZone: string; status: AreaStatus;
}
/** Mesmas regras do servidor (MapService); o servidor decide. */
export function areaFields(draft: AreaDraft, areaId: string | null): Omit<UpdateAreaBody, 'status' | 'expectedVersion'> | string {
  const name = displayText(draft.name, 160);
  if (!name) return 'Informe o nome da área com até 160 caracteres.';
  if (!draft.areaKind) return 'Escolha o tipo da área.';
  if ((draft.areaKind === 'AMENITY') !== isUUID(draft.amenityId)) return 'Área reservável exige escolher a área de reserva; outros tipos não têm área de reserva.';
  if (areaId !== null && draft.parentAreaId === areaId) return 'A área não pode estar dentro de si mesma.';
  const zone = draft.timeZone.trim();
  return { name, areaKind: draft.areaKind, blockId: draft.blockId || null, parentAreaId: draft.parentAreaId || null,
    amenityId: draft.areaKind === 'AMENITY' ? draft.amenityId : null, timeZone: zone ? zone : null, physicalSubtype: draft.physicalSubtype || null };
}
function AreaForm({ area, areas, close }: { area: AccessArea | null; areas: readonly AccessArea[]; close(done?: string): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const blocks = useQuery({ queryKey: managementKey(scope, 'blocks'), queryFn: ({ signal }) => management.blocks(scope.tenantId, signal) });
  const amenities = useQuery({ queryKey: managementKey(scope, 'amenities'), queryFn: ({ signal }) => management.amenities(scope.tenantId, signal) });
  const [areaId] = useState(() => area?.areaId ?? globalThis.crypto.randomUUID());
  const [draft, setDraft] = useState<AreaDraft>(() => area ? { code: area.code, name: area.name, areaKind: area.areaKind, physicalSubtype: area.physicalSubtype ?? '',
    blockId: area.blockId ?? '', parentAreaId: area.parentAreaId ?? '', amenityId: area.amenityId ?? '', timeZone: area.timeZone, status: area.status }
    : { code: '', name: '', areaKind: '', physicalSubtype: '', blockId: '', parentAreaId: '', amenityId: '', timeZone: '', status: 'ACTIVE' });
  const [validation, setValidation] = useState('');
  const set = <K extends keyof AreaDraft>(key: K, value: AreaDraft[K]) => setDraft((current) => ({ ...current, [key]: value }));
  const prefix = area ? `access-area-${area.areaId}` : 'access-area-new';
  const pending = action.state.pending;
  const statuses: readonly AreaStatus[] = area ? areaStatuses : ['DRAFT', 'ACTIVE'];
  async function submit(event: FormEvent) {
    event.preventDefault();
    const fields = areaFields(draft, area?.areaId ?? null);
    if (typeof fields === 'string') { setValidation(fields); return; }
    if (!area && !CODE.test(draft.code)) { setValidation('Código: letras maiúsculas, números, _ ou -, até 64 caracteres.'); return; }
    setValidation('');
    const ok = await action.run(() => area
      ? accessInstall.updateArea(scope.tenantId, area.areaId, { ...fields, status: draft.status, expectedVersion: area.version })
      : accessInstall.createArea(scope.tenantId, { ...fields, areaId, code: draft.code, status: draft.status === 'DRAFT' ? 'DRAFT' : 'ACTIVE' }),
    area ? 'Área de acesso atualizada.' : 'Área de acesso criada.', async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'access-areas') }); });
    if (ok) close(area ? 'Área de acesso atualizada.' : 'Área de acesso criada.');
  }
  return <form className="panel management-form stack-form" aria-label={area ? `Editar ${area.name}` : 'Nova área de acesso'} onSubmit={(event) => void submit(event)}>
    <h3>{area ? `Editar ${area.name}` : 'Nova área de acesso'}</h3>
    <div className="two-fields"><div><label htmlFor={`${prefix}-code`}>Código</label>
      <input id={`${prefix}-code`} required maxLength={64} value={draft.code} disabled={pending || area !== null} onChange={(event) => set('code', event.target.value.toUpperCase())} />
      <small className="field-help">Ex.: HALL-A. Não muda depois de criado.</small></div>
      <div><label htmlFor={`${prefix}-name`}>Nome</label><input id={`${prefix}-name`} required maxLength={160} value={draft.name} disabled={pending} onChange={(event) => set('name', event.target.value)} /></div></div>
    <div className="two-fields"><div><label htmlFor={`${prefix}-kind`}>Tipo</label>
      <select id={`${prefix}-kind`} required value={draft.areaKind} disabled={pending} onChange={(event) => set('areaKind', event.target.value as AreaKind)}>
        <option value="" disabled>Escolha…</option>{areaKinds.map((kind) => <option key={kind} value={kind}>{areaKindLabels[kind]}</option>)}</select></div>
      <div><label htmlFor={`${prefix}-subtype`}>Subtipo (opcional)</label>
        <select id={`${prefix}-subtype`} value={draft.physicalSubtype} disabled={pending} onChange={(event) => set('physicalSubtype', event.target.value as AreaSubtype | '')}>
          <option value="">Não informado</option>{areaSubtypes.map((kind) => <option key={kind} value={kind}>{areaSubtypeLabels[kind]}</option>)}</select></div></div>
    {draft.areaKind === 'AMENITY' ? <div><label htmlFor={`${prefix}-amenity`}>Área de reserva</label>
      <select id={`${prefix}-amenity`} required value={draft.amenityId} disabled={pending || amenities.isPending} onChange={(event) => set('amenityId', event.target.value)}>
        <option value="" disabled>{amenities.isPending ? 'Carregando…' : 'Escolha…'}</option>{(amenities.data ?? []).map((item) => <option key={item.amenityId} value={item.amenityId}>{item.name}</option>)}</select></div> : null}
    <div className="two-fields"><div><label htmlFor={`${prefix}-parent`}>Dentro de (opcional)</label>
      <select id={`${prefix}-parent`} value={draft.parentAreaId} disabled={pending} onChange={(event) => set('parentAreaId', event.target.value)}>
        <option value="">Nenhuma</option>{areas.filter((item) => item.areaId !== area?.areaId).map((item) => <option key={item.areaId} value={item.areaId}>{item.name} ({item.code})</option>)}</select></div>
      <div><label htmlFor={`${prefix}-block`}>Bloco (opcional)</label>
        <select id={`${prefix}-block`} value={draft.blockId} disabled={pending || blocks.isPending} onChange={(event) => set('blockId', event.target.value)}>
          <option value="">Nenhum</option>{(blocks.data ?? []).map((item) => <option key={item.blockId} value={item.blockId}>{item.name}</option>)}</select></div></div>
    <div className="two-fields"><div><label htmlFor={`${prefix}-zone`}>Fuso (opcional)</label>
      <input id={`${prefix}-zone`} maxLength={64} placeholder={scope.timeZone} value={draft.timeZone} disabled={pending} onChange={(event) => set('timeZone', event.target.value)} />
      <small className="field-help">Vazio usa o fuso do condomínio.</small></div>
      <div><label htmlFor={`${prefix}-status`}>Situação</label>
        <select id={`${prefix}-status`} value={draft.status} disabled={pending} onChange={(event) => set('status', event.target.value as AreaStatus)}>
          {statuses.map((status) => <option key={status} value={status}>{areaStatusLabels[status]}</option>)}</select></div></div>
    {area && draft.status === 'INTERDICTED' && area.status !== 'INTERDICTED' ? <Notice tone="warning">Interditar a área vale para todos os pontos que levam a ela.</Notice> : null}
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={pending}>{pending ? 'Salvando…' : area ? 'Salvar área' : 'Criar área'}</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={() => close()}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}

function PointList({ areas }: { areas: readonly AccessArea[] }) {
  const scope = useManagement();
  const points = useQuery({ queryKey: managementKey(scope, 'access-points'), queryFn: ({ signal }) => accessInstall.managementPoints(scope.tenantId, signal) });
  if (useAccessGuard(points.error)) return null;
  const name = (id: string) => areas.find((item) => item.areaId === id)?.name ?? 'área não listada';
  return <section className="management-section" aria-labelledby="access-points-title">
    <div className="section-heading"><div><span className="section-index">02 / PONTOS</span><h2 id="access-points-title">Pontos de acesso</h2></div>
      <button className="button button-secondary button-compact" type="button" onClick={() => void points.refetch()}>Atualizar</button></div>
    <p className="field-help">Portas, catracas e portões cadastrados pela implantação. Equipamentos e ligações não aparecem para a gestão.</p>
    {points.isPending ? <div className="panel"><Loading label="Consultando pontos…" /></div> : points.isError && !points.data ? <ErrorState error={points.error} retry={() => void points.refetch()} />
      : points.data!.length === 0 ? <div className="panel"><Empty icon="door" title="Nenhum ponto cadastrado">A implantação ainda não cadastrou pontos neste condomínio.</Empty></div>
        : <ol className="management-list" aria-label="Pontos de acesso">{points.data!.map((point) => <li key={point.pointId} className="management-row"><div>
          <strong>{point.name}</strong><span className={`status-pill ${point.status === 'COMMISSIONED' ? 'status-ready' : 'status-neutral'}`}>{pointStatusLabels[point.status]}</span>
          <span>{pointKindLabels[point.pointKind]} · {name(point.fromAreaId)} → {name(point.toAreaId)} · {flowLabels[point.flowDirection]}</span></div></li>)}</ol>}
  </section>;
}

function AcceptanceList() {
  const scope = useManagement();
  const acceptances = useQuery({ queryKey: managementKey(scope, 'installation-acceptances'), queryFn: ({ signal }) => accessInstall.managementAcceptances(scope.tenantId, signal) });
  const [signing, setSigning] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  if (useAccessGuard(acceptances.error)) return null;
  const list = [...(acceptances.data ?? [])].sort((a, b) => b.installationRevision - a.installationRevision);
  return <section className="management-section" aria-labelledby="access-acceptance-title">
    <div className="section-heading"><div><span className="section-index">03 / ACEITE</span><h2 id="access-acceptance-title">Aceite da instalação</h2></div>
      <button className="button button-secondary button-compact" type="button" onClick={() => void acceptances.refetch()}>Atualizar</button></div>
    <p className="field-help">A implantação assina o aceite técnico; a gestão assina as regras (aceite local), em ação separada. As duas assinaturas ficam registradas com autor e horário.</p>
    {outcome ? <Notice tone="success">{outcome}</Notice> : null}
    {acceptances.isPending ? <div className="panel"><Loading label="Consultando aceites…" /></div>
      : acceptances.isError && !acceptances.data ? <ErrorState error={acceptances.error} retry={() => void acceptances.refetch()} />
        : list.length === 0 ? <div className="panel"><Empty icon="shield" title="Nenhum aceite aberto">A implantação ainda não abriu o aceite da instalação.</Empty></div>
          : <ol className="management-list" aria-label="Aceites da instalação">{list.map((item) => <li key={item.acceptanceId} className="management-row">
            <div><strong>Revisão {item.installationRevision}</strong><span className={`status-pill ${item.status === 'APPROVED' ? 'status-ready' : 'status-neutral'}`}>{acceptanceStatusLabels[item.status]}</span>
              <span>Testes críticos: {item.criticalTestsPending} pendente(s), {item.criticalTestsFailed} reprovado(s)</span>
              {item.effectiveAt ? <span>Em vigor desde {formatWhen(item.effectiveAt, scope.timeZone)}</span> : null}
              {item.reviewDueAt ? <span>Revisão periódica até {formatWhen(item.reviewDueAt, scope.timeZone)}</span> : null}</div>
            <dl className="install-facts install-signatures" aria-label={`Aceites da revisão ${item.installationRevision}`}>
              <div><dt>Aceite técnico (implantação)</dt><dd>{item.technicalSignedAt ? `Assinado em ${formatWhen(item.technicalSignedAt, scope.timeZone)}` : 'Pendente'}</dd></div>
              <div><dt>Aceite local das regras (gestão)</dt><dd>{item.rulesSignedAt ? `Assinado em ${formatWhen(item.rulesSignedAt, scope.timeZone)}${item.rulesApproverAccountId === scope.accountId ? ' por você' : ''}` : 'Pendente'}</dd></div>
            </dl>
            {signing === item.acceptanceId ? <RulesSignature acceptance={item} close={(done) => { setSigning(null); setOutcome(done ?? null); }} />
              : rulesSignable(item) ? <div className="button-row"><button className="button button-compact" type="button" disabled={signing !== null} onClick={() => { setOutcome(null); setSigning(item.acceptanceId); }}>Assinar regras (aceite local)</button></div>
                : !item.technicalSignedAt && (item.status === 'DRAFT' || item.status === 'TESTING') ? <p className="field-help">A assinatura das regras fica disponível depois do aceite técnico.</p> : null}
          </li>)}</ol>}
  </section>;
}
export const rulesSignable = (item: AcceptanceSummary) => item.technicalSignedAt !== null && item.rulesSignedAt === null && (item.status === 'DRAFT' || item.status === 'TESTING');
function RulesSignature({ acceptance, close }: { acceptance: AcceptanceSummary; close(done?: string): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [read, setRead] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!read) return;
    if (await action.run(() => accessInstall.signRules(scope.tenantId, acceptance.acceptanceId, { expectedVersion: acceptance.version }),
      'Regras assinadas pela gestão. A aprovação final é do comissionamento técnico.',
      async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'installation-acceptances') }); })) close('Regras assinadas pela gestão. A aprovação final é do comissionamento técnico.');
  }
  return <form className="reason-form" aria-label={`Assinar regras da revisão ${acceptance.installationRevision}`} onSubmit={(event) => void submit(event)}>
    <Notice>Você assina, pela gestão de {scope.tenantName}, as regras de circulação desta revisão (áreas, pontos e interdições). A assinatura não muda depois.</Notice>
    <label className="checkbox-label"><input type="checkbox" checked={read} disabled={action.state.pending} onChange={(event) => setRead(event.target.checked)} />
      <span>Conferi áreas e pontos desta revisão</span></label>
    <div className="button-row"><button className="button" type="submit" disabled={action.state.pending || !read}>{action.state.pending ? 'Assinando…' : 'Assinar regras'}</button>
      <button className="button button-secondary" type="button" disabled={action.state.pending} onClick={() => close()}>Voltar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}
