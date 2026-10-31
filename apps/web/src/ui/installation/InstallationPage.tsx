import { useCallback, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, isUUID } from '../../api/client';
import { accessInstall, acceptanceStatusLabels, scopeLabels, type TechnicalSession } from '../../api/accessInstall';
import { useAuth } from '../Auth';
import { MfaPanel, mfaStatusKey } from '../Mfa';
import { Empty, ErrorState, Icon, Loading, Notice, SectionTabs, type IconName } from '../primitives';
import { formatWhen } from '../dateTime';
import { AreaPhysicalSection, ChannelSection, DeviceSection, PointSection } from './Inventory';
import { AcceptanceSection, BindingSection } from './Commissioning';
import { INSTALL_ZONE, InstallationContext, OutcomeContext, can, installationKey, useAccessGuard, useInstallation } from './shared';
import '../management/Management.css';
import './Installation.css';

/**
 * Seções da implantação e o escopo técnico que cada uma exige além de installation.read. A navegação lateral e as abas
 * usam a mesma regra; o servidor confere de novo em cada chamada.
 */
export const installationSections = [
  { id: 'roteiro', label: 'Roteiro', icon: 'list', scope: null },
  { id: 'areas', label: 'Áreas físicas', icon: 'grid', scope: 'installation.manage' },
  { id: 'pontos', label: 'Pontos', icon: 'door', scope: 'installation.manage' },
  { id: 'equipamentos', label: 'Equipamentos', icon: 'box', scope: 'installation.manage' },
  { id: 'canais', label: 'Canais', icon: 'key', scope: 'installation.manage' },
  { id: 'vinculos', label: 'Vínculos', icon: 'arrow', scope: 'installation.bind_channel' },
  { id: 'aceite', label: 'Ensaios e aceite', icon: 'shield', scope: 'installation.commission' },
] as const satisfies readonly { id: string; label: string; icon: IconName; scope: TechnicalSession['scopes'][number] | null }[];
export type InstallationSection = typeof installationSections[number]['id'];

export function visibleInstallationSections(session: TechnicalSession | undefined) {
  if (!session || !can(session, 'installation.read')) return [];
  return installationSections.filter((item) => item.scope === null || can(session, item.scope));
}
export const technicalSessionKey = (accountId: string, tenantId: string) => ['private', accountId, 'tenant', tenantId, 'installation', 'session'] as const;
/** Navegação lateral: só observa a sessão técnica já consultada pela porta, sem requisição própria. */
export function useInstallationNavigation(accountId: string | undefined, tenantId: string | undefined, section: string) {
  const session = useQuery({ queryKey: technicalSessionKey(accountId ?? '', tenantId ?? ''), queryFn: ({ signal }) => accessInstall.session(tenantId!, signal),
    enabled: false });
  const items = visibleInstallationSections(session.data);
  return { items, current: items.find((item) => item.id === section)?.id ?? 'roteiro' };
}

/** Implantação técnica do condomínio: concessão técnica vigente e segundo fator; não depende de vínculo no condomínio. */
export function InstallationPage() {
  const { identity } = useAuth();
  const { tenantId = '', section = 'roteiro' } = useParams();
  if (!identity) return null;
  if (!isUUID(tenantId)) return <div className="panel"><Empty icon="shield" title="Condomínio inválido">Abra a implantação pelo painel master ou pelo link recebido.</Empty></div>;
  return <InstallationGate key={`${identity.accountId}:${tenantId}`} accountId={identity.accountId} tenantId={tenantId} section={section} />;
}

function InstallationGate({ accountId, tenantId, section }: { accountId: string; tenantId: string; section: string }) {
  const queries = useQueryClient();
  const status = useQuery({ queryKey: mfaStatusKey(accountId), queryFn: ({ signal }) => api.mfaStatus(signal), refetchOnWindowFocus: false });
  const aal2 = status.data?.assuranceLevel === 'AAL2';
  const session = useQuery({ queryKey: technicalSessionKey(accountId, tenantId), queryFn: ({ signal }) => accessInstall.session(tenantId, signal),
    enabled: aal2, refetchOnWindowFocus: false });
  const [demanded, setDemanded] = useState(false);
  const [denied, setDenied] = useState<unknown>(null);
  const requireMfa = useCallback(() => setDemanded(true), []);
  const deny = useCallback((error: unknown) => {
    setDenied(error);
    const prefix = installationKey({ accountId, tenantId });
    void queries.cancelQueries({ queryKey: prefix }); queries.removeQueries({ queryKey: prefix });
  }, [queries, accountId, tenantId]);
  const refreshSession = useCallback(() => { void queries.invalidateQueries({ queryKey: technicalSessionKey(accountId, tenantId) }); }, [queries, accountId, tenantId]);
  const value = useMemo(() => session.data ? { accountId, tenantId, session: session.data, requireMfa, denied: deny, refreshSession } : null,
    [accountId, tenantId, session.data, requireMfa, deny, refreshSession]);
  if (denied) return <div className="panel"><Notice tone="error">A concessão técnica deste condomínio não está mais vigente para esta conta. Os dados exibidos foram removidos.</Notice>
    <Link className="button button-secondary" to="/contextos">Voltar para meus acessos</Link></div>;
  if (status.isPending) return <div className="panel"><Loading label="Conferindo o segundo fator…" /></div>;
  if (status.isError && !status.data) return <ErrorState error={status.error} retry={() => void status.refetch()} />;
  if (!aal2 || demanded || (session.error instanceof ApiError && session.error.code === 'MFA_REQUIRED')) {
    return <MfaPanel accountId={accountId} onVerified={() => {
      setDemanded(false);
      void queries.invalidateQueries({ queryKey: mfaStatusKey(accountId) });
      void queries.invalidateQueries({ queryKey: installationKey({ accountId, tenantId }) });
    }} />;
  }
  if (session.isPending) return <div className="panel"><Loading label="Conferindo a concessão técnica…" /></div>;
  if (session.isError && !session.data) {
    const code = session.error instanceof ApiError ? session.error.code : '';
    if (code === 'TENANT_ACCESS_DENIED') return <div className="panel"><Empty icon="shield" title="Sem concessão técnica">Esta conta não tem concessão técnica vigente neste condomínio. A concessão é emitida pela administração da plataforma, com prazo e escopos definidos.</Empty></div>;
    if (code === 'API_NOT_AVAILABLE') return <div className="panel"><Empty icon="door" title="Implantação indisponível">O acesso facial e a implantação física ainda não estão ativos neste ambiente.</Empty></div>;
    return <ErrorState error={session.error} retry={() => void session.refetch()} />;
  }
  if (!value) return null;
  return <InstallationContext.Provider value={value}><InstallationConsole section={section} /></InstallationContext.Provider>;
}

function InstallationConsole({ section }: { section: string }) {
  const scope = useInstallation();
  const visible = visibleInstallationSections(scope.session);
  const base = `/implantacao/${scope.tenantId}`;
  const current = visible.find((item) => item.id === section)?.id ?? 'roteiro';
  const [outcome, setOutcome] = useState<{ section: string; text: string } | null>(null);
  const announce = useCallback((text: string) => setOutcome(text ? { section: current, text } : null), [current]);
  return <OutcomeContext.Provider value={announce}>
    <div className="residence-heading"><div><span className="eyebrow">IMPLANTAÇÃO DO CONDOMÍNIO</span><h1>Implantação física do acesso</h1>
      <p>Inventário, rede, canais, sentidos, sensores e ensaios do condomínio <code>{scope.tenantId}</code>. Nenhuma senha de equipamento é exibida ou digitada aqui. Horários em {INSTALL_ZONE}.</p></div>
      <div className="context-badge"><Icon name="shield" /><div><strong>Concessão técnica vigente</strong><span>até {formatWhen(scope.session.validUntil, INSTALL_ZONE)}</span></div></div></div>
    {visible.length === 0 ? <div className="panel"><Empty icon="shield" title="Concessão sem leitura da instalação">Sua concessão técnica não inclui a leitura da instalação (installation.read). Peça à administração da plataforma o escopo necessário.</Empty></div> : <>
      <SectionTabs label="Seções da implantação" current={current} items={visible.map((item) => ({ id: item.id, label: item.label, to: `${base}/${item.id}` }))} />
      {outcome && outcome.section === current ? <Notice tone="success">{outcome.text}</Notice> : null}
      {current === 'roteiro' ? <Guide /> : null}
      {current === 'areas' ? <AreaPhysicalSection /> : null}
      {current === 'pontos' ? <PointSection /> : null}
      {current === 'equipamentos' ? <DeviceSection /> : null}
      {current === 'canais' ? <ChannelSection /> : null}
      {current === 'vinculos' ? <BindingSection /> : null}
      {current === 'aceite' ? <AcceptanceSection /> : null}
    </>}
  </OutcomeContext.Provider>;
}

interface Step { id: InstallationSection; title: string; done: boolean; lines: string[] }
/** Roteiro da implantação: o que falta em cada etapa, a partir dos cadastros lidos do servidor (nada é inferido do equipamento). */
function Guide() {
  const scope = useInstallation();
  const areas = useQuery({ queryKey: installationKey(scope, 'areas'), queryFn: ({ signal }) => accessInstall.technicalAreas(scope.tenantId, signal) });
  const points = useQuery({ queryKey: installationKey(scope, 'points'), queryFn: ({ signal }) => accessInstall.points(scope.tenantId, signal) });
  const devices = useQuery({ queryKey: installationKey(scope, 'devices'), queryFn: ({ signal }) => accessInstall.devices(scope.tenantId, signal) });
  const channels = useQuery({ queryKey: installationKey(scope, 'channels'), queryFn: ({ signal }) => accessInstall.channels(scope.tenantId, signal) });
  const bindings = useQuery({ queryKey: installationKey(scope, 'bindings'), queryFn: ({ signal }) => accessInstall.bindings(scope.tenantId, signal) });
  const acceptances = useQuery({ queryKey: installationKey(scope, 'acceptances'), queryFn: ({ signal }) => accessInstall.acceptances(scope.tenantId, signal) });
  const all = [areas, points, devices, channels, bindings, acceptances];
  const failed = all.find((query) => query.isError && !query.data);
  if (useAccessGuard(failed?.error)) return null;
  const visible = new Set<string>(visibleInstallationSections(scope.session).map((item) => item.id));
  if (all.some((query) => query.isPending)) return <div className="panel"><Loading label="Consultando a instalação…" /></div>;
  if (failed) return <ErrorState error={failed.error} retry={() => all.forEach((query) => void query.refetch())} />;
  const a = areas.data!, p = points.data!, d = devices.data!.filter((item) => item.status === 'ACTIVE'), c = channels.data!;
  const b = bindings.data!, latest = [...acceptances.data!].sort((x, y) => y.installationRevision - x.installationRevision)[0];
  const live = a.filter((item) => item.status !== 'RETIRED');
  const openPoints = p.filter((item) => item.status !== 'RETIRED');
  const bound = new Set(b.filter((item) => ['DRAFT', 'PENDING_COMMISSION', 'ACTIVE'].includes(item.status)).map((item) => item.pointId));
  const steps: Step[] = [
    { id: 'areas', title: 'Áreas e separação física', done: live.length > 0 && live.every((item) => item.physicalBoundaryVerified),
      lines: [`${live.length} área(s) cadastrada(s) pela gestão`, `${live.filter((item) => item.physicalBoundaryVerified).length} com separação física verificada`,
        `${live.filter((item) => item.essentialRoute).length} marcada(s) como rota essencial`] },
    { id: 'pontos', title: 'Pontos, sentidos e rotas de saída', done: openPoints.length > 0 && openPoints.every((item) => item.flowDirection !== 'UNKNOWN' && item.egressClass !== 'UNVERIFIED'),
      lines: [`${openPoints.length} ponto(s) ativo(s)`, `${openPoints.filter((item) => item.flowDirection === 'UNKNOWN').length} com sentido não verificado`,
        `${openPoints.filter((item) => item.egressClass === 'UNVERIFIED').length} com saída de emergência não verificada`] },
    { id: 'equipamentos', title: 'Inventário e rede', done: d.length > 0 && d.every((item) => item.homologationId !== null && item.connectionMode !== null),
      lines: [`${d.length} equipamento(s) ativo(s)`, `${d.filter((item) => item.homologationId === null).length} sem combinação homologada informada`,
        `${d.filter((item) => item.connectionMode === null).length} usando a conexão padrão do condomínio`] },
    { id: 'canais', title: 'Canais e sensores', done: c.some((item) => item.channelKind === 'ACCESS_ACTUATOR' && item.status === 'VERIFIED'),
      lines: [`${c.filter((item) => item.channelKind === 'ACCESS_ACTUATOR' && item.status === 'VERIFIED').length} atuador(es) verificado(s)`,
        `${c.filter((item) => ['DOOR_CONTACT', 'EXIT_REQUEST', 'PASSAGE_SENSOR'].includes(item.channelKind)).length} sensor(es) e botão(ões)`,
        `${c.filter((item) => item.status === 'DISCOVERED').length} canal(is) ainda não verificado(s)`] },
    { id: 'vinculos', title: 'Vínculos físicos dos pontos', done: openPoints.length > 0 && openPoints.every((item) => bound.has(item.pointId)),
      lines: [`${openPoints.filter((item) => !bound.has(item.pointId)).length} ponto(s) sem vínculo`, `${b.filter((item) => item.status === 'PENDING_COMMISSION').length} aguardando comissionamento`,
        `${b.filter((item) => item.status === 'ACTIVE').length} em vigor`] },
    { id: 'aceite', title: 'Ensaios e aceite', done: latest?.status === 'APPROVED',
      lines: latest ? [`Revisão ${latest.installationRevision}: ${acceptanceStatusLabels[latest.status]}`,
        `Testes críticos: ${latest.criticalTestsPending} pendente(s), ${latest.criticalTestsFailed} reprovado(s)`,
        `Assinatura técnica: ${latest.technicalSignedAt ? 'registrada' : 'pendente'} · Assinatura da gestão (regras): ${latest.rulesSignedAt ? 'registrada' : 'pendente'}`]
        : ['Nenhum aceite de instalação aberto'] },
  ];
  return <section className="management-section" aria-labelledby="install-guide-title">
    <div className="section-heading"><div><span className="section-index">ROTEIRO</span><h2 id="install-guide-title">Etapas da implantação</h2></div>
      <button className="button button-secondary button-compact" type="button" onClick={() => all.forEach((query) => void query.refetch())}>Atualizar</button></div>
    <p className="field-help">Siga as etapas na ordem. A gestão do condomínio cria as áreas lógicas e assina as regras do aceite; ela não vê equipamentos, canais, relés nem vínculos.</p>
    <ol className="install-steps">{steps.map((step, index) => <li key={step.id} className="management-row">
      <div><strong>{index + 1}. {step.title}</strong><span className={`status-pill ${step.done ? 'status-ready' : 'status-pending'}`}>{step.done ? 'Concluída' : 'Pendente'}</span>
        {step.lines.map((line) => <span key={line}>{line}</span>)}</div>
      {visible.has(step.id) ? <div className="button-row"><Link className="button button-secondary button-compact" to={`/implantacao/${scope.tenantId}/${step.id}`}>Abrir {step.title.toLowerCase()}</Link></div>
        : <p className="field-help">Sua concessão não inclui esta etapa.</p>}
    </li>)}</ol>
    <div className="panel"><h3>Escopos desta concessão</h3>
      <ul className="detail-list">{scope.session.scopes.map((item) => <li key={item}>{scopeLabels[item]}</li>)}</ul></div>
    <div className="panel install-placeholder"><h3>Catálogo e homologação de modelos</h3>
      <p className="field-help">A aprovação de modelos e combinações é do MASTER da plataforma e ainda não tem tela aqui. Informe nos equipamentos e vínculos o identificador da combinação homologada recebido da plataforma.</p>
      <button className="button button-secondary button-compact" type="button" disabled>Abrir catálogo (indisponível)</button></div>
  </section>;
}
