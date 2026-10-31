import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { PartnerContext } from '../../api/types';
import { useAuth } from '../Auth';
import { MfaPanel, mfaStatusKey } from '../Mfa';
import { Empty, ErrorState, Icon, Loading, Notice, SectionTabs, errorMessage } from '../primitives';
import '../management/Management.css';
import { ENTRY_PATH, currentPartnerSection, partnerContextOf, partnerPanelLabels, partnerPath, partnerRoleLabels, partnerSections,
  type PartnerSection } from '../shell/paths';
import { applyRefusal, failureKind, initialRefusalState, refusalOf, type RefusalState } from './model';
import { MemberSection, PortfolioSection, ScheduleSection } from './PartnerTeam';
import { SecurityLogSection } from './SecurityLog';

/**
 * Casca /p/{parceiro}/{painel} (CPX-005, PRD complementar §4.1 e R4.5). O papel vem de /v1/me (partnerContexts); as
 * seções saem das capacidades do papel e o servidor confere papel, contrato, escala e AAL2 em cada chamada. Seção sem
 * backend nesta versão aparece "em breve", sem dado.
 */
export interface PartnerScope {
  accountId: string; context: PartnerContext;
  /** Tratador único de recusa (refusalOf): devolve true quando o erro foi recusa e a tela já foi trocada. */
  refuse(error: unknown, tenantId?: string): boolean;
  /** Recusa registrada para o condomínio nesta sessão do painel (os dados dele já saíram do cache). */
  deniedTenant(tenantId: string): unknown;
}
const PartnerScopeContext = createContext<PartnerScope | null>(null);
/** Provedor do escopo do parceiro; a porta (PartnerGate) o usa depois de conferir o segundo fator. */
export const PartnerScopeProvider = PartnerScopeContext.Provider;
export function usePartnerScope(): PartnerScope {
  const value = useContext(PartnerScopeContext);
  if (!value) throw new Error('Contexto do parceiro ausente.');
  return value;
}
/**
 * Consulta com recusa de autorização: entrega ao tratador único depois da renderização e devolve true para a seção
 * não mostrar nada enquanto a tela é trocada.
 */
export function usePartnerAccessGuard(error: unknown, tenantId?: string): boolean {
  const scope = usePartnerScope();
  const refused = refusalOf(error, tenantId) !== null;
  useEffect(() => { if (refused) scope.refuse(error, tenantId); }, [refused, error, tenantId, scope]);
  return refused;
}

/** Estado de uma consulta que falhou: flag desligada, indisponível, sem acesso ou erro com nova tentativa. */
export function FailureState({ error, retry, off, subject }: { error: unknown; retry(): void; off: string; subject: string }) {
  const kind = failureKind(error);
  if (kind === 'off') return <div className="panel"><Empty icon="shield" title={`${subject} indisponível neste ambiente`}>{off}</Empty></div>;
  if (kind === 'denied') return <div className="panel"><Empty icon="shield" title="Acesso não disponível">{errorMessage(error)}</Empty></div>;
  if (kind === 'unavailable') return <div className="error-state"><Notice tone="warning">{subject} está temporariamente indisponível. {errorMessage(error)}</Notice>
    <button className="button button-secondary" type="button" onClick={retry}>Tentar novamente</button></div>;
  return <ErrorState error={error} retry={retry} />;
}

export function PartnerPage() {
  const { identity } = useAuth();
  const { partnerId = '', panel = '', section = '' } = useParams();
  if (!identity) return null;
  const context = partnerContextOf(identity.partnerContexts, partnerId, panel);
  if (!context) return <div className="panel"><Empty icon="shield" title="Painel não disponível">
    Esta conta não tem papel ativo neste parceiro com este painel, ou a hierarquia de parceiros ainda não está ativa neste ambiente.
    {' '}<Link to={ENTRY_PATH}>Voltar para meus acessos</Link></Empty></div>;
  return <PartnerGate key={`${identity.accountId}:${context.memberId}`} accountId={identity.accountId} context={context} section={section} />;
}

function PartnerGate({ accountId, context, section }: { accountId: string; context: PartnerContext; section: string }) {
  const queries = useQueryClient();
  const status = useQuery({ queryKey: mfaStatusKey(accountId), queryFn: ({ signal }) => api.mfaStatus(signal), refetchOnWindowFocus: false });
  const [refusals, setRefusals] = useState<RefusalState>(initialRefusalState);
  const tenantIds = useMemo(() => context.tenants.map((tenant) => tenant.tenantId), [context.tenants]);
  const forget = useCallback((prefixes: readonly (readonly unknown[])[]) => {
    for (const prefix of prefixes) { void queries.cancelQueries({ queryKey: prefix }); queries.removeQueries({ queryKey: prefix }); }
  }, [queries]);
  const scopeKeys = useMemo(() => ({ accountId, partnerId: context.partnerId, tenantIds }), [accountId, context.partnerId, tenantIds]);
  const refuse = useCallback((error: unknown, tenantId?: string) => {
    const refusal = refusalOf(error, tenantId);
    if (!refusal) return false;
    // Cache apagado já aqui, não só ao desmontar: erros MFA_REQUIRED não reabrem o segundo fator depois da confirmação.
    forget(applyRefusal(initialRefusalState(), refusal, scopeKeys, error).forget);
    setRefusals((current) => applyRefusal(current, refusal, scopeKeys, error).state);
    // O papel ou o alcance podem ter mudado: /v1/me é relido para a entrada e o menu.
    if (refusal.kind !== 'mfa') void queries.invalidateQueries({ queryKey: ['identity'] });
    return true;
  }, [forget, scopeKeys, queries]);
  const deniedTenant = useCallback((tenantId: string) => refusals.deniedTenants.get(tenantId.toLowerCase()) ?? null, [refusals.deniedTenants]);
  const denied = refusals.partnerDenied;
  const demanded = refusals.mfa;
  const value = useMemo(() => ({ accountId, context, refuse, deniedTenant }), [accountId, context, refuse, deniedTenant]);
  if (denied) return <div className="panel"><Notice tone="error">{errorMessage(denied)} Os dados exibidos foram removidos.</Notice>
    <Link className="button button-secondary" to={ENTRY_PATH}>Voltar para meus acessos</Link></div>;
  if (status.isPending) return <div className="panel"><Loading label="Conferindo o segundo fator…" /></div>;
  if (status.isError && !status.data) return <ErrorState error={status.error} retry={() => void status.refetch()} />;
  // Papéis de parceiro exigem AAL2 (R4.3): sem o segundo fator nenhuma consulta é feita.
  if (status.data.assuranceLevel !== 'AAL2' || demanded) {
    return <MfaPanel accountId={accountId} onVerified={() => {
      setRefusals((current) => ({ ...current, mfa: false }));
      void queries.invalidateQueries({ queryKey: mfaStatusKey(accountId) });
      forget(applyRefusal(refusals, { kind: 'mfa' }, scopeKeys, null).forget);
    }} />;
  }
  return <PartnerScopeProvider value={value}><PartnerConsole section={section} /></PartnerScopeProvider>;
}

export function PartnerConsole({ section }: { section: string }) {
  const { context } = usePartnerScope();
  const sections = partnerSections(context);
  const current = currentPartnerSection(context, section);
  const ready = sections.filter((item) => item.ready);
  return <>
    <div className="residence-heading"><div><span className="eyebrow">{partnerRoleLabels[context.role].toUpperCase()}</span><h1>{partnerPanelLabels[context.panel]}</h1>
      <p>Parceiro <bdi>{context.partnerName}</bdi>. Cada seção segue as capacidades do seu papel; o servidor confere papel, contrato, escala e segundo fator em cada ação.</p></div>
      <div className="context-badge"><Icon name="shield" /><div><strong>Segundo fator confirmado</strong>
        <span>{context.tenants.length === 1 ? '1 condomínio alcançado agora' : `${context.tenants.length} condomínios alcançados agora`}</span></div></div></div>
    {ready.length > 1 ? <SectionTabs label={`Seções do ${partnerPanelLabels[context.panel]}`} current={current ?? ''}
      items={ready.map((item) => ({ id: item.id, label: item.label, to: partnerPath(context.partnerId, context.panel, item.id) }))} /> : null}
    {current === null ? <ComingSoon sections={sections} panelLabel={partnerPanelLabels[context.panel]} />
      : <SectionBody section={current} />}
  </>;
}

function SectionBody({ section }: { section: string }): ReactNode {
  if (section === 'equipe') return <MemberSection />;
  if (section === 'escala') return <ScheduleSection />;
  if (section === 'carteira') return <PortfolioSection />;
  if (section === 'log') return <SecurityLogSection />;
  return null;
}

/** Painel sem seção pronta: lista o que o papel vai ter, sem rota nem dado. */
export function ComingSoon({ sections, panelLabel }: { sections: readonly PartnerSection[]; panelLabel: string }) {
  return <div className="panel"><Empty icon="clock" title={`${panelLabel}: em breve`}>
    Este painel ainda não tem funções ativas nesta versão. {sections.length > 0
      ? `Previsto para o seu papel: ${sections.map((item) => item.label).join(', ')}.` : 'Nenhuma capacidade do seu papel tem tela prevista nesta versão.'}
  </Empty></div>;
}
