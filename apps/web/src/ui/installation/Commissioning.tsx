import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  acceptanceStatusLabels, acceptanceTransitions, accessInstall, bindingStatusLabels, readerSenses, reasonRequired, senseLabels, HASH, REF, SUITE,
  type AcceptanceStatus, type AccessArea, type AccessDevice, type AccessPoint, type CreateAcceptanceBody, type CreateBindingBody, type DeviceChannel,
  type InstallationAcceptance, type PointBinding, type ReaderSense,
} from '../../api/accessInstall';
import { isUUID } from '../../api/client';
import { displayText } from '../../api/text';
import { formatWhen, toInstant } from '../dateTime';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { areaName } from './Inventory';
import { queryFailure } from '../queryFailure';
import { StaleNotice, useVersionedDraft } from '../readVersion';
import { ActionFeedback, INSTALL_ZONE, PhysicalContext, blank, can, installationKey, newId, short, useAccessGuard, useAction, useInstallation } from './shared';

function useInstallData() {
  const scope = useInstallation();
  const query = <T,>(part: string, load: (tenantId: string, signal?: AbortSignal) => Promise<T>) =>
    useQuery({ queryKey: installationKey(scope, part), queryFn: ({ signal }) => load(scope.tenantId, signal) });
  const areas = query('areas', accessInstall.technicalAreas);
  const points = query('points', accessInstall.points);
  const devices = query('devices', accessInstall.devices);
  const channels = query('channels', accessInstall.channels);
  const bindings = query('bindings', accessInstall.bindings);
  const acceptances = query('acceptances', accessInstall.acceptances);
  const all = [areas, points, devices, channels, bindings, acceptances];
  return { areas, points, devices, channels, bindings, acceptances, all,
    failed: queryFailure(all), pending: all.some((item) => item.isPending),
    refresh: () => all.forEach((item) => void item.refetch()) };
}
export function placeOf(point: AccessPoint | undefined, areas: readonly AccessArea[]): string {
  if (!point) return 'Ponto não encontrado: atualize os dados';
  return `${point.name} (${point.code}) — ${areaName(areas, point.fromAreaId)} → ${areaName(areas, point.toAreaId)}`;
}

// ---- Vínculos: relé, controladora, leitores com sentido e sensores de cada ponto.
export function BindingSection() {
  const data = useInstallData();
  const [creating, setCreating] = useState(false);
  const [acting, setActing] = useState<string | null>(null);
  if (useAccessGuard(data.failed)) return null;
  const ready = !data.pending && !data.failed;
  const points = data.points.data ?? [], areas = data.areas.data ?? [], devices = data.devices.data ?? [], channels = data.channels.data ?? [];
  const deviceName = (id: string | null) => id ? devices.find((item) => item.deviceId === id)?.label ?? `equipamento ${short(id)}` : null;
  const channelName = (id: string | null) => {
    if (!id) return null;
    const channel = channels.find((item) => item.channelId === id);
    return channel ? `${channel.nativeNamespace}/${channel.externalChannel} em ${deviceName(channel.deviceId)}` : `canal ${short(id)}`;
  };
  const sorted = [...(data.bindings.data ?? [])].sort((a, b) => a.pointId.localeCompare(b.pointId) || b.bindingRevision - a.bindingRevision);
  return <section className="management-section" aria-labelledby="install-bindings-title">
    <div className="section-heading"><div><span className="section-index">05 / VÍNCULOS</span><h2 id="install-bindings-title">Vínculos físicos dos pontos</h2></div>
      <div className="button-row"><button className="button button-compact" type="button" disabled={!ready || creating || acting !== null} onClick={() => setCreating(true)}>Novo vínculo</button>
        <button className="button button-secondary button-compact" type="button" onClick={data.refresh}>Atualizar</button></div></div>
    <p className="field-help">Cada revisão de vínculo diz qual relé abre o ponto, qual controladora o comanda, o sentido de cada leitor e os sensores. O vínculo só entra em vigor quando o aceite da instalação é aprovado.</p>
    {creating && ready ? <BindingForm points={points} areas={areas} devices={devices} channels={channels} close={() => setCreating(false)} /> : null}
    {data.pending ? <div className="panel"><Loading label="Consultando vínculos…" /></div> : data.failed ? <ErrorState error={data.failed} retry={data.refresh} />
      : sorted.length === 0 ? <div className="panel"><Empty icon="arrow" title="Nenhum vínculo cadastrado">Vincule relé, controladora, leitores e sensores a cada ponto.</Empty></div>
        : <ol className="management-list" aria-label="Vínculos">{sorted.map((binding) => {
          const point = points.find((item) => item.pointId === binding.pointId);
          return <li key={binding.bindingId} className="management-row">
            <div><strong>{point ? `${point.name} · revisão ${binding.bindingRevision}` : `Ponto ${short(binding.pointId)} · revisão ${binding.bindingRevision}`}</strong>
              <span className={`status-pill ${binding.status === 'ACTIVE' ? 'status-ready' : binding.status === 'PENDING_COMMISSION' ? 'status-pending' : 'status-neutral'}`}>{bindingStatusLabels[binding.status]}</span>
              <span>{placeOf(point, areas)}</span>
              <span>Relé: {channelName(binding.actuatorChannelId)} · controladora: {deviceName(binding.controllerDeviceId)}</span>
              <span>Leitor de entrada: {binding.entryReaderDeviceId ? `${deviceName(binding.entryReaderDeviceId)} (${senseLabels[binding.directions.entryReader!]})` : 'nenhum'}
                {' · '}Leitor de saída: {binding.exitReaderDeviceId ? `${deviceName(binding.exitReaderDeviceId)} (${senseLabels[binding.directions.exitReader!]})` : 'nenhum'}</span>
              <span>Sensores: porta {channelName(binding.doorContactChannelId) ?? 'não'} · botão de saída {channelName(binding.exitRequestChannelId) ?? 'não'} · passagem {channelName(binding.passageSensorChannelId) ?? 'não'}</span>
              <span>Ligação {binding.wiringEvidenceRef} · combinação {short(binding.homologationId)}{binding.validFrom ? ` · em vigor desde ${formatWhen(binding.validFrom, INSTALL_ZONE)}` : ''}{binding.validUntil ? ` até ${formatWhen(binding.validUntil, INSTALL_ZONE)}` : ''}</span></div>
            {acting === binding.bindingId ? <BindingActions binding={binding} place={placeOf(point, areas)} acceptances={data.acceptances.data ?? []} close={() => setActing(null)} />
              : ['DRAFT', 'PENDING_COMMISSION'].includes(binding.status) ? <div className="button-row"><button className="button button-secondary button-compact" type="button"
                disabled={acting !== null || creating} onClick={() => setActing(binding.bindingId)}>{binding.status === 'DRAFT' ? 'Enviar ao aceite ou descartar' : 'Descartar'}</button></div> : null}
          </li>;
        })}</ol>}
  </section>;
}

interface BindingDraft {
  pointId: string; controllerDeviceId: string; actuatorChannelId: string; entryReaderDeviceId: string; entrySense: ReaderSense | '';
  exitReaderDeviceId: string; exitSense: ReaderSense | ''; doorContactChannelId: string; exitRequestChannelId: string; passageSensorChannelId: string;
  homologationId: string; wiringEvidenceRef: string;
}
const emptyBinding: BindingDraft = { pointId: '', controllerDeviceId: '', actuatorChannelId: '', entryReaderDeviceId: '', entrySense: '', exitReaderDeviceId: '',
  exitSense: '', doorContactChannelId: '', exitRequestChannelId: '', passageSensorChannelId: '', homologationId: '', wiringEvidenceRef: '' };
/** Mesmas regras do servidor (leitor e sentido andam juntos); o servidor decide. */
export function bindingBody(bindingId: string, draft: BindingDraft): CreateBindingBody | string {
  if (!isUUID(draft.pointId)) return 'Escolha o ponto.';
  if (!isUUID(draft.controllerDeviceId)) return 'Escolha a controladora.';
  if (!isUUID(draft.actuatorChannelId)) return 'Escolha o relé de abertura.';
  if (Boolean(draft.entryReaderDeviceId) !== Boolean(draft.entrySense) || Boolean(draft.exitReaderDeviceId) !== Boolean(draft.exitSense))
    return 'Cada leitor exige o sentido em relação ao ponto.';
  const homologationId = draft.homologationId.trim().toLowerCase();
  if (!isUUID(homologationId)) return 'Informe a combinação homologada (identificador recebido da plataforma).';
  const wiringEvidenceRef = draft.wiringEvidenceRef.trim();
  if (!REF.test(wiringEvidenceRef)) return 'Referência da ligação: 3 a 120 caracteres, só letras, números e . _ : / -.';
  const optional = (value: string) => value ? value : null;
  return { bindingId, pointId: draft.pointId, actuatorChannelId: draft.actuatorChannelId, controllerDeviceId: draft.controllerDeviceId,
    entryReaderDeviceId: optional(draft.entryReaderDeviceId), exitReaderDeviceId: optional(draft.exitReaderDeviceId),
    doorContactChannelId: optional(draft.doorContactChannelId), exitRequestChannelId: optional(draft.exitRequestChannelId),
    passageSensorChannelId: optional(draft.passageSensorChannelId), homologationId,
    directions: { entryReader: draft.entrySense || null, exitReader: draft.exitSense || null }, wiringEvidenceRef };
}
function BindingForm({ points, areas, devices, channels, close }: {
  points: readonly AccessPoint[]; areas: readonly AccessArea[]; devices: readonly AccessDevice[]; channels: readonly DeviceChannel[]; close(): void;
}) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const [bindingId] = useState(newId);
  const [draft, setDraft] = useState<BindingDraft>(emptyBinding);
  const [confirmed, setConfirmed] = useState(false);
  const [validation, setValidation] = useState('');
  const pending = action.state.pending;
  const set = <K extends keyof BindingDraft>(key: K, value: BindingDraft[K]) => { setConfirmed(false); setDraft((current) => ({ ...current, [key]: value })); };
  const activeDevices = devices.filter((item) => item.status === 'ACTIVE');
  const usable = channels.filter((item) => item.status !== 'RETIRED' && item.status !== 'DISABLED');
  const ofKind = (kind: DeviceChannel['channelKind']) => usable.filter((item) => item.channelKind === kind);
  const label = (channel: DeviceChannel) => `${channel.nativeNamespace}/${channel.externalChannel} — ${devices.find((item) => item.deviceId === channel.deviceId)?.label ?? short(channel.deviceId)}${channel.status === 'VERIFIED' ? '' : ' (não verificado)'}`;
  const point = points.find((item) => item.pointId === draft.pointId);
  async function submit(event: FormEvent) {
    event.preventDefault();
    const body = bindingBody(bindingId, draft);
    if (typeof body === 'string') { setValidation(body); return; }
    if (!confirmed) { setValidation('Confirme o local e o condomínio antes de gravar o vínculo.'); return; }
    setValidation('');
    const ok = await action.run(() => accessInstall.createBinding(scope.tenantId, body), 'Vínculo gravado em rascunho.',
      async () => { await queries.invalidateQueries({ queryKey: installationKey(scope, 'bindings') }); });
    if (ok) close();
  }
  const channelSelect = (key: 'actuatorChannelId' | 'doorContactChannelId' | 'exitRequestChannelId' | 'passageSensorChannelId', title: string, kind: DeviceChannel['channelKind'], required = false) =>
    <div><label htmlFor={`binding-${key}`}>{title}</label>
      <select id={`binding-${key}`} required={required} value={draft[key]} disabled={pending} onChange={(event) => set(key, event.target.value)}>
        <option value="" disabled={required}>{required ? 'Escolha…' : 'Nenhum'}</option>
        {ofKind(kind).map((item) => <option key={item.channelId} value={item.channelId}>{label(item)}</option>)}</select></div>;
  const deviceSelect = (key: 'controllerDeviceId' | 'entryReaderDeviceId' | 'exitReaderDeviceId', title: string, required = false) =>
    <div><label htmlFor={`binding-${key}`}>{title}</label>
      <select id={`binding-${key}`} required={required} value={draft[key]} disabled={pending} onChange={(event) => {
        set(key, event.target.value);
        // Sem leitor não há sentido: o seletor desabilitado não pode guardar um valor que bloqueie o envio.
        if (!event.target.value && key === 'entryReaderDeviceId') set('entrySense', '');
        if (!event.target.value && key === 'exitReaderDeviceId') set('exitSense', '');
        const chosen = devices.find((item) => item.deviceId === event.target.value);
        if (key === 'controllerDeviceId' && chosen?.homologationId && !draft.homologationId) set('homologationId', chosen.homologationId);
      }}>
        <option value="" disabled={required}>{required ? 'Escolha…' : 'Nenhum'}</option>
        {activeDevices.map((item) => <option key={item.deviceId} value={item.deviceId}>{item.label}</option>)}</select></div>;
  const senseSelect = (key: 'entrySense' | 'exitSense', title: string, enabled: boolean) =>
    <div><label htmlFor={`binding-${key}`}>{title}</label>
      <select id={`binding-${key}`} required={enabled} value={draft[key]} disabled={pending || !enabled} onChange={(event) => set(key, event.target.value as ReaderSense | '')}>
        <option value="">{enabled ? 'Escolha…' : 'Sem leitor'}</option>{readerSenses.map((sense) => <option key={sense} value={sense}>{senseLabels[sense]}</option>)}</select></div>;
  return <form className="panel management-form stack-form" aria-label="Novo vínculo" onSubmit={(event) => void submit(event)}>
    <h3>Novo vínculo</h3>
    <div><label htmlFor="binding-point">Ponto</label>
      <select id="binding-point" required value={draft.pointId} disabled={pending} onChange={(event) => set('pointId', event.target.value)}>
        <option value="" disabled>Escolha…</option>
        {points.filter((item) => item.status !== 'RETIRED').map((item) => <option key={item.pointId} value={item.pointId}>{item.name} ({item.code})</option>)}</select></div>
    <div className="two-fields">{deviceSelect('controllerDeviceId', 'Controladora', true)}{channelSelect('actuatorChannelId', 'Relé de abertura', 'ACCESS_ACTUATOR', true)}</div>
    <div className="two-fields">{deviceSelect('entryReaderDeviceId', 'Leitor de entrada')}{senseSelect('entrySense', 'Sentido do leitor de entrada', Boolean(draft.entryReaderDeviceId))}</div>
    <div className="two-fields">{deviceSelect('exitReaderDeviceId', 'Leitor de saída')}{senseSelect('exitSense', 'Sentido do leitor de saída', Boolean(draft.exitReaderDeviceId))}</div>
    <div className="two-fields">{channelSelect('doorContactChannelId', 'Sensor de porta', 'DOOR_CONTACT')}{channelSelect('exitRequestChannelId', 'Botão de saída', 'EXIT_REQUEST')}</div>
    <div className="two-fields">{channelSelect('passageSensorChannelId', 'Sensor de passagem', 'PASSAGE_SENSOR')}
      <div><label htmlFor="binding-homologation">Combinação homologada</label>
        <input id="binding-homologation" required value={draft.homologationId} disabled={pending} onChange={(event) => set('homologationId', event.target.value)} />
        <small className="field-help">Identificador recebido da plataforma; a aprovação do aceite exige combinação aprovada para piloto ou produção.</small></div></div>
    <div><label htmlFor="binding-wiring">Referência da ligação (diagrama ou foto no repositório da obra)</label>
      <input id="binding-wiring" required maxLength={120} value={draft.wiringEvidenceRef} disabled={pending} onChange={(event) => set('wiringEvidenceRef', event.target.value)} /></div>
    <PhysicalContext place={placeOf(point, areas)} confirmed={confirmed} onConfirm={setConfirmed} disabled={pending || !point} />
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={pending || !confirmed}>{pending ? 'Gravando…' : 'Gravar vínculo'}</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={close}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}
function BindingActions({ binding, place, acceptances, close }: { binding: PointBinding; place: string; acceptances: readonly InstallationAcceptance[]; close(): void }) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const seen = useVersionedDraft(binding.version, () => { setConfirmed(false); action.clear(); });
  const open = acceptances.filter((item) => item.status === 'DRAFT' || item.status === 'TESTING');
  const [acceptanceId, setAcceptanceId] = useState(open.length === 1 ? open[0]!.acceptanceId : '');
  const [confirmed, setConfirmed] = useState(false);
  const pending = action.state.pending;
  const after = async () => { await queries.invalidateQueries({ queryKey: installationKey(scope, 'bindings') }); };
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (seen.stale) return;
    if (!isUUID(acceptanceId) || !confirmed) return;
    if (await action.run(() => accessInstall.submitBinding(scope.tenantId, binding.bindingId, { installationAcceptanceId: acceptanceId, expectedVersion: seen.version }),
      'Vínculo enviado ao aceite: aguarda comissionamento.', after)) close();
  }
  async function discard() {
    if (!confirmed || seen.stale) return;
    if (await action.run(() => accessInstall.discardBinding(scope.tenantId, binding.bindingId, { expectedVersion: seen.version }), 'Vínculo descartado.', after)) close();
  }
  return <form className="reason-form" aria-label="Ações do vínculo" onSubmit={(event) => void submit(event)}>
    {binding.status === 'DRAFT' ? open.length === 0 ? <Notice>Não há aceite em rascunho ou em ensaio. Abra um aceite em "Ensaios e aceite" antes de enviar o vínculo.</Notice>
      : <div><label htmlFor={`submit-${binding.bindingId}`}>Aceite de instalação</label>
        <select id={`submit-${binding.bindingId}`} value={acceptanceId} disabled={pending} onChange={(event) => setAcceptanceId(event.target.value)}>
          <option value="" disabled>Escolha…</option>{open.map((item) => <option key={item.acceptanceId} value={item.acceptanceId}>Revisão {item.installationRevision} · {acceptanceStatusLabels[item.status]}</option>)}</select></div> : null}
    <PhysicalContext place={place} confirmed={confirmed} onConfirm={setConfirmed} disabled={pending} />
    {seen.stale ? <StaleNotice onReload={seen.reload} /> : null}
    <div className="button-row">{binding.status === 'DRAFT' && open.length > 0 ? <button className="button" type="submit" disabled={seen.stale || pending || !confirmed || !acceptanceId}>{pending ? 'Enviando…' : 'Enviar ao aceite'}</button> : null}
      <button className="button button-danger" type="button" disabled={seen.stale || pending || !confirmed} onClick={() => void discard()}>Descartar vínculo</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={close}>Voltar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}

// ---- Ensaios e aceite: revisão da instalação, resultados, assinatura técnica e situação. A assinatura das regras é da gestão.
export function AcceptanceSection() {
  const scope = useInstallation();
  const data = useInstallData();
  const [creating, setCreating] = useState(false);
  if (useAccessGuard(data.failed)) return null;
  const commission = can(scope.session, 'installation.commission');
  const list = [...(data.acceptances.data ?? [])].sort((a, b) => b.installationRevision - a.installationRevision);
  return <section className="management-section" aria-labelledby="install-acceptance-title">
    <div className="section-heading"><div><span className="section-index">06 / ENSAIOS E ACEITE</span><h2 id="install-acceptance-title">Ensaios e aceite da instalação</h2></div>
      <div className="button-row">{commission ? <button className="button button-compact" type="button" disabled={creating || data.pending} onClick={() => setCreating(true)}>Novo aceite</button> : null}
        <button className="button button-secondary button-compact" type="button" onClick={data.refresh}>Atualizar</button></div></div>
    <p className="field-help">Cada aceite é uma revisão da instalação. O aceite técnico (MASTER de implantação) e o aceite local das regras (gestão do condomínio) são registrados separadamente; a aprovação exige os dois, testes críticos zerados e combinações aprovadas.</p>
    {creating ? <AcceptanceForm close={() => setCreating(false)} /> : null}
    {data.pending ? <div className="panel"><Loading label="Consultando aceites…" /></div> : data.failed ? <ErrorState error={data.failed} retry={data.refresh} />
      : list.length === 0 ? <div className="panel"><Empty icon="shield" title="Nenhum aceite aberto">Abra o aceite com as referências da planta, da topologia, dos ensaios e do plano de reversão.</Empty></div>
        : <ol className="management-list" aria-label="Aceites de instalação">{list.map((item) => <AcceptanceRow key={item.acceptanceId} acceptance={item}
          bindings={(data.bindings.data ?? []).filter((binding) => binding.installationAcceptanceId === item.acceptanceId)}
          points={data.points.data ?? []} areas={data.areas.data ?? []} commission={commission} />)}</ol>}
  </section>;
}
const hashText = (value: string) => `${value.slice(0, 12)}…`;
function AcceptanceRow({ acceptance, bindings, points, areas, commission }: {
  acceptance: InstallationAcceptance; bindings: readonly PointBinding[]; points: readonly AccessPoint[]; areas: readonly AccessArea[]; commission: boolean;
}) {
  const [mode, setMode] = useState<'results' | 'sign' | AcceptanceStatus | null>(null);
  const a = acceptance;
  const pendingBindings = bindings.filter((item) => item.status === 'PENDING_COMMISSION');
  const place = pendingBindings.length === 0 ? `Instalação do condomínio, revisão ${a.installationRevision} (sem vínculo enviado)`
    : `Revisão ${a.installationRevision}: ${pendingBindings.map((item) => placeOf(points.find((point) => point.pointId === item.pointId), areas)).join('; ')}`;
  const open = a.status === 'DRAFT' || a.status === 'TESTING';
  const approvable = a.technicalSignedAt !== null && a.rulesSignedAt !== null && a.criticalTestsPending === 0 && a.criticalTestsFailed === 0 && pendingBindings.length > 0;
  return <li className="management-row">
    <div><strong>Revisão {a.installationRevision}</strong>
      <span className={`status-pill ${a.status === 'APPROVED' ? 'status-ready' : open ? 'status-pending' : 'status-neutral'}`}>{acceptanceStatusLabels[a.status]}</span>
      <span>Planta {a.plantRef} ({hashText(a.plantHash)}) · topologia {a.topologyManifestRef} ({hashText(a.topologyHash)}) · envelope {hashText(a.capabilityEnvelopeHash)}</span>
      <span>Suíte {a.testSuiteVersion} · relatório {a.testReportRef} · emergência {a.emergencyTestRef} · reversão {a.rollbackPlanRef}{a.revocationCheckpointRef ? ` · ponto de revogação ${a.revocationCheckpointRef}` : ''}</span>
      <span>Testes críticos: {a.criticalTestsPending} pendente(s), {a.criticalTestsFailed} reprovado(s) · {bindings.length} vínculo(s), {pendingBindings.length} aguardando comissionamento</span>
      {a.reviewDueAt ? <span>Revisão periódica até {formatWhen(a.reviewDueAt, INSTALL_ZONE)}</span> : null}
      {a.statusReason ? <span>Motivo: {a.statusReason}</span> : null}</div>
    <dl className="install-facts install-signatures" aria-label={`Aceites da revisão ${a.installationRevision}`}>
      <div><dt>Aceite técnico (implantação)</dt><dd>{a.technicalSignedAt ? `Assinado em ${formatWhen(a.technicalSignedAt, INSTALL_ZONE)}` : 'Pendente'}</dd></div>
      <div><dt>Aceite local das regras (gestão)</dt><dd>{a.rulesSignedAt ? `Assinado em ${formatWhen(a.rulesSignedAt, INSTALL_ZONE)}` : a.technicalSignedAt ? 'Aguardando a gestão do condomínio' : 'Depois do aceite técnico'}</dd></div>
      <div><dt>Vigência</dt><dd>{a.effectiveAt ? `Desde ${formatWhen(a.effectiveAt, INSTALL_ZONE)}` : 'Sem vigência'}</dd></div>
    </dl>
    {commission && mode === null ? <div className="button-row">
      {open && a.technicalSignedAt === null ? <button className="button button-secondary button-compact" type="button" onClick={() => setMode('results')}>Registrar resultados dos ensaios</button> : null}
      {open && a.technicalSignedAt === null ? <button className="button button-secondary button-compact" type="button" onClick={() => setMode('sign')}>Assinar aceite técnico</button> : null}
      {acceptanceTransitions[a.status].map((next) => <button key={next} className={`button button-compact ${reasonRequired.includes(next) ? 'button-danger' : 'button-secondary'}`} type="button"
        disabled={next === 'APPROVED' && !approvable} onClick={() => setMode(next)}>{transitionLabels[next]}</button>)}
    </div> : null}
    {commission && a.status === 'TESTING' && !approvable ? <p className="field-help">Aprovar exige aceite técnico, aceite local das regras, testes críticos zerados e ao menos um vínculo aguardando comissionamento.</p> : null}
    {mode === 'results' ? <ResultsForm acceptance={a} close={() => setMode(null)} /> : null}
    {mode === 'sign' ? <SignTechnical acceptance={a} place={place} close={() => setMode(null)} /> : null}
    {mode !== null && mode !== 'results' && mode !== 'sign' ? <TransitionForm acceptance={a} target={mode} place={place} close={() => setMode(null)} /> : null}
  </li>;
}
const transitionLabels: Record<AcceptanceStatus, string> = {
  DRAFT: 'Voltar a rascunho', TESTING: 'Iniciar ensaios', APPROVED: 'Aprovar e comissionar', REJECTED: 'Reprovar', SUSPENDED: 'Suspender', SUPERSEDED: 'Substituir',
};
const count = (value: string): number | null => /^\d{1,6}$/.test(value.trim()) ? Number(value.trim()) : null;

interface AcceptanceDraft {
  plantRef: string; plantHash: string; topologyManifestRef: string; topologyHash: string; capabilityEnvelopeHash: string; testSuiteVersion: string;
  testReportRef: string; criticalTestsPending: string; criticalTestsFailed: string; emergencyTestRef: string; rollbackPlanRef: string;
  revocationCheckpointRef: string; reviewDue: string;
}
const emptyAcceptance: AcceptanceDraft = { plantRef: '', plantHash: '', topologyManifestRef: '', topologyHash: '', capabilityEnvelopeHash: '', testSuiteVersion: '',
  testReportRef: '', criticalTestsPending: '0', criticalTestsFailed: '0', emergencyTestRef: '', rollbackPlanRef: '', revocationCheckpointRef: '', reviewDue: '' };
export function acceptanceBody(acceptanceId: string, draft: AcceptanceDraft): CreateAcceptanceBody | string {
  const refs = [draft.plantRef, draft.topologyManifestRef, draft.testReportRef, draft.emergencyTestRef, draft.rollbackPlanRef].map((value) => value.trim());
  if (refs.some((value) => !REF.test(value))) return 'Planta, topologia, relatório, teste de emergência e plano de reversão: 3 a 120 caracteres, só letras, números e . _ : / -.';
  const checkpoint = blank(draft.revocationCheckpointRef);
  if (checkpoint && !REF.test(checkpoint)) return 'Ponto de revogação: 3 a 120 caracteres, só letras, números e . _ : / -.';
  const hashes = [draft.plantHash, draft.topologyHash, draft.capabilityEnvelopeHash].map((value) => value.trim().toLowerCase());
  if (hashes.some((value) => !HASH.test(value))) return 'Hashes SHA-256 com 64 caracteres hexadecimais.';
  const suite = draft.testSuiteVersion.trim();
  if (!SUITE.test(suite)) return 'Versão da suíte de ensaios: até 64 caracteres, letras, números e . _ -.';
  const pending = count(draft.criticalTestsPending), failed = count(draft.criticalTestsFailed);
  if (pending === null || failed === null) return 'Testes críticos pendentes e reprovados: números inteiros, 0 ou mais.';
  let reviewDueAt: string | null = null;
  if (draft.reviewDue) {
    try { reviewDueAt = toInstant(`${draft.reviewDue}T23:59`, INSTALL_ZONE); } catch { return 'Data de revisão periódica inválida.'; }
  }
  return { acceptanceId, plantRef: refs[0]!, plantHash: hashes[0]!, topologyManifestRef: refs[1]!, topologyHash: hashes[1]!, capabilityEnvelopeHash: hashes[2]!,
    testSuiteVersion: suite, testReportRef: refs[2]!, criticalTestsPending: pending, criticalTestsFailed: failed, emergencyTestRef: refs[3]!,
    rollbackPlanRef: refs[4]!, revocationCheckpointRef: checkpoint, reviewDueAt };
}
function AcceptanceForm({ close }: { close(): void }) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const [acceptanceId] = useState(newId);
  const [draft, setDraft] = useState<AcceptanceDraft>(emptyAcceptance);
  const [validation, setValidation] = useState('');
  const pending = action.state.pending;
  async function submit(event: FormEvent) {
    event.preventDefault();
    const body = acceptanceBody(acceptanceId, draft);
    if (typeof body === 'string') { setValidation(body); return; }
    setValidation('');
    if (await action.run(() => accessInstall.createAcceptance(scope.tenantId, body), 'Aceite aberto em rascunho.',
      async () => { await queries.invalidateQueries({ queryKey: installationKey(scope, 'acceptances') }); })) close();
  }
  const field = (key: keyof AcceptanceDraft, title: string, help?: string, type = 'text') => <div><label htmlFor={`acceptance-${key}`}>{title}</label>
    <input id={`acceptance-${key}`} type={type} value={draft[key]} disabled={pending} required={!['revocationCheckpointRef', 'reviewDue'].includes(key)}
      onChange={(event) => setDraft({ ...draft, [key]: event.target.value })} />{help ? <small className="field-help">{help}</small> : null}</div>;
  return <form className="panel management-form stack-form" aria-label="Novo aceite" onSubmit={(event) => void submit(event)}>
    <h3>Novo aceite de instalação</h3>
    <p className="field-help">Use referências do repositório da obra (sem senhas, sem endereços de rede com credenciais). A revisão é numerada pelo servidor.</p>
    <div className="two-fields">{field('plantRef', 'Referência da planta')}{field('plantHash', 'SHA-256 da planta')}</div>
    <div className="two-fields">{field('topologyManifestRef', 'Referência do manifesto de topologia (rede)')}{field('topologyHash', 'SHA-256 da topologia')}</div>
    <div className="two-fields">{field('capabilityEnvelopeHash', 'SHA-256 do envelope de capacidades')}{field('testSuiteVersion', 'Versão da suíte de ensaios')}</div>
    <div className="two-fields">{field('testReportRef', 'Referência do relatório de ensaios')}{field('emergencyTestRef', 'Referência do teste de emergência')}</div>
    <div className="two-fields">{field('criticalTestsPending', 'Testes críticos pendentes')}{field('criticalTestsFailed', 'Testes críticos reprovados')}</div>
    <div className="two-fields">{field('rollbackPlanRef', 'Referência do plano de reversão')}{field('revocationCheckpointRef', 'Ponto de revogação (opcional)')}</div>
    {field('reviewDue', 'Revisão periódica até (opcional)', `Data no fuso ${INSTALL_ZONE}.`, 'date')}
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={pending}>{pending ? 'Abrindo…' : 'Abrir aceite'}</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={close}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}
function ResultsForm({ acceptance, close }: { acceptance: InstallationAcceptance; close(): void }) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const seen = useVersionedDraft(acceptance.version, () => { setDraft({ pending: String(acceptance.criticalTestsPending), failed: String(acceptance.criticalTestsFailed), report: acceptance.testReportRef, checkpoint: acceptance.revocationCheckpointRef ?? '' }); action.clear(); });
  const [draft, setDraft] = useState({ pending: String(acceptance.criticalTestsPending), failed: String(acceptance.criticalTestsFailed),
    report: acceptance.testReportRef, checkpoint: acceptance.revocationCheckpointRef ?? '' });
  const [validation, setValidation] = useState('');
  const prefix = `results-${acceptance.acceptanceId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (seen.stale) return;
    const pending = count(draft.pending), failed = count(draft.failed), report = draft.report.trim(), checkpoint = blank(draft.checkpoint);
    if (pending === null || failed === null) { setValidation('Testes críticos: números inteiros, 0 ou mais.'); return; }
    if (!REF.test(report) || (checkpoint && !REF.test(checkpoint))) { setValidation('Referências: 3 a 120 caracteres, só letras, números e . _ : / -.'); return; }
    setValidation('');
    if (await action.run(() => accessInstall.recordResults(scope.tenantId, acceptance.acceptanceId, { criticalTestsPending: pending, criticalTestsFailed: failed,
      testReportRef: report, revocationCheckpointRef: checkpoint, expectedVersion: seen.version }), 'Resultados registrados.',
    async () => { await queries.invalidateQueries({ queryKey: installationKey(scope, 'acceptances') }); })) close();
  }
  return <form className="reason-form" aria-label={`Resultados da revisão ${acceptance.installationRevision}`} onSubmit={(event) => void submit(event)}>
    <div className="two-fields"><div><label htmlFor={`${prefix}-pending`}>Testes críticos pendentes</label><input id={`${prefix}-pending`} inputMode="numeric" required value={draft.pending} disabled={action.state.pending} onChange={(event) => setDraft({ ...draft, pending: event.target.value })} /></div>
      <div><label htmlFor={`${prefix}-failed`}>Testes críticos reprovados</label><input id={`${prefix}-failed`} inputMode="numeric" required value={draft.failed} disabled={action.state.pending} onChange={(event) => setDraft({ ...draft, failed: event.target.value })} /></div></div>
    <div className="two-fields"><div><label htmlFor={`${prefix}-report`}>Referência do relatório</label><input id={`${prefix}-report`} required maxLength={120} value={draft.report} disabled={action.state.pending} onChange={(event) => setDraft({ ...draft, report: event.target.value })} /></div>
      <div><label htmlFor={`${prefix}-checkpoint`}>Ponto de revogação (opcional)</label><input id={`${prefix}-checkpoint`} maxLength={120} value={draft.checkpoint} disabled={action.state.pending} onChange={(event) => setDraft({ ...draft, checkpoint: event.target.value })} /></div></div>
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    {seen.stale ? <StaleNotice onReload={seen.reload} /> : null}
    <div className="button-row"><button className="button" type="submit" disabled={seen.stale || action.state.pending}>{action.state.pending ? 'Salvando…' : 'Salvar resultados'}</button>
      <button className="button button-secondary" type="button" disabled={action.state.pending} onClick={close}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}
function SignTechnical({ acceptance, place, close }: { acceptance: InstallationAcceptance; place: string; close(): void }) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const seen = useVersionedDraft(acceptance.version, () => { setConfirmed(false); action.clear(); });
  const [confirmed, setConfirmed] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (seen.stale) return;
    if (!confirmed) return;
    if (await action.run(() => accessInstall.signTechnical(scope.tenantId, acceptance.acceptanceId, { expectedVersion: seen.version }),
      'Aceite técnico assinado. O conteúdo desta revisão não muda mais; a gestão do condomínio assina as regras na tela dela.',
      async () => { await queries.invalidateQueries({ queryKey: installationKey(scope, 'acceptances') }); })) close();
  }
  return <form className="reason-form" aria-label={`Assinar aceite técnico da revisão ${acceptance.installationRevision}`} onSubmit={(event) => void submit(event)}>
    <Notice tone="warning">Depois da assinatura técnica, planta, topologia, ensaios e reversão desta revisão ficam fechados. Mudança exige nova revisão.</Notice>
    <PhysicalContext place={place} confirmed={confirmed} onConfirm={setConfirmed} disabled={action.state.pending} />
    {seen.stale ? <StaleNotice onReload={seen.reload} /> : null}
    <div className="button-row"><button className="button" type="submit" disabled={seen.stale || action.state.pending || !confirmed}>{action.state.pending ? 'Assinando…' : 'Assinar aceite técnico'}</button>
      <button className="button button-secondary" type="button" disabled={action.state.pending} onClick={close}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}
function TransitionForm({ acceptance, target, place, close }: { acceptance: InstallationAcceptance; target: AcceptanceStatus; place: string; close(): void }) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const seen = useVersionedDraft(acceptance.version, () => { setConfirmed(false); action.clear(); });
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const needsReason = reasonRequired.includes(target);
  const physical = target === 'APPROVED' || target === 'SUSPENDED';
  const normalized = displayText(reason, 300);
  const ready = (!needsReason || (normalized !== null && normalized.length >= 3)) && (!physical || confirmed);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (seen.stale) return;
    if (!ready) return;
    if (await action.run(() => accessInstall.transition(scope.tenantId, acceptance.acceptanceId, { status: target, reason: needsReason ? normalized : null, expectedVersion: seen.version }),
      target === 'APPROVED' ? 'Aceite aprovado: vínculos em vigor e pontos comissionados.' : `Aceite: ${acceptanceStatusLabels[target].toLowerCase()}.`,
      async () => { await queries.invalidateQueries({ queryKey: installationKey(scope) }); })) close();
  }
  return <form className="reason-form" aria-label={`${transitionLabels[target]} revisão ${acceptance.installationRevision}`} onSubmit={(event) => void submit(event)}>
    {target === 'APPROVED' ? <Notice tone="warning">Aprovar coloca em vigor os vínculos enviados a esta revisão (o anterior de cada ponto é substituído) e comissiona os pontos.</Notice> : null}
    {target === 'SUSPENDED' ? <Notice tone="warning">Suspender devolve os pontos desta revisão a mapeado e suspende os vínculos em vigor.</Notice> : null}
    {needsReason ? <><label htmlFor={`reason-${acceptance.acceptanceId}`}>Motivo (3 a 300 caracteres)</label>
      <textarea id={`reason-${acceptance.acceptanceId}`} maxLength={300} rows={2} required value={reason} disabled={action.state.pending} onChange={(event) => setReason(event.target.value)} /></> : null}
    {physical ? <PhysicalContext place={place} confirmed={confirmed} onConfirm={setConfirmed} disabled={action.state.pending} /> : null}
    {seen.stale ? <StaleNotice onReload={seen.reload} /> : null}
    <div className="button-row"><button className={`button ${needsReason ? 'button-danger' : ''}`} type="submit" disabled={seen.stale || action.state.pending || !ready}>{action.state.pending ? 'Enviando…' : transitionLabels[target]}</button>
      <button className="button button-secondary" type="button" disabled={action.state.pending} onClick={close}>Voltar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}
