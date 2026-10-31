import { useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  accessInstall, areaKindLabels, areaStatusLabels, channelKindLabels, channelKinds, channelStatusLabels, channelStatuses, connectionModeLabels,
  connectionModes, deviceDirectionLabels, deviceDirections, deviceRoleLabels, deviceRoles, egressClasses, egressLabels, flowDirections, flowLabels,
  pointKindLabels, pointKinds, pointStatusLabels, CHANNEL, CODE, EXTERNAL, NAMESPACE, REF,
  type AccessArea, type AccessDevice, type AccessPoint, type ChannelKind, type ChannelStatus, type ConnectionMode, type CreateChannelBody,
  type CreateDeviceBody, type CreatePointBody, type DeviceChannel, type DeviceDirection, type DeviceRole, type EgressClass, type FlowDirection,
  type PointKind, type PointStatus, type UpdateDeviceBody, type UpdatePointBody,
} from '../../api/accessInstall';
import { isUUID } from '../../api/client';
import { displayText } from '../../api/text';
import { Empty, ErrorState, Loading, Notice } from '../primitives';
import { queryFailure } from '../queryFailure';
import { ActionFeedback, blank, can, installationKey, newId, short, useAccessGuard, useAction, useInstallation } from './shared';

function useList<T>(part: string, load: (tenantId: string, signal?: AbortSignal) => Promise<T>) {
  const scope = useInstallation();
  return useQuery({ queryKey: installationKey(scope, part), queryFn: ({ signal }) => load(scope.tenantId, signal) });
}
function Heading({ index, title, id, children }: { index: string; title: string; id: string; children?: ReactNode }) {
  return <div className="section-heading"><div><span className="section-index">{index}</span><h2 id={id}>{title}</h2></div>
    {children ? <div className="button-row">{children}</div> : null}</div>;
}
function Select<T extends string>({ id, label, value, options, labels, onChange, disabled, empty }: {
  id: string; label: string; value: T | ''; options: readonly T[]; labels: Record<T, string>; onChange(value: T | ''): void; disabled?: boolean; empty?: string;
}) {
  return <div><label htmlFor={id}>{label}</label>
    <select id={id} value={value} disabled={disabled} onChange={(event) => onChange(event.target.value as T | '')} required={empty === undefined}>
      {empty !== undefined ? <option value="">{empty}</option> : value === '' ? <option value="" disabled>Escolha…</option> : null}
      {options.map((option) => <option key={option} value={option}>{labels[option]}</option>)}
    </select></div>;
}
export const areaName = (areas: readonly AccessArea[], id: string) => {
  const area = areas.find((item) => item.areaId === id);
  return area ? `${area.name} (${area.code})` : `área ${short(id)}`;
};

// ---- Áreas: a gestão cria a área lógica; o MASTER de implantação marca rota essencial e separação física.
export function AreaPhysicalSection() {
  const scope = useInstallation();
  const areas = useList('areas', accessInstall.technicalAreas);
  const [editing, setEditing] = useState<string | null>(null);
  const failed = queryFailure([areas]);
  if (useAccessGuard(failed)) return null;
  return <section className="management-section" aria-labelledby="install-areas-title">
    <Heading index="01 / ÁREAS" title="Áreas e separação física" id="install-areas-title">
      <button className="button button-secondary button-compact" type="button" disabled={areas.isFetching} onClick={() => void areas.refetch()}>Atualizar</button></Heading>
    <p className="field-help">Nome, tipo e interdição das áreas são da gestão do condomínio. Aqui só se registra o que foi conferido no local.</p>
    {areas.isPending ? <div className="panel"><Loading label="Consultando áreas…" /></div> : failed || !areas.data ? <ErrorState error={failed} retry={() => void areas.refetch()} />
      : areas.data.length === 0 ? <div className="panel"><Empty icon="grid" title="Nenhuma área cadastrada">A gestão do condomínio ainda não cadastrou as áreas de acesso.</Empty></div>
        : <ol className="management-list" aria-label="Áreas de acesso">{areas.data.map((area) => <li key={area.areaId} className="management-row">
          <div><strong>{area.name}</strong><span className="status-pill status-neutral">{areaStatusLabels[area.status]}</span>
            <span>Código {area.code} · {areaKindLabels[area.areaKind]} · fuso {area.timeZone}</span>
            <span>{area.physicalBoundaryVerified ? 'Separação física verificada' : 'Separação física não verificada'} · {area.essentialRoute ? 'Rota essencial' : 'Não é rota essencial'}</span></div>
          {editing === area.areaId ? <AreaPhysicalForm area={area} close={() => setEditing(null)} />
            : can(scope.session, 'installation.manage') ? <div className="button-row"><button className="button button-secondary button-compact" type="button"
              disabled={editing !== null} onClick={() => setEditing(area.areaId)}>Registrar conferência física</button></div> : null}
        </li>)}</ol>}
  </section>;
}
function AreaPhysicalForm({ area, close }: { area: AccessArea; close(): void }) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const [essential, setEssential] = useState(area.essentialRoute);
  const [verified, setVerified] = useState(area.physicalBoundaryVerified);
  async function submit(event: FormEvent) {
    event.preventDefault();
    const ok = await action.run(() => accessInstall.setAreaPhysical(scope.tenantId, area.areaId,
      { essentialRoute: essential, physicalBoundaryVerified: verified, expectedVersion: area.version }), 'Conferência física registrada.',
    async () => { await queries.invalidateQueries({ queryKey: installationKey(scope, 'areas') }); });
    if (ok) close();
  }
  return <form className="reason-form" aria-label={`Conferência física de ${area.name}`} onSubmit={(event) => void submit(event)}>
    <label className="checkbox-label"><input type="checkbox" checked={verified} disabled={action.state.pending} onChange={(event) => setVerified(event.target.checked)} />
      <span>Separação física verificada no local (paredes, portas e grades fecham a área)</span></label>
    <label className="checkbox-label"><input type="checkbox" checked={essential} disabled={action.state.pending} onChange={(event) => setEssential(event.target.checked)} />
      <span>Rota essencial (passagem que não pode ficar bloqueada, inclusive em falha)</span></label>
    <div className="button-row"><button className="button" type="submit" disabled={action.state.pending}>{action.state.pending ? 'Salvando…' : 'Salvar conferência'}</button>
      <button className="button button-secondary" type="button" disabled={action.state.pending} onClick={close}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}

// ---- Pontos: origem/destino, mecanismo, sentido e saída de emergência.
interface PointDraft {
  code: string; name: string; fromAreaId: string; toAreaId: string; pointKind: PointKind | ''; flowDirection: FlowDirection; egressClass: EgressClass;
  accessibleRouteRef: string; emergencyDesignRef: string; status: PointStatus;
}
const emptyPoint: PointDraft = { code: '', name: '', fromAreaId: '', toAreaId: '', pointKind: '', flowDirection: 'UNKNOWN', egressClass: 'UNVERIFIED',
  accessibleRouteRef: '', emergencyDesignRef: '', status: 'DRAFT' };
/** Mesmas regras do servidor; o servidor decide. */
export function pointFields(draft: PointDraft): Omit<UpdatePointBody, 'status' | 'expectedVersion'> | string {
  const name = displayText(draft.name, 160);
  if (!name) return 'Informe o nome do ponto com até 160 caracteres.';
  if (!isUUID(draft.fromAreaId) || !isUUID(draft.toAreaId) || draft.fromAreaId === draft.toAreaId) return 'Escolha origem e destino diferentes.';
  if (!draft.pointKind) return 'Escolha o mecanismo do ponto.';
  const accessibleRouteRef = blank(draft.accessibleRouteRef), emergencyDesignRef = blank(draft.emergencyDesignRef);
  if ((accessibleRouteRef && !REF.test(accessibleRouteRef)) || (emergencyDesignRef && !REF.test(emergencyDesignRef)))
    return 'Referências de projeto: 3 a 120 caracteres, só letras, números e . _ : / -.';
  return { name, fromAreaId: draft.fromAreaId, toAreaId: draft.toAreaId, pointKind: draft.pointKind, flowDirection: draft.flowDirection,
    egressClass: draft.egressClass, accessibleRouteRef, emergencyDesignRef };
}
export function PointSection() {
  const areas = useList('areas', accessInstall.technicalAreas);
  const points = useList('points', accessInstall.points);
  const [editing, setEditing] = useState<string | null>(null);
  const failed = queryFailure([areas, points]);
  if (useAccessGuard(failed)) return null;
  const refresh = () => { void areas.refetch(); void points.refetch(); };
  return <section className="management-section" aria-labelledby="install-points-title">
    <Heading index="02 / PONTOS" title="Pontos de acesso" id="install-points-title">
      <button className="button button-compact" type="button" disabled={editing !== null || !areas.data || areas.data.length < 2} onClick={() => setEditing('new')}>Novo ponto</button>
      <button className="button button-secondary button-compact" type="button" onClick={refresh}>Atualizar</button></Heading>
    <p className="field-help">Cada ponto liga duas áreas. Ponto só fica comissionado pelo aceite técnico; mudar o físico de um ponto comissionado o devolve a mapeado e suspende o vínculo em vigor.</p>
    {areas.data && areas.data.length < 2 ? <Notice>Cadastre ao menos duas áreas (pela gestão) antes de criar pontos.</Notice> : null}
    {editing === 'new' && areas.data ? <PointForm areas={areas.data} point={null} close={() => setEditing(null)} /> : null}
    {areas.isPending || points.isPending ? <div className="panel"><Loading label="Consultando pontos…" /></div>
      : failed ? <ErrorState error={failed} retry={refresh} />
        : points.data!.length === 0 ? <div className="panel"><Empty icon="door" title="Nenhum ponto cadastrado">Cadastre portas, catracas e portões com origem, destino e sentido.</Empty></div>
          : <ol className="management-list" aria-label="Pontos de acesso">{points.data!.map((point) => editing === point.pointId
            ? <li key={point.pointId}><PointForm areas={areas.data!} point={point} close={() => setEditing(null)} /></li>
            : <li key={point.pointId} className="management-row"><div><strong>{point.name}</strong>
              <span className={`status-pill ${point.status === 'COMMISSIONED' ? 'status-ready' : 'status-neutral'}`}>{pointStatusLabels[point.status]}</span>
              <span>Código {point.code} · {pointKindLabels[point.pointKind]} · {areaName(areas.data!, point.fromAreaId)} → {areaName(areas.data!, point.toAreaId)}</span>
              <span>{flowLabels[point.flowDirection]} · {egressLabels[point.egressClass]}</span></div>
              <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={editing !== null} onClick={() => setEditing(point.pointId)}>Editar</button></div>
            </li>)}</ol>}
  </section>;
}
function PointForm({ areas, point, close }: { areas: readonly AccessArea[]; point: AccessPoint | null; close(): void }) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const [pointId] = useState(() => point?.pointId ?? newId());
  const [draft, setDraft] = useState<PointDraft>(() => point ? { ...point, accessibleRouteRef: point.accessibleRouteRef ?? '',
    emergencyDesignRef: point.emergencyDesignRef ?? '' } : emptyPoint);
  const [validation, setValidation] = useState('');
  const set = <K extends keyof PointDraft>(key: K, value: PointDraft[K]) => setDraft((current) => ({ ...current, [key]: value }));
  const prefix = point ? `point-${point.pointId}` : 'point-new';
  const pending = action.state.pending;
  const usable = areas.filter((area) => area.status !== 'RETIRED' || area.areaId === draft.fromAreaId || area.areaId === draft.toAreaId);
  const statuses: PointStatus[] = point?.status === 'COMMISSIONED' ? ['COMMISSIONED', 'SUSPENDED', 'RETIRED'] : point ? ['DRAFT', 'MAPPED', 'SUSPENDED', 'RETIRED'] : ['DRAFT', 'MAPPED'];
  async function submit(event: FormEvent) {
    event.preventDefault();
    const fields = pointFields(draft);
    if (typeof fields === 'string') { setValidation(fields); return; }
    if (!point && !CODE.test(draft.code)) { setValidation('Código: letras maiúsculas, números, _ ou -, até 64 caracteres.'); return; }
    setValidation('');
    const ok = await action.run(() => point
      ? accessInstall.updatePoint(scope.tenantId, point.pointId, { ...fields, status: draft.status, expectedVersion: point.version })
      : accessInstall.createPoint(scope.tenantId, { ...fields, pointId, code: draft.code, status: draft.status as CreatePointBody['status'] }),
    point ? 'Ponto atualizado.' : 'Ponto cadastrado.', async () => { await queries.invalidateQueries({ queryKey: installationKey(scope, 'points') }); });
    if (ok) close();
  }
  return <form className="panel management-form stack-form" aria-label={point ? `Editar ${point.name}` : 'Novo ponto'} onSubmit={(event) => void submit(event)}>
    <h3>{point ? `Editar ${point.name}` : 'Novo ponto'}</h3>
    {point?.status === 'COMMISSIONED' ? <Notice tone="warning">Ponto comissionado: mudar origem, destino, mecanismo, sentido ou saída o devolve a mapeado e suspende o vínculo em vigor.</Notice> : null}
    <div className="two-fields"><div><label htmlFor={`${prefix}-code`}>Código</label>
      <input id={`${prefix}-code`} required maxLength={64} value={draft.code} disabled={pending || point !== null} onChange={(event) => set('code', event.target.value.toUpperCase())} />
      <small className="field-help">Ex.: P-PORTARIA-01. Não muda depois de criado.</small></div>
      <div><label htmlFor={`${prefix}-name`}>Nome</label><input id={`${prefix}-name`} required maxLength={160} value={draft.name} disabled={pending} onChange={(event) => set('name', event.target.value)} /></div></div>
    <div className="two-fields"><div><label htmlFor={`${prefix}-from`}>Origem</label>
      <select id={`${prefix}-from`} required value={draft.fromAreaId} disabled={pending} onChange={(event) => set('fromAreaId', event.target.value)}>
        <option value="" disabled>Escolha…</option>{usable.map((area) => <option key={area.areaId} value={area.areaId}>{area.name} ({area.code})</option>)}</select></div>
      <div><label htmlFor={`${prefix}-to`}>Destino</label>
        <select id={`${prefix}-to`} required value={draft.toAreaId} disabled={pending} onChange={(event) => set('toAreaId', event.target.value)}>
          <option value="" disabled>Escolha…</option>{usable.map((area) => <option key={area.areaId} value={area.areaId}>{area.name} ({area.code})</option>)}</select></div></div>
    <div className="two-fields"><Select id={`${prefix}-kind`} label="Mecanismo" value={draft.pointKind} options={pointKinds} labels={pointKindLabels} disabled={pending} onChange={(value) => set('pointKind', value)} />
      <Select id={`${prefix}-flow`} label="Sentido permitido" value={draft.flowDirection} options={flowDirections} labels={flowLabels} disabled={pending} onChange={(value) => set('flowDirection', value || 'UNKNOWN')} /></div>
    <div className="two-fields"><Select id={`${prefix}-egress`} label="Saída de emergência" value={draft.egressClass} options={egressClasses} labels={egressLabels} disabled={pending} onChange={(value) => set('egressClass', value || 'UNVERIFIED')} />
      <Select id={`${prefix}-status`} label="Situação" value={draft.status} options={statuses} labels={pointStatusLabels} disabled={pending} onChange={(value) => set('status', value || draft.status)} /></div>
    <div className="two-fields"><div><label htmlFor={`${prefix}-route`}>Referência da rota acessível (opcional)</label>
      <input id={`${prefix}-route`} maxLength={120} value={draft.accessibleRouteRef} disabled={pending} onChange={(event) => set('accessibleRouteRef', event.target.value)} /></div>
      <div><label htmlFor={`${prefix}-design`}>Referência do projeto de emergência (opcional)</label>
        <input id={`${prefix}-design`} maxLength={120} value={draft.emergencyDesignRef} disabled={pending} onChange={(event) => set('emergencyDesignRef', event.target.value)} /></div></div>
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={pending}>{pending ? 'Salvando…' : point ? 'Salvar ponto' : 'Cadastrar ponto'}</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={close}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}

// ---- Equipamentos: inventário e rede (gateway local ou nuvem). Senha e chamada de fabricante nunca passam por esta tela.
interface DeviceDraft {
  connectorId: string; externalId: string; label: string; direction: DeviceDirection; hardwareRevision: string; homologationId: string;
  deviceRole: DeviceRole | ''; connectionMode: ConnectionMode | ''; status: 'ACTIVE' | 'RETIRED';
}
const emptyDevice: DeviceDraft = { connectorId: '', externalId: '', label: '', direction: 'UNKNOWN', hardwareRevision: '', homologationId: '',
  deviceRole: '', connectionMode: '', status: 'ACTIVE' };
export function deviceFields(draft: DeviceDraft): Omit<UpdateDeviceBody, 'status' | 'expectedVersion'> | string {
  const label = displayText(draft.label, 120);
  if (!label) return 'Informe o nome do equipamento com até 120 caracteres.';
  const hardwareRevision = blank(draft.hardwareRevision);
  if (hardwareRevision && hardwareRevision.length > 64) return 'Revisão de hardware com até 64 caracteres.';
  const homologationId = blank(draft.homologationId);
  if (homologationId && !isUUID(homologationId)) return 'A combinação homologada deve ser o identificador recebido da plataforma.';
  return { label, direction: draft.direction, hardwareRevision, homologationId: homologationId?.toLowerCase() ?? null,
    deviceRole: draft.deviceRole || null, connectionMode: draft.connectionMode || null };
}
export function DeviceSection() {
  const devices = useList('devices', accessInstall.devices);
  const [editing, setEditing] = useState<string | null>(null);
  const failed = queryFailure([devices]);
  if (useAccessGuard(failed)) return null;
  return <section className="management-section" aria-labelledby="install-devices-title">
    <Heading index="03 / EQUIPAMENTOS" title="Inventário e rede" id="install-devices-title">
      <button className="button button-compact" type="button" disabled={editing !== null} onClick={() => setEditing('new')}>Novo equipamento</button>
      <button className="button button-secondary button-compact" type="button" disabled={devices.isFetching} onClick={() => void devices.refetch()}>Atualizar</button></Heading>
    <p className="field-help">Registre o identificador do aparelho no fabricante e a forma de conexão. Credenciais do equipamento ficam no cofre do conector e nunca aparecem aqui. Equipamento trocado é cadastro novo.</p>
    {editing === 'new' ? <DeviceForm device={null} known={devices.data ?? []} close={() => setEditing(null)} /> : null}
    {devices.isPending ? <div className="panel"><Loading label="Consultando equipamentos…" /></div> : failed || !devices.data ? <ErrorState error={failed} retry={() => void devices.refetch()} />
      : devices.data.length === 0 ? <div className="panel"><Empty icon="box" title="Nenhum equipamento cadastrado">Cadastre terminais, controladoras e leitores instalados.</Empty></div>
        : <ol className="management-list" aria-label="Equipamentos">{devices.data.map((device) => editing === device.deviceId
          ? <li key={device.deviceId}><DeviceForm device={device} known={devices.data} close={() => setEditing(null)} /></li>
          : <li key={device.deviceId} className="management-row"><div><strong>{device.label}</strong>
            <span className={`status-pill ${device.status === 'ACTIVE' ? 'status-ready' : 'status-neutral'}`}>{device.status === 'ACTIVE' ? 'Ativo' : 'Desativado'}</span>
            <span>{device.deviceRole ? deviceRoleLabels[device.deviceRole] : 'Papel não informado'} · {deviceDirectionLabels[device.direction]} · {device.connectionMode ? connectionModeLabels[device.connectionMode] : 'Conexão padrão do condomínio'}</span>
            <span>Id. no fabricante {device.externalId} · conector {short(device.connectorId)}{device.hardwareRevision ? ` · revisão ${device.hardwareRevision}` : ''}</span>
            <span>{device.homologationId ? `Combinação homologada ${short(device.homologationId)}` : 'Sem combinação homologada informada'}</span></div>
            <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={editing !== null} onClick={() => setEditing(device.deviceId)}>Editar</button></div>
          </li>)}</ol>}
  </section>;
}
function DeviceForm({ device, known, close }: { device: AccessDevice | null; known: readonly AccessDevice[]; close(): void }) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const [deviceId] = useState(() => device?.deviceId ?? newId());
  const [draft, setDraft] = useState<DeviceDraft>(() => device ? { ...device, hardwareRevision: device.hardwareRevision ?? '',
    homologationId: device.homologationId ?? '', deviceRole: device.deviceRole ?? '', connectionMode: device.connectionMode ?? '' } : emptyDevice);
  const [validation, setValidation] = useState('');
  const set = <K extends keyof DeviceDraft>(key: K, value: DeviceDraft[K]) => setDraft((current) => ({ ...current, [key]: value }));
  const prefix = device ? `device-${device.deviceId}` : 'device-new';
  const pending = action.state.pending;
  const connectors = [...new Set(known.map((item) => item.connectorId))];
  async function submit(event: FormEvent) {
    event.preventDefault();
    const fields = deviceFields(draft);
    if (typeof fields === 'string') { setValidation(fields); return; }
    if (!device && !isUUID(draft.connectorId.trim())) { setValidation('Informe o identificador do conector recebido no cadastro da integração.'); return; }
    if (!device && !EXTERNAL.test(draft.externalId.trim())) { setValidation('Identificador no fabricante: até 120 caracteres, só letras, números e . _ : -.'); return; }
    setValidation('');
    const body: CreateDeviceBody | null = device ? null : { ...fields, deviceId, connectorId: draft.connectorId.trim().toLowerCase(), externalId: draft.externalId.trim() };
    const ok = await action.run(() => body ? accessInstall.createDevice(scope.tenantId, body)
      : accessInstall.updateDevice(scope.tenantId, device!.deviceId, { ...fields, status: draft.status, expectedVersion: device!.version }),
    device ? 'Equipamento atualizado.' : 'Equipamento cadastrado.', async () => { await queries.invalidateQueries({ queryKey: installationKey(scope, 'devices') }); });
    if (ok) close();
  }
  return <form className="panel management-form stack-form" aria-label={device ? `Editar ${device.label}` : 'Novo equipamento'} onSubmit={(event) => void submit(event)}>
    <h3>{device ? `Editar ${device.label}` : 'Novo equipamento'}</h3>
    <div className="two-fields"><div><label htmlFor={`${prefix}-connector`}>Conector (integração)</label>
      <input id={`${prefix}-connector`} list={`${prefix}-connectors`} required={!device} value={draft.connectorId} disabled={pending || device !== null} onChange={(event) => set('connectorId', event.target.value)} />
      <datalist id={`${prefix}-connectors`}>{connectors.map((id) => <option key={id} value={id} />)}</datalist>
      <small className="field-help">Identificador do conector do condomínio. Ainda não há lista de conectores para a implantação.</small></div>
      <div><label htmlFor={`${prefix}-external`}>Identificador no fabricante</label>
        <input id={`${prefix}-external`} required={!device} maxLength={120} value={draft.externalId} disabled={pending || device !== null} onChange={(event) => set('externalId', event.target.value)} />
        <small className="field-help">Número de série ou id lógico. Nunca senha ou token.</small></div></div>
    <div className="two-fields"><div><label htmlFor={`${prefix}-label`}>Nome</label><input id={`${prefix}-label`} required maxLength={120} value={draft.label} disabled={pending} onChange={(event) => set('label', event.target.value)} /></div>
      <Select id={`${prefix}-role`} label="Papel" value={draft.deviceRole} options={deviceRoles} labels={deviceRoleLabels} disabled={pending} empty="Não informado" onChange={(value) => set('deviceRole', value)} /></div>
    <div className="two-fields"><Select id={`${prefix}-direction`} label="Sentido do equipamento" value={draft.direction} options={deviceDirections} labels={deviceDirectionLabels} disabled={pending} onChange={(value) => set('direction', value || 'UNKNOWN')} />
      <Select id={`${prefix}-mode`} label="Conexão" value={draft.connectionMode} options={connectionModes} labels={connectionModeLabels} disabled={pending} empty="Padrão do condomínio" onChange={(value) => set('connectionMode', value)} /></div>
    <div className="two-fields"><div><label htmlFor={`${prefix}-hardware`}>Revisão de hardware (opcional)</label>
      <input id={`${prefix}-hardware`} maxLength={64} value={draft.hardwareRevision} disabled={pending} onChange={(event) => set('hardwareRevision', event.target.value)} /></div>
      <div><label htmlFor={`${prefix}-homologation`}>Combinação homologada (opcional)</label>
        <input id={`${prefix}-homologation`} value={draft.homologationId} disabled={pending} onChange={(event) => set('homologationId', event.target.value)} />
        <small className="field-help">Identificador recebido da plataforma.</small></div></div>
    {device ? <Select id={`${prefix}-status`} label="Situação" value={draft.status} options={['ACTIVE', 'RETIRED'] as const} labels={{ ACTIVE: 'Ativo', RETIRED: 'Desativado' }} disabled={pending} onChange={(value) => set('status', value || 'ACTIVE')} /> : null}
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={pending}>{pending ? 'Salvando…' : device ? 'Salvar equipamento' : 'Cadastrar equipamento'}</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={close}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}

// ---- Canais: relés, sensores e botões nativos de cada equipamento.
export function ChannelSection() {
  const devices = useList('devices', accessInstall.devices);
  const channels = useList('channels', accessInstall.channels);
  const [editing, setEditing] = useState<string | null>(null);
  const failed = queryFailure([devices, channels]);
  if (useAccessGuard(failed)) return null;
  const refresh = () => { void devices.refetch(); void channels.refetch(); };
  const active = devices.data?.filter((item) => item.status === 'ACTIVE') ?? [];
  const deviceName = (id: string) => devices.data?.find((item) => item.deviceId === id)?.label ?? `equipamento ${short(id)}`;
  return <section className="management-section" aria-labelledby="install-channels-title">
    <Heading index="04 / CANAIS" title="Canais e sensores" id="install-channels-title">
      <button className="button button-compact" type="button" disabled={editing !== null || active.length === 0} onClick={() => setEditing('new')}>Novo canal</button>
      <button className="button button-secondary button-compact" type="button" onClick={refresh}>Atualizar</button></Heading>
    <p className="field-help">Canal nativo do equipamento (relé, sensor de porta, botão de saída, sensor de passagem). Só canal verificado no local deve ir para um vínculo.</p>
    {devices.data && active.length === 0 ? <Notice>Cadastre um equipamento ativo antes dos canais.</Notice> : null}
    {editing === 'new' ? <ChannelCreateForm devices={active} close={() => setEditing(null)} /> : null}
    {devices.isPending || channels.isPending ? <div className="panel"><Loading label="Consultando canais…" /></div>
      : failed ? <ErrorState error={failed} retry={refresh} />
        : channels.data!.length === 0 ? <div className="panel"><Empty icon="key" title="Nenhum canal cadastrado">Cadastre o relé de abertura e os sensores de cada ponto.</Empty></div>
          : <ol className="management-list" aria-label="Canais">{channels.data!.map((channel) => <li key={channel.channelId} className="management-row">
            <div><strong>{channelKindLabels[channel.channelKind]} · {deviceName(channel.deviceId)}</strong>
              <span className={`status-pill ${channel.status === 'VERIFIED' ? 'status-ready' : 'status-neutral'}`}>{channelStatusLabels[channel.status]}</span>
              <span>Canal nativo {channel.nativeNamespace}/{channel.externalChannel}</span></div>
            {editing === channel.channelId ? <ChannelEditForm channel={channel} close={() => setEditing(null)} />
              : <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={editing !== null} onClick={() => setEditing(channel.channelId)}>Alterar função ou situação</button></div>}
          </li>)}</ol>}
  </section>;
}
function ChannelCreateForm({ devices, close }: { devices: readonly AccessDevice[]; close(): void }) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const [channelId] = useState(newId);
  const [draft, setDraft] = useState({ deviceId: '', nativeNamespace: '', externalChannel: '', channelKind: '' as ChannelKind | '', status: 'DISCOVERED' as 'DISCOVERED' | 'VERIFIED' });
  const [validation, setValidation] = useState('');
  const pending = action.state.pending;
  async function submit(event: FormEvent) {
    event.preventDefault();
    const body: CreateChannelBody = { channelId, deviceId: draft.deviceId, nativeNamespace: draft.nativeNamespace.trim(), externalChannel: draft.externalChannel.trim(),
      channelKind: draft.channelKind as ChannelKind, status: draft.status };
    if (!isUUID(body.deviceId)) { setValidation('Escolha o equipamento.'); return; }
    if (!NAMESPACE.test(body.nativeNamespace) || !CHANNEL.test(body.externalChannel)) {
      setValidation('Espaço nativo: minúsculas, números e . _ - (começa com letra). Canal: até 64 caracteres, letras, números e . _ : -.'); return;
    }
    if (!draft.channelKind) { setValidation('Escolha a função do canal.'); return; }
    setValidation('');
    const ok = await action.run(() => accessInstall.createChannel(scope.tenantId, body), 'Canal cadastrado.',
      async () => { await queries.invalidateQueries({ queryKey: installationKey(scope, 'channels') }); });
    if (ok) close();
  }
  return <form className="panel management-form stack-form" aria-label="Novo canal" onSubmit={(event) => void submit(event)}>
    <h3>Novo canal</h3>
    <div className="two-fields"><div><label htmlFor="channel-new-device">Equipamento</label>
      <select id="channel-new-device" required value={draft.deviceId} disabled={pending} onChange={(event) => setDraft({ ...draft, deviceId: event.target.value })}>
        <option value="" disabled>Escolha…</option>{devices.map((item) => <option key={item.deviceId} value={item.deviceId}>{item.label}</option>)}</select></div>
      <Select id="channel-new-kind" label="Função" value={draft.channelKind} options={channelKinds} labels={channelKindLabels} disabled={pending} onChange={(value) => setDraft({ ...draft, channelKind: value })} /></div>
    <div className="two-fields"><div><label htmlFor="channel-new-namespace">Espaço nativo</label>
      <input id="channel-new-namespace" required maxLength={64} value={draft.nativeNamespace} disabled={pending} onChange={(event) => setDraft({ ...draft, nativeNamespace: event.target.value })} />
      <small className="field-help">Ex.: relay, door, input.</small></div>
      <div><label htmlFor="channel-new-external">Canal no equipamento</label>
        <input id="channel-new-external" required maxLength={64} value={draft.externalChannel} disabled={pending} onChange={(event) => setDraft({ ...draft, externalChannel: event.target.value })} /></div></div>
    <Select id="channel-new-status" label="Situação" value={draft.status} options={['DISCOVERED', 'VERIFIED'] as const} labels={{ DISCOVERED: 'Descoberto', VERIFIED: 'Verificado no local' }} disabled={pending} onChange={(value) => setDraft({ ...draft, status: value || 'DISCOVERED' })} />
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={pending}>{pending ? 'Salvando…' : 'Cadastrar canal'}</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={close}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}
function ChannelEditForm({ channel, close }: { channel: DeviceChannel; close(): void }) {
  const scope = useInstallation();
  const queries = useQueryClient();
  const action = useAction();
  const [kind, setKind] = useState<ChannelKind>(channel.channelKind);
  const [status, setStatus] = useState<ChannelStatus>(channel.status);
  const prefix = `channel-${channel.channelId}`;
  async function submit(event: FormEvent) {
    event.preventDefault();
    const ok = await action.run(() => accessInstall.updateChannel(scope.tenantId, channel.channelId, { channelKind: kind, status, expectedVersion: channel.version }),
      'Canal atualizado.', async () => { await queries.invalidateQueries({ queryKey: installationKey(scope, 'channels') }); });
    if (ok) close();
  }
  return <form className="reason-form" aria-label={`Alterar canal ${channel.externalChannel}`} onSubmit={(event) => void submit(event)}>
    <div className="two-fields"><Select id={`${prefix}-kind`} label="Função" value={kind} options={channelKinds} labels={channelKindLabels} disabled={action.state.pending} onChange={(value) => setKind(value || kind)} />
      <Select id={`${prefix}-status`} label="Situação" value={status} options={channelStatuses} labels={channelStatusLabels} disabled={action.state.pending} onChange={(value) => setStatus(value || status)} /></div>
    <div className="button-row"><button className="button" type="submit" disabled={action.state.pending}>{action.state.pending ? 'Salvando…' : 'Salvar canal'}</button>
      <button className="button button-secondary" type="button" disabled={action.state.pending} onClick={close}>Cancelar</button></div>
    <ActionFeedback state={action.state} />
  </form>;
}
