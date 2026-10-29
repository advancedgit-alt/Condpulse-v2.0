import { useCallback, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import { useAuth } from '../Auth';
import { MfaPanel, mfaStatusKey } from '../Mfa';
import { Empty, ErrorState, Icon, Loading, Notice } from '../primitives';
import { dateInZone } from '../dateTime';
import { AuditSection } from './Audit';
import { MembershipList, PeopleSection } from './People';
import { PendingReservations, ReservationAgenda } from './Reservations';
import { AmenitySection, UnitSection } from './Setup';
import { ManagementProvider, type ManagementScope } from './shared';
import './Management.css';

export const managementSections = [
  { id: 'pendencias', label: 'Pendências' },
  { id: 'reservas', label: 'Reservas' },
  { id: 'areas', label: 'Áreas' },
  { id: 'unidades', label: 'Unidades' },
  { id: 'pessoas', label: 'Pessoas' },
  { id: 'auditoria', label: 'Auditoria' },
] as const;
export type ManagementSection = typeof managementSections[number]['id'];

/** Gestão do condomínio: exige vínculo MANAGER vigente e segundo fator; o backend confere de novo em cada ação. */
export function ManagementPage() {
  const { identity } = useAuth();
  const { tenantId = '', section = 'pendencias' } = useParams();
  const membership = identity?.memberships.find((item) => item.tenantId === tenantId && item.role === 'MANAGER');
  if (!identity || !membership) return <div className="panel"><Empty title="Gestão indisponível">Seu cadastro não inclui a gestão deste condomínio. Volte para escolher um acesso disponível.</Empty></div>;
  try { dateInZone(membership.timeZone); }
  catch { return <Notice tone="error">O fuso do condomínio precisa ser conferido antes de abrir a gestão.</Notice>; }
  const current = managementSections.some((item) => item.id === section) ? section as ManagementSection : 'pendencias';
  return <StaffGate key={`${identity.accountId}:${tenantId}`} scope={{ accountId: identity.accountId, tenantId,
    tenantName: membership.tenantName, timeZone: membership.timeZone }} section={current} />;
}

function StaffGate({ scope, section }: { scope: ManagementScope; section: ManagementSection }) {
  const queries = useQueryClient();
  // O nível de garantia só muda pela verificação nesta tela; o servidor confere AAL2 em cada ação. Sem nova consulta
  // ao voltar à aba, uma falha momentânea não derruba formulários abertos nem o link único de convite.
  const status = useQuery({ queryKey: mfaStatusKey(scope.accountId), queryFn: ({ signal }) => api.mfaStatus(signal),
    refetchOnWindowFocus: false });
  const [demanded, setDemanded] = useState(false);
  const [denied, setDenied] = useState<unknown>(null);
  const requireMfa = useCallback(() => setDemanded(true), []);
  const deny = useCallback((error: unknown) => {
    setDenied(error);
    const prefix = ['private', scope.accountId, 'tenant', scope.tenantId];
    void queries.cancelQueries({ queryKey: prefix }); queries.removeQueries({ queryKey: prefix });
    void queries.invalidateQueries({ queryKey: ['identity'] });
  }, [queries, scope.accountId, scope.tenantId]);
  const value = useMemo(() => ({ ...scope, requireMfa, denied: deny }), [scope, requireMfa, deny]);
  if (denied) return <div className="panel"><Notice tone="error">A gestão deste condomínio não está mais disponível para o seu vínculo. Os dados exibidos foram removidos.</Notice>
    <Link className="button button-secondary" to="/contextos">Escolher outro acesso</Link></div>;
  if (status.isPending) return <div className="panel"><Loading label="Conferindo o segundo fator…" /></div>;
  if (status.isError && !status.data) return <ErrorState error={status.error} retry={() => void status.refetch()} />;
  if (!status.data) return null;
  if (status.data.assuranceLevel !== 'AAL2' || demanded) {
    return <MfaPanel accountId={scope.accountId} onVerified={() => {
      setDemanded(false);
      void queries.invalidateQueries({ queryKey: mfaStatusKey(scope.accountId) });
      void queries.invalidateQueries({ queryKey: ['private', scope.accountId, 'tenant', scope.tenantId, 'management'] });
      void queries.invalidateQueries({ queryKey: ['identity'] });
    }} />;
  }
  return <ManagementProvider value={value}><ManagementConsole scope={scope} section={section} /></ManagementProvider>;
}

function ManagementConsole({ scope, section }: { scope: ManagementScope; section: ManagementSection }) {
  const base = `/gestao/${scope.tenantId}`;
  return <>
    <div className="residence-heading"><div><span className="eyebrow">GESTÃO DO CONDOMÍNIO</span><h1>{scope.tenantName}</h1>
      <p>Decisões e cadastros ficam registrados na auditoria com autor e motivo. Horários em {scope.timeZone}.</p></div>
      <div className="context-badge"><Icon name="shield" /><div><strong>Segundo fator confirmado</strong><span>Sessão de gestão</span></div></div></div>
    <nav className="management-tabs" aria-label="Seções da gestão">{managementSections.map((item) =>
      <Link key={item.id} to={`${base}/${item.id}`} aria-current={item.id === section ? 'page' : undefined}>{item.label}</Link>)}</nav>
    {section === 'pendencias' ? <Overview /> : null}
    {section === 'reservas' ? <ReservationAgenda /> : null}
    {section === 'areas' ? <AmenitySection /> : null}
    {section === 'unidades' ? <UnitSection /> : null}
    {section === 'pessoas' ? <PeopleSection /> : null}
    {section === 'auditoria' ? <AuditSection /> : null}
  </>;
}

function Overview() {
  return <div className="management-columns">
    <section className="management-section" aria-labelledby="pending-reservations-title">
      <div className="section-heading"><div><span className="section-index">01 / RESERVAS</span><h2 id="pending-reservations-title">Pedidos aguardando decisão</h2></div></div>
      <PendingReservations />
    </section>
    <section className="management-section" aria-labelledby="pending-people-title">
      <div className="section-heading"><div><span className="section-index">02 / PESSOAS</span><h2 id="pending-people-title">Vínculos pendentes</h2></div></div>
      <MembershipList status="PENDING" empty="Nenhum vínculo aguardando aprovação." />
    </section>
  </div>;
}
