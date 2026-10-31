import { useQuery } from '@tanstack/react-query';
import { Link, Navigate } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { platform } from '../../api/platform';
import { useAuth } from '../Auth';
import { storedInvitation } from '../invitationStorage';
import { Empty, Icon, Loading, Notice } from '../primitives';
import { centralPath, condoPath, condominiums, entryDecision, roleLabels, type PlatformAccess } from './paths';

/**
 * Papel de plataforma consultado na entrada. /v1/me ainda não informa esse papel, então a entrada pergunta a
 * /v1/platform/session (sem efeito colateral). Fica em cache até sair da conta só para decidir o roteamento e mostrar o
 * atalho da central; a central confere o papel e o segundo fator de novo ao abrir.
 */
export const entryPlatformKey = (accountId: string) => ['private', accountId, 'entry', 'platform'] as const;
export function usePlatformAccess(accountId: string | undefined, probe: boolean): PlatformAccess {
  const query = useQuery({ queryKey: entryPlatformKey(accountId ?? ''), enabled: probe && Boolean(accountId), retry: false,
    staleTime: Infinity, gcTime: Infinity, refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      try { await platform.session(signal); return true; }
      catch (error) {
        if (error instanceof ApiError && ['PLATFORM_ACCESS_DENIED', 'API_NOT_AVAILABLE'].includes(error.code)) return false;
        throw error;
      }
    } });
  if (query.data !== undefined) return query.data ? 'yes' : 'no';
  if (query.isError) return 'unknown';
  return 'pending';
}

/** Entrada única: decide para onde a conta vai ou mostra o seletor só de condomínio (sem unidade nem atalho de gestão). */
export function EntryPage() {
  const { identity } = useAuth();
  const access = usePlatformAccess(identity?.accountId, true);
  if (!identity) return null;
  const condos = condominiums(identity.memberships, identity.tenantModules);
  const invitation = storedInvitation();
  const managerInvitation = storedInvitation('manager');
  const decision = entryDecision(condos, access, Boolean(invitation || managerInvitation));
  if (decision.kind === 'wait') return <div className="panel"><Loading label="Conferindo seus acessos…" /></div>;
  if (decision.kind === 'redirect') return <Navigate to={decision.to} replace />;
  return <div className="entry-page"><div className="page-intro"><span className="eyebrow">SEUS ACESSOS</span><h1>Escolha o condomínio.</h1>
    <p>Dentro de cada condomínio você vê apenas as áreas dos seus papéis. A unidade é escolhida na área do morador.</p></div>
    {invitation ? <Notice>Há um convite aberto nesta janela. <Link to="/convite">Concluir o aceite do convite</Link></Notice> : null}
    {managerInvitation ? <Notice>Há um convite de gestor aberto nesta janela. <Link to="/convite-gestor">Concluir o aceite como gestor</Link></Notice> : null}
    {access === 'unknown' ? <Notice tone="warning">Não foi possível conferir o acesso à central administradora agora. Os condomínios abaixo continuam disponíveis.</Notice> : null}
    {access === 'yes' ? <Link className="panel entry-central" to={centralPath()}><span className="round-icon"><Icon name="shield" /></span>
      <span><strong>Central administradora</strong><span>Condomínios, financeiro, concessões técnicas, operação e auditoria da plataforma.</span></span><Icon name="arrow" /></Link> : null}
    {decision.kind === 'empty' || condos.length === 0
      ? <div className="panel"><Empty icon="building" title="Nenhum condomínio disponível">A gestão do condomínio precisa validar seu vínculo antes do primeiro acesso. Se o cadastro acabou de ser aprovado, atualize a página.</Empty></div>
      : <ul className="condo-cards" aria-label="Condomínios">{condos.map((condo) => <li key={condo.tenantId}>
        <Link className="condo-card" to={condoPath(condo.tenantId)}><span className="round-icon"><Icon name="building" /></span>
          <span className="condo-card-text"><strong><bdi>{condo.tenantName}</bdi></strong><span>{condo.roles.map((role) => roleLabels[role]).join(' · ')}</span></span>
          <Icon name="arrow" /></Link></li>)}</ul>}
  </div>;
}
