import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { ApiError, isUUID } from '../../api/client';
import { platform } from '../../api/platform';
import { scopeLabels, type TechnicalScope } from '../../api/accessInstall';
import { HARD_MAX_DAYS, implantationDefaults, restrictedScopes, technicalGrants, type IssueGrantBody, type TechnicalGrant } from '../../api/technicalGrants';
import { displayText } from '../../api/text';
import { Empty, ErrorState, Loading, Notice, errorMessage } from '../primitives';
import { formatWhen } from '../dateTime';
import { queryFailure } from '../queryFailure';
import { StaleNotice, useVersionedDraft } from '../readVersion';
import { PLATFORM_ZONE, isUncertain, platformKey, react, short, tenantListKey, useAccessGuard, usePlatform, type Outcome } from './shared';

/**
 * Concessões técnicas (AC-003, V037): o MASTER da plataforma escolhe o condomínio, informa a conta (pelo identificador:
 * não há busca de contas no servidor), o papel MASTER de implantação, os escopos, a validade e o motivo. Ninguém concede
 * a si próprio; revogar exige motivo e vale para a versão exibida.
 */
export const grantsKey = (accountId: string, tenantId: string) => platformKey(accountId, 'technical-grants', tenantId);

export interface GrantDraft { accountId: string; scopes: TechnicalScope[]; validDays: string; reason: string }
export const emptyGrantDraft = (): GrantDraft => ({ accountId: '', scopes: [...implantationDefaults], validDays: '7', reason: '' });
/** Mesmas regras do servidor (TechnicalGrantService.validate); o servidor decide e aplica o teto configurado. */
export function grantBody(grantId: string, draft: GrantDraft, actorId: string): IssueGrantBody | string {
  const accountId = draft.accountId.trim().toLowerCase();
  if (!isUUID(accountId)) return 'Informe o identificador (UUID) da conta que recebe a concessão.';
  if (accountId === actorId.toLowerCase()) return 'Ninguém concede papel técnico a si próprio pelo painel. Peça a outra conta da plataforma.';
  if (draft.scopes.length < 1 || draft.scopes.length > 16) return 'Escolha ao menos um escopo.';
  const days = /^\d{1,3}$/.test(draft.validDays.trim()) ? Number(draft.validDays.trim()) : NaN;
  if (!(days >= 1 && days <= HARD_MAX_DAYS)) return `A validade deve ser de 1 a ${HARD_MAX_DAYS} dias.`;
  const reason = displayText(draft.reason, 500);
  if (!reason || reason.length < 10) return 'Informe o motivo e a referência da aprovação (10 a 500 caracteres).';
  return { grantId, accountId, roleCode: 'MASTER_IMPLANTACAO', scopes: [...draft.scopes].sort(), validDays: days, reason };
}

export function TechnicalGrantSection() {
  const scope = usePlatform();
  const [params, setParams] = useSearchParams();
  const tenants = useQuery({ queryKey: tenantListKey(scope.accountId, null), queryFn: ({ signal }) => platform.tenants(null, signal) });
  const chosen = params.get('condominio') ?? '';
  const tenantId = isUUID(chosen) ? chosen.toLowerCase() : '';
  // O condomínio escolhido vem do próprio cadastro, não da lista do seletor (limitada pelo servidor): a ficha e o link
  // `?condominio=` funcionam para qualquer condomínio.
  const detail = useQuery({ queryKey: platformKey(scope.accountId, 'tenant', tenantId), queryFn: ({ signal }) => platform.tenant(tenantId, signal),
    enabled: tenantId !== '' });
  const failed = queryFailure([tenants]);
  const detailFailed = tenantId ? queryFailure([detail]) : null;
  const guarded = useAccessGuard(failed ?? detailFailed);
  if (guarded) return null;
  const tenant = detail.data?.tenant;
  const listed = tenants.data?.tenants ?? [];
  const options = tenant && !listed.some((item) => item.tenantId === tenant.tenantId) ? [tenant, ...listed] : listed;
  return <section className="management-section" aria-labelledby="grants-title">
    <div className="section-heading"><div><span className="section-index">CONCESSÕES TÉCNICAS</span><h2 id="grants-title">MASTER de implantação por condomínio</h2></div></div>
    <p className="field-help">A concessão dá à conta, por prazo curto, os escopos técnicos de implantação física em um condomínio. Não dá acesso aos dados dos moradores nem ao uso físico das áreas. Toda emissão e revogação fica na auditoria da plataforma.</p>
    {tenants.isPending ? <div className="panel"><Loading label="Consultando condomínios…" /></div> : failed || !tenants.data ? <ErrorState error={failed} retry={() => void tenants.refetch()} />
      : <div className="panel"><label htmlFor="grant-tenant">Condomínio</label>
        <select id="grant-tenant" value={tenantId} onChange={(event) => setParams(event.target.value ? { condominio: event.target.value } : {}, { replace: true })}>
          <option value="">Escolha…</option>{options.map((item) => <option key={item.tenantId} value={item.tenantId}>{item.name}{item.active ? '' : ' (suspenso)'}</option>)}</select>
        {tenants.data.truncated ? <p className="field-help">A lista foi limitada pelo servidor; para outro condomínio, busque-o em Condomínios e use "Concessões técnicas" na ficha.</p> : null}</div>}
    {tenantId && detail.isPending ? <div className="panel"><Loading label="Consultando o condomínio…" /></div> : null}
    {tenantId && detailFailed ? <ErrorState error={detailFailed} retry={() => void detail.refetch()} /> : null}
    {tenant && !detailFailed ? <TenantGrants key={tenant.tenantId} tenantId={tenant.tenantId} tenantName={tenant.name} active={tenant.active} /> : null}
  </section>;
}

function TenantGrants({ tenantId, tenantName, active }: { tenantId: string; tenantName: string; active: boolean }) {
  const scope = usePlatform();
  const grants = useQuery({ queryKey: grantsKey(scope.accountId, tenantId), queryFn: ({ signal }) => technicalGrants.list(tenantId, signal) });
  const [issuing, setIssuing] = useState(false);
  const [revoking, setRevoking] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const failed = queryFailure([grants]);
  if (useAccessGuard(failed)) return null;
  if (failed instanceof ApiError && failed.code === 'API_NOT_AVAILABLE')
    return <div className="panel"><Empty icon="key" title="Concessões indisponíveis">O acesso facial ainda não está ativo neste ambiente.</Empty></div>;
  const list = [...(grants.data ?? [])].sort((a, b) => Number(b.current) - Number(a.current) || b.validUntil.localeCompare(a.validUntil));
  return <>
    <div className="section-heading"><div><h3>Concessões em {tenantName}</h3></div>
      <div className="button-row"><button className="button button-compact" type="button" disabled={issuing || !active || !grants.data}
        onClick={() => { setOutcome(null); setIssuing(true); }}>Nova concessão</button>
        <button className="button button-secondary button-compact" type="button" disabled={grants.isFetching} onClick={() => void grants.refetch()}>Atualizar</button></div></div>
    {!active ? <Notice tone="warning">Condomínio suspenso: novas concessões não são emitidas.</Notice> : null}
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {issuing ? <GrantForm tenantId={tenantId} tenantName={tenantName} close={(result) => { setIssuing(false); if (result) setOutcome(result); }} /> : null}
    {grants.isPending ? <div className="panel"><Loading label="Consultando concessões…" /></div> : failed || !grants.data ? <ErrorState error={failed} retry={() => void grants.refetch()} />
      : list.length === 0 ? <div className="panel"><Empty icon="key" title="Nenhuma concessão técnica">Este condomínio ainda não tem MASTER de implantação.</Empty></div>
        : <ol className="management-list" aria-label="Concessões técnicas">{list.map((grant) => <li key={grant.grantId} className="management-row">
          <div><strong>Conta <code>{grant.accountId}</code></strong>
            <span className={`status-pill ${grant.current ? 'status-ready' : 'status-neutral'}`}>{grant.current ? 'Vigente' : grant.status === 'REVOKED' ? 'Revogada' : 'Fora da vigência'}</span>
            <span>{grant.roleCode === 'MASTER_IMPLANTACAO' ? 'MASTER de implantação' : 'Suporte técnico'} · {grant.grantedVia === 'PLATFORM_API' ? 'emitida pelo painel' : 'emitida por procedimento operacional'} · concessão {short(grant.grantId)}</span>
            <span>De {formatWhen(grant.validFrom, PLATFORM_ZONE)} até {formatWhen(grant.validUntil, PLATFORM_ZONE)}{grant.revokedAt ? ` · revogada em ${formatWhen(grant.revokedAt, PLATFORM_ZONE)}` : ''}</span>
            <span>Escopos: {grant.scopes.map((item) => scopeLabels[item]).join('; ')}</span></div>
          {revoking === grant.grantId ? <RevokeForm tenantId={tenantId} grant={grant} close={(result) => { setRevoking(null); if (result) setOutcome(result); }} />
            : grant.status === 'ACTIVE' ? <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={revoking !== null}
              onClick={() => { setOutcome(null); setRevoking(grant.grantId); }}>Revogar</button></div> : null}
        </li>)}</ol>}
  </>;
}

function GrantForm({ tenantId, tenantName, close }: { tenantId: string; tenantName: string; close(result: Outcome | null): void }) {
  const scope = usePlatform();
  const queries = useQueryClient();
  const [grantId] = useState(() => globalThis.crypto.randomUUID());
  const [draft, setDraft] = useState<GrantDraft>(emptyGrantDraft);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [validation, setValidation] = useState('');
  // Resultado incerto: a repetição precisa sair com o mesmo grantId e os mesmos dados (o servidor devolve a gravada).
  const locked = failure !== null && isUncertain(failure);
  const toggle = (item: TechnicalScope, on: boolean) => setDraft((current) => ({ ...current, scopes: on ? [...current.scopes, item] : current.scopes.filter((value) => value !== item) }));
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    const body = grantBody(grantId, draft, scope.accountId);
    if (typeof body === 'string') { setValidation(body); return; }
    setValidation(''); setPending(true); setFailure(null);
    try {
      const result = await technicalGrants.issue(tenantId, body);
      await queries.invalidateQueries({ queryKey: grantsKey(scope.accountId, tenantId) });
      close(result.applied ? { tone: 'success', text: `Concessão emitida em ${tenantName}, vigente até ${formatWhen(result.grant.validUntil, PLATFORM_ZONE)}.` }
        : { tone: 'info', text: 'Esta concessão já estava gravada. Nada foi emitido de novo.' });
    } catch (error) {
      react(error, scope);
      setFailure(error);
    } finally { setPending(false); }
  }
  const all = [...implantationDefaults, ...restrictedScopes];
  return <form className="panel management-form stack-form" aria-label="Nova concessão técnica" onSubmit={(event) => void submit(event)}>
    <h3>Nova concessão em {tenantName}</h3>
    <label htmlFor="grant-account">Conta que recebe (identificador)</label>
    <input id="grant-account" required value={draft.accountId} disabled={pending || locked} onChange={(event) => setDraft({ ...draft, accountId: event.target.value })} />
    <small className="field-help">Não há busca de contas no painel: use o identificador da conta informado pela operação. Não pode ser a sua própria conta.</small>
    <p className="field-help">Papel: <strong>MASTER de implantação</strong>.</p>
    <fieldset className="weekday-fieldset" disabled={pending || locked}><legend>Escopos</legend>
      {all.map((item) => <label key={item} className="checkbox-label"><input type="checkbox" checked={draft.scopes.includes(item)} onChange={(event) => toggle(item, event.target.checked)} />
        <span>{scopeLabels[item]}{restrictedScopes.includes(item) ? ' (só por pedido específico)' : ''}</span></label>)}
    </fieldset>
    {draft.scopes.some((item) => restrictedScopes.includes(item)) ? <Notice tone="warning">Pessoas, abertura em ensaio e exportação de auditoria ficam fora do padrão e exigem pedido específico registrado no motivo.</Notice> : null}
    <label htmlFor="grant-days">Validade (dias)</label>
    <input id="grant-days" inputMode="numeric" required value={draft.validDays} disabled={pending || locked} onChange={(event) => setDraft({ ...draft, validDays: event.target.value })} />
    <small className="field-help">De 1 a {HARD_MAX_DAYS} dias; o servidor aplica o teto configurado da plataforma.</small>
    <label htmlFor="grant-reason">Motivo e referência da aprovação (fica na auditoria da plataforma)</label>
    <textarea id="grant-reason" required maxLength={500} rows={2} value={draft.reason} disabled={pending || locked} onChange={(event) => setDraft({ ...draft, reason: event.target.value })} />
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    {failure ? <Notice tone={locked ? 'warning' : 'error'}>{errorMessage(failure)}{locked ? ' Os dados ficaram travados: confirme o mesmo pedido ou atualize a lista.' : ''}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={pending}>{pending ? 'Emitindo…' : locked ? 'Tentar confirmar a mesma concessão' : 'Emitir concessão'}</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={() => close(null)}>Voltar</button></div>
  </form>;
}

function RevokeForm({ tenantId, grant, close }: { tenantId: string; grant: TechnicalGrant; close(result: Outcome | null): void }) {
  const scope = usePlatform();
  const queries = useQueryClient();
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const seen = useVersionedDraft(grant.version, () => { setFailure(null); });
  const normalized = displayText(reason, 500);
  const valid = normalized !== null && normalized.length >= 3;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || seen.stale || !valid) return;
    setPending(true); setFailure(null);
    try {
      await technicalGrants.revoke(tenantId, grant.grantId, { expectedVersion: seen.version, reason: normalized! });
      await queries.invalidateQueries({ queryKey: grantsKey(scope.accountId, tenantId) });
      close({ tone: 'success', text: 'Concessão revogada: a conta perde os escopos técnicos na próxima requisição.' });
    } catch (error) {
      react(error, scope);
      setFailure(error);
      void queries.invalidateQueries({ queryKey: grantsKey(scope.accountId, tenantId) });
    } finally { setPending(false); }
  }
  const uncertain = failure !== null && isUncertain(failure);
  return <form className="reason-form" aria-label={`Revogar concessão ${short(grant.grantId)}`} onSubmit={(event) => void submit(event)}>
    {seen.stale ? <StaleNotice onReload={seen.reload} /> : null}
    <label htmlFor={`revoke-${grant.grantId}`}>Motivo da revogação (3 a 500 caracteres)</label>
    <textarea id={`revoke-${grant.grantId}`} required maxLength={500} rows={2} value={reason} disabled={pending} onChange={(event) => setReason(event.target.value)} />
    {failure ? <Notice tone={uncertain ? 'warning' : 'error'}>{errorMessage(failure)}{uncertain ? ' Atualize a lista e confira o estado antes de repetir.' : ''}</Notice> : null}
    <div className="button-row"><button className="button button-danger" type="submit" disabled={pending || seen.stale || !valid}>{pending ? 'Revogando…' : 'Revogar concessão'}</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={() => close(null)}>Voltar</button></div>
  </form>;
}
